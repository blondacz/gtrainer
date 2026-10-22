import contextlib
import copy
import datetime as dt
import io
import json
import os
from pathlib import Path
import secrets
import stat
import tempfile
import unittest
from unittest.mock import patch
import uuid

import capture
import protocol


class PrivateCaptureTest(unittest.TestCase):
    now = dt.datetime(2026, 10, 18, tzinfo=dt.timezone.utc)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve())
        self.directory = Path(self.temporary.name)
        self.directory.chmod(0o700)
        self.addCleanup(self.temporary.cleanup)
        self.definition, self.artifacts = protocol.verify()
        self.calls = 0

    def write(self, name, value):
        capture.private_write(self.directory / name, protocol.compact(value))

    def inputs(self, profile="scoped-descriptive-v1", correction=True):
        case_ids = [c["caseId"] for c in self.artifacts["cases"] if c["profile"] == profile]
        run = {"version": "scoped-private-run-v1", "runId": str(uuid.uuid4()), "executionMode": "offline_replay",
               "inputOrigin": "synthetic_fake", "profile": profile, "caseIds": case_ids,
               "manifestSha256": protocol.digest((protocol.HERE / "manifest-v1.json").read_bytes()),
               "model": {"candidate": "synthetic-fake", "provider": "no-provider", "modelVersionSha256": "a" * 64},
               "settings": {"temperature": 0, "seed": 123},
               "resourceLimits": {"attemptTimeoutSeconds": 60, "caseTimeoutSeconds": 120,
                                  "maxResponseBytes": 12288, "memoryLimitBytes": 1000000, "maxCostUsd": 0}}
        approval = {"version": "scoped-capture-approval-v1", "runSha256": protocol.digest(protocol.compact(run)),
                    "purpose": "synthetic_offline_replay_only", "allowCorrection": correction, "recordedBy": "synthetic-test",
                    "recordedAt": "2026-10-17T23:00:00Z", "expiresUtc": "2026-10-18T01:00:00Z"}
        return run, approval

    def attempt(self, outcome="response", raw="{}", number=1):
        return {"attempt": number, "outcome": outcome, "raw": raw,
                "resources": {name: None for name in capture.RESOURCE_FIELDS}}

    def stage(self, run, approval, cases):
        self.write("run.json", run)
        self.write("approval.json", approval)
        self.write("replay.json", {"version": "scoped-private-replay-v1", "cases": cases})

    def fake_validator(self, directory, requests):
        self.calls += 1
        return [{"caseId": r["caseId"], "attempt": r["attempt"],
                 "status": "accepted" if r["raw"] == "valid-fake" else "rejected"} for r in requests]

    def replay(self):
        return capture.replay_capture(self.directory, self.now, self.fake_validator)

    def test_all_profiles_keep_full_denominator_attempts_and_private_reports(self):
        for profile in protocol.PROFILES:
            with self.subTest(profile=profile), tempfile.TemporaryDirectory(dir=self.directory) as nested:
                outer = self.directory
                self.directory = Path(nested)
                try:
                    run, approval = self.inputs(profile)
                    ids = run["caseIds"]
                    cases = [
                        {"caseId": ids[0], "attempts": [self.attempt(), self.attempt(raw="valid-fake", number=2)]},
                        {"caseId": ids[1], "attempts": [self.attempt(outcome="timeout", raw=None)]},
                        {"caseId": ids[2], "attempts": [self.attempt(raw="")]},
                        {"caseId": ids[3], "attempts": [self.attempt(outcome="incomplete", raw="partial")]},
                        {"caseId": ids[4], "attempts": [self.attempt(outcome="unrun", raw=None)]}]
                    self.stage(run, approval, cases)
                    result = self.replay()
                    self.assertEqual(len(result["cases"]), len(ids))
                    self.assertEqual(result["inferenceCalls"], 0)
                    self.assertEqual(result["productionAdmission"], "closed")
                    self.assertEqual([c["finalOutcome"] for c in result["cases"][:6]],
                                     ["accepted", "timeout", "empty", "incomplete", "unrun", "unrun"])
                    self.assertEqual([r["validatedOutcome"] for r in result["cases"][0]["attempts"]], ["rejected", "accepted"])
                    for name in ("capture.json", "human-review.json"):
                        self.assertEqual(stat.S_IMODE((self.directory / name).stat().st_mode), 0o600)
                    review = capture.read_json(self.directory / "human-review.json")
                    self.assertEqual(review["captureSha256"], protocol.digest(capture.private_read(self.directory / "capture.json")))
                    self.assertEqual(review["qualification"], "incomplete")
                    self.assertTrue(all(all(v is None for v in c["scores"].values()) for c in review["cases"]))
                    with self.assertRaises(ValueError):
                        self.replay()
                finally:
                    self.directory = outer

    def test_absent_stale_mismatched_or_inference_approval_has_zero_callbacks(self):
        run, approval = self.inputs()
        self.write("run.json", run)
        # Missing approval refuses before even attempting to read a replay.
        with self.assertRaises(FileNotFoundError):
            self.replay()
        changes = [lambda a: a.update(expiresUtc="2026-10-17T23:59:59Z"),
                   lambda a: a.update(recordedAt="2026-10-18T00:30:00Z"),
                   lambda a: a.update(runSha256="0" * 64),
                   lambda a: a.update(purpose="inference"),
                   lambda a: a.update(allowCorrection="true")]
        for change in changes:
            bad = copy.deepcopy(approval)
            change(bad)
            self.write("approval.json", bad)
            with self.assertRaises(ValueError):
                self.replay()
            (self.directory / "approval.json").unlink()
        self.assertEqual(self.calls, 0)

    def test_live_mode_settings_model_manifest_profile_and_case_substitution_refused(self):
        run, approval = self.inputs()
        changes = [lambda r: r.update(executionMode="live"), lambda r: r.update(profile="connected-review-v1"),
                   lambda r: r["caseIds"].pop(), lambda r: r.update(manifestSha256="0" * 64),
                   lambda r: r["settings"].update(temperature=1), lambda r: r["model"].update(candidate="another")]
        for change in changes:
            bad = copy.deepcopy(run)
            change(bad)
            with self.assertRaises(ValueError):
                capture.authorize(bad, approval, self.definition, self.now)
        self.assertEqual(self.calls, 0)

    def test_unapproved_correction_and_invalid_raw_envelopes_refused_before_validation(self):
        run, approval = self.inputs(correction=False)
        self.stage(run, approval, [{"caseId": "d01", "attempts": [self.attempt(), self.attempt(number=2)]}])
        with self.assertRaises(ValueError):
            self.replay()
        self.assertEqual(self.calls, 0)
        for cases in ([{"caseId": "unknown", "attempts": []}],
                      [{"caseId": "d01", "attempts": [self.attempt(raw="x" * 12289)]}],
                      [{"caseId": "d01", "attempts": [self.attempt(number=3)]}],
                      [{"caseId": "d01", "attempts": []}] * 2):
            with self.assertRaises(ValueError):
                capture.attempts_from({"version": "scoped-private-replay-v1", "cases": cases}, run)

    def test_orphan_and_redundant_correction_never_replace_first_final(self):
        run, approval = self.inputs()
        self.stage(run, approval, [{"caseId": "d01", "attempts": [self.attempt(raw="valid-fake", number=2)]},
                                   {"caseId": "d02", "attempts": [self.attempt(raw="valid-fake"), self.attempt(number=2)]},
                                   {"caseId": "d03", "attempts": [self.attempt(outcome="timeout", raw=None), self.attempt(raw="valid-fake", number=2)]}])
        cases = self.replay()["cases"]
        self.assertEqual([c["finalOutcome"] for c in cases[:3]], ["unrun", "accepted", "timeout"])
        self.assertTrue(all(not c["attempts"][-1]["eligible"] for c in cases[:3]))

    def test_imported_resource_overruns_cannot_count_as_accepted(self):
        run, approval = self.inputs()
        rows = []
        for case, field, value in (("d01", "elapsedMs", 60001), ("d02", "peakRssBytes", 1000001), ("d03", "costUsd", 1)):
            attempt = self.attempt(raw="valid-fake")
            attempt["resources"][field] = value
            rows.append({"caseId": case, "attempts": [attempt]})
        self.stage(run, approval, rows)
        cases = self.replay()["cases"]
        self.assertEqual([c["finalOutcome"] for c in cases[:3]], ["timeout", "incomplete", "incomplete"])
        self.assertTrue(all(c["attempts"][0]["validatedOutcome"] == "accepted" for c in cases[:3]))

    def test_permissions_symlinks_hardlinks_repository_paths_and_overwrite_refused(self):
        path = self.directory / "safe.json"
        capture.private_write(path, b"{}")
        with self.assertRaises(FileExistsError):
            capture.private_write(path, b"replacement")
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            capture.private_read(path)
        path.chmod(0o600)
        (self.directory / "link").symlink_to(path)
        with self.assertRaises(ValueError):
            capture.private_read(self.directory / "link")
        os.link(path, self.directory / "hard")
        with self.assertRaises(ValueError):
            capture.private_read(path)
        self.directory.chmod(0o755)
        with self.assertRaises(ValueError):
            capture.private_directory(self.directory)
        self.directory.chmod(0o700)
        with self.assertRaises(ValueError):
            capture.private_directory(protocol.HERE)

    def test_runtime_sensitive_marker_stays_private_and_cli_errors_do_not_echo(self):
        marker = secrets.token_hex(32)
        run, approval = self.inputs()
        self.stage(run, approval, [{"caseId": "d01", "attempts": [self.attempt(raw=marker)]}])
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stdout):
            result = self.replay()
        self.assertFalse(marker in stdout.getvalue())
        self.assertTrue(result["cases"][0]["attempts"][0]["raw"] == marker)
        with patch("sys.argv", ["capture.py", "--directory", str(self.directory)]), contextlib.redirect_stdout(stdout):
            self.assertEqual(capture.main(), 1)
        self.assertFalse(marker in stdout.getvalue())
        self.assertFalse(any(marker.encode() in p.read_bytes() for p in protocol.HERE.iterdir() if p.is_file()))

    def test_invalid_validator_output_cannot_publish_capture(self):
        run, approval = self.inputs()
        self.stage(run, approval, [{"caseId": "d01", "attempts": [self.attempt()]}])
        with self.assertRaises(ValueError):
            capture.replay_capture(self.directory, self.now, lambda *_: [{"caseId": "d02", "attempt": 1, "status": "accepted"}])
        self.assertFalse((self.directory / "capture.json").exists())


if __name__ == "__main__":
    unittest.main()
