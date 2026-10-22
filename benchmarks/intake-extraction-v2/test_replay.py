import contextlib
import datetime as dt
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

import replay as r


class ReplayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="intake-v2-replay-test-", dir=os.environ.get("INTAKE_V2_TEST_TEMP_ROOT"))
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name).resolve()
        self.directory.chmod(0o700)
        self.now = dt.datetime(2026, 10, 18, 18, tzinfo=dt.timezone.utc)
        self.definition, self.artifacts = r.e.verify()
        self.run = {"version": "intake-extraction-private-run-v2", "runId": str(uuid.uuid4()),
                    "executionMode": "offline_replay", "inputOrigin": "synthetic_fake", "profile": r.e.PROFILE,
                    "caseIds": r.e.IDS, "manifestSha256": r.e.digest((r.e.HERE / "manifest-v2.json").read_bytes()),
                    "toolingSha256": r.tooling(), "model": r.FAKE_MODEL, "settings": r.settings(self.artifacts), "resourceLimits": r.LIMITS}
        self.approval = {"version": "intake-extraction-replay-approval-v2", "runSha256": r.e.digest(r.e.compact(self.run)),
                         "purpose": "synthetic_offline_replay_only", "recordedBy": "synthetic fake unit test",
                         "recordedAt": "2026-10-18T17:00:00Z", "expiresUtc": "2026-10-18T19:00:00Z"}
        self.source = {"version": "intake-extraction-replay-v2", "cases": [self.case(c) for c in r.e.IDS]}

    def case(self, case_id, outcome="response", raw='{"suggestions":[],"questions":[]}'):
        return {"caseId": case_id, "outcome": outcome, "raw": raw, "resources": {k: None for k in sorted(r.RESOURCE_FIELDS)}}

    def write_inputs(self):
        for name, value in (("run.json", self.run), ("approval.json", self.approval), ("replay.json", self.source)):
            r.private.private_write(self.directory / name, r.e.compact(value))

    def good_validator(self, directory, requests):
        return [{"caseId": req["caseId"], "rawSha256": req["rawSha256"],
                 "stages": {"wire": "accepted", "binding": "accepted", "semantic": "accepted", "reason": None,
                            "assembledDraft": '{"syntheticFakeCallback":true}'}} for req in requests]

    def test_all_eight_stage_results_retain_raw_hashes_and_blank_human_fields(self):
        self.write_inputs()
        result = r.replay(self.directory, self.now, self.good_validator)
        self.assertEqual([c["caseId"] for c in result["cases"]], r.e.IDS)
        self.assertTrue(all(c["outcome"] == "accepted" and c["rawSha256"] for c in result["cases"]))
        self.assertTrue(all(c["resourcesProvenance"] == "unmeasured_fake" for c in result["cases"]))
        self.assertFalse(result["experimentalSuccess"])
        self.assertEqual(result["productionAdmission"], "closed")
        human = r.private.read_json(self.directory / "human-review.json")
        self.assertIsNone(human["independentHuman"])
        self.assertTrue(all(c["usefulFinal"] is None and c["criticalFailures"] is None and all(v is None for v in c["scores"].values()) for c in human["cases"]))
        self.assertEqual(human["reportSha256"], r.e.digest((self.directory / "report.json").read_bytes()))

    def test_wrong_approval_or_settings_prevents_response_read_and_callback(self):
        mutations = [lambda: self.run.update(profile="context-intake-suggestion-v1"),
                     lambda: self.run["settings"].update(num_ctx=8192),
                     lambda: self.run["settings"].update(num_ctx=4096.0),
                     lambda: self.run.update(caseIds=r.e.IDS[:-1]),
                     lambda: self.run.update(toolingSha256={}),
                     lambda: self.run.update(manifestSha256="0" * 64),
                     lambda: self.run.update(executionMode="inference"),
                     lambda: self.approval.update(purpose="inference"),
                     lambda: self.approval.update(expiresUtc="2026-10-18T18:00:00Z")]
        original_run, original_approval = r.e.compact(self.run), r.e.compact(self.approval)
        for mutation in mutations:
            self.run, self.approval = r.e.decode(original_run), r.e.decode(original_approval)
            mutation()
            with self.subTest(mutation=mutations.index(mutation)), self.assertRaises(ValueError):
                r.authorize(self.run, self.approval, self.artifacts, self.now)
        self.approval["runSha256"] = "0" * 64
        self.write_inputs()
        (self.directory / "replay.json").unlink()
        callback = Mock()
        with patch.object(r.private, "private_read", wraps=r.private.private_read) as read:
            with self.assertRaises(ValueError):
                r.replay(self.directory, self.now, callback)
            self.assertNotIn(self.directory / "replay.json", [call.args[0] for call in read.call_args_list])
        callback.assert_not_called()

    def test_all_eight_cases_remain_after_timeout_or_empty_batch(self):
        self.source["cases"] = [self.case("i01", "timeout", None)]
        self.write_inputs()
        callback = Mock()
        report = r.replay(self.directory, self.now, callback)
        callback.assert_not_called()
        self.assertEqual([c["outcome"] for c in report["cases"]], ["timeout"] + ["unrun"] * 7)
        self.assertTrue(all(c["stages"]["semantic"] == "not_run" for c in report["cases"]))
        self.assertTrue(all(v is None for c in report["cases"] for v in c["resources"].values()))

    def test_attempts_reordering_filtered_cases_and_later_generation_after_abort_refused(self):
        variants = [self.source["cases"][1:], list(reversed(self.source["cases"])),
                    [self.case("i01"), self.case("i01")],
                    [self.case("i01", "incomplete", None), self.case("i02")],
                    [dict(self.case("i01"), attempt=2)],
                    [dict(self.case("i01"), raw="x" * 12289)]]
        for cases in variants:
            with self.assertRaises((ValueError, KeyError)):
                r.cases_from(dict(self.source, cases=cases), self.run)

    def test_fake_measurements_and_boolean_tokens_refused(self):
        for value in (1, True, float("nan"), -1):
            self.source["cases"][0]["resources"]["inputTokens"] = value
            with self.assertRaises(ValueError):
                r.cases_from(self.source, self.run)

    def test_externally_recorded_values_are_marked_unverified_not_measured(self):
        self.run["inputOrigin"] = "externally_recorded"
        self.run["model"] = r.MODEL
        self.approval["runSha256"] = r.e.digest(r.e.compact(self.run))
        self.source["cases"][0]["resources"]["elapsedMs"] = 1000
        self.write_inputs()
        result = r.replay(self.directory, self.now, self.good_validator)
        self.assertEqual(result["cases"][0]["resources"]["elapsedMs"], 1000)
        self.assertEqual(result["cases"][0]["resourcesProvenance"], "externally_recorded_unverified")
        self.assertIsNone(result["cases"][0]["resources"]["inputTokens"])

    def test_duplicate_keys_private_modes_links_and_existing_outputs_refused(self):
        self.write_inputs()
        (self.directory / "run.json").chmod(0o644)
        callback = Mock()
        with self.assertRaises(ValueError):
            r.replay(self.directory, self.now, callback)
        (self.directory / "run.json").chmod(0o600)
        r.private.private_write(self.directory / "report.json", b"original")
        with self.assertRaises(ValueError):
            r.replay(self.directory, self.now, callback)
        self.assertEqual((self.directory / "report.json").read_bytes(), b"original")
        callback.assert_not_called()
        with self.assertRaises(ValueError):
            r.e.decode(b'{"cases":[],"cases":[]}')
        with self.assertRaises(FileExistsError):
            r.private.private_write(self.directory / "run.json", b"changed")
        (self.directory / "link.json").symlink_to(self.directory / "run.json")
        with self.assertRaises(ValueError):
            r.private.private_read(self.directory / "link.json")
        os.link(self.directory / "run.json", self.directory / "hardlink.json")
        with self.assertRaises(ValueError):
            r.private.private_read(self.directory / "run.json")

    def test_callback_tampering_or_failure_never_creates_acceptance_report(self):
        self.write_inputs()
        def bad(directory, requests):
            results = self.good_validator(directory, requests)
            results[0]["rawSha256"] = "0" * 64
            return results
        with self.assertRaises(ValueError):
            r.replay(self.directory, self.now, bad)
        self.assertFalse((self.directory / "report.json").exists())
        self.assertTrue((self.directory / "replay.lock").exists())
        callback = Mock()
        with self.assertRaises(ValueError):
            r.replay(self.directory, self.now, callback)
        callback.assert_not_called()

    def test_empty_output_is_not_given_usefulness_or_human_scores(self):
        self.source["cases"] = [self.case("i01", "empty", " ")]
        self.write_inputs()
        result = r.replay(self.directory, self.now, Mock())
        self.assertEqual(result["cases"][0]["outcome"], "empty")
        self.assertEqual(result["usefulness"], "incomplete")
        self.assertFalse(result["experimentalSuccess"])

    def test_stage_results_must_be_consistent_complete_ordered_and_bounded(self):
        requests = [{"caseId": "i01", "rawSha256": "a" * 64, "raw": "{}"}]
        for mutate in (lambda rows: rows.append(rows[0]), lambda rows: rows[0].update(caseId="i02"),
                       lambda rows: rows[0]["stages"].update(assembledDraft=None),
                       lambda rows: rows[0]["stages"].update(assembledDraft="x" * 12289),
                       lambda rows: rows[0]["stages"].update(wire="rejected"),
                       lambda rows: rows[0]["stages"].update(reason="private-marker")):
            rows = self.good_validator(self.directory, requests)
            mutate(rows)
            with self.assertRaises(ValueError):
                r.check_results(rows, requests)

    def test_cli_failure_does_not_echo_sensitive_input(self):
        output = io.StringIO()
        with patch("sys.argv", ["replay.py", "--directory", str(self.directory)]), \
             patch.object(r, "replay", side_effect=ValueError("private-sensitive-marker")), contextlib.redirect_stdout(output):
            self.assertEqual(r.main(), 1)
        self.assertNotIn("private-sensitive-marker", output.getvalue())


