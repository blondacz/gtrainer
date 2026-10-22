"""Bounded orchestration for separately approved synthetic intake acquisition."""
import datetime as dt
import json
import math
import os
from pathlib import Path
import time
import uuid

import capture
import protocol

SETTINGS = {"temperature": 0, "seed": 42, "num_thread": 3, "num_ctx": 4096,
            "num_predict": 1536, "think": False, "stream": False,
            "keep_alive": "0s", "format": "json"}
MODEL = {"candidate": "qwen3:8b", "provider": "ollama_mac_cpu",
         "modelVersionSha256": "500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41"}
LIMITS = {"attemptTimeoutSeconds": 900, "caseTimeoutSeconds": 1800,
          "batchTimeoutSeconds": 14400, "stopTimeoutSeconds": 60,
          "memoryLimitBytes": 8589934592, "memoryAbortBytes": 8053063680,
          "maxResponseBytes": 12288, "maxWrapperBytes": 65536, "maxCostUsd": 0}
RUN_FIELDS = {"version", "runId", "profile", "caseIds", "manifestSha256", "model", "settings", "resourceLimits", "runtime", "toolingSha256"}
APPROVAL_FIELDS = {"version", "runSha256", "purpose", "recordedBy", "recordedAt", "expiresUtc",
                   "allowCorrection", "allowCancellationProbe", "approvalEvidence"}
OUTCOMES = {"response", "timeout", "empty", "incomplete", "unrun"}
OUTPUTS = ("acquisition-events.jsonl", "acquisition-result.json", "replay.json")
RUNTIME_FAILURES = {"runtime_refused", "context_template_mismatch", "context_budget_exceeded", "owned_command_failed",
                    "host_memory_pressure", "node_unhealthy", "owned_pod_changed_or_unhealthy", "memory_abort_threshold",
                    "response_transport_limit", "probe_not_active", "probe_deadline"}


class RuntimeFailure(RuntimeError):
    def __init__(self, category):
        self.category = category if category in RUNTIME_FAILURES else "runtime_refused"
        super().__init__("Owned runtime refused")


def tooling_hashes():
    return {name: protocol.digest((protocol.HERE / name).read_bytes())
            for name in ("acquire.py", "mac_runtime.py", "run_mac_intake.py", "capture.py")}


def _need(condition):
    if not condition:
        raise ValueError("Acquisition refused")


def _stamp(value):
    _need(type(value) is str and value.endswith("Z"))
    parsed = dt.datetime.fromisoformat(value[:-1] + "+00:00")
    _need(parsed.utcoffset() == dt.timedelta(0))
    return parsed


def _same_types(value, expected):
    if type(value) is not type(expected):
        return False
    if type(expected) is dict:
        return set(value) == set(expected) and all(_same_types(value[k], v) for k, v in expected.items())
    return value == expected


def authorize(run, approval, now):
    definition, artifacts = protocol.verify()
    _need(type(run) is dict and set(run) == RUN_FIELDS)
    _need(run["version"] == "scoped-intake-acquisition-v1" and type(run["runId"]) is str)
    _need(str(uuid.UUID(run["runId"])) == run["runId"])
    _need(run["profile"] == "context-intake-suggestion-v1")
    profile = next(p for p in definition["profiles"] if p["profile"] == run["profile"])
    cases = [c["id"] for c in profile["cases"]]
    _need(run["caseIds"] == cases)
    _need(run["manifestSha256"] == protocol.digest((protocol.HERE / "manifest-v1.json").read_bytes()))
    _need(run["toolingSha256"] == tooling_hashes())
    _need(_same_types(run["model"], MODEL))
    _need(_same_types(run["settings"], SETTINGS))
    _need(_same_types(run["resourceLimits"], LIMITS))
    _need(type(run["runtime"]) is dict and 0 < len(protocol.compact(run["runtime"])) <= 8192)
    _need(type(approval) is dict and set(approval) == APPROVAL_FIELDS)
    _need(approval["version"] == "scoped-intake-inference-approval-v1")
    _need(approval["runSha256"] == protocol.digest(protocol.compact(run)))
    _need(approval["purpose"] == "synthetic_intake_inference_only")
    _need(capture.text(approval["recordedBy"]) and capture.text(approval["approvalEvidence"], 2048))
    _need(type(approval["allowCorrection"]) is bool and approval["allowCancellationProbe"] is True)
    recorded, expires = _stamp(approval["recordedAt"]), _stamp(approval["expiresUtc"])
    _need(type(now) is dt.datetime and now.tzinfo is not None)
    _need(recorded <= now < expires and dt.timedelta(0) < expires - recorded <= dt.timedelta(hours=24))
    return definition, artifacts


