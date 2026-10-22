import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import durable_tests_v4 as d
import supervisor_v4 as s


class DurablePolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="v4-offline-policy-", dir=s.BASE)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.spec = {"mode": "offline_tests", "nonce": "fake-test-only", "suite": "shared", "kotlin": False,
            "toolingSha256": {"source": "before"}, "workCutoff": d.c.native_clock.monotonic() + 30,
            "expiresUnix": time.time() + 30}
        d.life.write(self.directory / "job.json", self.spec)
        d.life.write(self.directory / "cleanup-result.json", {"verified": True})

    def test_fixed_allowlist_refuses_arbitrary_commands(self):
        for suite in ("curl", "python -c arbitrary", "model", "restart"):
            with self.assertRaises(ValueError): d.command(suite)
        self.assertIn("test_mac_runtime*.py", d.command("shared"))

    def test_launcher_failures_retry_only_three_times_with_one_fixed_deadline(self):
        calls = []
        def failure(directory, spec, index):
            calls.append((index, spec["workCutoff"]))
            return {"classification": "launcher_failure", "exitCode": None}
        with patch.object(d, "fingerprint", return_value=self.spec["toolingSha256"]):
            d.execute(self.directory, self.spec, attempt=failure)
        self.assertEqual([x[0] for x in calls], [1, 2, 3])
        self.assertEqual(len({x[1] for x in calls}), 1)
        self.assertEqual(d.c.private.read_json(self.directory / "test-result.json")["classification"], "attempt_budget_exhausted")

    def test_assertion_failure_waits_for_changed_source_repair_then_reruns(self):
        current = dict(self.spec["toolingSha256"])
        calls = []
        def attempt(directory, spec, index):
            calls.append(index)
            return {"classification": "test_failure" if index == 1 else "passed", "exitCode": 1 if index == 1 else 0}
        def repair_wait(delay):
            self.assertEqual(calls, [1])
            current["source"] = "after"
            d.life.write(self.directory / "repair-01.json", {"jobSha256": d.c.e.digest(d.c.private.private_read(self.directory / "job.json")),
                "previousAttempt": 1, "sourceSha256": dict(current), "checksEvidenceSha256": "a" * 64})
        with patch.object(d, "fingerprint", side_effect=lambda spec: dict(current)):
            d.execute(self.directory, self.spec, attempt=attempt, sleep=repair_wait)
        self.assertEqual(calls, [1, 2])
        self.assertEqual(d.c.private.read_json(self.directory / "attempt-01-result.json")["classification"], "test_failure")
        self.assertEqual(d.c.private.read_json(self.directory / "test-result.json")["classification"], "passed")

    def test_unknown_process_loss_is_not_blindly_retried(self):
        calls = []
        def lost(directory, spec, index):
            calls.append(index)
            return {"classification": "process_lost", "exitCode": -9}
        with patch.object(d, "fingerprint", return_value=self.spec["toolingSha256"]):
            d.execute(self.directory, self.spec, attempt=lost)
        self.assertEqual(calls, [1])
        self.assertEqual(d.c.private.read_json(self.directory / "test-result.json")["classification"], "process_lost")

    def test_unchanged_source_repair_does_not_authorize_retry(self):
        def failed(directory, spec, index):
            d.life.write(directory / "repair-01.json", {"jobSha256": d.c.e.digest(d.c.private.private_read(directory / "job.json")),
                "previousAttempt": 1, "sourceSha256": spec["toolingSha256"]})
            return {"classification": "test_failure", "exitCode": 1}
        with patch.object(d, "fingerprint", return_value=self.spec["toolingSha256"]):
            with self.assertRaises(ValueError): d.execute(self.directory, self.spec, attempt=failed)
        self.assertFalse((self.directory / "attempt-02-intent.json").exists())

    def test_inventory_binds_shared_test_edits_and_added_tests(self):
        fake=self.directory/'checkout';tests=fake/'benchmarks/scoped-profiles';tests.mkdir(mode=0o700,parents=True)
        path=tests/'test_mac_runtime.py';path.write_text('# before\n')
        with patch.object(d.c.e,'ROOT',fake),patch.object(d.c,'tooling',return_value={}):
            before=d.fingerprint(self.spec)
            path.write_text('# fixed\n');changed=d.fingerprint(self.spec)
            self.assertNotEqual(before,changed)
            (tests/'test_mac_runtime_added.py').write_text('# added\n')
            added=d.fingerprint(self.spec)
            self.assertNotEqual(changed,added)
            self.assertIn('benchmarks/scoped-profiles/test_mac_runtime_added.py',added)

    def test_failure_observer_notifies_before_repair_deadline(self):
        d.life.write(self.directory/'attempt-01-result.json',{'classification':'test_failure','exitCode':1})
        started=time.monotonic();event=d.watch(self.directory)
        self.assertEqual(event['state'],'awaiting_repair')
        self.assertLess(time.monotonic()-started,1)

    def test_fresh_executor_inherits_deadline_and_only_remaining_third_attempt(self):
        root=self.directory/'original';root.mkdir(mode=0o700)
        original=dict(self.spec,maxAttempts=3,startMonotonic=d.c.native_clock.monotonic(),startedUnix=time.time())
        d.life.write(root/'job.json',original)
        child=self.directory/'replacement';child.mkdir(mode=0o700)
        spec=dict(original,attemptOffset=2,budgetRoot=str(root),nonce='new-executor',
                  budgetAuthoritySha256=d.c.e.digest(d.c.private.private_read(root/'job.json')))
        d.life.write(child/'job.json',spec)
        d.life.write(root/'restart-03.json',{'directory':str(child),'nonce':spec['nonce']})
        d.life.write(child/'cleanup-result.json',{'verified':True})
        calls=[]
        def attempt(directory,scope,index):calls.append(index);return {'classification':'passed','exitCode':0}
        with patch.object(d,'fingerprint',return_value=spec['toolingSha256']):d.execute(child,spec,attempt=attempt)
        self.assertEqual(calls,[3])
        self.assertEqual(d.c.private.read_json(child/'test-result.json')['attempts'],3)
        with self.assertRaises(ValueError):d.validate_budget(child,dict(spec,expiresUnix=spec['expiresUnix']+1))


@unittest.skipUnless(sys.platform == "darwin", "actual detached launchd offline test run")
class DetachedOfflineTest(unittest.TestCase):
    def test_real_shared_suite_finishes_without_monitoring_caller(self):
        directory = d.start("shared")
        end = time.monotonic() + 30
        while not (directory / "outcome.json").exists():
            self.assertLess(time.monotonic(), end)
            time.sleep(0.1)
        outcome = s.status(directory)["outcome"]
        self.assertEqual(outcome["reason"], "normal")
        self.assertTrue(outcome["cleanup"]["verified"])
        self.assertEqual(d.c.private.read_json(directory / "test-result.json")["classification"], "passed")
        self.assertEqual(d.c.private.read_json(directory / "test-result.json")["attempts"], 1)
        job = d.c.private.read_json(directory / "launchd.json")
        subprocess.run(["/bin/launchctl", "bootout", job["domain"] + "/" + job["label"]],
                       capture_output=True, timeout=10, check=True)


if __name__ == "__main__": unittest.main()
