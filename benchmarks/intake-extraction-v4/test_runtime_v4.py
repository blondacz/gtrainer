import copy
import datetime as dt
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch
import uuid

import runtime_v4 as v
from test_revision_v4 import fixture_counts


class RuntimeFixture(unittest.TestCase):
    def setUp(self):
        self.now = dt.datetime(2026, 10, 19, 20, tzinfo=dt.timezone.utc)
        self.evidence = fixture_counts(v.revision.INSTRUCTIONS_R1)
        self.prepared = v.revision.definition(str(uuid.uuid4()), v.revision.INSTRUCTIONS_R1, self.evidence)
        self.run = v.make_run(str(uuid.uuid4()), "live", self.prepared, self.evidence, "a" * 64)
        self.approval = {"version": "intake-conformance-batch-approval-v4",
            "purpose": "synthetic_campaign_batch_with_automatic_validation", "runSha256": v.e.digest(v.e.compact(self.run)),
            "campaignSha256": "a" * 64, "recordedBy": "offline runtime test", "recordedAt": "2026-10-19T19:00:00Z",
            "expiresUtc": "2026-10-19T23:00:00Z", "approvalEvidence": "synthetic fake transport; no live actions",
            "allowCancellationProbe": True, "allowCorrection": False, "automaticLocalValidation": True}

    def runtime(self, guard=None):
        return v.Runtime(self.run, self.approval, self.prepared, self.evidence,
                         campaign_guard=guard or (lambda run: True), request_guard=lambda run, request_hash: True, now=lambda: self.now)


class RuntimeV4Test(RuntimeFixture):
    def test_explicit_profile_hook_and_exact_probe_and_eight_requests(self):
        runtime = self.runtime()
        self.assertEqual(runtime.request_profile, v.e.PROFILE)
        runtime.check_request_mode(self.run)
        requests = v.revision.expected_requests(v.revision.INSTRUCTIONS_R1)
        self.assertEqual(runtime.payload(v.revision.PROBE), requests["probe"])
        for case in v.revision.prompt_cases(v.revision.INSTRUCTIONS_R1):
            self.assertEqual(runtime.case_request(case["exactPrompt"]), requests[case["caseId"]])
        for prompt in ({}, dict(v.revision.PROBE, userPacketJson="changed")):
            with self.assertRaises(ValueError): runtime.payload(prompt)
        with self.assertRaises(ValueError): runtime.case_request(v.revision.PROBE)
        with self.assertRaises(RuntimeError): v.mac.MacRuntime().check_request_mode(self.run)
        with self.assertRaises(RuntimeError):
            v.mac.MacRuntime(request_builder=lambda p: {}, context_validator=lambda r: {}).check_request_mode(self.run)

    def test_invalid_authority_and_missing_campaign_guard_refuse_before_operations(self):
        for field, value in (("version", "old"), ("runSha256", "b" * 64), ("campaignSha256", "b" * 64),
                             ("automaticLocalValidation", 1), ("allowCorrection", True), ("purpose", "inference")):
            approval = dict(self.approval, **{field: value})
            with patch.object(v.mac.MacRuntime, "preflight") as preflight:
                with self.assertRaises(ValueError):
                    v.Runtime(self.run, approval, self.prepared, self.evidence, campaign_guard=lambda run: True, request_guard=lambda run, sha: True, now=lambda: self.now)
                preflight.assert_not_called()
        with self.assertRaises(ValueError):
            v.Runtime(self.run, self.approval, self.prepared, self.evidence, campaign_guard=None, request_guard=lambda run, sha: True, now=lambda: self.now)
        with self.assertRaises(ValueError): self.runtime(lambda run: False)

    def test_expiry_run_revision_and_body_drift_are_pre_transport(self):
        runtime = self.runtime()
        requests = v.revision.expected_requests(v.revision.INSTRUCTIONS_R1)
        for body in ({}, dict(requests["i01"], think=True), dict(requests["i01"], options={})):
            with patch.object(v.mac.MacRuntime, "launch") as launch:
                with self.assertRaises(ValueError): runtime.launch("chat", body)
                launch.assert_not_called()
        runtime.approval["expiresUtc"] = "2026-10-19T19:59:00Z"
        with patch.object(runtime, "baseline") as baseline:
            with self.assertRaises(ValueError): runtime.preflight(self.run)
            baseline.assert_not_called()
        runtime = self.runtime(); runtime.revision["requestSha256"]["i01"] = "b" * 64
        with patch.object(runtime, "sample") as sample:
            with self.assertRaises(ValueError): runtime.generate({}, self.run, time.monotonic() + 10, lambda event: None)
            sample.assert_not_called()

    def test_every_actual_chat_body_is_rechecked_before_owned_transport(self):
        runtime = self.runtime(); requests = v.revision.expected_requests(v.revision.INSTRUCTIONS_R1)
        with patch.object(runtime, "verify_server") as server, patch.object(v.mac.MacRuntime, "launch", return_value="fake-transport") as launch:
            for body in requests.values(): self.assertEqual(runtime.launch("chat", body), "fake-transport")
        self.assertEqual(server.call_count, 9)
        self.assertEqual(launch.call_count, 9)
        self.assertEqual([v.e.digest(v.e.compact(call.args[1])) for call in launch.call_args_list], list(self.prepared["requestSha256"].values()))

    def test_campaign_action_refusal_blocks_even_a_valid_revision_request(self):
        runtime = self.runtime(); runtime.request_guard = lambda run, request_hash: False
        body = v.revision.expected_requests(v.revision.INSTRUCTIONS_R1)["i01"]
        with patch.object(runtime, "verify_server") as server, patch.object(v.mac.MacRuntime, "launch") as launch:
            with self.assertRaises(ValueError): runtime.launch("chat", body)
            server.assert_not_called(); launch.assert_not_called()

    def test_cleanup_does_not_require_valid_authority_and_fences_reuse(self):
        runtime = self.runtime(); runtime.campaign_guard = lambda run: False
        runtime.approval["purpose"] = "expired authority"
        with patch.object(v.mac.MacRuntime, "cancel", return_value={"verified": True}) as cancel:
            self.assertEqual(runtime.cancel(self.run, 60, lambda event: None), {"verified": True})
            cancel.assert_called_once()
        self.assertTrue(runtime.closed)
        with self.assertRaises(ValueError): runtime.context(self.run)
        runtime = self.runtime()
        with patch.object(v.mac.MacRuntime, "cancel", side_effect=RuntimeError("failed stop")):
            with self.assertRaises(RuntimeError): runtime.cancel(self.run, 60, lambda event: None)
        self.assertTrue(runtime.closed)

    def test_fake_mode_never_constructs_live_adapter_and_scoring_requires_probe(self):
        fake = v.make_run(self.run["runId"], "synthetic_fake", self.prepared, self.evidence, "a" * 64)
        approval = dict(self.approval, purpose="synthetic_fake_batch_only", runSha256=v.e.digest(v.e.compact(fake)))
        with self.assertRaises(ValueError):
            v.Runtime(fake, approval, self.prepared, self.evidence, campaign_guard=lambda run: True, request_guard=lambda run, sha: True, now=lambda: self.now)
        runtime = self.runtime(); runtime.prepared = runtime.probed = True
        with patch.object(runtime, "sample") as sample:
            with self.assertRaises(RuntimeError):
                runtime.generate(v.revision.prompt_cases(v.revision.INSTRUCTIONS_R1)[0]["exactPrompt"], self.run, time.monotonic()+60, lambda event: None)
            sample.assert_not_called()

    def test_fake_transport_preserves_all_eight_raws_and_request_hashes(self):
        outputs, events = fake_transport_outputs(self)
        self.assertEqual([event["sha256"] for event in events if event["type"] == "request"],
                         [self.prepared["requestSha256"][case_id] for case_id in v.e.IDS])
        self.assertEqual(len(outputs), 8)


