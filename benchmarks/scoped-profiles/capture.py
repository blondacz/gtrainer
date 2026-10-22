"""Private, offline-only replay and human-review packets for frozen scoped profiles.

This module has no model adapter. A replay approval authorizes local processing of
existing synthetic responses, never inference, transfer, qualification or application.
"""
import argparse
import datetime as dt
import json
import math
import os
from pathlib import Path
import stat
import subprocess
import uuid

import protocol

MAX_FILE_BYTES = 2 * 1024 * 1024
RUN_FIELDS = {"version", "runId", "executionMode", "inputOrigin", "profile", "caseIds", "manifestSha256",
              "model", "settings", "resourceLimits"}
APPROVAL_FIELDS = {"version", "runSha256", "purpose", "allowCorrection", "recordedBy", "recordedAt", "expiresUtc"}
RESOURCE_FIELDS = {"elapsedMs", "peakRssBytes", "inputTokens", "outputTokens", "costUsd"}


def require(condition):
    if not condition:
        raise ValueError("Private replay input refused")


def timestamp(value):
    require(type(value) is str and value.endswith("Z"))
    return dt.datetime.fromisoformat(value[:-1] + "+00:00")


def private_directory(directory):
    directory = Path(directory).absolute()
    require(directory.exists() and directory.is_dir())
    require(all(not p.is_symlink() for p in [directory, *directory.parents]))
    require(os.path.commonpath([str(directory.resolve()), str(protocol.ROOT)]) != str(protocol.ROOT))
    require(stat.S_IMODE(directory.stat().st_mode) == 0o700)
    return directory


def private_read(path):
    require(not path.is_symlink())
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(source.fileno())
        require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600 and info.st_nlink == 1)
        require(info.st_size <= MAX_FILE_BYTES)
        data = source.read(MAX_FILE_BYTES + 1)
    require(len(data) <= MAX_FILE_BYTES)
    return data


def private_write(path, data):
    require(len(data) <= MAX_FILE_BYTES)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def read_json(path):
    return protocol.decode(private_read(path))


def text(value, limit=256):
    return type(value) is str and 0 < len(value.encode("utf-8")) <= limit


def sha(value):
    return type(value) is str and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def bounded_number(value, maximum):
    return type(value) in (int, float) and math.isfinite(value) and 0 <= value <= maximum


def authorize(run, approval, definition, now):
    require(set(run) == RUN_FIELDS and run["version"] == "scoped-private-run-v1")
    require(str(uuid.UUID(run["runId"])) == run["runId"])
    require(run["executionMode"] == "offline_replay" and run["inputOrigin"] in ("synthetic_fake", "externally_recorded"))
    profiles = {p["profile"]: p for p in definition["profiles"]}
    require(run["profile"] in profiles)
    profile = profiles[run["profile"]]
    require(run["caseIds"] == [c["id"] for c in profile["cases"]])
    require(run["manifestSha256"] == protocol.digest((protocol.HERE / "manifest-v1.json").read_bytes()))
    model = run["model"]
    require(set(model) == {"candidate", "provider", "modelVersionSha256"})
    require(text(model["candidate"]) and text(model["provider"]) and sha(model["modelVersionSha256"]))
    require(type(run["settings"]) is dict and bool(run["settings"]) and len(protocol.compact(run["settings"])) <= 4096)
    limits = run["resourceLimits"]
    require(set(limits) == {"attemptTimeoutSeconds", "caseTimeoutSeconds", "maxResponseBytes", "memoryLimitBytes", "maxCostUsd"})
    require(type(limits["maxResponseBytes"]) is int and 1 <= limits["maxResponseBytes"] <= 12288)
    require(type(limits["attemptTimeoutSeconds"]) is int and 1 <= limits["attemptTimeoutSeconds"] <= 3600)
    require(type(limits["caseTimeoutSeconds"]) is int and limits["attemptTimeoutSeconds"] <= limits["caseTimeoutSeconds"] <= 7200)
    require(type(limits["memoryLimitBytes"]) is int and limits["memoryLimitBytes"] > 0)
    require(bounded_number(limits["maxCostUsd"], 1000))
    require(set(approval) == APPROVAL_FIELDS and approval["version"] == "scoped-capture-approval-v1")
    require(approval["purpose"] == "synthetic_offline_replay_only" and type(approval["allowCorrection"]) is bool)
    require(text(approval["recordedBy"]) and timestamp(approval["recordedAt"]) <= now < timestamp(approval["expiresUtc"]))
    require(timestamp(approval["expiresUtc"]) - timestamp(approval["recordedAt"]) <= dt.timedelta(hours=24))
    require(approval["runSha256"] == protocol.digest(protocol.compact(run)))
    return profile


