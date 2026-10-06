"""Read-only reconciliation of preserved Q3 captures; never resumes inference."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from connected_review_packets_v2 import ROOT, load_cases

PROFILE = "connected-review-pi-qwen3-q3-microsd-reference-v1"
IDENTITY_FIELDS = (
    "evaluation_version", "contract_version", "rubric_version", "validator_version",
    "settings", "model_digest", "model_source", "storage_kind",
    "container_memory_limit_mib", "minimum_host_available_mib",
    "attempt_timeout_seconds", "case_total_timeout_seconds",
    "max_response_bytes", "maximum_attempts_per_case", "system_prompt_sha256",
)
FROZEN_FILES = {
    "cases": ROOT / "cases-v2.json",
    "evaluation": ROOT / "evaluation-v3.json",
    "packet_adapter": ROOT / "connected_review_packets_v2.py",
    "validator": ROOT / "connected_review_validator_v3.py",
    "contract_source": ROOT.parents[1] / "backend/src/main/kotlin/com/gtrainer/InterpretationContractV1.kt",
}


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def read_part(path: Path) -> dict:
    raw = path.read_bytes()
    records = []
    for line_number, line in enumerate(raw.splitlines(), 1):
        if line.strip():
            event = json.loads(line)
            if not isinstance(event, dict):
                raise ValueError("Capture records must be objects")
            records.append((line_number, event))
    headers = [event for _, event in records if event.get("phase") == "evaluation_started"]
    if len(headers) != 1 or headers[0].get("evaluation_version") != PROFILE:
        raise ValueError("Capture chain requires one Q3 evaluation header per part")
    header = headers[0]
    if any(key not in header for key in IDENTITY_FIELDS):
        raise ValueError("Capture chain has incomplete runtime identity")
    artifacts = header.get("artifact_sha256", {})
    if any(artifacts.get(key) != digest(file.read_bytes()) for key, file in FROZEN_FILES.items()):
        raise ValueError("Capture chain frozen artifacts differ")
    cleanup = [event for _, event in records if event.get("phase") == "owned_cleanup_complete"]
    if len(cleanup) != 1 or cleanup[0].get("namespace_removed") is not True:
        raise ValueError("Capture chain requires confirmed owned cleanup for every part")
    return {"path": str(path), "sha256": digest(raw), "header": header, "records": records}


def verify_link(part: dict, previous: dict | None, remaining: list[str]) -> None:
    resumes = [event for _, event in part["records"] if event.get("phase") == "evaluation_resumed"]
    if previous is None:
        if resumes:
            raise ValueError("Capture chain must start with its original capture")
        return
    if any(part["header"][key] != previous["header"][key] for key in IDENTITY_FIELDS):
        raise ValueError("Capture chain runtime/model/budget identity differs")
    if len(resumes) != 1 or not remaining:
        raise ValueError("Capture chain continuation requires one resume record")
    resume = resumes[0]
    if (resume.get("previous_capture_sha256") != previous["sha256"] or
            resume.get("start_case") != remaining[0] or
            resume.get("selected_case_ids") != remaining or
            resume.get("previous_outcomes_not_copied") is not True):
        raise ValueError("Capture chain link or selected case suffix differs")


def accepted_prefix(part: dict, remaining: list[str]) -> list[str]:
    completed = [event for _, event in part["records"] if event.get("phase") == "case_complete"]
    prefix = [event.get("case") for event in completed]
    if prefix != remaining[:len(prefix)] or any(event.get("status") != "accepted" for event in completed):
        raise ValueError("Capture chain requires a contiguous accepted completed prefix")
    attempts = [event for _, event in part["records"]
                if event.get("phase") in ("attempt_result", "attempt_failure")]
    allowed = remaining[:min(len(prefix) + 1, len(remaining))]
    if any(event.get("case") not in allowed for event in attempts):
        raise ValueError("Capture chain attempts are outside the completed prefix/next case")
    for case_id in prefix:
        case_attempts = [event for event in attempts if event.get("case") == case_id]
        if not case_attempts or case_attempts[-1].get("status") != "accepted":
            raise ValueError("Capture chain completed case has no accepted final attempt")
    for case_id in allowed:
        numbers = [event.get("attempt") for event in attempts if event.get("case") == case_id]
        if numbers and numbers not in ([1], [1, 2]):
            raise ValueError("Capture chain attempt numbers must restart per invocation")
    return prefix


def reconcile(paths: list[Path]) -> dict:
    if not paths:
        raise ValueError("Capture chain requires at least one original capture")
    parts = [read_part(path) for path in paths]
    if len({part["sha256"] for part in parts}) != len(parts):
        raise ValueError("Capture chain contains duplicate source bytes")
    case_ids = [case["id"] for case in load_cases()]
    completed = []
    attempts = []
    previous = None
    for number, part in enumerate(parts, 1):
        remaining = case_ids[len(completed):]
        verify_link(part, previous, remaining)
        completed.extend(accepted_prefix(part, remaining))
        for line, event in part["records"]:
            if event.get("phase") in ("attempt_result", "attempt_failure"):
                attempts.append({"event": event, "capture_part": number,
                                 "capture_sha256": part["sha256"], "source_line": line})
        previous = part
    return {"parts": parts, "attempts": attempts, "accepted_cases": completed,
            "not_run_cases": [case for case in case_ids
                              if not any(item["event"].get("case") == case for item in attempts)]}
