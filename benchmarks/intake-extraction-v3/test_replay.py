import contextlib
import copy
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
        self.temp = tempfile.TemporaryDirectory(prefix="intake-v3-replay-", dir=os.environ.get("INTAKE_V3_TEST_TEMP_ROOT", "/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode"))
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name).resolve(); self.directory.chmod(0o700)
        self.now = dt.datetime(2026, 10, 18, 18, tzinfo=dt.timezone.utc)
        self.definition, self.artifacts = r.e.verify()
        self.run = {"version": "intake-extraction-private-run-v3", "runId": str(uuid.uuid4()),
                    "executionMode": "offline_replay", "inputOrigin": "synthetic_fake", "profile": r.e.PROFILE,
                    "caseIds": r.e.IDS, "manifestSha256": r.e.digest((r.e.HERE / "manifest-v3.json").read_bytes()),
                    "toolingSha256": r.tooling(), "model": r.FAKE_MODEL, "settings": r.settings(self.artifacts), "resourceLimits": r.LIMITS}
        self.approval = {"version": "intake-extraction-replay-approval-v3", "runSha256": r.e.digest(r.e.compact(self.run)),
                         "purpose": "synthetic_offline_replay_only", "recordedBy": "synthetic fake unit test",
                         "recordedAt": "2026-10-18T17:00:00Z", "expiresUtc": "2026-10-18T19:00:00Z"}
        self.source = {"version": "intake-extraction-replay-v3", "cases": [self.case(c) for c in r.e.IDS]}

    def case(self, case_id, outcome="response", raw='{"suggestions":[],"questions":[]}'):
        return {"caseId": case_id, "outcome": outcome, "raw": raw, "resources": {k: None for k in sorted(r.RESOURCE_FIELDS)}}

    def write_inputs(self):
        for name, value in (("run.json", self.run), ("approval.json", self.approval), ("replay.json", self.source)):
            r.private.private_write(self.directory / name, r.e.compact(value))

    def good_validator(self, directory, requests):
        return [{"caseId": req["caseId"], "rawSha256": req["rawSha256"], "stages": {
            "wire": "accepted", "binding": "accepted", "semantic": "accepted", "reason": None,
            "assembledDraft": '{"syntheticFakeCallback":true}'}} for req in requests]

    def test_report_hashes_all_eight_blank_assessments_and_adaptation_labels(self):
        self.write_inputs(); report = r.replay(self.directory, self.now, self.good_validator)
        self.assertEqual([c["caseId"] for c in report["cases"]], r.e.IDS)
        self.assertTrue(all(c["rawSha256"] and c["resourcesProvenance"] == "unmeasured_fake" for c in report["cases"]))
        self.assertFalse(report["experimentalSuccess"])
        self.assertEqual(report["productionAdmission"], "closed")
        self.assertIn("validator adaptation", report["assembledDraftProvenance"])
        packet = r.private.read_json(self.directory / "human-review.json")
        self.assertEqual(packet["reportSha256"], r.e.digest((self.directory / "report.json").read_bytes()))
        self.assertIsNone(packet["independentHuman"])
        self.assertTrue(all(c["usefulFinal"] is None and c["criticalFailures"] is None and all(v is None for v in c["scores"].values()) for c in packet["cases"]))

    def test_cross_version_settings_manifest_source_and_expiry_refuse_before_response_read(self):
        for key, value in (("version", "intake-extraction-private-run-v2"), ("profile", "context-intake-suggestion-v1"),
                           ("profile", "context-intake-extraction-v2"), ("manifestSha256", "0" * 64), ("toolingSha256", {}),
                           ("caseIds", r.e.IDS[:-1]), ("executionMode", "inference")):
            run = copy.deepcopy(self.run); run[key] = value
            approval = dict(self.approval, runSha256=r.e.digest(r.e.compact(run)))
            with self.subTest(key=key, value=value), self.assertRaises(ValueError): r.authorize(run, approval, self.artifacts, self.now)
        for key, value in (("num_ctx", 8192), ("num_ctx", 4096.0), ("num_predict", True), ("formatSchemaSha256", "0" * 64)):
            run = copy.deepcopy(self.run); run["settings"][key] = value
            approval = dict(self.approval, runSha256=r.e.digest(r.e.compact(run)))
            with self.assertRaises(ValueError): r.authorize(run, approval, self.artifacts, self.now)
        for key, value in (("version", "intake-extraction-replay-approval-v2"), ("purpose", "inference"), ("expiresUtc", "2026-10-18T18:00:00Z")):
            with self.assertRaises(ValueError): r.authorize(self.run, dict(self.approval, **{key: value}), self.artifacts, self.now)
        self.approval["runSha256"] = "0" * 64; self.write_inputs(); (self.directory / "replay.json").unlink()
        callback = Mock()
        with patch.object(r.private, "private_read", wraps=r.private.private_read) as read:
            with self.assertRaises(ValueError): r.replay(self.directory, self.now, callback)
        self.assertNotIn(self.directory / "replay.json", [c.args[0] for c in read.call_args_list]); callback.assert_not_called()

    def test_timeout_and_empty_keep_denominator_without_scores_or_callbacks(self):
        self.source["cases"] = [self.case("i01", "empty", " "), self.case("i02", "timeout", None)]
        self.write_inputs(); callback = Mock(); report = r.replay(self.directory, self.now, callback)
        callback.assert_not_called()
        self.assertEqual([c["outcome"] for c in report["cases"]], ["empty", "timeout"] + ["unrun"] * 6)
        self.assertEqual(report["usefulness"], "incomplete")
        self.assertTrue(all(c["stages"]["semantic"] == "not_run" for c in report["cases"]))

    def test_shapes_sizes_attempts_order_and_later_cases_after_abort_refuse(self):
        cases = self.source["cases"]
        for variant in (cases[1:], list(reversed(cases)), [cases[0], cases[0]],
                        [self.case("i01", "incomplete", "partial"), self.case("i02")],
                        [dict(cases[0], attempt=2)], [dict(cases[0], raw="x" * 12289)], [None]):
            with self.assertRaises(ValueError): r.cases_from(dict(self.source, cases=variant), self.run)
        with self.assertRaises(ValueError): r.cases_from(dict(self.source, version="intake-extraction-replay-v2"), self.run)

    def test_fake_and_invalid_resources_refuse_imported_values_stay_unverified(self):
        for value in (1, True, -1, float("nan")):
            source = copy.deepcopy(self.source); source["cases"][0]["resources"]["inputTokens"] = value
            with self.assertRaises(ValueError): r.cases_from(source, self.run)
        self.run["inputOrigin"] = "externally_recorded"; self.run["model"] = r.MODEL
        self.approval["runSha256"] = r.e.digest(r.e.compact(self.run))
        self.source["cases"][0]["resources"]["elapsedMs"] = 123
        self.write_inputs(); report = r.replay(self.directory, self.now, self.good_validator)
        self.assertEqual(report["cases"][0]["resourcesProvenance"], "externally_recorded_unverified")
        self.assertEqual(report["cases"][0]["resources"]["elapsedMs"], 123)
        self.assertIsNone(report["cases"][0]["resources"]["inputTokens"])

    def test_private_modes_links_duplicates_and_existing_outputs_refuse(self):
        self.write_inputs(); callback = Mock(); path = self.directory / "run.json"; path.chmod(0o644)
        with self.assertRaises(ValueError): r.replay(self.directory, self.now, callback)
        path.chmod(0o600)
        (self.directory / "symbolic").symlink_to(path)
        with self.assertRaises(ValueError): r.private.private_read(self.directory / "symbolic")
        os.link(path, self.directory / "hard")
        with self.assertRaises(ValueError): r.private.private_read(path)
        (self.directory / "hard").unlink()
        r.private.private_write(self.directory / "report.json", b"original")
        with self.assertRaises(ValueError): r.replay(self.directory, self.now, callback)
        callback.assert_not_called()
        with self.assertRaises(FileExistsError): r.private.private_write(path, b"new")
        with self.assertRaises(ValueError): r.e.decode(b'{"cases":[],"cases":[]}')

    def test_invalid_stage_results_or_callback_failure_preserve_lock_without_report(self):
        requests = [{"caseId": "i01", "rawSha256": "a" * 64, "raw": "{}"}]
        for change in (lambda x: x.append(x[0]), lambda x: x[0].update(caseId="i02"),
                       lambda x: x[0]["stages"].update(assembledDraft=None), lambda x: x[0]["stages"].update(reason="private-marker"),
                       lambda x: x[0]["stages"].update(assembledDraft="x" * 12289), lambda x: x[0]["stages"].update(wire="rejected")):
            rows = self.good_validator(self.directory, requests); change(rows)
            with self.assertRaises(ValueError): r.check_results(rows, requests)
        self.write_inputs()
        with self.assertRaises(ValueError): r.replay(self.directory, self.now, Mock(side_effect=ValueError("private-marker")))
        self.assertTrue((self.directory / "replay.lock").exists()); self.assertFalse((self.directory / "report.json").exists())
        callback = Mock()
        with self.assertRaises(ValueError): r.replay(self.directory, self.now, callback)
        callback.assert_not_called()

    def test_cli_never_echoes_sensitive_exception(self):
        output = io.StringIO()
        with patch("sys.argv", ["replay.py", "--directory", str(self.directory)]), patch.object(r, "replay", side_effect=ValueError("private-marker")), contextlib.redirect_stdout(output):
            self.assertEqual(r.main(), 1)
        self.assertNotIn("private-marker", output.getvalue())


