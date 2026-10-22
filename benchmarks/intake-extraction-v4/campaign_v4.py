"""Private, persistent three-batch controller. No inference or recovery on import."""
import copy
import datetime as dt
import fcntl
import hashlib
import math
import os
from pathlib import Path
import re
import stat
import subprocess
import time
import uuid

import runtime_v4 as v
import native_clock_v4 as native_clock

e, private = v.e, v.replay.private
LIMITS = {"maxBatches": 3, "maxScoredRequests": 24, "maxProbes": 3,
          "campaignSeconds": 14400, "cleanupReserveSeconds": 240,
          "attemptSeconds": 900, "caseSeconds": 1800, "cleanupSeconds": 60}
FILES = [e.PREFIX + name for name in ("campaign_v4.py", "test_campaign_v4.py", "run_mac_v4.py", "native_clock_v4.py", "test_native_clock_v4.py")]
FILES += [e.PREFIX + name for name in ("lifecycle_v4.py", "supervisor_v4.py", "test_lifecycle_v4.py", "supervisor_fixture_v4.py")]
FILES += [e.PREFIX + name for name in ("durable_tests_v4.py", "test_durable_tests_v4.py")]
FILES += [e.PREFIX + name for name in ("recovery_v4.py", "test_recovery_v4.py")]
FILES += [e.PREFIX + name for name in ("owned_processes_v4.py", "test_owned_processes_v4.py")]
CLEANUP_MONOTONIC = time.monotonic


def need(value):
    if not value: raise ValueError("V4 campaign fenced")


def cleanup_time():
    value = CLEANUP_MONOTONIC()
    need(type(value) in (int, float) and math.isfinite(value))
    return value


def durable(path, value):
    private.private_write(path, e.compact(value))
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try: os.fsync(descriptor)
    finally: os.close(descriptor)


