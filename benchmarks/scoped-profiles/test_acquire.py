import copy
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import patch

import acquire
import capture
import protocol


class Clock:
    def __init__(self):
        self.value = 0.0

    def __call__(self):
        return self.value


class FakeRuntime:
    def __init__(self, outputs=None, fail=None):
        self.calls = []
        self.outputs = list(outputs or [])
        self.fail = fail

    def preflight(self, run):
        self.calls.append("preflight")
        if self.fail == "preflight":
            raise RuntimeError("private")
        return {"ready": True}

    def cancellation_probe(self, run, emit, deadline):
        self.calls.append("probe")
        if self.fail == "probe":
            raise RuntimeError("private")
        return {"verified": True}

    def generate(self, prompt, run, deadline, emit):
        self.calls.append(("generate", prompt))
        if self.fail == "generate":
            raise RuntimeError("private")
        return self.outputs.pop(0) if self.outputs else {
            "outcome": "response", "raw": "ok", "resources": resources()}

    def cancel(self, run, deadline, emit):
        self.calls.append("cancel")
        return {"verified": self.fail != "cancel"}


def resources(**changes):
    result = {key: None for key in capture.RESOURCE_FIELDS}
    result.update(changes)
    return result


class AcquisitionTest(unittest.TestCase):
    now = dt.datetime(2026, 10, 18, tzinfo=dt.timezone.utc)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve())
        self.directory = Path(self.temp.name)
        self.directory.chmod(0o700)
        self.addCleanup(self.temp.cleanup)
        self.definition, self.artifacts = protocol.verify()
        intake = next(p for p in self.definition["profiles"] if p["profile"] == "context-intake-suggestion-v1")
        self.ids = [c["id"] for c in intake["cases"]]
        self.run = {"version": "scoped-intake-acquisition-v1", "runId": str(uuid.uuid4()),
                    "profile": intake["profile"], "caseIds": self.ids,
                    "manifestSha256": protocol.digest((protocol.HERE / "manifest-v1.json").read_bytes()),
                    "model": copy.deepcopy(acquire.MODEL), "settings": copy.deepcopy(acquire.SETTINGS),
                    "resourceLimits": copy.deepcopy(acquire.LIMITS), "runtime": {"adapter": "fake"},
                    "toolingSha256": acquire.tooling_hashes()}
        self.approval = {"version": "scoped-intake-inference-approval-v1",
                         "runSha256": protocol.digest(protocol.compact(self.run)),
                         "purpose": "synthetic_intake_inference_only", "recordedBy": "test-author",
                         "recordedAt": "2026-10-18T00:00:00Z", "expiresUtc": "2026-10-18T01:00:00Z",
                         "allowCorrection": True, "allowCancellationProbe": True, "approvalEvidence": "separate approval"}
        self.clock = Clock()

    def runit(self, runtime=None, validator=None):
        return acquire.run_acquisition(self.directory, self.run, self.approval,
            runtime or FakeRuntime(), validator or (lambda case, attempt, raw: True), self.clock, lambda: self.now)

    def read(self, name):
        return json.loads((self.directory / name).read_text())

    def test_approval_and_path_refusal_precede_all_runtime_calls(self):
        runtime = FakeRuntime()
        bad = copy.deepcopy(self.approval)
        bad["allowCancellationProbe"] = False
        with self.assertRaises(ValueError):
            acquire.run_acquisition(self.directory, self.run, bad, runtime, lambda *args: True,
                                    self.clock, lambda: self.now)
        self.assertEqual(runtime.calls, [])
        self.assertFalse(any((self.directory / name).exists() for name in acquire.OUTPUTS))
        (self.directory / "replay.json").write_text("collision")
        with self.assertRaises(ValueError):
            self.runit(runtime)
        self.assertEqual(runtime.calls, [])

    def test_full_eight_cases_exact_prompts_and_private_evidence(self):
        runtime = FakeRuntime()
        result = self.runit(runtime, lambda *args: True)
        self.assertEqual(result["status"], "completed")
        self.assertEqual([c["caseId"] for c in result["cases"]], self.ids)
        generated = [entry[1] for entry in runtime.calls if isinstance(entry, tuple)]
        expected = {r["caseId"]: r["exactPrompt"] for r in self.artifacts["cases"] if r["profile"] == self.run["profile"]}
        self.assertEqual(generated, [expected[cid] for cid in self.ids])
        self.assertEqual(self.read("replay.json")["version"], "scoped-private-replay-v1")
        for name in acquire.OUTPUTS:
            self.assertEqual((self.directory / name).stat().st_mode & 0o777, 0o600)

    def test_rejection_then_correction_uses_frozen_second_prompt_only(self):
        runtime = FakeRuntime([{"outcome": "response", "raw": "bad", "resources": resources()},
                               {"outcome": "response", "raw": "good", "resources": resources()}])
        result = self.runit(runtime, lambda case, attempt, raw: raw == "good")
        generated = [entry[1] for entry in runtime.calls if isinstance(entry, tuple)]
        artifacts = {r["caseId"]: r for r in self.artifacts["cases"] if r["profile"] == self.run["profile"]}
        self.assertEqual(generated[:2], [artifacts["i01"]["exactPrompt"], artifacts["i01"]["exactCorrectionPrompt"]])
        self.assertEqual(result["cases"][0]["attempts"][0]["validation"], "rejected")
        self.assertEqual(result["cases"][0]["attempts"][1]["raw"], "good")

    def test_timeout_incomplete_resource_and_adapter_failures_abort_batch(self):
        variants = [({"outcome": "timeout", "raw": None, "resources": resources()}, None),
                    ({"outcome": "incomplete", "raw": "partial", "resources": resources()}, None),
                    ({"outcome": "response", "raw": "ok", "resources": resources(peakRssBytes=acquire.LIMITS["memoryAbortBytes"] + 1)}, None)]
        for output, expected in variants:
            with self.subTest(output=output["outcome"]), tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as folder:
                self.directory = Path(folder); self.directory.chmod(0o700)
                runtime = FakeRuntime([output])
                result = self.runit(runtime)
                self.assertEqual(result["status"], "aborted")
                self.assertEqual(len([c for c in runtime.calls if isinstance(c, tuple)]), 1)
                self.assertIn("cancel", runtime.calls)
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as folder:
            self.directory = Path(folder); self.directory.chmod(0o700)
            runtime = FakeRuntime(fail="generate")
            self.assertEqual(self.runit(runtime)["status"], "aborted")
            self.assertIn("cancel", runtime.calls)

    def test_preflight_failure_does_not_cancel_and_probe_failure_does(self):
        runtime = FakeRuntime(fail="preflight")
        self.assertEqual(self.runit(runtime)["status"], "aborted")
        self.assertNotIn("cancel", runtime.calls)
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as folder:
            self.directory = Path(folder); self.directory.chmod(0o700)
            runtime = FakeRuntime(fail="probe")
            self.assertEqual(self.runit(runtime)["status"], "aborted")
            self.assertIn("cancel", runtime.calls)

    def test_bounds_and_validator_deadline_abort_without_correction(self):
        too_large = "x" * (acquire.LIMITS["maxResponseBytes"] + 1)
        runtime = FakeRuntime([{"outcome": "response", "raw": too_large, "resources": resources()}])
        self.assertEqual(self.runit(runtime)["status"], "aborted")
        with tempfile.TemporaryDirectory(dir=Path(tempfile.gettempdir()).resolve()) as folder:
            self.directory = Path(folder); self.directory.chmod(0o700)
            runtime = FakeRuntime([{"outcome": "response", "raw": "no", "resources": resources()}])
            def slow(*args):
                self.clock.value = 1800
                return False
            result = self.runit(runtime, slow)
            self.assertEqual(result["status"], "aborted")
            self.assertEqual(len([c for c in runtime.calls if isinstance(c, tuple)]), 1)

    def test_numeric_bool_and_unknown_settings_refused_before_actions(self):
        for group, key, value in (("settings", "temperature", False), ("settings", "seed", 42.0),
                                  ("resourceLimits", "maxCostUsd", False)):
            with self.subTest(group=group, key=key):
                run = copy.deepcopy(self.run); run[group][key] = value
                approval = dict(self.approval, runSha256=protocol.digest(protocol.compact(run)))
                runtime = FakeRuntime()
                with self.assertRaises(ValueError):
                    acquire.run_acquisition(self.directory, run, approval, runtime, lambda *args: True,
                                            self.clock, lambda: self.now)
                self.assertEqual(runtime.calls, [])

    def test_false_probe_never_starts_a_scored_case(self):
        runtime = FakeRuntime()
        runtime.cancellation_probe = lambda *args: {"verified": False}
        result = self.runit(runtime)
        self.assertEqual(result["status"], "aborted")
        self.assertFalse(any(isinstance(c, tuple) for c in runtime.calls))
        self.assertTrue(all(c["attempts"][0]["outcome"] == "unrun" for c in result["cases"]))

    def test_successful_batch_still_requires_final_verified_stop(self):
        runtime = FakeRuntime(fail="cancel")
        result = self.runit(runtime)
        self.assertEqual(result["status"], "aborted")
        self.assertEqual(result["errorCategory"], "cancellation_unverified")

    def test_validator_failure_retains_received_response(self):
        def unavailable(*args):
            raise ValueError("private failure")
        result = self.runit(FakeRuntime(), unavailable)
        self.assertEqual(result["status"], "aborted")
        self.assertEqual(result["cases"][0]["attempts"][0]["raw"], "ok")
        self.assertEqual(result["cases"][0]["attempts"][0]["outcome"], "response")

    def test_expired_attempt_aborts_before_validator_or_correction(self):
        runtime = FakeRuntime()
        def slow(*args):
            self.clock.value = 901
            return {"outcome": "response", "raw": "late", "resources": resources()}
        runtime.generate = slow
        calls = []
        result = self.runit(runtime, lambda *args: calls.append(args))
        self.assertEqual(result["cases"][0]["attempts"][0]["outcome"], "timeout")
        self.assertEqual(calls, [])

    def test_approval_expiry_shortens_batch_and_allows_cleanup_time(self):
        self.approval["expiresUtc"] = "2026-10-18T00:00:20Z"
        runtime = FakeRuntime()
        deadlines = []
        def late(prompt, run, deadline, emit):
            deadlines.append(deadline); self.clock.value = 21
            return {"outcome": "timeout", "raw": None, "resources": resources()}
        runtime.generate = late
        runtime.cancel = lambda run, deadline, emit: deadlines.append(deadline) or {"verified": True}
        result = self.runit(runtime)
        self.assertEqual(result["status"], "aborted")
        self.assertEqual(deadlines, [20, 81])

    def test_no_correction_permission_never_sends_second_prompt(self):
        self.approval["allowCorrection"] = False
        runtime = FakeRuntime()
        result = self.runit(runtime, lambda *args: False)
        self.assertEqual(len([c for c in runtime.calls if isinstance(c, tuple)]), 8)
        self.assertTrue(all(len(c["attempts"]) == 1 for c in result["cases"]))

    def test_frozen_refusal_and_wrong_path_make_no_runtime_calls(self):
        runtime = FakeRuntime()
        with patch.object(protocol, "verify", side_effect=ValueError("frozen mismatch")):
            with self.assertRaises(ValueError):
                self.runit(runtime)
        self.directory.chmod(0o755)
        with self.assertRaises(ValueError):
            self.runit(runtime)
        self.assertEqual(runtime.calls, [])

    def test_existing_replay_reader_accepts_all_eight_case_records(self):
        self.runit()
        indexed = capture.attempts_from(self.read("replay.json"), self.run)
        self.assertEqual(list(indexed), self.ids)
        self.assertEqual(len(indexed), 8)

    def test_probe_startup_uses_attempt_budget_not_stop_budget(self):
        self.approval["expiresUtc"] = "2026-10-18T06:00:00Z"
        runtime = FakeRuntime()
        observed = []
        runtime.cancellation_probe = lambda run, emit, deadline: observed.append(deadline) or {"verified": True}
        self.runit(runtime)
        self.assertEqual(observed, [acquire.LIMITS["attemptTimeoutSeconds"]])

    def test_old_memory_envelope_is_not_silently_upgraded(self):
        self.run["resourceLimits"]["memoryLimitBytes"] = 6979321856
        self.run["resourceLimits"]["memoryAbortBytes"] = 6442450944
        self.approval["runSha256"] = protocol.digest(protocol.compact(self.run))
        runtime = FakeRuntime()
        with self.assertRaises(ValueError):
            self.runit(runtime)
        self.assertEqual(runtime.calls, [])


if __name__ == "__main__":
    unittest.main()