def fake_transport_outputs(test):
    """Actual v4 generation logic, with every operational boundary replaced."""
    runtime = test.runtime(); runtime.prepared = runtime.probed = runtime.runner_probe_verified = True
    _, _, _, fixtures = v.e.verify()
    events = []; outputs = []
    for case in v.revision.prompt_cases(v.revision.INSTRUCTIONS_R1):
        raw = v.e.compact(fixtures[case["caseId"]]).decode()
        done = threading.Event(); done.set(); process = Mock()
        holder = {"raw": json.dumps({"done": True, "done_reason": "stop", "message": {"content": raw}}).encode()}
        with patch.object(runtime, "sample"), patch.object(runtime, "api", return_value={"models": []}), \
             patch.object(runtime, "verify_server"), patch.object(v.mac.MacRuntime, "launch", return_value=(process, done, holder)), \
             patch.object(runtime, "finish_client") as finish:
            output = runtime.generate(case["exactPrompt"], test.run, time.monotonic()+60, events.append)
        test.assertEqual(output["outcome"], "response"); test.assertEqual(output["raw"], raw)
        finish.assert_called_once_with(process)
        outputs.append(output)
    return outputs, events


@unittest.skipUnless(os.environ.get("INTAKE_V4_KOTLIN") == "1", "Explicit offline Kotlin integration required")
class RuntimeIntegration(RuntimeFixture):
    def test_fake_transport_actual_kotlin_oracle_all_eight(self):
        outputs, events = fake_transport_outputs(self)
        with tempfile.TemporaryDirectory(prefix="intake-v4-fake-transport-", dir="/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode") as temporary:
            directory = Path(temporary)
            if os.environ.get("INTAKE_V4_RETAIN_INTEGRATION"):
                directory = Path(os.environ["INTAKE_V4_RETAIN_INTEGRATION"]) / self._testMethodName
                directory.mkdir(mode=0o700)
            directory.chmod(0o700)
            run = v.replay.make_run(str(uuid.uuid4()), "synthetic_fake")
            approval = {"version": "intake-conformance-validation-approval-v4", "runSha256": v.e.digest(v.e.compact(run)),
                "purpose": "synthetic_offline_validation_only", "recordedBy": "fake transport integration",
                "recordedAt": "2026-10-19T19:00:00Z", "expiresUtc": "2026-10-19T21:00:00Z"}
            # Transport fixtures have no observed model resource measurements.
            responses = {"version": "intake-conformance-responses-v4", "cases": [
                {"caseId": case_id, "outcome": output["outcome"], "raw": output["raw"],
                 "resources": {key: None for key in v.replay.RESOURCES}} for case_id, output in zip(v.e.IDS, outputs)]}
            for name, value in (("run.json", run), ("approval.json", approval), ("responses.json", responses),
                                ("fake-runtime-run.json", self.run), ("fake-transport-events.json", events)):
                v.replay.private.private_write(directory / name, v.e.compact(value))
            report = v.replay.replay(directory, self.now)
            self.assertEqual(report["suiteConformance"], "passed")
            self.assertTrue(all(case["stages"]["semantic"] == "accepted" and case["oracle"]["status"] == "passed" for case in report["cases"]))


if __name__ == "__main__": unittest.main()
