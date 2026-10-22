import copy
import datetime as dt
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import uuid

import suite_v5 as suite
import revision_v5 as revision
import campaign_v4 as c
import supervisor_v4 as supervisor
import lifecycle_v4 as life
from test_campaign_v4 import FakeClock
import test_lifecycle_v4 as legacy_lifecycle


def candidate():
    path = os.environ.get("INTAKE_V5_TEST_PROMPT")
    if not path:
        raise ValueError("Explicit private frozen candidate prompt required for v5 execution tests")
    text = c.private.private_read(Path(path)).decode()
    revision.need(c.e.digest(text.encode()) == suite.PROMPT_SHA256)
    return text


def fixture_counts(text):
    document = revision.counter_input(text)
    return {"version": "intake-conformance-token-evidence-v5", "tokenizer": revision.TOKENIZER,
        "inputSha256": c.e.digest(c.e.compact(document)), "tensorWeightsLoaded": False,
        "inferenceContextCreated": False, "generationCalled": False,
        "cases": [{"caseId": row["caseId"], "tokens": 100, "tokensSha256": "1" * 64,
            "renderedSha256": c.e.digest(c.e.v3.render(row["exactPrompt"])),
            "renderedUtf8Bytes": len(c.e.v3.render(row["exactPrompt"]))} for row in document["cases"]]}


class FakeRuntime:
    synthetic_fake = True

    def __init__(self, run, guard, request_guard, prepared, fixtures, failure):
        self.run, self.guard, self.request_guard = run, guard, request_guard
        self.prepared, self.fixtures, self.failure = prepared, fixtures, failure
        self.actions = []
        self.cancelled = 0

    def preflight(self, run):
        self.guard(run)
        return {"syntheticFake": True}

    def cancellation_probe(self, run, emit, deadline):
        self.guard(run)
        self.request_guard(run, self.prepared["requestSha256"]["probe"])
        self.actions.append("probe")
        if self.failure == "duplicate_probe":
            self.request_guard(run, self.prepared["requestSha256"]["probe"])
        return {"verified": True, "probeNonScored": True}

    def generate(self, prompt, run, deadline, emit):
        case_id = next(row["caseId"] for row in revision.prompt_cases(self.prepared["systemInstructions"]) if row["exactPrompt"] == prompt)
        self.request_guard(run, self.prepared["requestSha256"][case_id])
        self.actions.append(case_id)
        if self.failure == "duplicate_launch":
            self.request_guard(run, self.prepared["requestSha256"][case_id])
        if self.failure == "generation":
            raise RuntimeError("Synthetic infrastructure failure")
        return {"outcome": "response", "raw": c.e.compact(self.fixtures[case_id]).decode(),
                "resources": {key: None for key in c.v.replay.RESOURCES}}

    def cancel(self, run, deadline, emit):
        self.cancelled += 1
        return {"verified": True}


