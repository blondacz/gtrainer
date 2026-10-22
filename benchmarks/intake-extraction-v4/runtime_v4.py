"""Exact-authorized v4 adapter over the shared owned Mac runtime.

No CLI, implicit campaign authority, profile substitution or generation on import.
An external campaign guard is mandatory and must validate the spent claim and
remaining campaign budget. Cleanup never depends on still-valid inference approval.
"""
import copy
import datetime as dt
import uuid

import experiment_v4 as e
import oracle_v4
import replay_v4 as replay
import revision_v4 as revision
import acquire as shared_acquire
import mac_runtime as mac
import suite_selection_v4 as suites
from observed_mmap_runtime import ObservedMmapRuntime, RUNNER_POLICY

TOOLING = list(dict.fromkeys(revision.TOOLING + replay.TOOLING + [
    e.PREFIX + "runtime_v4.py", e.PREFIX + "test_runtime_v4.py", e.PREFIX + "suite_selection_v4.py",
    "benchmarks/scoped-profiles/observed_mmap_runtime.py"]))


def need(value):
    if not value: raise ValueError("V4 runtime authorization refused")


def tooling(profile=e.PROFILE):
    if profile != e.PROFILE:
        return suites.select(profile).revision.tooling()
    return {name: e.digest((e.ROOT / name).read_bytes()) for name in TOOLING}


def make_run(run_id, mode, prepared, evidence, campaign_sha256):
    suite = suites.select(prepared.get("profile"))
    suite.revision.check_revision(prepared, evidence)
    need(type(run_id) is str and str(uuid.UUID(run_id)) == run_id)
    need(mode in ("live", "synthetic_fake") and type(mode) is str)
    need(replay.private.sha(campaign_sha256))
    return copy.deepcopy({"version": "intake-conformance-batch-run-" + suite.edition, "runId": run_id,
        "executionMode": mode, "profile": suite.profile, "caseIds": suite.ids, "campaignSha256": campaign_sha256,
        "revisionSha256": e.digest(e.compact(prepared)), "contextEvidenceSha256": e.digest(e.compact(evidence)),
        "manifestSha256": prepared["manifestSha256"], "oracleSha256": prepared["oracleSha256"],
        "toolingSha256": tooling(suite.profile), "model": replay.MODEL if mode == "live" else replay.FAKE_MODEL,
        "settings": dict(replay.SETTINGS, formatSchemaSha256=prepared["schemaSha256"]),
        "resourceLimits": shared_acquire.LIMITS, "runtime": mac.IDENTITY, "runnerPolicy": RUNNER_POLICY})


def authorize(run, approval, prepared, evidence, now):
    need(type(run) is dict)
    expected = make_run(run.get("runId"), run.get("executionMode"), prepared, evidence, run.get("campaignSha256"))
    need(oracle_v4.same(run, expected))
    need(type(approval) is dict and set(approval) == {"version", "purpose", "runSha256", "campaignSha256",
        "recordedBy", "recordedAt", "expiresUtc", "approvalEvidence", "allowCancellationProbe",
        "allowCorrection", "automaticLocalValidation"})
    need(approval["version"] == "intake-conformance-batch-approval-v4")
    need(approval["purpose"] == ("synthetic_campaign_batch_with_automatic_validation" if run["executionMode"] == "live" else "synthetic_fake_batch_only"))
    need(approval["runSha256"] == e.digest(e.compact(run)) and approval["campaignSha256"] == run["campaignSha256"])
    need(approval["allowCancellationProbe"] is True and approval["allowCorrection"] is False and approval["automaticLocalValidation"] is True)
    need(replay.private.text(approval["recordedBy"]) and replay.private.text(approval["approvalEvidence"], 2048))
    start, end = replay.private.timestamp(approval["recordedAt"]), replay.private.timestamp(approval["expiresUtc"])
    need(type(now) is dt.datetime and now.tzinfo is not None and start <= now < end)
    need(dt.timedelta(0) < end - start <= dt.timedelta(hours=4))


class Runtime(ObservedMmapRuntime):
    def __init__(self, run, approval, prepared, evidence, *, campaign_guard, request_guard, now=lambda: dt.datetime.now(dt.timezone.utc)):
        need(callable(campaign_guard) and callable(request_guard))
        authorize(run, approval, prepared, evidence, now())
        need(run["executionMode"] == "live")
        self.bound_run, self.approval = copy.deepcopy(run), copy.deepcopy(approval)
        self.revision, self.evidence = copy.deepcopy(prepared), copy.deepcopy(evidence)
        self.suite = suites.select(run["profile"])
        self.campaign_guard, self.request_guard, self.now = campaign_guard, request_guard, now
        self.closed = False
        super().__init__(request_builder=self.case_request, context_validator=self.context, request_profile=self.suite.profile)
        self.context(self.bound_run)

    def context(self, run, show=None):
        need(not self.closed and oracle_v4.same(run, self.bound_run))
        authorize(run, self.approval, self.revision, self.evidence, self.now())
        # Trusted source-bound controller checks authority and time on every action;
        # this callback must not spend another claim or reset the campaign clock.
        need(self.campaign_guard(copy.deepcopy(run)) is True)
        if show is not None:
            need(type(show) is dict and e.digest(show.get("template", "").encode()) == e.v3.TEMPLATE)
            need(not show.get("system") and not show.get("messages") and show.get("model_info", {}).get("tokenizer.ggml.model") == "gpt2")
        return {"method": "runtime_matched_offline_vocabulary_only_" + self.suite.edition, "contextEvidenceSha256": self.bound_run["contextEvidenceSha256"],
                "maximumAuditedInputTokens": self.revision["maximumOfflineInputTokens"],
                "reservedOutputTokens": 1536, "extraSafetyMarginTokens": 256, "contextTokens": 4096,
                "providerMeasuredInputTokens": None}

    def case_request(self, prompt):
        self.context(self.bound_run)
        requests = self.suite.revision.check_revision(self.revision, self.evidence)
        for case in self.suite.revision.prompt_cases(self.revision["systemInstructions"]):
            if oracle_v4.same(prompt, case["exactPrompt"]): return copy.deepcopy(requests[case["caseId"]])
        need(False)

    def payload(self, prompt):
        # MacRuntime.cancellation_probe dispatches here, not to request_builder.
        self.context(self.bound_run)
        need(oracle_v4.same(prompt, self.suite.revision.PROBE))
        return copy.deepcopy(self.suite.revision.check_revision(self.revision, self.evidence)["probe"])

    def launch(self, path, body=None):
        if path == "chat":
            self.context(self.bound_run)
            requests = self.suite.revision.check_revision(self.revision, self.evidence)
            need(any(oracle_v4.same(body, request) for request in requests.values()))
            need(e.digest(e.compact(body)) in self.revision["requestSha256"].values())
            # The ledger admits only the current probe/case and spends its single
            # launch durably. Membership in the revision alone is insufficient.
            need(self.request_guard(copy.deepcopy(self.bound_run), e.digest(e.compact(body))) is True)
        return super().launch(path, body)

    def cancel(self, run, deadline, emit):
        # Expiry, drift or a lost controller must never block authoritative stopping.
        try: return super().cancel(run, deadline, emit)
        finally: self.closed = True
