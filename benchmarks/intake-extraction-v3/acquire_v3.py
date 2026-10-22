"""One-attempt v3 acquisition orchestration. No CLI or implicit authority."""
import datetime as dt
import copy
import os
import time
import uuid

import experiment as e
import replay
import runtime_v3
import capture
import acquire as mac_acquire
import mac_runtime as mac

LIMITS = dict(mac_acquire.LIMITS)
LIMITS["batchTimeoutSeconds"] = 14400
RUN_VERSION = "intake-extraction-acquisition-run-v3"
APPROVAL_VERSION = "intake-extraction-acquisition-approval-v3"
RUN_FIELDS = {"version", "runId", "executionMode", "profile", "caseIds", "manifestSha256", "toolingSha256",
              "model", "settings", "resourceLimits", "runtime", "experiment", "runnerPolicy", "requestVariant"}
APPROVAL_FIELDS = {"version", "runSha256", "purpose", "recordedBy", "recordedAt", "expiresUtc",
                   "allowCancellationProbe", "allowCorrection", "approvalEvidence"}
OUTCOMES = {"response", "empty", "timeout", "incomplete", "unrun"}
FILES = ("acquisition-run.json", "inference-approval.json", "acquisition-events.jsonl",
         "acquisition-result.json", "replay.json", "acquisition.lock")


def _need(value):
    if not value:
        raise ValueError("V3 acquisition refused")


def _same(a, b):
    if type(a) is not type(b): return False
    if type(b) is dict: return set(a) == set(b) and all(_same(a[k], b[k]) for k in b)
    if type(b) is list: return len(a) == len(b) and all(_same(x, y) for x, y in zip(a, b))
    return a == b


def _stamp(value):
    _need(type(value) is str and value.endswith("Z"))
    result = dt.datetime.fromisoformat(value[:-1] + "+00:00")
    _need(result.utcoffset() == dt.timedelta(0))
    return result


def tooling_hashes():
    names = [e.PREFIX + n for n in ("acquire_v3.py", "test_acquire_v3.py", "runtime_v3.py",
             "run_mac_v3.py", "test_runtime_v3.py", "replay.py", "experiment.py")]
    names.append(e.PREFIX + "test_acquisition_integration.py")
    names += ["benchmarks/scoped-profiles/" + n for n in ("acquire.py", "mac_runtime.py", "observed_mmap_runtime.py", "capture.py", "protocol.py",
              "run_mac_intake.py", "test_mac_runtime.py", "test_mac_runtime_safety.py")]
    names += replay.TOOLING
    return {name: e.digest((e.ROOT / name).read_bytes()) for name in dict.fromkeys(names)}


def make_run(run_id, execution_mode):
    _need(execution_mode in ("live", "synthetic_fake") and type(execution_mode) is str)
    _need(type(run_id) is str and str(uuid.UUID(run_id)) == run_id)
    definition, artifacts = e.verify()
    model = replay.MODEL if execution_mode == "live" else replay.FAKE_MODEL
    return copy.deepcopy({"version": RUN_VERSION, "runId": run_id, "executionMode": execution_mode,
            "profile": e.PROFILE, "caseIds": list(e.IDS),
            "manifestSha256": e.digest((e.HERE / "manifest-v3.json").read_bytes()),
            "toolingSha256": tooling_hashes(), "model": model,
            "settings": replay.settings(artifacts), "resourceLimits": LIMITS,
              "runtime": mac.IDENTITY, "experiment": runtime_v3.bindings(),
              "runnerPolicy": runtime_v3.RUNNER_POLICY, "requestVariant": runtime_v3.request_variant()})


def authorize(run, approval, now):
    _, artifacts = e.verify()
    _need(type(run) is dict and set(run) == RUN_FIELDS and run["version"] == RUN_VERSION)
    _need(type(run["runId"]) is str and str(uuid.UUID(run["runId"])) == run["runId"])
    _need(run["executionMode"] in ("live", "synthetic_fake") and type(run["executionMode"]) is str)
    _need(run["profile"] == e.PROFILE and type(run["caseIds"]) is list and run["caseIds"] == e.IDS)
    _need(run["manifestSha256"] == e.digest((e.HERE / "manifest-v3.json").read_bytes()))
    _need(_same(run["toolingSha256"], tooling_hashes()))
    _need(_same(run["model"], replay.MODEL if run["executionMode"] == "live" else replay.FAKE_MODEL))
    _need(_same(run["settings"], replay.settings(artifacts)) and _same(run["resourceLimits"], LIMITS))
    _need(_same(run["runtime"], mac.IDENTITY))
    _need(_same(run["runnerPolicy"], runtime_v3.RUNNER_POLICY))
    _need(_same(run["requestVariant"], runtime_v3.request_variant()))
    _need(_same(run["experiment"], runtime_v3.bindings()))
    _need(type(approval) is dict and set(approval) == APPROVAL_FIELDS)
    _need(approval["version"] == APPROVAL_VERSION and approval["runSha256"] == e.digest(e.compact(run)))
    purpose = "synthetic_intake_inference_only" if run["executionMode"] == "live" else "synthetic_fake_acquisition_only"
    _need(approval["purpose"] == purpose and capture.text(approval["recordedBy"]) and capture.text(approval["approvalEvidence"], 2048))
    _need(type(approval["allowCancellationProbe"]) is bool and approval["allowCancellationProbe"] is True)
    _need(type(approval["allowCorrection"]) is bool and approval["allowCorrection"] is False)
    _need(type(now) is dt.datetime and now.tzinfo is not None)
    recorded, expires = _stamp(approval["recordedAt"]), _stamp(approval["expiresUtc"])
    _need(recorded <= now < expires and dt.timedelta(0) < expires - recorded <= dt.timedelta(hours=24))
    return artifacts


