import datetime as dt
import copy
import os
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import Mock, patch

import acquire_v3 as a
import experiment as e
import replay


class FakeRuntime:
    synthetic_fake = True
    def __init__(self, clock, outputs=None, fail_stop=False):
        self.clock, self.outputs, self.calls, self.fail_stop = clock, list(outputs or []), [], fail_stop

    def preflight(self, run): self.calls.append("preflight"); return {"fake": True}
    def cancellation_probe(self, run, emit, deadline): self.calls.append("probe"); return {"verified": True}
    def generate(self, prompt, run, deadline, emit):
        self.calls.append("generate")
        self.clock[0] += 0.1
        return self.outputs.pop(0) if self.outputs else response()
    def cancel(self, run, deadline, emit):
        self.calls.append("stop")
        if self.fail_stop: self.clock[0] = deadline + 1
        return {"verified": not self.fail_stop}


def response(outcome="response", raw='{"fake":true}'):
    return {"outcome": outcome, "raw": raw,
            "resources": {k: None for k in replay.RESOURCE_FIELDS}}


class AcquisitionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="intake-v3-acquire-", dir=os.environ.get("INTAKE_V3_TEST_TEMP_ROOT", "/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode"))
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name); self.directory.chmod(0o700)
        self.now = dt.datetime(2026, 10, 18, 18, tzinfo=dt.timezone.utc)
        self.run = a.make_run(str(uuid.uuid4()), "synthetic_fake")
        self.approval = {"version": a.APPROVAL_VERSION, "runSha256": e.digest(e.compact(self.run)),
            "purpose": "synthetic_fake_acquisition_only", "recordedBy": "fake unit test",
            "recordedAt": "2026-10-18T17:00:00Z", "expiresUtc": "2026-10-18T19:00:00Z",
            "allowCancellationProbe": True, "allowCorrection": False, "approvalEvidence": "fixture only; no authority"}
        self.clock = [10.0]

    def runit(self, fake=None, validator=None):
        fake = fake or FakeRuntime(self.clock)
        return a.run_acquisition(self.directory, self.run, self.approval, fake,
            validator or self.valid, monotonic=lambda: self.clock[0], now=lambda: self.now), fake

    @staticmethod
    def valid(case_id, raw, deadline):
        return {"caseId": case_id, "rawSha256": e.digest(raw.encode()), "stages": {
            "wire": "accepted", "binding": "accepted", "semantic": "accepted", "reason": None,
            "assembledDraft": '{"synthetic":true}'}}

    def test_eight_complete_cases_distinct_fake_authority_and_private_outputs(self):
        result, fake = self.runit()
        self.assertEqual(len(result["cases"]), 8)
        self.assertEqual(result["executionMode"], "synthetic_fake")
        self.assertEqual(result["cases"][0]["attempts"][0]["validation"], "accepted")
        self.assertEqual(fake.calls.count("generate"), 8)
        self.assertTrue(all((self.directory / n).exists() for n in a.FILES))
        self.assertEqual((self.directory / "acquisition-events.jsonl").stat().st_mode & 0o777, 0o600)

    def test_binding_mismatch_has_zero_runtime_callbacks(self):
        fake = FakeRuntime(self.clock)
        bad = dict(self.run, profile="wrong")
        with self.assertRaises(ValueError): a.run_acquisition(self.directory, bad, self.approval, fake, self.valid, now=lambda: self.now)
        self.assertEqual(fake.calls, [])

    def test_expired_and_fake_cannot_authorize_live(self):
        with self.assertRaises(ValueError): a.authorize(self.run, self.approval, self.now + dt.timedelta(hours=2))
        live = a.make_run(str(uuid.uuid4()), "live")
        fake = dict(self.approval, runSha256=e.digest(e.compact(live)))
        with self.assertRaises(ValueError): a.authorize(live, fake, self.now)

    def test_empty_advances_timeout_aborts_and_marks_remaining_unrun(self):
        rows = [response("empty", ""), response("timeout", None)]
        result, fake = self.runit(FakeRuntime(self.clock, rows))
        self.assertEqual([c["attempts"][0]["outcome"] for c in result["cases"][:3]], ["empty", "timeout", "unrun"])
        self.assertEqual(fake.calls.count("generate"), 2)
        self.assertEqual(result["status"], "aborted")

    def test_validation_deadline_and_failed_stop_are_retained(self):
        def late(case_id, raw, deadline):
            self.clock[0] = deadline + 1
            return self.valid(case_id, raw, deadline)
        fake = FakeRuntime(self.clock, fail_stop=True)
        result, _ = self.runit(fake, late)
        self.assertEqual(result["cases"][0]["attempts"][0]["outcome"], "response")
        self.assertEqual(result["errorCategory"], "validation_deadline")
        self.assertFalse(result["stopVerified"])
        self.assertEqual(result["status"], "aborted")

    def test_validator_rejection_is_retained_as_stages(self):
        def reject(case_id, raw, deadline):
            return {"caseId": case_id, "rawSha256": e.digest(raw.encode()), "stages": {
                "wire": "rejected", "binding": "not_run", "semantic": "not_run", "reason": "invalid_wire", "assembledDraft": None}}
        result, _ = self.runit(validator=reject)
        first = result["cases"][0]["attempts"][0]
        self.assertEqual(first["validation"], "rejected")
        self.assertEqual(first["stages"]["reason"], "invalid_wire")

    def test_every_identity_constraint_and_type_binding_refuses_before_callbacks(self):
        changes = [(key, value) for key, value in (
            ("version", "scoped-intake-acquisition-v1"), ("profile", "context-intake-extraction-v2"),
            ("caseIds", list(reversed(e.IDS))), ("manifestSha256", "0" * 64), ("toolingSha256", {}),
            ("model", replay.MODEL), ("runtime", {}), ("experiment", {}), ("runnerPolicy", {}), ("requestVariant", {}))]
        for key, value in changes:
            run = copy.deepcopy(self.run); run[key] = value
            approval = dict(self.approval, runSha256=e.digest(e.compact(run)))
            runtime = Mock(synthetic_fake=True)
            with self.subTest(key=key), self.assertRaises(ValueError):
                a.run_acquisition(self.directory, run, approval, runtime, self.valid, now=lambda: self.now)
            self.assertEqual(runtime.mock_calls, [])
        for field, key, value in (("settings", "num_ctx", 4096.0), ("settings", "think", 0),
                                  ("resourceLimits", "attemptTimeoutSeconds", 901),
                                  ("resourceLimits", "memoryLimitBytes", 10 * 1024**3),
                                  ("resourceLimits", "memoryAbortBytes", 9 * 1024**3),
                                  ("runtime", "vmMemoryBytes", 12 * 1024**3),
                                  ("runnerPolicy", "environment", {"LLAMA_ARG_REPACK": "1"}),
                                  ("runnerPolicy", "verifyRunnerBeforeScoring", False),
                                  ("requestVariant", "requestOptionsDelta", {"use_mmap": 1}),
                                  ("requestVariant", "requestSha256", {}),
                                  ("requestVariant", "frozenContextProofSha256", "0" * 64),
                                  ("experiment", "templateSha256", "0" * 64),
                                  ("experiment", "contextProofSha256", "0" * 64),
                                  ("experiment", "schemaSha256", "0" * 64),
                                  ("experiment", "requestSha256", {})):
            run = copy.deepcopy(self.run); run[field][key] = value
            approval = dict(self.approval, runSha256=e.digest(e.compact(run)))
            with self.subTest(field=field, key=key), self.assertRaises(ValueError): a.authorize(run, approval, self.now)
        for key, value in (("purpose", "synthetic_intake_inference_only"), ("allowCorrection", True),
                           ("allowCancellationProbe", False), ("version", "intake-extraction-replay-approval-v3"),
                           ("recordedAt", "2026-10-18T19:00:00Z"), ("expiresUtc", "2026-10-20T19:00:00Z")):
            with self.subTest(key=key), self.assertRaises(ValueError): a.authorize(self.run, dict(self.approval, **{key: value}), self.now)

    def test_existing_records_preserved_and_second_attempt_refused(self):
        for name, value in (("acquisition-run.json", self.run), ("inference-approval.json", self.approval)):
            replay.private.private_write(self.directory / name, e.compact(value))
        before = (self.directory / "acquisition-run.json").read_bytes()
        self.runit()
        self.assertEqual(before, (self.directory / "acquisition-run.json").read_bytes())
        runtime = FakeRuntime(self.clock)
        with self.assertRaises(ValueError): self.runit(runtime)
        self.assertEqual(runtime.calls, [])

    def test_validation_has_case_not_attempt_budget_and_failed_start_preserves_denominator(self):
        validations = []
        def validate(case_id, raw, deadline):
            validations.append(deadline)
            if len(validations) == 1: self.assertEqual(deadline, 1810)
            self.clock[0] += 950
            return self.valid(case_id, raw, deadline)
        result, runtime = self.runit(validator=validate)
        self.assertEqual(result["cases"][0]["attempts"][0]["validation"], "accepted")
        self.assertEqual(result["cases"][0]["attempts"][0]["outcome"], "response")
        self.assertEqual(result["status"], "aborted")
        self.assertEqual(result["errorCategory"], "validation_deadline")
        self.assertEqual(runtime.calls.count("stop"), 1)

    def test_infrastructure_monitoring_pressure_schema_and_interruptions_abort_without_retry(self):
        for failure in (a.mac_acquire.RuntimeFailure("memory_abort_threshold"),
                        a.mac_acquire.RuntimeFailure("owned_pod_changed_or_unhealthy"),
                        RuntimeError("monitor unavailable"), RuntimeError("schema refused"), KeyboardInterrupt()):
            folder = self.directory / str(len(list(self.directory.iterdir())))
            folder.mkdir(mode=0o700)
            runtime = FakeRuntime(self.clock)
            def generate(prompt, run, deadline, emit):
                runtime.calls.append("generate")
                emit({"type": "provider_partial_chunk", "base64": "eA=="})
                raise failure
            runtime.generate = generate
            result = a.run_acquisition(folder, self.run, self.approval, runtime, self.valid,
                monotonic=lambda: self.clock[0], now=lambda: self.now)
            self.assertEqual(runtime.calls.count("generate"), 1)
            self.assertEqual(runtime.calls.count("stop"), 1)
            self.assertEqual([c["attempts"][0]["outcome"] for c in result["cases"]], ["incomplete"] + ["unrun"] * 7)
            self.assertIn(b"provider_partial_chunk", (folder / "acquisition-events.jsonl").read_bytes())

    def test_event_failure_still_stops_without_retry_and_keeps_first_reason(self):
        runtime = FakeRuntime(self.clock)
        def generate(prompt, run, deadline, emit):
            emit({"type": "bad_event", "value": object()})
        def cancel(run, deadline, emit):
            runtime.calls.append("stop")
            emit({"type": "bad_event", "value": object()})
            return {"verified": True}
        runtime.generate, runtime.cancel = generate, cancel
        result, _ = self.runit(runtime)
        self.assertEqual(runtime.calls.count("stop"), 1)
        self.assertTrue(result["stopVerified"])
        self.assertEqual(result["errorCategory"], "adapter_or_validation_failure")

    def test_probe_failure_or_expiry_prevents_scored_calls_and_stop_gets_fresh_budget(self):
        runtime = FakeRuntime(self.clock)
        def probe(run, emit, deadline):
            self.assertEqual(deadline, 910)
            self.clock[0] = deadline + 1
            return {"verified": True}
        def cancel(run, deadline, emit):
            self.assertEqual(deadline - self.clock[0], 60)
            runtime.calls.append("stop")
            return {"verified": False}
        runtime.cancellation_probe, runtime.cancel = probe, cancel
        result, _ = self.runit(runtime)
        self.assertNotIn("generate", runtime.calls)
        self.assertFalse(result["stopVerified"])
        self.assertTrue(all(c["attempts"][0]["outcome"] == "unrun" for c in result["cases"]))

    def test_invalid_resource_measurements_abort_but_preserve_raw_and_hash(self):
        for key, value in (("inputTokens", True), ("peakRssBytes", -1), ("costUsd", float("nan")), ("elapsedMs", 1)):
            folder = self.directory / key; folder.mkdir(mode=0o700)
            output = response(); output["resources"][key] = value
            runtime = FakeRuntime(self.clock, [output])
            result = a.run_acquisition(folder, self.run, self.approval, runtime, self.valid,
                monotonic=lambda: self.clock[0], now=lambda: self.now)
            attempt = result["cases"][0]["attempts"][0]
            self.assertEqual(attempt["outcome"], "incomplete")
            self.assertEqual(attempt["raw"], output["raw"])
            self.assertEqual(attempt["rawSha256"], e.digest(output["raw"].encode()))
            self.assertEqual(runtime.calls.count("generate"), 1)
            self.assertTrue(result["stopVerified"])


if __name__ == "__main__": unittest.main()
