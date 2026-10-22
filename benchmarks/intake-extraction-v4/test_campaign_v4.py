import copy
import contextlib
import datetime as dt
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

import campaign_v4 as c
from test_revision_v4 import fixture_counts
from test_oracle_v4 import assembled, stages


class FakeClock:
    def __init__(self):
        self.value = 1000.0
        self.boot = "test-boot-identity"
        self.fail = False
    def monotonic(self):
        if self.fail: raise RuntimeError("synthetic lost clock")
        return self.value
    def identity(self): return {"version": c.native_clock.VERSION, "clock": c.native_clock.NAME, "bootSessionUuid": self.boot}
    def now(self): return dt.datetime(2026, 10, 19, 20, tzinfo=dt.timezone.utc) + dt.timedelta(seconds=self.value - 1000)
    def arguments(self): return dict(monotonic=self.monotonic, identity=self.identity, now=self.now)


class FakeRuntime:
    synthetic_fake = True
    def __init__(self, run, guard, request_guard, prepared, clock, fixtures, failure=None):
        self.run, self.guard, self.request_guard = run, guard, request_guard
        self.prepared, self.clock, self.fixtures, self.failure = prepared, clock, fixtures, failure
        self.requests = []; self.cancelled = 0
    def preflight(self, run):
        self.guard(run)
        if self.failure == "preflight": raise RuntimeError("synthetic preflight")
        return {"syntheticFake": True}
    def cancellation_probe(self, run, emit, deadline):
        self.guard(run); self.request_guard(run, self.prepared["requestSha256"]["probe"])
        self.requests.append("probe")
        if self.failure == "probe": raise RuntimeError("synthetic probe")
        return {"verified": True, "ownedPod": {"name": "synthetic-owned", "uid": "synthetic-uid"}, "probeNonScored": True}
    def generate(self, prompt, run, deadline, emit):
        self.guard(run)
        case_id = next(case["caseId"] for case in c.v.revision.prompt_cases(self.prepared["systemInstructions"]) if case["exactPrompt"] == prompt)
        request_hash = self.prepared["requestSha256"][case_id]
        if self.failure == "delete_claim":
            owner = self.guard.__self__
            (owner.directory / f"claim-{owner.active['ordinal']:02}.json").unlink()
        if self.failure == "wrong_case": request_hash = self.prepared["requestSha256"]["i02"]
        self.request_guard(run, request_hash); self.requests.append(case_id)
        if self.failure == "duplicate_launch": self.request_guard(run, request_hash)
        if self.failure == "generation": raise RuntimeError("synthetic monitor failure")
        if self.failure == "overrun": self.clock.value += 15000
        if self.failure == "clock": self.clock.fail = True
        raw = c.e.compact(self.fixtures[case_id]).decode()
        outcome = self.failure if self.failure in ("timeout", "incomplete", "empty", "unrun") else "response"
        if outcome == "empty": raw = " "
        if outcome == "unrun": raw = None
        return {"outcome": outcome, "raw": raw, "resources": {key: None for key in c.v.replay.RESOURCES}}
    def cancel(self, run, deadline, emit):
        self.cancelled += 1
        if self.failure == "delete_claim_cleanup":
            owner = self.guard.__self__
            (owner.directory / f"claim-{owner.active['ordinal']:02}.json").unlink()
        if self.failure == "cleanup": return {"verified": False}
        return {"verified": True}
    def safety_state(self): return {"syntheticFake": True, "cancelled": self.cancelled}


class CampaignTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="intake-v4-fake-campaign-", dir="/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name); self.directory.chmod(0o700)
        self.base_directory = self.directory
        self.clock = FakeClock()
        _, self.inputs, _, self.fixtures = c.e.verify()
        self.campaign = c.Campaign.create(self.directory, "synthetic_fake", "offline campaign test", "Synthetic fixtures only; no live runtime.", **self.clock.arguments())
        self.addCleanup(lambda: self.campaign.close())
        self.runtimes = []

    def fresh_campaign(self, name):
        self.campaign.close()
        self.directory = self.base_directory / name; self.directory.mkdir(mode=0o700)
        self.clock = FakeClock()
        self.campaign = c.Campaign.create(self.directory, "synthetic_fake", "offline test", "Synthetic fixtures only.", **self.clock.arguments())
        self.runtimes = []

    def revision(self, previous=None, suffix=""):
        instructions = c.v.revision.INSTRUCTIONS_R1 + suffix
        evidence = fixture_counts(instructions)
        prepared = c.v.revision.definition(str(uuid.uuid4()), instructions, evidence, previous)
        return prepared, evidence

    def run_batch(self, prepared, evidence, *, quality_failure=False, failure=None, validator=None):
        fixtures = copy.deepcopy(self.fixtures)
        if quality_failure: fixtures["i01"]["suggestions"][0]["context"]["content"] = "no running"
        def factory(run, guard, request_guard):
            runtime = FakeRuntime(run, guard, request_guard, prepared, self.clock, fixtures, failure)
            self.runtimes.append(runtime)
            return runtime
        def validate(case_id, raw, deadline):
            return {"caseId": case_id, "rawSha256": c.e.digest(raw.encode()),
                    "stages": stages(assembled(case_id, c.e.decode(raw.encode()), self.inputs))}
        return self.campaign.run_batch(prepared, evidence, runtime_factory=factory, validator=validator or validate)

    def test_failure_reopen_new_revision_success_and_no_later_admission(self):
        first, evidence = self.revision()
        report = self.run_batch(first, evidence, quality_failure=True)
        self.assertEqual(report["classification"], "quality_failure")
        self.assertFalse((self.directory / "terminal.json").exists())
        self.clock.value += 300  # Revision work spends the same campaign clock.
        self.campaign.close(); self.campaign = c.Campaign(self.directory, **self.clock.arguments())
        second, evidence = self.revision(c.e.digest(c.e.compact(first)), "\nKeep every supported fact.")
        report = self.run_batch(second, evidence)
        self.assertEqual(report["classification"], "passed")
        self.assertEqual(len(report["cases"]), 8)
        self.assertTrue(all(case["oracle"]["status"] == "passed" for case in report["cases"]))
        self.assertEqual(c.private.read_json(self.directory / "terminal.json")["status"], "passed")
        with self.assertRaises(ValueError): self.run_batch(second, evidence)
        self.assertEqual([runtime.cancelled for runtime in self.runtimes], [1, 1])
        self.assertEqual([runtime.requests for runtime in self.runtimes], [["probe", *c.e.IDS]] * 2)

    def test_same_prompt_new_uuid_and_wrong_predecessor_refuse(self):
        first, evidence = self.revision(); self.run_batch(first, evidence, quality_failure=True)
        for prior, suffix in ((c.e.digest(c.e.compact(first)), ""), ("a" * 64, " new instructions")):
            prepared, evidence = self.revision(prior, suffix)
            with self.assertRaises(ValueError): self.run_batch(prepared, evidence)
        self.assertEqual(len(self.runtimes), 1)

    def test_budget_reserve_and_revision_time_are_not_reset(self):
        prepared, evidence = self.revision()
        self.clock.value += 14400 - 240
        with self.assertRaises(ValueError): self.run_batch(prepared, evidence)
        self.assertFalse((self.directory / "claim-01.json").exists())
        self.assertEqual(self.runtimes, [])

    def test_concurrent_open_and_lost_claim_fence(self):
        with self.assertRaises(BlockingIOError): c.Campaign(self.directory, **self.clock.arguments())
        prepared, evidence = self.revision()
        with patch.object(c.Campaign, "intent", side_effect=KeyboardInterrupt):
            report = self.run_batch(prepared, evidence)
        self.assertEqual(report["classification"], "incomplete")
        self.assertEqual(self.runtimes[0].cancelled, 1)
        self.campaign.close()
        (self.directory / "receipt-01.json").unlink()
        with self.assertRaises(ValueError): c.Campaign(self.directory, **self.clock.arguments())

    def test_clock_reboot_regression_and_source_drift_refuse_before_runtime(self):
        prepared, evidence = self.revision()
        self.clock.boot = "different"
        with self.assertRaises(ValueError): self.run_batch(prepared, evidence)
        self.clock.boot = "test-boot-identity"; self.clock.value = 999
        with self.assertRaises(ValueError): self.run_batch(prepared, evidence)
        self.clock.value = 1001
        with patch.object(c, "tooling", return_value={}):
            with self.assertRaises(ValueError): self.run_batch(prepared, evidence)
        self.assertEqual(self.runtimes, [])

    def test_timeout_keeps_all_eight_and_fences_next_revision(self):
        prepared, evidence = self.revision()
        report = self.run_batch(prepared, evidence, failure="timeout")
        self.assertEqual(report["classification"], "incomplete")
        self.assertEqual([row["outcome"] for row in report["cases"]], ["timeout"] + ["unrun"] * 7)
        self.assertEqual(c.private.read_json(self.directory / "terminal.json")["status"], "incomplete")
        with self.assertRaises(ValueError): self.run_batch(prepared, evidence)

    def test_wrong_case_duplicate_transport_and_cleanup_failures_fence(self):
        prepared, evidence = self.revision()
        report = self.run_batch(prepared, evidence, failure="wrong_case")
        self.assertEqual(report["classification"], "incomplete")
        self.assertEqual(self.runtimes[0].requests, ["probe"])
        self.assertEqual(self.runtimes[0].cancelled, 1)

    def test_callback_overrun_still_cleans_once_and_cannot_pass(self):
        prepared, evidence = self.revision()
        report = self.run_batch(prepared, evidence, failure="overrun")
        self.assertEqual(report["classification"], "incomplete")
        self.assertTrue(report["deadlineBreach"])
        self.assertEqual(self.runtimes[0].cancelled, 1)
        self.assertEqual(len(self.runtimes[0].requests), 2)

    def test_three_quality_failures_exhaust_batch_probe_and_request_allowances(self):
        prior = None
        for index in range(3):
            prepared, evidence = self.revision(prior, "\nGeneric precision rule " + str(index))
            report = self.run_batch(prepared, evidence, quality_failure=True)
            self.assertEqual(report["classification"], "quality_failure")
            prior = c.e.digest(c.e.compact(prepared))
        self.assertEqual(c.private.read_json(self.directory / "terminal.json")["status"], "failed")
        self.assertEqual(sum(len(runtime.requests) for runtime in self.runtimes), 27)
        self.assertEqual(len(list(self.directory.glob("claim-*.json"))), 3)
        with self.assertRaises(ValueError): self.run_batch(*self.revision(prior, "different generic rule"))

    def test_every_infrastructure_failure_stops_later_cases_and_revisions(self):
        for failure in ("preflight", "probe", "generation", "incomplete", "duplicate_launch", "cleanup"):
            with self.subTest(failure=failure):
                self.fresh_campaign(failure)
                report = self.run_batch(*self.revision(), failure=failure)
                self.assertEqual(report["classification"], "incomplete")
                self.assertEqual(len(report["cases"]), 8)
                self.assertEqual(self.runtimes[0].cancelled, 1)
                if failure != "cleanup": self.assertLessEqual(len(self.runtimes[0].requests), 2)
                with self.assertRaises(ValueError): self.run_batch(*self.revision(suffix="generic revision"))
                self.assertEqual(len(self.runtimes), 1)

    def test_validator_or_oracle_failure_and_post_callback_drift_never_advance(self):
        for failure in ("validator", "oracle", "source"):
            with self.subTest(failure=failure):
                self.fresh_campaign(failure)
                def validator(case_id, raw, deadline):
                    if failure == "validator": raise RuntimeError("private-fixture-sentinel")
                    if failure == "source":
                        drift = patch.object(c, "tooling", return_value={}); drift.start(); self.addCleanup(drift.stop)
                    return {"caseId": case_id, "rawSha256": c.e.digest(raw.encode()), "stages": stages(assembled(case_id, c.e.decode(raw.encode()), self.inputs))}
                with patch.object(c.v.oracle_v4, "evaluate", side_effect=ValueError("oracle defect")) if failure == "oracle" else contextlib.nullcontext():
                    report = self.run_batch(*self.revision(), validator=validator)
                self.assertEqual(report["classification"], "incomplete")
                self.assertEqual(self.runtimes[0].requests, ["probe", "i01"])
                self.assertEqual(self.runtimes[0].cancelled, 1)
                if failure == "source": patch.stopall()

    def test_crash_after_cleanup_before_receipt_and_truncated_receipt_fence(self):
        prepared, evidence = self.revision()
        write = c.durable
        def fail_receipt(path, value):
            if path.name == "receipt-01.json": raise OSError("simulated publication loss")
            write(path, value)
        with patch.object(c, "durable", side_effect=fail_receipt):
            with self.assertRaises(OSError): self.run_batch(prepared, evidence)
        self.assertEqual(self.runtimes[0].cancelled, 1)
        self.assertTrue((self.directory / "batch-01/report.json").exists())
        self.campaign.close()
        with self.assertRaises(ValueError): c.Campaign(self.directory, **self.clock.arguments())
        c.private.private_write(self.directory / "receipt-01.json", b"{")
        with self.assertRaises(ValueError): c.Campaign(self.directory, **self.clock.arguments())

    def test_separate_process_continues_only_completed_failure_on_same_clock(self):
        prepared, evidence = self.revision(); self.run_batch(prepared, evidence, quality_failure=True)
        self.campaign.close()
        code = '''
import sys,uuid
from pathlib import Path
from test_campaign_v4 import c,FakeClock,FakeRuntime,fixture_counts,assembled,stages
clock=FakeClock();clock.value=1400
with c.Campaign(Path(sys.argv[1]),**clock.arguments()) as campaign:
 old=c.private.read_json(Path(sys.argv[1])/"batch-01/revision.json")
 instructions=old["systemInstructions"]+"\\nPreserve all reported facts."
 evidence=fixture_counts(instructions)
 prepared=c.v.revision.definition(str(uuid.uuid4()),instructions,evidence,c.e.digest(c.e.compact(old)))
 _,inputs,_,fixtures=c.e.verify()
 def factory(run,guard,request_guard):return FakeRuntime(run,guard,request_guard,prepared,clock,fixtures)
 def validate(case_id,raw,deadline):return {"caseId":case_id,"rawSha256":c.e.digest(raw.encode()),"stages":stages(assembled(case_id,c.e.decode(raw.encode()),inputs))}
 assert campaign.run_batch(prepared,evidence,runtime_factory=factory,validator=validate)["classification"]=="passed"
'''
        result = subprocess.run([sys.executable, "-c", code, str(self.directory)], capture_output=True,
                                # This is offline cross-process validation, not
                                # a model attempt or an inference budget grant.
                                env=dict(os.environ, PYTHONPATH=os.pathsep.join(sys.path)), timeout=600)
        self.assertEqual(result.returncode, 0, "Separate-process fake continuation failed")
        self.assertEqual(c.private.read_json(self.directory / "terminal.json")["status"], "passed")

    def test_expired_campaign_can_record_budget_exhaustion_but_not_infer(self):
        self.clock.value += 14401
        self.campaign.close(); self.campaign = c.Campaign(self.directory, **self.clock.arguments())
        with self.assertRaises(ValueError): self.run_batch(*self.revision())
        terminal = c.private.read_json(self.directory / "terminal.json")
        self.assertEqual(terminal["status"], "incomplete")
        self.assertEqual(terminal["reason"], "budget_exhausted")
        self.assertEqual(self.runtimes, [])

    def test_live_mode_refuses_all_test_overrides_before_claims_or_callbacks(self):
        prepared, evidence = self.revision()
        self.campaign.scope["executionMode"] = "live"
        callback = Mock()
        for overrides in ({"validator": callback}, {"runtime_factory": callback}):
            with self.assertRaises(ValueError): self.campaign.run_batch(prepared, evidence, **overrides)
        callback.assert_not_called()
        self.assertFalse((self.directory / "claim-01.json").exists())
        for key, value in self.clock.arguments().items():
            with self.subTest(clock=key), patch.object(c, "durable") as write:
                with self.assertRaises(ValueError):
                    c.Campaign.create(self.directory, "live", "test", "Must refuse test override", **{key: value})
                write.assert_not_called()
        # Constructor rejects injected clocks immediately after reading mode,
        # before trying to validate source or touch operational callbacks.
        path = self.directory / "campaign.json"; document = c.private.read_json(path)
        document["executionMode"] = "live"; path.unlink(); c.durable(path, document)
        for key, value in self.clock.arguments().items():
            with self.subTest(clock=key), self.assertRaises(ValueError): c.Campaign(self.directory, **{key: value})

    def test_deleted_active_claim_blocks_transport_and_false_pass_after_cleanup(self):
        for failure in ("delete_claim", "delete_claim_cleanup"):
            self.fresh_campaign(failure)
            report = self.run_batch(*self.revision(), failure=failure)
            self.assertEqual(report["classification"], "incomplete")
            self.assertEqual(self.runtimes[0].cancelled, 1)
            if failure == "delete_claim": self.assertEqual(self.runtimes[0].requests, ["probe"])
            self.assertEqual(c.private.read_json(self.directory / "terminal.json")["status"], "incomplete")
            with self.assertRaises(ValueError): self.run_batch(*self.revision(suffix="new generic instructions"))

    def test_clock_exception_after_launch_keeps_cleanup_and_unknown_time_null(self):
        report = self.run_batch(*self.revision(), failure="clock")
        self.assertEqual(report["classification"], "incomplete")
        self.assertEqual(self.runtimes[0].requests, ["probe", "i01"])
        self.assertEqual(self.runtimes[0].cancelled, 1)
        self.assertIsNone(report["finishedMonotonic"])
        self.assertIsNone(report["deadlineBreach"])
        self.assertTrue((self.directory / "receipt-01.json").exists())
        self.clock.fail = False
        with self.assertRaises(ValueError): self.run_batch(*self.revision(suffix="generic revision"))

    def test_cleanup_clock_failure_does_not_skip_the_single_cleanup_attempt(self):
        with patch.object(c, "CLEANUP_MONOTONIC", side_effect=[1000.0, RuntimeError("clock failed"), 1001.0]):
            report = self.run_batch(*self.revision())
        self.assertEqual(self.runtimes[0].cancelled, 1)
        self.assertEqual(report["classification"], "incomplete")
        self.assertEqual(report["errorCategory"], "cleanup_clock_failure")

    def test_attempt_cannot_be_hidden_as_unrun_and_probe_evidence_is_retained(self):
        report = self.run_batch(*self.revision(), failure="unrun")
        self.assertEqual(report["classification"], "incomplete")
        self.assertEqual([row["outcome"] for row in report["cases"]], ["incomplete"] + ["unrun"] * 7)
        self.assertEqual(self.runtimes[0].requests, ["probe", "i01"])
        evidence = c.private.read_json(self.directory / "batch-01/probe-result.json")
        self.assertEqual(evidence["evidence"]["ownedPod"]["uid"], "synthetic-uid")
        self.assertTrue(evidence["evidence"]["probeNonScored"])
        self.assertEqual(report["probeEvidenceSha256"], c.e.digest(c.private.private_read(self.directory / "batch-01/probe-result.json")))


if __name__ == "__main__": unittest.main()