@unittest.skipUnless(os.environ.get("INTAKE_V2_FAKE_VECTORS"), "Explicit offline Kotlin integration input required")
class KotlinIntegrationTest(unittest.TestCase):
    setUp = ReplayTest.setUp
    case = ReplayTest.case
    write_inputs = ReplayTest.write_inputs
    # Explicit integration runs add two actual Kotlin bridge invocations, not model calls.
    def test_actual_kotlin_all_eight_fake_cases(self):
        vectors = r.private.read_json(Path(os.environ["INTAKE_V2_FAKE_VECTORS"]))
        for case in self.source["cases"]:
            case["raw"] = vectors[case["caseId"]]
        self.write_inputs()
        result = r.replay(self.directory, self.now)
        self.assertTrue(all(c["stages"]["semantic"] == "accepted" for c in result["cases"]))
        self.assertFalse(result["experimentalSuccess"])

    def test_actual_kotlin_negative_and_early_abort_stages(self):
        vectors = r.private.read_json(Path(os.environ["INTAKE_V2_FAKE_VECTORS"]))
        marker = "private-v2-marker-67c1"
        bad_wire = json.loads(vectors["i01"])
        bad_wire["unknown"] = marker
        bad_binding = json.loads(vectors["i02"])
        bad_binding["suggestions"][0]["sourceExcerpt"] = marker
        bad_semantic = json.loads(vectors["i03"])
        bad_semantic["questions"] = []
        self.source["cases"] = [self.case("i01", raw=json.dumps(bad_wire)), self.case("i02", raw=json.dumps(bad_binding)),
                                 self.case("i03", raw=json.dumps(bad_semantic)), self.case("i04", "incomplete", marker)]
        self.write_inputs()
        result = r.replay(self.directory, self.now)
        self.assertEqual([c["outcome"] for c in result["cases"]], ["rejected"] * 3 + ["incomplete"] + ["unrun"] * 4)
        self.assertEqual([result["cases"][i]["stages"]["reason"] for i in range(3)], ["invalid_wire", "binding_failed", "semantic_rejected"])
        self.assertIsNotNone(result["cases"][2]["stages"]["assembledDraft"])
        # Raw marker is allowed only in the private input/report/bridge raw files, never logs or test reports.
        for path in (self.directory / "validation.log", *list((self.directory / "test-reports").rglob("*"))):
            if path.is_file():
                self.assertNotIn(marker.encode(), path.read_bytes())
        for folder in (r.e.ROOT / "backend/build/reports/tests", r.e.ROOT / "backend/build/test-results"):
            for path in folder.rglob("*"):
                if path.is_file():
                    self.assertNotIn(marker.encode(), path.read_bytes())


if __name__ == "__main__":
    unittest.main()