class V5ExecutionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="intake-v5-fake-", dir=supervisor.BASE)
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.clock = FakeClock()
        self.text = candidate()
        self.evidence = fixture_counts(self.text)
        self.prepared = revision.definition(str(uuid.uuid4()), self.text, self.evidence)
        _, self.inputs, _, self.fixtures = suite.verify()

    def create(self):
        self.campaign = c.Campaign.create(self.directory, "synthetic_fake", "offline v5 test",
            "Synthetic fixtures only; no candidate inference.", profile=suite.PROFILE, **self.clock.arguments())
        self.addCleanup(self.campaign.close)
        return self.campaign

    def run_batch(self, failure=None, quality_failure=False):
        fixtures = copy.deepcopy(self.fixtures)
        if quality_failure:
            fixtures["u01"]["suggestions"][0]["commitment"]["label"] = "Dinner"
        def factory(run, guard, request_guard):
            self.runtime = FakeRuntime(run, guard, request_guard, self.prepared, fixtures, failure)
            return self.runtime
        def validator(case_id, raw, deadline):
            draft = suite.assembled(case_id, c.e.decode(raw.encode()), self.inputs)
            return {"caseId": case_id, "rawSha256": c.e.digest(raw.encode()), "stages": {
                "wire": "accepted", "binding": "accepted", "semantic": "accepted", "reason": None,
                "assembledDraft": c.e.compact(draft).decode()}}
        return self.campaign.run_batch(self.prepared, self.evidence, runtime_factory=factory, validator=validator)

    def test_exact_new_requests_context_and_unchanged_prompt(self):
        requests = revision.check_revision(self.prepared, self.evidence)
        self.assertEqual(list(requests), suite.IDS + ["probe"])
        self.assertTrue(all(request["options"]["use_mmap"] for request in requests.values()))
        for text in (self.text + " ", "synthetic different prompt"):
            with self.assertRaises(ValueError):
                revision.prompt_cases(text)
        for mutate in (lambda e: e["cases"][0].update(tokens=2305),
                       lambda e: e["cases"][0].update(caseId="i01"),
                       lambda e: e.update(version="intake-conformance-token-evidence-v4")):
            evidence = copy.deepcopy(self.evidence)
            mutate(evidence)
            with self.assertRaises(ValueError):
                revision.check_counts(self.text, evidence)
        with self.assertRaises(ValueError):
            revision.definition(str(uuid.uuid4()), self.text, self.evidence, "0" * 64)
        with self.assertRaises(ValueError):
            c.v.suites.select("arbitrary.plugin")

    def test_complete_fake_batch_all_eight_receipts_and_terminal_no_replay(self):
        self.create()
        report = self.run_batch()
        self.assertEqual(report["classification"], "passed")
        self.assertEqual(report["profile"], suite.PROFILE)
        self.assertEqual(self.runtime.actions, ["probe", *suite.IDS])
        self.assertEqual(self.runtime.cancelled, 1)
        self.assertEqual(self.campaign.scope["limits"]["maxBatches"], 1)
        self.assertEqual(self.campaign.scope["limits"]["maxScoredRequests"], 8)
        self.assertEqual(self.campaign.scope["limits"]["maxProbes"], 1)
        with self.assertRaises(ValueError):
            self.campaign.intent("u09", "0" * 64, self.clock.value + 100)
        with self.assertRaises((ValueError, FileExistsError)):
            self.campaign.intent("probe", self.prepared["requestSha256"]["probe"], self.clock.value + 100)
        with self.assertRaises(ValueError):
            self.run_batch()
        self.campaign.close()
        with c.Campaign(self.directory, **self.clock.arguments()) as inspected:
            self.assertEqual(len(inspected.receipts), 1)
            with self.assertRaises(ValueError):
                inspected.run_batch(self.prepared, self.evidence)

    def test_complete_quality_failure_is_terminal_not_another_revision(self):
        self.create()
        report = self.run_batch(quality_failure=True)
        self.assertEqual(report["classification"], "quality_failure")
        self.assertEqual(len(self.runtime.actions), 9)
        self.assertEqual(c.private.read_json(self.directory / "terminal.json")["status"], "failed")
        with self.assertRaises(ValueError):
            self.run_batch()

    def test_duplicate_probe_or_scored_launch_and_infrastructure_stop(self):
        for failure in ("duplicate_probe", "duplicate_launch", "generation"):
            with self.subTest(failure=failure):
                directory = self.directory / failure
                directory.mkdir(mode=0o700)
                with c.Campaign.create(directory, "synthetic_fake", "offline test", "No inference.",
                        profile=suite.PROFILE, **self.clock.arguments()) as campaign:
                    self.campaign = campaign
                    report = self.run_batch(failure=failure)
                    self.assertEqual(report["classification"], "incomplete")
                    self.assertLessEqual(len(self.runtime.actions), 2)
                    self.assertEqual(self.runtime.cancelled, 1)
                    self.assertEqual(len(report["cases"]), 8)
                    self.assertTrue((directory / "terminal.json").exists())

    def test_cross_suite_revision_and_recovery_refuse_before_claims(self):
        self.create()
        wrong = copy.deepcopy(self.prepared)
        wrong["profile"] = c.e.PROFILE
        with self.assertRaises(ValueError):
            self.campaign.run_batch(wrong, self.evidence)
        self.assertFalse(list(self.directory.glob("claim-*.json")))
        with self.assertRaises(ValueError):
            c.Campaign.create(self.directory, "synthetic_fake", "test", "No inference.",
                              profile=suite.PROFILE, recovery={"root": "not-authorized"})

    def test_single_authority_consumption_binds_exact_root(self):
        from run_mac_v5 import create
        parent = self.directory
        root = parent / "campaign"
        root.mkdir(mode=0o700)
        authority = parent / "authority.json"
        c.durable(authority, {"version": "intake-conformance-user-authority-v5", "recordedBy": "offline test",
            "userInstruction": "Synthetic authority test only; no inference.", "applyInstruction": "apply v5",
            "campaignDirectory": str(root), "limits": {"roots": 1, "batches": 1, "scoredLaunches": 8,
                "probes": 1, "campaignSeconds": 14400, "cleanupReserveSeconds": 240, "retryPermitted": False}})
        with patch.object(c.Campaign, "create", return_value=Mock(__enter__=Mock(), __exit__=Mock())) as create_campaign:
            self.assertEqual(create(root, authority), "created")
            with self.assertRaises(FileExistsError):
                create(root, authority)
            self.assertEqual(create_campaign.call_count, 1)

    def test_new_runtime_requests_and_source_fences_before_transport(self):
        now = self.clock.now()
        run = c.v.make_run(str(uuid.uuid4()), "live", self.prepared, self.evidence, "f" * 64)
        approval = {"version": "intake-conformance-batch-approval-v4",
            "purpose": "synthetic_campaign_batch_with_automatic_validation",
            "runSha256": c.e.digest(c.e.compact(run)), "campaignSha256": "f" * 64,
            "recordedBy": "offline runtime test", "recordedAt": now.isoformat().replace("+00:00", "Z"),
            "expiresUtc": (now + dt.timedelta(hours=4)).isoformat().replace("+00:00", "Z"),
            "approvalEvidence": "No transport; mocked launch only.", "allowCancellationProbe": True,
            "allowCorrection": False, "automaticLocalValidation": True}
        request_guard = Mock(return_value=True)
        runtime = c.v.Runtime(run, approval, self.prepared, self.evidence,
            campaign_guard=lambda bound: bound == run, request_guard=request_guard, now=lambda: now)
        cases = revision.prompt_cases(self.text)
        requests = revision.expected_requests(self.text)
        for case in cases:
            self.assertEqual(runtime.case_request(case["exactPrompt"]), requests[case["caseId"]])
        self.assertEqual(runtime.payload(revision.PROBE), requests["probe"])
        with patch.object(c.v.ObservedMmapRuntime, "launch", return_value="mocked-only") as transport:
            self.assertEqual(runtime.launch("chat", requests["u01"]), "mocked-only")
            request_guard.assert_called_once_with(run, self.prepared["requestSha256"]["u01"])
            transport.reset_mock()
            bad = copy.deepcopy(requests["u01"])
            bad["messages"][1]["content"] = "not a frozen packet"
            with self.assertRaises(ValueError):
                runtime.launch("chat", bad)
            transport.assert_not_called()
            with patch.object(revision, "tooling", return_value={}):
                with self.assertRaises(ValueError):
                    runtime.launch("chat", requests["u01"])
            transport.assert_not_called()
        with self.assertRaises(ValueError):
            runtime.context(run, {"template": "different", "model_info": {}})


class V5LaunchdTest(legacy_lifecycle.LaunchdTest):
    def job(self, scenario, seconds=15):
        directory = Path(tempfile.mkdtemp(prefix="v5w-", dir=supervisor.BASE))
        spec = {"mode": "synthetic_fake", "scenario": scenario, "profile": suite.PROFILE,
            "clockIdentity": c.clock_identity(), "toolingSha256": c.tooling(suite.PROFILE),
            "workCutoff": life.clock.monotonic() + seconds, "expiresUnix": time.time() + seconds,
            "nonce": uuid.uuid4().hex}
        target = supervisor.prepare(directory, spec)
        self.addCleanup(self.remove_job, target, directory)
        return directory

    def test_real_elapsed_generation_lease_is_identified_separately_from_outer_expiry(self):
        directory = self.job("generation_expiry", seconds=15)
        result = self.outcome(directory)
        self.assertEqual(result["reason"], "supervisor_deadline")
        self.assertEqual(result["expiredPhase"]["name"], "u01")
        self.assertEqual(result["expiredPhase"]["trigger"], "phase")


if __name__ == "__main__":
    unittest.main()