def event_digest(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(source.fileno())
        need(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600 and info.st_nlink == 1)
        need(info.st_size <= 16 * 1024 * 1024)
        digest = hashlib.sha256(); size = 0
        while chunk := source.read(65536):
            size += len(chunk); need(size <= 16 * 1024 * 1024); digest.update(chunk)
        return digest.hexdigest()


def clock_identity():
    return native_clock.identity()


def tooling(profile=e.PROFILE):
    return dict(v.tooling(profile), **{name: e.digest((e.ROOT / name).read_bytes()) for name in FILES})


def definition(campaign_id, mode, identity, start, recorded, recovery=None, profile=e.PROFILE):
    need(type(campaign_id) is str and str(uuid.UUID(campaign_id)) == campaign_id)
    need(type(mode) is str and mode in ("live", "synthetic_fake"))
    need(type(start) in (float, int) and math.isfinite(start) and start >= 0)
    need(native_clock.valid_identity(identity))
    suite = v.suites.select(profile)
    suite.verify()
    limits = dict(LIMITS, maxBatches=suite.max_batches, maxScoredRequests=suite.max_scored, maxProbes=suite.max_probes)
    seconds = getattr(suite, "campaign_seconds", 14400)
    limits["campaignSeconds"] = seconds
    result = {"version": "intake-conformance-campaign-" + suite.edition, "campaignId": campaign_id, "executionMode": mode,
            "profile": suite.profile, "clockIdentity": identity, "startMonotonic": start,
            "endMonotonic": start + seconds, "workCutoffMonotonic": start + seconds - 240,
            "recordedUtc": recorded, "caseIds": suite.ids, "limits": limits,
            "manifestSha256": e.digest((suite.here / ("manifest-" + suite.edition + ".json")).read_bytes()),
            "oracleSha256": e.digest((suite.here / ("oracle-" + suite.edition + ".json")).read_bytes()),
            "inputsSha256": e.digest((suite.here / ("inputs-" + suite.edition + ".json")).read_bytes()),
            "toolingSha256": tooling(suite.profile), "runtime": v.mac.IDENTITY, "resourceLimits": v.shared_acquire.LIMITS,
            "model": v.replay.MODEL if mode == "live" else v.replay.FAKE_MODEL,
            "productionAdmission": "closed"}
    if recovery is not None:
        need(suite.edition == "v4")
        import recovery_v4
        grant = recovery_v4.authority(recovery, mode)
        recovery_v4.consumer(recovery, campaign_id=campaign_id)
        need(start == grant["startMonotonic"] and recorded == grant["recordedUtc"])
        result["recovery"] = recovery
    return copy.deepcopy(result)


class Campaign:
    def __init__(self, directory, *, monotonic=None, identity=None, now=None):
        self.directory = private.private_directory(directory)
        self.monotonic = monotonic or native_clock.monotonic
        self.identity = identity or clock_identity
        self.now = now or (lambda: dt.datetime.now(dt.timezone.utc))
        self.active = self.action = None
        self.last = None
        self.lock = None
        try:
            self.scope_raw = private.private_read(self.directory / "campaign.json")
            self.approval_raw = private.private_read(self.directory / "scope-approval.json")
            self.scope, self.approval = e.decode(self.scope_raw), e.decode(self.approval_raw)
            need(type(self.scope) is dict)
            self.suite = v.suites.select(self.scope.get("profile"))
            need(self.scope.get("executionMode") != "live" or all(callback is None for callback in (monotonic, identity, now)))
            self._static(allow_expired=True)
            path = self.directory / "ledger.lock"
            descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
            self.lock = os.fdopen(descriptor, "r+b")
            info = os.fstat(descriptor)
            need(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600 and info.st_nlink == 1)
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.lock_identity = (info.st_dev, info.st_ino)
            self.receipts = self._ledger()
        except BaseException:
            self.close()
            raise

    @classmethod
    def create(cls, directory, mode, recorded_by, approval_evidence, *, recovery=None, profile=e.PROFILE, **clock):
        need(recovery is None or v.suites.select(profile).edition == "v4")
        directory = private.private_directory(directory)
        need(mode != "live" or not clock)
        need(not any(directory.iterdir()))
        mono = clock.get("monotonic", native_clock.monotonic)
        identity = clock.get("identity", clock_identity)
        now = clock.get("now", lambda: dt.datetime.now(dt.timezone.utc))
        recorded = now().astimezone(dt.timezone.utc)
        start = mono()
        campaign_id = str(uuid.uuid4())
        if recovery is not None:
            import recovery_v4
            grant = recovery_v4.authority(recovery, mode)
            start = grant["startMonotonic"]; recorded = private.timestamp(grant["recordedUtc"])
            campaign_id = recovery_v4.consumer(recovery, directory, spend=True)
        scope = definition(campaign_id, mode, identity(), start, recorded.isoformat().replace("+00:00", "Z"), recovery, profile)
        need(private.text(recorded_by) and private.text(approval_evidence, 2048))
        approval = {"version": "intake-conformance-campaign-approval-v4", "campaignSha256": e.digest(e.compact(scope)),
                    "purpose": "synthetic_bounded_campaign_and_automatic_validation" if mode == "live" else "synthetic_fake_campaign_only",
                    "recordedBy": recorded_by, "approvalEvidence": approval_evidence,
                    "recordedUtc": scope["recordedUtc"], "expiresUtc": (recorded + dt.timedelta(seconds=scope["limits"]["campaignSeconds"])).isoformat().replace("+00:00", "Z"),
                    "automaticLocalValidation": True, "allowCancellationProbes": True, "allowCorrection": False,
                    "allowRecoveryRestart": False, "allowResponseRepair": False}
        durable(directory / "campaign.json", scope)
        durable(directory / "scope-approval.json", approval)
        return cls(directory, **clock)

    def close(self):
        if self.lock is not None:
            self.lock.close()
            self.lock = None

    def __enter__(self): return self
    def __exit__(self, *args): self.close()

    def _time(self):
        value = self.monotonic()
        need(type(value) in (float, int) and math.isfinite(value))
        need(value >= self.scope["startMonotonic"] and (self.last is None or value >= self.last))
        self.last = value
        return value

    def _runtime_deadline(self, shared_deadline):
        """Conservatively convert once into the unchanged runtime's local domain.

        Sampling the local clock first means conversion overhead shortens rather
        than extends remaining authority. Ledger and validation times stay shared.
        The separate cleanup clock/deadline is already local and is not converted.
        """
        need(type(shared_deadline) in (int, float) and math.isfinite(shared_deadline))
        local_before = time.monotonic()
        need(type(local_before) in (int, float) and math.isfinite(local_before))
        remaining = shared_deadline - self._time()
        need(remaining > 0)
        deadline = local_before + remaining
        need(math.isfinite(deadline))
        return deadline

    def _static(self, allow_expired=False):
        private.private_directory(self.directory)
        scope = self.scope
        if scope.get("recovery") is not None:
            import recovery_v4
            recovery_v4.consumer(scope["recovery"], self.directory, scope["campaignId"])
        need(type(scope) is dict)
        expected = definition(scope.get("campaignId"), scope.get("executionMode"), scope.get("clockIdentity"),
                              scope.get("startMonotonic"), scope.get("recordedUtc"), scope.get("recovery"), scope.get("profile"))
        need(v.oracle_v4.same(scope, expected))
        need(v.oracle_v4.same(scope["clockIdentity"], self.identity()))
        need(private.private_read(self.directory / "campaign.json") == self.scope_raw)
        need(private.private_read(self.directory / "scope-approval.json") == self.approval_raw)
        a = self.approval
        need(type(a) is dict and set(a) == {"version", "campaignSha256", "purpose", "recordedBy", "approvalEvidence",
             "recordedUtc", "expiresUtc", "automaticLocalValidation", "allowCancellationProbes", "allowCorrection",
             "allowRecoveryRestart", "allowResponseRepair"})
        need(a["version"] == "intake-conformance-campaign-approval-v4" and a["campaignSha256"] == e.digest(self.scope_raw))
        purpose = "synthetic_bounded_campaign_and_automatic_validation" if scope["executionMode"] == "live" else "synthetic_fake_campaign_only"
        need(a["purpose"] == purpose and private.text(a["recordedBy"]) and private.text(a["approvalEvidence"], 2048))
        need(a["automaticLocalValidation"] is True and a["allowCancellationProbes"] is True)
        need(a["allowCorrection"] is False and a["allowRecoveryRestart"] is False and a["allowResponseRepair"] is False)
        start, end = private.timestamp(a["recordedUtc"]), private.timestamp(a["expiresUtc"])
        need(a["recordedUtc"] == scope["recordedUtc"] and end - start == dt.timedelta(seconds=scope["limits"]["campaignSeconds"]))
        need(start <= self.now() and (allow_expired or self.now() < end))
        self._time()

    def _ledger(self):
        names = sorted(p.name for p in self.directory.glob("claim-*.json"))
        need(names == [f"claim-{i:02}.json" for i in range(1, len(names) + 1)] and len(names) <= self.suite.max_batches)
        receipt_names = sorted(p.name for p in self.directory.glob("receipt-*.json"))
        need(all(name == f"receipt-{i:02}.json" for i, name in enumerate(receipt_names, 1)))
        need(len(receipt_names) <= len(names))
        if self.active is not None:
            need(len(names) == self.active["ordinal"])
            need(private.private_read(self.directory / names[-1]) == e.compact(self.active))
        receipts = []; previous = None
        _, _, contract, _ = self.suite.verify()
        for index, name in enumerate(names, 1):
            claim = private.read_json(self.directory / name)
            need(type(claim) is dict and set(claim) == {"version", "ordinal", "campaignSha256", "approvalSha256",
                 "previousReceiptSha256", "runSha256", "batchApprovalSha256", "revisionSha256", "claimedMonotonic"})
            need(claim["version"] == "intake-conformance-batch-claim-v4" and claim["approvalSha256"] == e.digest(self.approval_raw))
            need(claim["ordinal"] == index and type(claim["ordinal"]) is int)
            need(claim["campaignSha256"] == e.digest(self.scope_raw) and claim["previousReceiptSha256"] == previous)
            need(self.scope["startMonotonic"] <= claim["claimedMonotonic"] <= self._time())
            receipt_path = self.directory / f"receipt-{index:02}.json"
            if not receipt_path.exists():
                need(self.active is not None and index == len(names) and v.oracle_v4.same(claim, self.active))
                continue
            raw = private.private_read(receipt_path); receipt = e.decode(raw)
            need(type(receipt) is dict and set(receipt) == {"version", "claimSha256", "reportSha256", "classification"})
            need(receipt["version"] == "intake-conformance-batch-receipt-v4")
            need(receipt["claimSha256"] == e.digest(e.compact(claim)))
            batch = private.private_directory(self.directory / f"batch-{index:02}")
            report_raw = private.private_read(batch / "report.json")
            report = e.decode(report_raw)
            need(receipt["reportSha256"] == e.digest(report_raw))
            need(receipt["classification"] == report["classification"])
            if self.scope["executionMode"] == "live":
                supervisor = private.read_json(self.directory / f"supervisor-{index:02}.json")
                supervision = private.private_directory(Path(supervisor["directory"]))
                result = private.read_json(supervision / "outcome.json")
                need(result["reason"] == "normal" and result["cleanup"]["verified"] is True)
                binding = private.read_json(supervision / "binding.json")
                need(binding["claim"] == claim and binding["batch"] == str(self.directory / f"batch-{index:02}"))
            need(report["runSha256"] == claim["runSha256"] and report["revisionSha256"] == claim["revisionSha256"])
            need([row["caseId"] for row in report["cases"]] == self.suite.ids)
            need(report["version"] == "intake-conformance-batch-report-" + self.suite.edition)
            if self.suite.edition == "v5":
                need(report["profile"] == self.suite.profile and report["manifestSha256"] == self.scope["manifestSha256"])
            need(report["eventsSha256"] == event_digest(batch / "events.jsonl"))
            if report.get("probeEvidenceSha256") is not None:
                need(report["probeEvidenceSha256"] == e.digest(private.private_read(batch / "probe-result.json")))
            need(report["classification"] in ("passed", "quality_failure", "incomplete"))
            stored_run = private.read_json(batch / "run.json")
            need(e.digest(e.compact(stored_run)) == claim["runSha256"])
            stored_revision = private.read_json(batch / "revision.json")
            need(e.digest(e.compact(stored_revision)) == claim["revisionSha256"])
            if self.suite.edition == "v5":
                need(all(row["packetSha256"] == stored_revision["packetSha256"][row["caseId"]] for row in report["cases"]))
            approval_raw = private.private_read(batch / "approval.json")
            need(e.digest(approval_raw) == claim["batchApprovalSha256"])
            v.authorize(stored_run, e.decode(approval_raw), stored_revision,
                        private.read_json(batch / "context-evidence.json"), private.timestamp(self.approval["recordedUtc"]))
            if report["finishedMonotonic"] is not None:
                need(type(report["finishedMonotonic"]) in (int, float) and report["finishedMonotonic"] <= self._time())
            else: need(report["classification"] == "incomplete")
            if report["classification"] in ("passed", "quality_failure"):
                need(report["stopVerified"] is True and report["probeVerified"] is True and report["errorCategory"] is None and not report["deadlineBreach"])
                need(all(row["outcome"] in ("response", "empty") for row in report["cases"]))
                for row in report["cases"]:
                    need(row["rawSha256"] == (None if row["raw"] is None else e.digest(row["raw"].encode())))
                    need(row["stagesSha256"] == e.digest(e.compact(row["stages"])))
                    need(row["oracleResultSha256"] == e.digest(e.compact(row["oracle"])))
                    need(v.oracle_v4.same(row["oracle"], self.suite.oracle.evaluate(row["caseId"], row["stages"], contract)))
                    need((batch / (row["caseId"] + ".launched.json")).exists())
                passed = all(row["oracle"]["status"] == "passed" for row in report["cases"])
                need((report["classification"] == "passed") == passed)
            if index < len(names): need(report["classification"] == "quality_failure")
            previous = e.digest(raw)
            receipts.append((receipt, report))
        return receipts

    def guard(self, run):
        need(self.lock is not None and self.active is not None)
        info = (self.directory / "ledger.lock").lstat()
        need((info.st_dev, info.st_ino) == self.lock_identity and info.st_nlink == 1 and stat.S_IMODE(info.st_mode) == 0o600)
        self._static()
        need(not (self.directory / "terminal.json").exists())
        if self.scope.get("recovery") is not None:
            import recovery_v4
            need(not recovery_v4.cancelled(Path(self.scope["recovery"]["root"])))
        self._ledger()
        need(not (self.directory / f"receipt-{self.active['ordinal']:02}.json").exists())
        need(self._time() < self.scope["workCutoffMonotonic"])
        need(e.digest(e.compact(run)) == self.active["runSha256"])
        private.private_directory(self.batch_directory)
        need(e.digest(private.private_read(self.batch_directory / "run.json")) == self.active["runSha256"])
        need(e.digest(private.private_read(self.batch_directory / "revision.json")) == self.active["revisionSha256"])
        need(e.digest(private.private_read(self.batch_directory / "context-evidence.json")) == run["contextEvidenceSha256"])
        need(e.digest(private.private_read(self.batch_directory / "approval.json")) == self.active["batchApprovalSha256"])
        return True

    def request_guard(self, run, request_hash):
        self.guard(run)
        need(self.action is not None and self.action["requestSha256"] == request_hash)
        need(self._time() < self.action["deadlineMonotonic"])
        path = self.batch_directory / (self.action["actionId"] + ".launched.json")
        durable(path, {"intentSha256": e.digest(e.compact(self.action)), "runSha256": self.active["runSha256"],
                       "requestSha256": request_hash, "launchedMonotonic": self._time()})
        return True

    def intent(self, action_id, request_hash, deadline):
        ids = self.suite.ids
        need(action_id in ["probe", *ids] and self._time() < deadline <= self.scope["workCutoffMonotonic"])
        names = sorted(p.name for p in self.batch_directory.glob("*.intent.json") if p.name != "probe.intent.json")
        need(names == [case_id + ".intent.json" for case_id in ids[:len(names)]])
        if action_id == "probe": need(not names)
        else:
            need(len(names) < len(ids) and action_id == ids[len(names)])
            need(private.read_json(self.batch_directory / "probe-result.json")["verified"] is True)
            if names:
                prior = private.read_json(self.batch_directory / (ids[len(names)-1] + ".result.json"))
                need(prior["outcome"] in ("response", "empty"))
        self.action = {"version": "intake-conformance-action-intent-v4", "claimSha256": e.digest(e.compact(self.active)),
                       "actionId": action_id, "requestSha256": request_hash,
                       "startedMonotonic": self._time(), "deadlineMonotonic": deadline}
        durable(self.batch_directory / (action_id + ".intent.json"), self.action)

    def _launched(self):
        row = private.read_json(self.batch_directory / (self.action["actionId"] + ".launched.json"))
        need(row["intentSha256"] == e.digest(e.compact(self.action)))
        need(row["runSha256"] == self.active["runSha256"] and row["requestSha256"] == self.action["requestSha256"])

    def finish(self, reason="no_defensible_revision"):
        self._static(allow_expired=True); receipts = self._ledger()
        need(self.active is None and not (self.directory / "terminal.json").exists())
        need(reason in ("no_defensible_revision", "budget_exhausted"))
        status = "failed" if receipts and receipts[-1][1]["classification"] == "quality_failure" else "incomplete"
        durable(self.directory / "terminal.json", {"version": "intake-conformance-decision-v4", "status": status,
                "reason": reason, "campaignSha256": e.digest(self.scope_raw), "batchCount": len(receipts),
                "finishedMonotonic": self._time(), "evaluationMethod": "deterministic_extractive",
                "independentHuman": False, "humanAssessment": None, "productionAdmission": "closed"})

    def run_batch(self, prepared, evidence, *, runtime_factory=None, validator=None, lifecycle=None):
        need(self.scope["executionMode"] != "live" or (runtime_factory is None and validator is None))
        if self.scope["executionMode"] == "live":
            import lifecycle_v4 as life
            need(type(lifecycle) is life.Worker)
            lifecycle.guard()
        else:
            need(lifecycle is None or self.suite.edition == "pilot")
            if lifecycle is not None: lifecycle.guard()
        self._static(allow_expired=True); receipts = self._ledger()
        if self.scope.get("recovery") is not None: need(not receipts)
        need(self.active is None and not (self.directory / "terminal.json").exists())
        if len(receipts) >= self.suite.max_batches or self._time() >= self.scope["workCutoffMonotonic"] or self.now() >= private.timestamp(self.approval["expiresUtc"]):
            self.finish("budget_exhausted")
            need(False)
        if receipts: need(receipts[-1][1]["classification"] == "quality_failure")
        need(prepared.get("profile") == self.suite.profile)
        self.suite.revision.check_revision(prepared, evidence)
        _, _, contract, _ = self.suite.verify()
        if receipts:
            need(prepared["previousRevisionSha256"] == receipts[-1][1]["revisionSha256"])
            need(all(e.digest(prepared["systemInstructions"].encode()) != report["instructionsSha256"] for _, report in receipts))
        else: need(prepared["previousRevisionSha256"] is None)
        ordinal = len(receipts) + 1
        run = v.make_run(str(uuid.uuid4()), self.scope["executionMode"], prepared, evidence, e.digest(self.scope_raw),
                         grant_expires_utc=self.approval["expiresUtc"])
        approval = {"version": "intake-conformance-batch-approval-v4", "runSha256": e.digest(e.compact(run)),
            "purpose": "synthetic_campaign_batch_with_automatic_validation" if run["executionMode"] == "live" else "synthetic_fake_batch_only",
            "campaignSha256": e.digest(self.scope_raw), "recordedBy": self.approval["recordedBy"],
            "recordedAt": self.approval["recordedUtc"], "expiresUtc": self.approval["expiresUtc"],
            "approvalEvidence": self.approval["approvalEvidence"], "allowCancellationProbe": True,
            "allowCorrection": False, "automaticLocalValidation": True}
        v.authorize(run, approval, prepared, evidence, self.now())
        claim = {"version": "intake-conformance-batch-claim-v4", "ordinal": ordinal,
            "campaignSha256": e.digest(self.scope_raw), "approvalSha256": e.digest(self.approval_raw),
            "previousReceiptSha256": None if not receipts else e.digest(private.private_read(self.directory / f"receipt-{ordinal-1:02}.json")),
            "runSha256": e.digest(e.compact(run)), "batchApprovalSha256": e.digest(e.compact(approval)),
            "revisionSha256": e.digest(e.compact(prepared)),
            "claimedMonotonic": self._time()}
        durable(self.directory / f"claim-{ordinal:02}.json", claim)
        self.active = claim
        self.batch_directory = self.directory / f"batch-{ordinal:02}"
        self.batch_directory.mkdir(mode=0o700)
        for name, value in (("run.json", run), ("approval.json", approval), ("revision.json", prepared), ("context-evidence.json", evidence)):
            durable(self.batch_directory / name, value)
        if lifecycle is not None: lifecycle.attach(run, claim, self.batch_directory)
        cases = [{"caseId": case_id, "outcome": "unrun", "raw": None,
                  "resources": {key: None for key in v.replay.RESOURCES},
                  "stages": {"wire": "not_run", "binding": "not_run", "semantic": "not_run", "reason": None, "assembledDraft": None},
                   "oracle": {"status": "not_run", "reasons": ["local_validation_not_accepted"]}} for case_id in self.suite.ids]
        runtime = None; error = None; stop_verified = None; probe_verified = False; deadline_breach = False
        cleanup_anchor = None
        fd = os.open(self.batch_directory / "events.jsonl", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as stream:
            def emit(event):
                raw = e.compact(dict(event, campaignMonotonic=self._time())) + b"\n"
                need(len(raw) <= 65536 and stream.tell() + len(raw) <= 16 * 1024 * 1024)
                stream.write(raw); stream.flush(); os.fsync(stream.fileno())
            try:
                cleanup_anchor = cleanup_time()
                if runtime_factory is None:
                    need(run["executionMode"] == "live")
                    def supervised_guard(bound_run):
                        lifecycle.guard()
                        return self.guard(bound_run)
                    runtime = life.JournalRuntime(run, approval, prepared, evidence, campaign_guard=supervised_guard,
                        request_guard=self.request_guard, now=self.now, lifecycle=lifecycle)
                else:
                    need(run["executionMode"] == "synthetic_fake")
                    runtime = runtime_factory(run, self.guard, self.request_guard)
                    need(getattr(runtime, "synthetic_fake", False) is True)
                self.guard(run)
                preflight_end = min(self._time() + 120, self.scope["workCutoffMonotonic"])
                if self.suite.edition == "pilot": preflight_end = min(preflight_end, self.scope["startMonotonic"] + 120)
                if lifecycle is not None: lifecycle.phase("preflight", preflight_end)
                runtime.deadline = self._runtime_deadline(preflight_end if self.suite.edition == "pilot" else self.scope["workCutoffMonotonic"])
                emit({"type": "preflight", "evidence": runtime.preflight(run)})
                self.guard(run)
                deadline = min(self._time() + 900, self.scope["workCutoffMonotonic"])
                self.intent("probe", prepared["requestSha256"]["probe"], deadline)
                if lifecycle is not None: lifecycle.phase("probe", deadline)
                probe = runtime.cancellation_probe(run, emit, self._runtime_deadline(deadline))
                self._launched(); self.guard(run)
                need(type(probe) is dict and probe.get("verified") is True and self._time() < deadline)
                probe_verified = True
                durable(self.batch_directory / "probe-result.json", {"verified": True, "intentSha256": e.digest(e.compact(self.action)), "evidence": probe})
                emit({"type": "cancellation_probe_verified", "evidence": probe})
                for row, case in zip(cases, self.suite.revision.prompt_cases(prepared["systemInstructions"])):
                    self.guard(run)
                    case_end = min(self._time() + 1800, self.scope["workCutoffMonotonic"])
                    deadline = min(self._time() + 900, case_end)
                    self.intent(row["caseId"], prepared["requestSha256"][row["caseId"]], deadline)
                    if lifecycle is not None: lifecycle.phase(row["caseId"], deadline)
                    row["outcome"] = "incomplete"
                    output = runtime.generate(case["exactPrompt"], run, self._runtime_deadline(deadline), emit)
                    self._launched()
                    need(type(output) is dict and set(output) == {"outcome", "raw", "resources"})
                    need(output["outcome"] in ("response", "empty", "timeout", "incomplete"))
                    candidate = dict(caseId=row["caseId"], **output)
                    # Reuse response/resource bounds without changing original bytes.
                    prefix = [dict(caseId=c["caseId"], outcome=c["outcome"], raw=c["raw"], resources=c["resources"]) for c in cases[:cases.index(row)]]
                    if output["outcome"] == "response" and (output["raw"] is None or not output["raw"].strip()): candidate["outcome"] = "empty"
                    checked = self.suite.replay.cases_from({"version": "intake-conformance-responses-" + self.suite.edition, "cases": prefix + [candidate]},
                                                  {"inputOrigin": "synthetic_fake" if run["executionMode"] == "synthetic_fake" else "externally_recorded"})
                    row.update(checked[len(prefix)])
                    resources = row["resources"]
                    need(resources["peakRssBytes"] is None or resources["peakRssBytes"] <= self.scope["resourceLimits"]["memoryAbortBytes"])
                    need(resources["costUsd"] is None or resources["costUsd"] <= self.scope["resourceLimits"]["maxCostUsd"])
                    emit({"type": "case_output", "case": row})
                    if self._time() >= deadline: row["outcome"] = "timeout"
                    if row["outcome"] in ("timeout", "incomplete"):
                        error = row["outcome"]; break
                    self.guard(run)
                    if row["outcome"] == "response":
                        request = {"caseId": row["caseId"], "raw": row["raw"], "rawSha256": e.digest(row["raw"].encode())}
                        if validator is None:
                            destination = self.batch_directory / (row["caseId"] + "-validation"); destination.mkdir(mode=0o700)
                            remaining = min(600, case_end - self._time())
                            need(remaining > 0)
                            if lifecycle is not None: lifecycle.phase("validation", self._time() + remaining)
                            results = self.suite.validate(destination, [request], timeout_seconds=remaining)
                            checked = results[0] if len(results) == 1 else None
                        else: checked = validator(row["caseId"], row["raw"], case_end)
                        v.replay.check_results([checked], [request])
                        row["stages"] = checked["stages"]
                        self.guard(run); need(self._time() < case_end)
                        row["oracle"] = self.suite.oracle.evaluate(row["caseId"], row["stages"], contract)
                        self.guard(run)
                    emit({"type": "case_validation", "case": row})
                    durable(self.batch_directory / (row["caseId"] + ".result.json"), row)
            except BaseException as exc:
                error = error or getattr(exc, "category", "infrastructure_or_state_failure")
                try: durable(self.batch_directory / "failure.json", {"category": error})
                except BaseException: pass
            finally:
                self.action = None
                if runtime is not None:
                    # Campaign clock/state failure cannot revoke owned cleanup.
                    # Never reset this separate timer or grant another attempt.
                    try: cleanup_anchor = cleanup_time()
                    except BaseException: error = error or "cleanup_clock_failure"
                    cleanup_deadline = cleanup_anchor + 60
                    def cleanup_emit(event):
                        nonlocal error
                        try: emit(event)
                        except BaseException: error = error or "cleanup_logging_failure"
                    try:
                        stopped = runtime.cancel(run, cleanup_deadline, cleanup_emit)
                        stop_verified = type(stopped) is dict and stopped.get("verified") is True and cleanup_time() <= cleanup_deadline
                    except BaseException: stop_verified = False
                    if not stop_verified: error = error or "cleanup_unverified"
                else: error = error or "runtime_construction_failed"
        try:
            self._static()
            self._ledger()
        except BaseException: error = error or "post_callback_clock_source_or_authority_failure"
        try: finished = self._time()
        except BaseException:
            finished = None
            error = error or "campaign_clock_unavailable"
        deadline_breach = None if finished is None else finished > self.scope["endMonotonic"]
        if deadline_breach: error = error or "campaign_deadline_breach"
        complete = all(row["outcome"] in ("response", "empty") for row in cases)
        suite_passed = complete and all(row["oracle"]["status"] == "passed" for row in cases)
        classification = ("passed" if suite_passed else "quality_failure") if complete and error is None and stop_verified is True and probe_verified else "incomplete"
        for row in cases:
            predicates = next(case for case in contract["cases"] if case["caseId"] == row["caseId"])
            row.update(rawSha256=None if row["raw"] is None else e.digest(row["raw"].encode()),
                       stagesSha256=e.digest(e.compact(row["stages"])), oracleResultSha256=e.digest(e.compact(row["oracle"])),
                       predicatesSha256=e.digest(e.compact(predicates)),
                       resourcesProvenance="unmeasured_fixture" if run["executionMode"] == "synthetic_fake" else "runtime_reported_unverified")
        report = {"version": "intake-conformance-batch-report-" + self.suite.edition, "runSha256": claim["runSha256"],
                  "revisionSha256": claim["revisionSha256"], "instructionsSha256": e.digest(prepared["systemInstructions"].encode()),
                  "cases": cases, "classification": classification, "suiteConformance": "passed" if classification == "passed" else "failed" if classification == "quality_failure" else "incomplete",
                  "probeVerified": probe_verified, "stopVerified": stop_verified, "deadlineBreach": deadline_breach,
                  "probeEvidenceSha256": e.digest(private.private_read(self.batch_directory / "probe-result.json")) if probe_verified else None,
                  "errorCategory": error, "finishedMonotonic": finished,
                  "eventsSha256": event_digest(self.batch_directory / "events.jsonl"),
                  "runtimeSafetyState": runtime.safety_state() if runtime is not None and hasattr(runtime, "safety_state") else None,
                  "evaluationMethod": "deterministic_extractive", "independentHuman": False, "humanAssessment": None,
                  "developmentSetTuning": True, "unseenInputReliabilityProven": False, "productionAdmission": "closed"}
        if self.suite.edition in ("v5", "pilot"):
            report.update(profile=self.suite.profile, manifestSha256=self.scope["manifestSha256"],
                           prospectiveSyntheticEvaluation=self.suite.edition == "v5", independentBlindHoldout=False, developmentSetTuning=False)
            for row in cases:
                row["packetSha256"] = prepared["packetSha256"][row["caseId"]]
        durable(self.batch_directory / "report.json", report)
        receipt = {"version": "intake-conformance-batch-receipt-v4", "claimSha256": e.digest(e.compact(claim)),
                   "reportSha256": e.digest(e.compact(report)), "classification": classification}
        durable(self.directory / f"receipt-{ordinal:02}.json", receipt)
        self.active = None
        if classification != "quality_failure" or ordinal == self.suite.max_batches or finished >= self.scope["workCutoffMonotonic"]:
            durable(self.directory / "terminal.json", {"version": "intake-conformance-decision-v4",
                "status": "passed" if classification == "passed" else "failed" if classification == "quality_failure" else "incomplete",
                "reason": classification if ordinal < 3 else "batch_budget_exhausted" if classification == "quality_failure" else classification,
                "campaignSha256": e.digest(self.scope_raw), "finalReceiptSha256": e.digest(e.compact(receipt)),
                "batchCount": ordinal, "finishedMonotonic": finished, "evaluationMethod": "deterministic_extractive",
                "independentHuman": False, "humanAssessment": None, "productionAdmission": "closed"})
        return report