def attempts_from(replay, run):
    require(set(replay) == {"version", "cases"} and replay["version"] == "scoped-private-replay-v1")
    require(type(replay["cases"]) is list and len(replay["cases"]) <= len(run["caseIds"]))
    indexed = {}
    for case in replay["cases"]:
        require(set(case) == {"caseId", "attempts"} and case["caseId"] in run["caseIds"] and case["caseId"] not in indexed)
        require(type(case["attempts"]) is list and len(case["attempts"]) <= 2)
        seen = []
        for attempt in case["attempts"]:
            require(set(attempt) == {"attempt", "outcome", "raw", "resources"})
            number = attempt["attempt"]
            require(type(number) is int and number in (1, 2) and number not in seen)
            seen.append(number)
            require(attempt["outcome"] in ("response", "timeout", "empty", "incomplete", "unrun"))
            raw = attempt["raw"]
            require(raw is None or type(raw) is str)
            require(raw is None or len(raw.encode("utf-8")) <= run["resourceLimits"]["maxResponseBytes"])
            require(attempt["outcome"] != "response" or raw is not None)
            resources = attempt["resources"]
            require(set(resources) == RESOURCE_FIELDS and all(value is None or bounded_number(value, 10**15) for value in resources.values()))
        require(seen == sorted(seen))
        indexed[case["caseId"]] = case["attempts"]
    return indexed