def run_acquisition(directory, run, approval, runtime, validator, monotonic=time.monotonic,
                    now=lambda: dt.datetime.now(dt.timezone.utc)):
    current = now()
    artifacts = authorize(run, approval, current)
    run, approval = copy.deepcopy(run), copy.deepcopy(approval)
    directory = capture.private_directory(directory)
    paths = {name: directory / name for name in FILES}
    _need(all(not paths[n].exists() and not paths[n].is_symlink() for n in FILES[2:]))
    _need(run["executionMode"] == "live" or getattr(runtime, "synthetic_fake", False) is True)
    def record(name, value):
        path = paths[name]
        if path.exists() or path.is_symlink():
            raw = capture.private_read(path)
            _need(_same(e.decode(raw), value))
            return raw
        raw = e.compact(value)
        capture.private_write(path, raw)
        return raw
    run_bytes = record("acquisition-run.json", run)
    approval_bytes = record("inference-approval.json", approval)
    capture.private_write(paths["acquisition.lock"], e.compact({"runSha256": e.digest(e.compact(run))}))
    start = monotonic()
    batch_end = start + min(LIMITS["batchTimeoutSeconds"], (_stamp(approval["expiresUtc"]) - current).total_seconds())
    cases = [{"caseId": cid, "attempts": []} for cid in e.IDS]
    state = {"owned": False, "stopped": False, "stopVerified": None, "aborted": False, "reason": None}
    stream = None

    def abort(reason):
        state["aborted"] = True
        if state["reason"] is None: state["reason"] = reason

    def emit(event):
        data = e.compact(dict(event, elapsedMsSinceStart=max(0, (monotonic() - start) * 1000))) + b"\n"
        _need(len(data) <= 65536 and stream.tell() + len(data) <= 16 * 1024 * 1024)
        stream.write(data); stream.flush(); os.fsync(stream.fileno())

    def stop():
        if not state["owned"] or state["stopped"]: return
        deadline = monotonic() + LIMITS["stopTimeoutSeconds"]
        def safe_emit(event):
            try: emit(event)
            except BaseException: abort("event_logging_failed")
        try:
            result = runtime.cancel(run, deadline, safe_emit)
            verified = type(result) is dict and result.get("verified") is True and monotonic() <= deadline
        except BaseException:
            verified = False
        state["stopVerified"], state["stopped"] = verified, True
        if not verified: abort("cancellation_unverified")
        try: emit({"type": "cancellation", "verified": verified,
                   "runtimeSafetyState": runtime.safety_state() if hasattr(runtime, "safety_state") else None})
        except BaseException: abort("event_logging_failed")

    try:
        fd = os.open(paths["acquisition-events.jsonl"], os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        stream = os.fdopen(fd, "wb")
        try:
            if hasattr(runtime, "deadline"):
                runtime.deadline = batch_end
            preflight = runtime.preflight(run)
            state["owned"] = True
            _need(monotonic() < batch_end)
            emit({"type": "preflight", "status": "passed", "evidence": preflight})
            _need(monotonic() < batch_end)
            probe_deadline = min(batch_end, monotonic() + LIMITS["attemptTimeoutSeconds"])
            _need(monotonic() < probe_deadline)
            probe = runtime.cancellation_probe(run, emit, probe_deadline)
            _need(type(probe) is dict and probe.get("verified") is True and monotonic() < probe_deadline)
            emit({"type": "cancellation_probe", "status": "passed", "evidence": probe})
        except BaseException as exc:
            abort(getattr(exc, "category", "preflight_or_probe_failure"))
        for case in cases:
            if state["aborted"]: break
            case_start = monotonic(); case_end = min(batch_end, case_start + LIMITS["caseTimeoutSeconds"])
            source = next(c for c in artifacts["cases"] if c["caseId"] == case["caseId"])
            deadline = min(case_end, batch_end, monotonic() + LIMITS["attemptTimeoutSeconds"])
            attempt = {"attempt": 1, "outcome": "incomplete", "raw": None,
                       "resources": {k: None for k in sorted(replay.RESOURCE_FIELDS)}, "rawSha256": None,
                       "validation": None, "stages": None}
            case["attempts"].append(attempt)
            try:
                _need(monotonic() < deadline)
                emit({"type": "attempt_started", "caseId": case["caseId"], "attempt": 1,
                       "requestSha256": run["requestVariant"]["requestSha256"][case["caseId"]]})
                output = runtime.generate(source["exactPrompt"], run, deadline, emit)
                _need(type(output) is dict and set(output) == {"outcome", "raw", "resources"})
                outcome, raw = output["outcome"], output["raw"]
                _need(outcome in OUTCOMES - {"unrun"})
                _need(raw is None or type(raw) is str and len(raw.encode()) <= LIMITS["maxResponseBytes"])
                attempt.update(outcome=outcome, raw=raw, rawSha256=None if raw is None else e.digest(raw.encode()))
                emit({"type": "attempt_raw", "caseId": case["caseId"], "attempt": attempt})
                _need(type(output["resources"]) is dict and set(output["resources"]) == replay.RESOURCE_FIELDS)
                for key, value in output["resources"].items():
                    _need(value is None or capture.bounded_number(value, 10**15))
                    if key in ("peakRssBytes", "inputTokens", "outputTokens"):
                        _need(value is None or type(value) is int)
                _need(output["resources"]["peakRssBytes"] is None or output["resources"]["peakRssBytes"] <= LIMITS["memoryAbortBytes"])
                _need(output["resources"]["costUsd"] is None or output["resources"]["costUsd"] <= LIMITS["maxCostUsd"])
                if run["executionMode"] == "synthetic_fake": _need(all(v is None for v in output["resources"].values()))
                attempt.update(outcome=outcome, raw=raw, resources=output["resources"],
                               rawSha256=None if raw is None else e.digest(raw.encode()))
                if monotonic() >= deadline: attempt["outcome"] = "timeout"
                elif outcome == "response" and (raw is None or not raw.strip()): attempt["outcome"] = "empty"
                _need(attempt["outcome"] != "empty" or raw is None or not raw.strip())
                emit({"type": "attempt_output", "caseId": case["caseId"], "attempt": attempt})
                if attempt["outcome"] == "response":
                    checked = validator(case["caseId"], raw, min(case_end, batch_end))
                    replay.check_results([checked], [{"caseId": case["caseId"], "rawSha256": attempt["rawSha256"]}])
                    attempt["stages"] = checked["stages"]
                    attempt["validation"] = "accepted" if attempt["stages"]["semantic"] == "accepted" else "rejected"
                    if monotonic() >= min(case_end, batch_end):
                        abort("validation_deadline")
                    emit({"type": "attempt_validation", "caseId": case["caseId"], "attempt": attempt})
                if attempt["outcome"] in ("timeout", "incomplete"): abort(attempt["outcome"])
                if monotonic() >= min(case_end, batch_end): abort("deadline")
            except BaseException as exc:
                if attempt["validation"] is None and attempt["outcome"] not in ("timeout", "incomplete"):
                    attempt["outcome"] = "incomplete"
                abort(getattr(exc, "category", "adapter_or_validation_failure"))
            if state["aborted"]: break
        stop()
    except BaseException:
        abort("acquisition_interrupted")
        stop()
        raise
    finally:
        if state["owned"] and not state["stopped"]: stop()
        if stream is not None:
            try: stream.close()
            except BaseException: abort("event_logging_failed")
    for case in cases:
        if not case["attempts"]:
            case["attempts"].append({"attempt": 1, "outcome": "unrun", "raw": None,
                "resources": {k: None for k in sorted(replay.RESOURCE_FIELDS)}, "rawSha256": None,
                "validation": None, "stages": None})
    result = {"version": "intake-extraction-acquisition-result-v3", "executionMode": run["executionMode"],
              "run": run, "runFileSha256": e.digest(run_bytes), "approvalSha256": e.digest(approval_bytes),
              "cases": cases, "status": "aborted" if state["aborted"] else "completed",
               "errorCategory": state["reason"], "stopVerified": state["stopVerified"],
               "runtimeSafetyState": runtime.safety_state() if hasattr(runtime, "safety_state") else None,
               "qualification": "incomplete", "experimentalSuccess": False, "productionAdmission": "closed"}
    replay_cases = [{"caseId": c["caseId"], "outcome": c["attempts"][0]["outcome"],
                     "raw": c["attempts"][0]["raw"], "resources": c["attempts"][0]["resources"]} for c in cases]
    payloads = {"acquisition-result.json": e.compact(result),
                "replay.json": e.compact({"version": "intake-extraction-replay-v3", "cases": replay_cases})}
    for name, data in payloads.items(): capture.private_write(paths[name], data)
    return result
