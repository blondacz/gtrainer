import contextlib
import copy
import datetime as dt
import io
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

import replay_v4 as r
from test_oracle_v4 import assembled, stages

TEMP = "/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode"


class ReplayFixture(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="intake-v4-unit-", dir=TEMP)
        self.addCleanup(self.folder.cleanup)
        self.directory = Path(self.folder.name); self.directory.chmod(0o700)
        self.now = dt.datetime(2026, 10, 19, 20, tzinfo=dt.timezone.utc)
        _, self.inputs, _, self.fixtures = r.e.verify()
        self.run = r.make_run(str(uuid.uuid4()), "synthetic_fake")
        self.approval = {"version": "intake-conformance-validation-approval-v4", "runSha256": r.e.digest(r.e.compact(self.run)),
                         "purpose": "synthetic_offline_validation_only", "recordedBy": "offline synthetic unit test",
                         "recordedAt": "2026-10-19T19:00:00Z", "expiresUtc": "2026-10-19T21:00:00Z"}
        self.source = {"version": "intake-conformance-responses-v4", "cases": [self.case(c) for c in r.e.IDS]}

    def case(self, case_id, outcome="response", raw=None):
        if raw is None and outcome == "response": raw = r.e.compact(self.fixtures[case_id]).decode()
        return {"caseId": case_id, "outcome": outcome, "raw": raw, "resources": {k: None for k in sorted(r.RESOURCES)}}

    def write(self):
        for name, value in (("run.json", self.run), ("approval.json", self.approval), ("responses.json", self.source)):
            r.private.private_write(self.directory / name, r.e.compact(value))

    def validator(self, directory, requests):
        return [{"caseId": req["caseId"], "rawSha256": req["rawSha256"],
                 "stages": stages(assembled(req["caseId"], r.e.decode(req["raw"].encode()), self.inputs))} for req in requests]