@unittest.skipUnless(os.environ.get("INTAKE_V3_FAKE_VECTORS"), "Explicit offline Kotlin integration input required")
class KotlinIntegrationTest(unittest.TestCase):
    setUp = ReplayTest.setUp
    case = ReplayTest.case
    write_inputs = ReplayTest.write_inputs

    def test_actual_kotlin_all_eight_fake_cases(self):
        vectors = r.private.read_json(Path(os.environ["INTAKE_V3_FAKE_VECTORS"]))
        for case in self.source["cases"]: case["raw"] = vectors[case["caseId"]]
        self.write_inputs(); report = r.replay(self.directory, self.now)
        self.assertTrue(all(c["stages"]["semantic"] == "accepted" for c in report["cases"]))
        self.assertFalse(report["experimentalSuccess"])

    def test_actual_kotlin_duplicates_binding_semantic_empty_and_early_abort(self):
        vectors = r.private.read_json(Path(os.environ["INTAKE_V3_FAKE_VECTORS"]))
        marker = "private-v3-marker-12ea"
        wire = json.loads(vectors["i01"]); wire["suggestions"][0]["unresolvedFields"] = ["observedOn", "observedOn"]
        binding = json.loads(vectors["i02"]); binding["suggestions"][0]["sourceExcerpt"] = marker
        semantic = json.loads(vectors["i03"]); semantic["questions"] = []
        self.source["cases"] = [self.case("i01", raw=json.dumps(wire)), self.case("i02", raw=json.dumps(binding)),
                                self.case("i03", raw=json.dumps(semantic)), self.case("i04", "empty", " "),
                                self.case("i05", "incomplete", marker)]
        self.write_inputs(); report = r.replay(self.directory, self.now)
        self.assertEqual([c["outcome"] for c in report["cases"]], ["rejected"] * 3 + ["empty", "incomplete"] + ["unrun"] * 3)
        self.assertEqual([report["cases"][i]["stages"]["reason"] for i in range(3)], ["invalid_wire", "binding_failed", "semantic_rejected"])
        self.assertIsNotNone(report["cases"][2]["stages"]["assembledDraft"])
        for path in (self.directory / "validation.log", *list((self.directory / "test-reports").rglob("*"))):
            if path.is_file(): self.assertNotIn(marker.encode(), path.read_bytes())
        for folder in (r.e.ROOT / "backend/build/reports/tests", r.e.ROOT / "backend/build/test-results"):
            for path in folder.rglob("*"):
                if path.is_file(): self.assertNotIn(marker.encode(), path.read_bytes())


if __name__ == "__main__":
    unittest.main()