def kotlin_validate(directory, requests):
    """Run test-source validators only. All subprocess output stays private."""
    bridge = directory / "validation"
    bridge.mkdir(mode=0o700)
    private_write(bridge / "request.json", protocol.compact({"version": "scoped-capture-validation-v1", "requests": requests}))
    environment = dict(os.environ, GTRAINER_SCOPED_CAPTURE_VALIDATION_DIR=str(bridge))
    environment.pop("GTRAINER_SCOPED_EVALUATION_EXPORT_DIR", None)
    command = [str(protocol.ROOT / "gradlew"), "--offline", "--no-daemon", "--max-workers=2",
               "-Pkotlin.compiler.execution.strategy=in-process", ":backend:test", "--tests",
               "com.gtrainer.ScopedCaptureValidationTest", "--rerun"]
    descriptor = os.open(directory / "validation.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as log:
        completed = subprocess.run(command, cwd=protocol.ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=600, check=False)
    require(completed.returncode == 0)
    result = read_json(bridge / "result.json")
    require(set(result) == {"version", "results"} and result["version"] == "scoped-capture-validation-v1")
    return result["results"]


def replay_capture(directory, now, validator=kotlin_validate):
    definition, artifacts = protocol.verify()
    directory = private_directory(directory)
    # A separate existing approval is checked before reading responses or invoking any callback.
    run = read_json(directory / "run.json")
    approval_bytes = private_read(directory / "approval.json")
    approval = protocol.decode(approval_bytes)
    profile = authorize(run, approval, definition, now)
    require(not (directory / "capture.json").exists() and not (directory / "human-review.json").exists())
    indexed = attempts_from(read_json(directory / "replay.json"), run)
    require(approval["allowCorrection"] or all(all(a["attempt"] == 1 for a in rows) for rows in indexed.values()))
    requests = [{"caseId": case, "attempt": a["attempt"], "raw": a["raw"]}
                for case in run["caseIds"] for a in indexed.get(case, []) if a["outcome"] == "response" and a["raw"]]
    results = validator(directory, requests) if requests else []
    require(type(results) is list and len(results) == len(requests))
    validated = {}
    for request, result in zip(requests, results):
        require(set(result) == {"caseId", "attempt", "status"} and result["caseId"] == request["caseId"]
                and type(result["attempt"]) is int and result["attempt"] == request["attempt"] and result["status"] in ("accepted", "rejected"))
        validated[(result["caseId"], result["attempt"])] = result["status"]
    frozen = {row["caseId"]: row for row in artifacts["cases"]}
    cases = []
    total_cost = 0
    for case in run["caseIds"]:
        rows = []
        final = "unrun"
        total_elapsed = 0
        for attempt in indexed.get(case, []):
            raw = attempt["raw"]
            outcome = attempt["outcome"]
            if outcome == "response":
                outcome = validated[(case, attempt["attempt"])] if raw else "empty"
            validated_outcome = outcome
            resources = attempt["resources"]
            limits = run["resourceLimits"]
            violations = []
            elapsed = resources["elapsedMs"]
            if elapsed is not None:
                total_elapsed += elapsed
                if elapsed > limits["attemptTimeoutSeconds"] * 1000 or total_elapsed > limits["caseTimeoutSeconds"] * 1000:
                    violations.append("deadline_exceeded")
            if resources["peakRssBytes"] is not None and resources["peakRssBytes"] > limits["memoryLimitBytes"]:
                violations.append("memory_exceeded")
            if resources["costUsd"] is not None:
                total_cost += resources["costUsd"]
            if total_cost > limits["maxCostUsd"]:
                violations.append("cost_exceeded")
            if violations:
                outcome = "timeout" if "deadline_exceeded" in violations else "incomplete"
            eligible = attempt["attempt"] == 1 or final in ("rejected", "empty")
            # Preserve an orphan/redundant correction but never count it as a successful final.
            if eligible:
                final = outcome
            rows.append(dict(attempt, validatedOutcome=validated_outcome, recordedOutcome=outcome, resourceViolations=violations, eligible=eligible,
                             rawSha256=None if raw is None else protocol.digest(raw.encode("utf-8"))))
        source = frozen[case]
        cases.append({"caseId": case, "consentDigest": source["consentDigest"],
                      "firstPromptSha256": protocol.digest(protocol.compact(source["exactPrompt"])),
                      "correctionPromptSha256": protocol.digest(protocol.compact(source["exactCorrectionPrompt"])),
                      "attempts": rows, "finalOutcome": final})
    capture = {"version": "scoped-private-capture-v1", "run": run, "approvalSha256": protocol.digest(approval_bytes),
               "createdUtc": now.isoformat(), "validation": "production_kotlin", "resourceProvenance": "imported_not_measured_or_verified",
               "toolingSha256": {"capture.py": protocol.digest(Path(__file__).read_bytes()),
                   "ScopedCaptureValidationTest.kt": protocol.digest((protocol.ROOT / "backend/src/test/kotlin/com/gtrainer/ScopedCaptureValidationTest.kt").read_bytes())},
               "inferenceCalls": 0, "productionAdmission": "closed", "cases": cases}
    capture_bytes = protocol.compact(capture)
    report = {"version": "scoped-private-human-review-v1", "captureSha256": protocol.digest(capture_bytes),
              "profile": profile, "gates": definition["gates"], "scoreScale": definition["scoreScale"],
              "criticalFailureCategories": definition["criticalFailures"], "preparationGuards": artifacts["guards"],
              "independentHumanScoringRequired": True, "qualification": "incomplete", "productionAdmission": "closed",
              "cases": [{"capture": case, "frozenInput": frozen[case["caseId"]],
                         "expectation": next(c for c in profile["cases"] if c["id"] == case["caseId"]),
                         "scores": {d["id"]: None for d in profile["dimensions"]}, "usefulFinal": None,
                         "criticalFailures": None} for case in cases]}
    # Serialize both before creating either; refusals never overwrite prior private evidence.
    report_bytes = protocol.compact(report)
    require(max(len(capture_bytes), len(report_bytes)) <= MAX_FILE_BYTES)
    private_write(directory / "capture.json", capture_bytes)
    private_write(directory / "human-review.json", report_bytes)
    return capture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path, help="Existing external 0700 private replay directory")
    args = parser.parse_args()
    try:
        replay_capture(args.directory, dt.datetime.now(dt.timezone.utc))
    except Exception:
        # Input, raw output and paths must never appear in terminal or CI diagnostics.
        print("Offline replay refused; inspect private inputs and validator status locally.")
        return 1
    print("Private offline replay complete. No inference. Qualification remains closed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