class ReplayTest(ReplayFixture):
    def test_run_records_do_not_alias_authority_constants(self):
        self.run["model"]["candidate"] = "mutated"
        self.run["resourceLimits"]["maxResponseBytes"] = 99
        self.assertEqual(r.FAKE_MODEL["candidate"], "synthetic_fake")
        self.assertEqual(r.LIMITS["maxResponseBytes"], 12288)

    def test_eight_passed_all_hashes_and_no_human_score_impersonation(self):
        self.write(); report = r.replay(self.directory, self.now, self.validator)
        self.assertEqual(report["suiteConformance"], "passed")
        self.assertEqual(report["evaluationMethod"], "deterministic_extractive")
        self.assertFalse(report["independentHuman"])
        self.assertIsNone(report["humanAssessment"])
        self.assertEqual(report["productionAdmission"], "closed")
        self.assertEqual([c["caseId"] for c in report["cases"]], r.e.IDS)
        for row in report["cases"]:
            self.assertEqual(row["oracle"], {"status": "passed", "reasons": []})
            self.assertEqual(row["rawSha256"], r.e.digest(row["raw"].encode()))
            self.assertEqual(row["stagesSha256"], r.e.digest(r.e.compact(row["stages"])))
            self.assertEqual(row["oracleResultSha256"], r.e.digest(r.e.compact(row["oracle"])))
            self.assertTrue(r.private.sha(row["predicatesSha256"]))
            self.assertEqual(row["resourcesProvenance"], "unmeasured_fixture")
            self.assertNotIn("scores", row)

    def test_cross_version_exact_type_drift_and_expiry_refuse_before_read(self):
        for field, value in (("version", "intake-extraction-private-run-v3"), ("profile", "context-intake-extraction-v3"),
                             ("toolingSha256", {}), ("manifestSha256", "0" * 64), ("caseIds", r.e.IDS[:-1]),
                             ("executionMode", "live"), ("model", {}), ("extra", True)):
            bad = copy.deepcopy(self.run); bad[field] = value
            approval = dict(self.approval, runSha256=r.e.digest(r.e.compact(bad)))
            with self.subTest(field=field), self.assertRaises(ValueError): r.authorize(bad, approval, self.now)
        for field, value in (("num_ctx", 4096.0), ("think", 0), ("use_mmap", 1)):
            bad = copy.deepcopy(self.run); bad["settings"][field] = value
            with self.assertRaises(ValueError): r.authorize(bad, dict(self.approval, runSha256=r.e.digest(r.e.compact(bad))), self.now)
        self.write(); (self.directory / "responses.json").unlink()
        (self.directory / "approval.json").unlink()
        r.private.private_write(self.directory / "approval.json", r.e.compact(dict(self.approval, purpose="inference")))
        callback = Mock()
        with patch.object(r.private, "private_read", wraps=r.private.private_read) as read:
            with self.assertRaises(ValueError): r.replay(self.directory, self.now, callback)
        self.assertNotIn(self.directory / "responses.json", [c.args[0] for c in read.call_args_list])
        callback.assert_not_called()
        with self.assertRaises(ValueError): r.authorize(self.run, self.approval, self.now + dt.timedelta(days=1))

    def test_empty_and_early_abort_keep_denominator_without_callbacks(self):
        self.source["cases"] = [self.case("i01", "empty", " "), self.case("i02", "incomplete", "partial")]
        self.write(); callback = Mock(); report = r.replay(self.directory, self.now, callback)
        callback.assert_not_called()
        self.assertEqual(report["suiteConformance"], "incomplete")
        self.assertEqual([c["outcome"] for c in report["cases"]], ["empty", "incomplete"] + ["unrun"] * 6)
        self.assertTrue(all(c["oracle"]["status"] == "not_run" for c in report["cases"]))

    def test_complete_locally_accepted_oracle_failure_is_not_suite_success(self):
        bad = copy.deepcopy(self.fixtures["i01"]); bad["suggestions"][0]["context"]["content"] = "no running"
        self.source["cases"][0]["raw"] = r.e.compact(bad).decode()
        self.write(); report = r.replay(self.directory, self.now, self.validator)
        self.assertEqual(report["cases"][0]["outcome"], "accepted")
        self.assertEqual(report["cases"][0]["oracle"]["status"], "failed")
        self.assertEqual(report["suiteConformance"], "failed")

    def test_shape_order_bounds_invalid_resources_and_no_post_abort_case(self):
        cases = self.source["cases"]
        for rows in (list(reversed(cases)), [cases[0], cases[0]], [dict(cases[0], attempt=2)],
                     [dict(cases[0], raw="x" * 12289)], [self.case("i01", "timeout"), self.case("i02")]):
            with self.assertRaises(ValueError): r.cases_from(dict(self.source, cases=rows), self.run)
        for value in (True, -1, 12.5, float("nan")):
            source = copy.deepcopy(self.source); source["cases"][0]["resources"]["inputTokens"] = value
            with self.assertRaises(ValueError): r.cases_from(source, self.run)

    def test_imported_measurements_stay_unverified_unknowns_stay_null(self):
        self.run = r.make_run(self.run["runId"], "externally_recorded")
        self.approval["runSha256"] = r.e.digest(r.e.compact(self.run))
        self.source["cases"][0]["resources"]["inputTokens"] = 123
        self.write(); report = r.replay(self.directory, self.now, self.validator)
        self.assertEqual(report["cases"][0]["resourcesProvenance"], "imported_unverified")
        self.assertEqual(report["cases"][0]["resources"]["inputTokens"], 123)
        self.assertIsNone(report["cases"][0]["resources"]["peakRssBytes"])

    def test_private_modes_links_existing_files_and_failed_callback_are_fenced(self):
        self.write(); path = self.directory / "run.json"; path.chmod(0o644)
        with self.assertRaises(ValueError): r.replay(self.directory, self.now, self.validator)
        path.chmod(0o600); (self.directory / "linked").symlink_to(path)
        with self.assertRaises(ValueError): r.private.private_read(self.directory / "linked")
        os.link(path, self.directory / "hard")
        with self.assertRaises(ValueError): r.private.private_read(path)
        (self.directory / "hard").unlink()
        with self.assertRaises(ValueError): r.replay(self.directory, self.now, Mock(side_effect=ValueError("private-v4-marker")))
        self.assertTrue((self.directory / "validation.lock").exists())
        self.assertFalse((self.directory / "report.json").exists())
        callback = Mock()
        with self.assertRaises(ValueError): r.replay(self.directory, self.now, callback)
        callback.assert_not_called()

    def test_wrong_stage_results_and_sensitive_cli_error_fail_closed(self):
        requests = [{"caseId": "i01", "rawSha256": "a" * 64, "raw": "{}"}]
        for result in ([], [{"caseId": "i02", "rawSha256": "a" * 64, "stages": {}}],
                       [{"caseId": "i01", "rawSha256": "a" * 64, "stages": dict(stages(self.fixtures["i01"]), reason="private-v4-marker")} ]):
            with self.assertRaises(ValueError): r.check_results(result, requests)
        output = io.StringIO()
        with patch("sys.argv", ["replay_v4.py", "--directory", str(self.directory)]), patch.object(r, "replay", side_effect=ValueError("private-v4-marker")), contextlib.redirect_stdout(output):
            self.assertEqual(r.main(), 1)
        self.assertNotIn("private-v4-marker", output.getvalue())

    def test_callback_source_or_tooling_drift_fences_report(self):
        self.write()
        def mutate(directory, requests):
            results = self.validator(directory, requests)
            (directory / "responses.json").unlink()
            r.private.private_write(directory / "responses.json", r.e.compact(dict(self.source, cases=[])))
            return results
        with self.assertRaises(ValueError): r.replay(self.directory, self.now, mutate)
        self.assertFalse((self.directory / "report.json").exists())
        changed = dict(r.tooling(), **{"test-drift": "0" * 64})
        with patch.object(r, "tooling", return_value=changed), self.assertRaises(ValueError):
            r.authorize(self.run, self.approval, self.now)

    def test_every_frozen_file_drift_and_refreeze_refuse(self):
        root = self.directory / "source"; folder = root / r.e.PREFIX
        folder.mkdir(parents=True)
        for version in ("scoped-profiles", "intake-extraction-v2", "intake-extraction-v3"):
            (root / "benchmarks" / version).symlink_to(r.e.ROOT / "benchmarks" / version, target_is_directory=True)
        for path in (*r.e.PINNED, r.e.PREFIX + "manifest-v4.json"):
            shutil.copyfile(r.e.ROOT / path, root / path)
        with patch.object(r.e, "ROOT", root), patch.object(r.e, "HERE", folder):
            r.e.verify()
            for name in r.e.PINNED:
                path = root / name; original = path.read_bytes()
                path.write_bytes(original + b" ")
                with self.subTest(path=name), self.assertRaises(ValueError): r.e.verify()
                path.write_bytes(original)
            with self.assertRaises(ValueError): r.e.freeze()
            r.e.verify()

    def test_cross_version_run_never_reads_responses_or_invokes_callback(self):
        self.run["version"] = "intake-extraction-private-run-v3"
        self.approval["runSha256"] = r.e.digest(r.e.compact(self.run))
        self.write(); callback = Mock()
        with patch.object(r.private, "private_read", wraps=r.private.private_read) as read:
            with self.assertRaises(ValueError): r.replay(self.directory, self.now, callback)
        self.assertNotIn(self.directory / "responses.json", [c.args[0] for c in read.call_args_list])
        callback.assert_not_called()
        self.assertFalse((self.directory / "validation.lock").exists())

    def test_offline_process_time_output_and_exit_are_bounded(self):
        for command, limit in (("import time;time.sleep(30)", 0.1),
                               ("import os;os.write(1,b'x'*2100000)", 5), ("raise SystemExit(1)", 5)):
            with self.subTest(command=command), (self.directory / "bounded.log").open("wb") as log:
                with self.assertRaises(ValueError):
                    r.bounded_validation([sys.executable, "-c", command], dict(os.environ), log, limit)
            self.assertLessEqual((self.directory / "bounded.log").stat().st_size, r.LIMITS["maxFileBytes"])