def _resources(value):
    _need(type(value) is dict and set(value) == capture.RESOURCE_FIELDS)
    for item in value.values():
        _need(item is None or (type(item) in (int, float) and math.isfinite(item) and 0 <= item <= 10**15))
    if value["peakRssBytes"] is not None:
        _need(value["peakRssBytes"] <= LIMITS["memoryAbortBytes"])
    if value["costUsd"] is not None:
        _need(value["costUsd"] <= LIMITS["maxCostUsd"])
    return value


def run_acquisition(directory, run, approval, runtime, validator, monotonic=time.monotonic,
                    now=lambda: dt.datetime.now(dt.timezone.utc)):
    """Execute approved batch through injected adapter and validator; retain private evidence."""
    current = now()
    definition, artifacts = authorize(run, approval, current)
    directory = capture.private_directory(directory)
    paths = [directory / name for name in OUTPUTS]
    _need(all(not p.exists() and not p.is_symlink() for p in paths))
    frozen = {row["caseId"]: row for row in artifacts["cases"] if row["profile"] == run["profile"]}
    cases = [{"caseId": cid, "attempts": []} for cid in run["caseIds"]]
    event_descriptor = os.open(paths[0], os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    event_stream = os.fdopen(event_descriptor, "wb")
    start = monotonic()
    batch_end = start + min(LIMITS["batchTimeoutSeconds"], (_stamp(approval["expiresUtc"]) - current).total_seconds())
    state = {"owned": False, "aborted": False, "reason": None, "cancelled": False, "stopVerified": None}

    def emit(event):
        _need(type(event) is dict and len(protocol.compact(event)) <= 8192)
        event = dict(event, elapsedMsSinceStart=(monotonic() - start) * 1000)
        _need(event_stream.tell() + len(protocol.compact(event)) + 1 <= 16 * 1024 * 1024)
        event_stream.write(protocol.compact(event) + b"\n")
        event_stream.flush()
        os.fsync(event_stream.fileno())

    def abort(reason):
        state["aborted"], state["reason"] = True, reason

    def remaining(deadline):
        if monotonic() >= deadline:
            abort("deadline")
            return False
        return True

    try:
        preflight = runtime.preflight(run)
        _need(type(preflight) is dict and len(protocol.compact(preflight)) <= 8192)
        state["owned"] = True
        emit({"type": "preflight", "status": "passed", "evidence": preflight})
        probe = runtime.cancellation_probe(run, emit, min(batch_end, monotonic() + LIMITS["attemptTimeoutSeconds"]))
        _need(type(probe) is dict and probe.get("verified") is True and len(protocol.compact(probe)) <= 8192)
        emit({"type": "cancellation_probe", "status": "passed", "evidence": probe})
    except Exception as error:
        abort(error.category if isinstance(error, RuntimeFailure) else "preflight_or_probe_failure")
        if state["owned"]:
            try:
                result = runtime.cancel(run, monotonic() + LIMITS["stopTimeoutSeconds"], emit)
                state["stopVerified"] = type(result) is dict and result.get("verified") is True
                emit({"type": "cancellation", "verified": state["stopVerified"]})
                state["cancelled"] = True
            except Exception:
                emit({"type": "cancellation", "verified": False})
                state["stopVerified"] = False
                state["cancelled"] = True

    for index, case in enumerate(cases):
        if state["aborted"]:
            break
        case_start = monotonic()
        case_end = min(batch_end, case_start + LIMITS["caseTimeoutSeconds"])
        source = frozen[case["caseId"]]
        prompts = (source["exactPrompt"], source["exactCorrectionPrompt"])
        for attempt_index in range(2):
            if state["aborted"] or (attempt_index and not approval["allowCorrection"]):
                break
            if attempt_index and case["attempts"][-1].get("validation") != "rejected" and case["attempts"][-1]["outcome"] != "empty":
                break
            deadline = min(batch_end, case_end, monotonic() + LIMITS["attemptTimeoutSeconds"])
            if not remaining(deadline):
                break
            try:
                output = runtime.generate(prompts[attempt_index], run, deadline, emit)
                _need(type(output) is dict and set(output) <= {"outcome", "raw", "resources"})
                outcome = output.get("outcome")
                raw = output.get("raw")
                _need(outcome in OUTCOMES - {"unrun"})
                _need(raw is None or type(raw) is str)
                _need(raw is None or len(raw.encode("utf-8")) <= LIMITS["maxResponseBytes"])
                resources = _resources(output.get("resources"))
                if monotonic() >= deadline:
                    outcome = "timeout"
                if outcome == "response" and not raw:
                    outcome = "empty"
                retained = {"attempt": attempt_index + 1, "outcome": outcome, "raw": raw,
                            "resources": resources, "validation": None}
                case["attempts"].append(retained)
                if outcome == "response":
                    valid = validator(case["caseId"], attempt_index + 1, raw)
                    _need(type(valid) is bool)
                    retained["validation"] = "accepted" if valid else "rejected"
                    if not remaining(min(batch_end, case_end)):
                        break
                    if valid:
                        break
                if outcome in ("timeout", "incomplete"):
                    abort(outcome)
                    break
                if not remaining(min(batch_end, case_end)):
                    break
            except Exception as error:
                if len(case["attempts"]) == attempt_index:
                    case["attempts"].append({"attempt": attempt_index + 1, "outcome": "incomplete", "raw": None,
                                              "resources": {name: None for name in capture.RESOURCE_FIELDS},
                                              "validation": None})
                abort(error.category if isinstance(error, RuntimeFailure) else "adapter_or_validation_failure")
                break
        if monotonic() >= min(batch_end, case_end):
            abort("deadline")
    if state["owned"] and not state["cancelled"]:
        try:
            stopped = runtime.cancel(run, monotonic() + LIMITS["stopTimeoutSeconds"], emit)
            verified = type(stopped) is dict and stopped.get("verified") is True
            state["stopVerified"] = verified
            emit({"type": "cancellation", "verified": verified})
            if not verified:
                abort("cancellation_unverified")
        except Exception:
            emit({"type": "cancellation", "verified": False})
            state["stopVerified"] = False
            abort("cancellation_unverified")
        state["cancelled"] = True
    # Mark every not-started case explicitly, preserving complete frozen denominator.
    for case in cases:
        if not case["attempts"]:
            case["attempts"].append({"attempt": 1, "outcome": "unrun", "raw": None,
                                     "resources": {name: None for name in capture.RESOURCE_FIELDS}, "validation": None})
    event_stream.close()
    result = {"version": "scoped-intake-acquisition-result-v1", "run": run,
              "approvalSha256": protocol.digest(protocol.compact(approval)), "cases": cases,
               "status": "aborted" if state["aborted"] else "completed", "errorCategory": state["reason"],
               "cancellationVerified": state["stopVerified"],
              "qualification": "incomplete", "productionAdmission": "closed"}
    replay_cases = [{"caseId": c["caseId"], "attempts": [{k: a[k] for k in ("attempt", "outcome", "raw", "resources")} for a in c["attempts"]]}
                    for c in cases]
    replay = {"version": "scoped-private-replay-v1", "cases": replay_cases}
    payloads = (protocol.compact(result), protocol.compact(replay))
    _need(all(len(data) <= capture.MAX_FILE_BYTES for data in payloads))
    for path, data in zip(paths[1:], payloads):
        capture.private_write(path, data)
    return result
