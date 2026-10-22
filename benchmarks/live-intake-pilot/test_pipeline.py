"""Real cross-process fake acquisition tests. No provider or runtime is constructed."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

import pilot_profile as p
import run_mac_pilot as launch
import seal_capture as capture
import campaign_v4 as c
import supervisor_v4 as supervisor
from test_profile import candidate


class PipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(tempfile.mkdtemp(prefix="pilot-integration-fake-", dir=supervisor.BASE))
        os.chmod(cls.root, 0o700)
        cls.directory = cls.root / "campaign"; cls.directory.mkdir(mode=0o700)
        grant = {"version": p.PROFILE, "executionMode": "synthetic_fake", "recordedBy": "offline_integration",
            "userInstruction": "Approved fake acquisition integration only, with zero model or runtime operations.",
            "applyInstruction": "apply guarded-live-intake-pilot", "campaignDirectory": str(cls.directory),
            "limits": p.LIMITS, "toolingSha256": c.tooling(p.PROFILE), "counter": None}
        c.durable(cls.root / "authority.json", grant)
        launch.create(cls.directory, cls.root / "authority.json", Path(os.environ["INTAKE_PILOT_TEST_PROMPT"]))
        supervisor.run(cls.directory, cls.directory / "prepared", wait=False)
        cls.supervision = Path(c.private.read_json(cls.directory / "supervisor-01.json")["directory"])
        # Observe without retaining an observer/worker channel. Its closure does not cancel.
        end = time.monotonic() + 600
        while not (cls.supervision / "outcome.json").exists():
            if time.monotonic() >= end:
                supervisor.cancel(cls.supervision)
                raise AssertionError("Fake integration exceeded its test observer limit")
            time.sleep(0.1)
        outcome = c.private.read_json(cls.supervision / "outcome.json")
        if outcome["reason"] != "normal" or outcome["cleanup"]["verified"] is not True:
            raise AssertionError("Fake integration failed; private evidence: " + str(cls.root))
        cls.receipt = capture.seal(cls.directory)

    @classmethod
    def tearDownClass(cls):
        # Only remove a completed temporary registration, never interrupt its cleanup.
        if (cls.supervision / "outcome.json").exists():
            registration = c.private.read_json(cls.supervision / "launchd.json")
            subprocess.run(["/bin/launchctl", "bootout", registration["domain"] + "/" + registration["label"]],
                           capture_output=True, timeout=10)
        # Preserve test evidence and terminal bytes; this is not a model allowance.
        print("Fake pipeline evidence:", cls.root)

    def test_actual_durable_capture_has_two_claims_and_unmistakable_fake_provenance(self):
        admitted = capture.verify(self.directory, self.receipt, "synthetic_fake")
        self.assertEqual("synthetic_fake", admitted["evidenceMode"])
        self.assertEqual(["probe.launched.json", "u02.launched.json"],
            sorted(path.name for path in (self.directory / "batch-01").glob("*.launched.json")))
        self.assertEqual(p.PROFILE, admitted["profile"])
        self.assertEqual(2, len(json.loads(admitted["draftJson"])["suggestions"]))

    def test_fake_cannot_be_loaded_as_live_or_without_expected_digest(self):
        for expected, mode in ((self.receipt, "live"), ("0" * 64, "synthetic_fake"), ("", "synthetic_fake")):
            with self.assertRaises(ValueError): capture.verify(self.directory, expected, mode)

    def test_symlink_and_hardlink_capture_are_rejected(self):
        path = self.directory / "capture.json"
        link = self.directory / "extra-link.json"
        os.link(path, link)
        try:
            with self.assertRaises(ValueError): capture.verify(self.directory, self.receipt, "synthetic_fake")
        finally: link.unlink()
        symbolic = self.root / "symbolic-campaign"
        symbolic.symlink_to(self.directory, target_is_directory=True)
        try:
            with self.assertRaises(ValueError): capture.verify(symbolic, self.receipt, "synthetic_fake")
        finally: symbolic.unlink()

    def test_payload_mutation_is_detected_even_with_matching_local_hash(self):
        path = self.directory / "capture.json"; original = c.private.private_read(path)
        value = json.loads(original); value["raw"] = "{}"; value["rawSha256"] = p.e.digest(b"{}")
        changed = p.e.compact(value)
        try:
            path.write_bytes(changed)
            with self.assertRaises(ValueError): capture.verify(self.directory, p.e.digest(changed), "synthetic_fake")
        finally: path.write_bytes(original)

    def test_traversal_oversize_and_failed_cleanup_cannot_be_imported(self):
        with self.assertRaises(ValueError): capture.verify(self.directory / ".." / "campaign", self.receipt, "synthetic_fake")
        path = self.directory / "capture.json"; original = c.private.private_read(path)
        try:
            oversized = b" " * 131073
            path.write_bytes(oversized)
            with self.assertRaises(ValueError): capture.verify(self.directory, p.e.digest(oversized), "synthetic_fake")
        finally: path.write_bytes(original)
        cleanup = self.supervision / "cleanup-result.json"; original = c.private.private_read(cleanup)
        try:
            failed = p.e.decode(original); failed["verified"] = False; cleanup.write_bytes(p.e.compact(failed))
            with self.assertRaises(ValueError): capture.verify(self.directory, self.receipt, "synthetic_fake")
        finally: cleanup.write_bytes(original)

    def test_duplicate_start_and_second_seal_are_refused(self):
        with self.assertRaises(ValueError): supervisor.run(self.directory, self.directory / "prepared", wait=False)
        with self.assertRaises(FileExistsError): capture.seal(self.directory)

    def test_pod_deadline_and_no_restore_policy_do_not_change_legacy_defaults(self):
        import datetime as dt
        import mac_runtime as mac
        runtime = mac.MacRuntime()
        base = {"runId": "11111111-1111-4111-8111-111111111111"}
        old = runtime.manifest(base)["spec"]["activeDeadlineSeconds"]
        run = dict(base, profile=p.PROFILE, runtimeExpiresUtc=(dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=120)).isoformat())
        remaining = runtime.manifest(run)["spec"]["activeDeadlineSeconds"]
        self.assertGreater(old, 3600); self.assertLessEqual(remaining, 105)
        runtime.paused = True
        with patch.object(runtime, "stop_pod"), patch.object(runtime, "baseline", return_value={"spec": {"replicas": 0}}), \
             patch.object(runtime, "document", return_value={"items": []}), patch.object(runtime, "scale_deployment") as scale:
            result = runtime.cancel(dict(run, restoreOriginalService=False), time.monotonic() + 5, lambda event: None)
            self.assertTrue(result["deploymentLeftPaused"]); self.assertFalse(result["restored"]); scale.assert_not_called()


if __name__ == "__main__":
    unittest.main()