@unittest.skipUnless(os.environ.get("INTAKE_V4_KOTLIN") == "1", "Explicit offline Kotlin integration required")
class KotlinIntegrationTest(ReplayFixture):
    def setUp(self):
        super().setUp()
        retained = os.environ.get("INTAKE_V4_RETAIN_INTEGRATION")
        if retained:
            self.directory = Path(retained) / self._testMethodName
            self.directory.mkdir(mode=0o700)

    def test_actual_kotlin_all_eight_fixtures(self):
        self.write(); report = r.replay(self.directory, self.now)
        self.assertEqual(report["suiteConformance"], "passed")
        self.assertTrue(all(c["stages"]["semantic"] == "accepted" and c["oracle"]["status"] == "passed" for c in report["cases"]))
        self.assertFalse(report["independentHuman"])
        corrected = report["cases"][6]
        draft = r.e.decode(corrected["stages"]["assembledDraft"].encode())
        self.assertEqual(draft["intakeRevision"], 2)
        suggestion = draft["suggestions"][0]
        self.assertEqual(suggestion["sourceEnd"], len(suggestion["sourceExcerpt"].encode("utf-16-le")) // 2)
        self.assertGreater(suggestion["sourceEnd"], len(suggestion["sourceExcerpt"]))

    def test_actual_kotlin_wire_binding_semantic_oracle_and_early_stop(self):
        one = copy.deepcopy(self.fixtures["i01"]); one["suggestions"][0]["unresolvedFields"].append("applicableUntil")
        two = copy.deepcopy(self.fixtures["i02"]); two["suggestions"][0]["sourceExcerpt"] = "private-v4-marker-29e1"
        three = copy.deepcopy(self.fixtures["i03"]); three["suggestions"][0]["unresolvedFields"] *= 2
        from fixtures_v4 import context
        text = r.e.decode(self.inputs["cases"][3]["userPacketJson"].encode())["intake"]["content"]
        four = {"suggestions": [context(text, "2026-10-17", category="symptom")], "questions": [{"text": "When does the meeting apply?"}]}
        for i, value in enumerate((one, two, three, four)): self.source["cases"][i]["raw"] = r.e.compact(value).decode()
        self.source["cases"] = self.source["cases"][:5] + [self.case("i06", "empty", " "), self.case("i07", "incomplete", "private-v4-marker-29e1")]
        self.write(); report = r.replay(self.directory, self.now)
        self.assertEqual(report["suiteConformance"], "incomplete")
        self.assertEqual([c["outcome"] for c in report["cases"]], ["rejected"] * 3 + ["accepted", "accepted", "empty", "incomplete", "unrun"])
        self.assertEqual([c["stages"]["reason"] for c in report["cases"][:3]], ["semantic_rejected", "binding_failed", "invalid_wire"])
        self.assertEqual(report["cases"][3]["oracle"]["status"], "failed")
        self.assertEqual(report["cases"][4]["oracle"]["status"], "passed")
        for root in (self.directory / "test-reports", r.e.ROOT / "backend/build/reports/tests", r.e.ROOT / "backend/build/test-results"):
            for path in root.rglob("*"):
                if path.is_file(): self.assertNotIn(b"private-v4-marker-29e1", path.read_bytes())
        self.assertNotIn(b"private-v4-marker-29e1", (self.directory / "validation.log").read_bytes())


if __name__ == "__main__": unittest.main()
