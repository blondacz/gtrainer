"""Private v3 offline replay. No provider, runtime, store or network access."""
import argparse
import datetime as dt
import os
from pathlib import Path
import subprocess
import uuid

import experiment as e
import capture as private

TOOLING = [e.PREFIX + n for n in ("replay.py", "test_replay.py")]
TOOLING += ["backend/src/test/kotlin/com/gtrainer/IntakeExtractionV3BridgeTest.kt", "benchmarks/scoped-profiles/capture.py",
            "gradlew", "backend/build.gradle.kts", "build.gradle.kts"]
MODEL = {"candidate": "qwen3:8b", "provider": "ollama_mac_cpu", "modelVersionSha256": e.MODEL}
FAKE_MODEL = {"candidate": "synthetic_fake", "provider": "none", "modelVersionSha256": "0" * 64}
LIMITS = {"attemptTimeoutSeconds": 900, "caseTimeoutSeconds": 1800, "maxResponseBytes": 12288,
          "memoryLimitBytes": 8 * 1024**3, "maxCostUsd": 0}
RESOURCE_FIELDS = {"elapsedMs", "peakRssBytes", "inputTokens", "outputTokens", "costUsd"}


def need(condition):
    if not condition:
        raise ValueError("V3 private replay refused")


def settings(artifacts):
    return {"temperature": 0, "seed": 42, "num_thread": 3, "num_ctx": 4096, "num_predict": 1536,
            "think": False, "stream": False, "keep_alive": "0s", "formatSchemaSha256": e.digest(e.compact(artifacts["schema"]))}


def tooling():
    return {name: e.digest((e.ROOT / name).read_bytes()) for name in TOOLING}


def authorize(run, approval, artifacts, now):
    need(type(run) is dict and set(run) == {"version", "runId", "executionMode", "inputOrigin", "profile", "caseIds",
         "manifestSha256", "toolingSha256", "model", "settings", "resourceLimits"})
    need(run["version"] == "intake-extraction-private-run-v3" and str(uuid.UUID(run["runId"])) == run["runId"])
    need(run["executionMode"] == "offline_replay" and run["inputOrigin"] in ("synthetic_fake", "externally_recorded"))
    need(run["profile"] == e.PROFILE and run["caseIds"] == e.IDS)
    need(run["manifestSha256"] == e.digest((e.HERE / "manifest-v3.json").read_bytes()) and run["toolingSha256"] == tooling())
    need(e.compact(run["model"]) == e.compact(FAKE_MODEL if run["inputOrigin"] == "synthetic_fake" else MODEL))
    need(e.compact(run["settings"]) == e.compact(settings(artifacts)) and e.compact(run["resourceLimits"]) == e.compact(LIMITS))
    need(type(approval) is dict and set(approval) == {"version", "runSha256", "purpose", "recordedBy", "recordedAt", "expiresUtc"})
    need(approval["version"] == "intake-extraction-replay-approval-v3" and approval["purpose"] == "synthetic_offline_replay_only")
    need(private.text(approval["recordedBy"]) and approval["runSha256"] == e.digest(e.compact(run)))
    start, end = private.timestamp(approval["recordedAt"]), private.timestamp(approval["expiresUtc"])
    need(start <= now < end and dt.timedelta(0) < end - start <= dt.timedelta(hours=24))


def cases_from(source, run):
    need(type(source) is dict and set(source) == {"version", "cases"} and source["version"] == "intake-extraction-replay-v3")
    need(type(source["cases"]) is list and len(source["cases"]) <= 8)
    need(all(type(c) is dict and set(c) == {"caseId", "outcome", "raw", "resources"} for c in source["cases"]))
    need([c["caseId"] for c in source["cases"]] == e.IDS[:len(source["cases"])])
    stopped = False
    for case in source["cases"]:
        need(case["outcome"] in ("response", "empty", "timeout", "incomplete", "unrun"))
        need(not stopped or case["outcome"] == "unrun")
        raw = case["raw"]
        need(raw is None or type(raw) is str and len(raw.encode("utf-8")) <= LIMITS["maxResponseBytes"])
        need(case["outcome"] != "response" or type(raw) is str and bool(raw.strip()))
        need(case["outcome"] != "empty" or raw is None or not raw.strip())
        need(case["outcome"] != "unrun" or raw is None)
        resources = case["resources"]
        need(type(resources) is dict and set(resources) == RESOURCE_FIELDS)
        for key, value in resources.items():
            need(value is None or private.bounded_number(value, 10**15))
            if key in ("peakRssBytes", "inputTokens", "outputTokens"):
                need(value is None or type(value) is int)
        need(run["inputOrigin"] != "synthetic_fake" or all(v is None for v in resources.values()))
        need(case["outcome"] != "unrun" or all(v is None for v in resources.values()))
        stopped = stopped or case["outcome"] in ("timeout", "incomplete", "unrun")
    indexed = {c["caseId"]: c for c in source["cases"]}
    return [indexed.get(case_id, {"caseId": case_id, "outcome": "unrun", "raw": None,
                                 "resources": {k: None for k in sorted(RESOURCE_FIELDS)}}) for case_id in e.IDS]


def kotlin_validate(directory, requests, *, timeout_seconds=600):
    """Run the test-source bridge offline, keeping all reports private."""
    need(type(timeout_seconds) in (int, float) and 0 < timeout_seconds <= 600)
    bridge = directory / "validation"
    bridge.mkdir(mode=0o700)
    rows = []
    for request in requests:
        private.private_write(bridge / (request["caseId"] + ".raw"), request["raw"].encode())
        rows.append({"caseId": request["caseId"], "rawSha256": request["rawSha256"]})
    private.private_write(bridge / "request.json", e.compact({"version": "intake-extraction-stage-bridge-v3", "profile": e.PROFILE, "requests": rows}))
    environment = {k: v for k, v in os.environ.items() if not k.startswith("GTRAINER_")}
    environment["GTRAINER_INTAKE_V3_VALIDATION_DIR"] = str(bridge)
    init = directory / "private-reports.gradle"
    private.private_write(init, b'''allprojects { tasks.withType(Test).configureEach {
        reports.junitXml.outputLocation.set(file(System.getenv("GTRAINER_PRIVATE_REPORT_DIR") + "/xml"))
        reports.html.outputLocation.set(file(System.getenv("GTRAINER_PRIVATE_REPORT_DIR") + "/html"))
        binaryResultsDirectory.set(file(System.getenv("GTRAINER_PRIVATE_REPORT_DIR") + "/binary"))
    } }\n''')
    reports = directory / "test-reports"
    reports.mkdir(mode=0o700)
    environment["GTRAINER_PRIVATE_REPORT_DIR"] = str(reports)
    command = [str(e.ROOT / "gradlew"), "--offline", "--no-daemon", "--max-workers=2",
               "-Pkotlin.compiler.execution.strategy=in-process", "--init-script", str(init),
               ":backend:test", "--tests", "com.gtrainer.IntakeExtractionV3BridgeTest", "--rerun"]
    fd = os.open(directory / "validation.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as log:
        result = subprocess.run(command, cwd=e.ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT,
                                timeout=timeout_seconds, check=False, umask=0o077)
    need(result.returncode == 0)
    result = private.read_json(bridge / "result.json")
    need(set(result) == {"version", "results"} and result["version"] == "intake-extraction-stage-bridge-v3")
    return result["results"]


def check_results(results, requests):
    need(type(results) is list and len(results) == len(requests))
    for result, request in zip(results, requests):
        need(type(result) is dict and set(result) == {"caseId", "rawSha256", "stages"})
        need(result["caseId"] == request["caseId"] and result["rawSha256"] == request["rawSha256"])
        stages = result["stages"]
        need(type(stages) is dict and set(stages) == {"wire", "binding", "semantic", "reason", "assembledDraft"})
        need((stages["wire"], stages["binding"], stages["semantic"], stages["reason"]) in (
            ("accepted", "accepted", "accepted", None), ("rejected", "not_run", "not_run", "invalid_wire"),
            ("accepted", "rejected", "not_run", "binding_failed"), ("accepted", "rejected", "not_run", "assembled_too_large"),
            ("accepted", "accepted", "rejected", "semantic_rejected")))
        assembled = stages["assembledDraft"]
        need(type(assembled) is str and 0 < len(assembled.encode()) <= 12288 if stages["binding"] == "accepted" else assembled is None)


def replay(directory, now, validator=kotlin_validate):
    definition, artifacts = e.verify()
    directory = private.private_directory(directory)
    run_bytes, approval_bytes = private.private_read(directory / "run.json"), private.private_read(directory / "approval.json")
    run, approval = e.decode(run_bytes), e.decode(approval_bytes)
    authorize(run, approval, artifacts, now)
    for name in ("report.json", "human-review.json", "validation", "validation.log", "replay.lock", "private-reports.gradle", "test-reports"):
        need(not (directory / name).exists() and not (directory / name).is_symlink())
    private.private_write(directory / "replay.lock", e.compact({"runSha256": e.digest(e.compact(run))}))
    source_bytes = private.private_read(directory / "replay.json")
    cases = cases_from(e.decode(source_bytes), run)
    requests = [{"caseId": c["caseId"], "raw": c["raw"], "rawSha256": e.digest(c["raw"].encode())} for c in cases if c["outcome"] == "response"]
    results = validator(directory, requests) if requests else []
    check_results(results, requests)
    indexed = {r["caseId"]: r["stages"] for r in results}
    rows, human = [], []
    for case in cases:
        stages = indexed.get(case["caseId"], {"wire": "not_run", "binding": "not_run", "semantic": "not_run", "reason": None, "assembledDraft": None})
        outcome = ("accepted" if stages["semantic"] == "accepted" else "rejected") if case["outcome"] == "response" else case["outcome"]
        rows.append(dict(case, outcome=outcome, rawSha256=None if case["raw"] is None else e.digest(case["raw"].encode()), stages=stages,
                         resourcesProvenance="unmeasured_fake" if run["inputOrigin"] == "synthetic_fake" else "externally_recorded_unverified"))
        human.append({"caseId": case["caseId"], "outcome": outcome, "scores": {d["id"]: None for d in definition["dimensions"]},
                      "usefulFinal": None, "criticalFailures": None})
    report = {"version": "intake-extraction-capture-v3", "profile": e.PROFILE, "run": run,
              "runFileSha256": e.digest(run_bytes), "approvalSha256": e.digest(approval_bytes), "replaySha256": e.digest(source_bytes),
              "cases": rows, "usefulness": "incomplete", "experimentalSuccess": False, "productionAdmission": "closed",
              "assembledDraftProvenance": "internal v1-compatible validator adaptation, not an original v1/v2 provider response",
              "constraintOwnership": e.ownership(), "contextProofSha256": e.digest((e.HERE / "context-proof-v3.json").read_bytes()),
              "baselineDifferences": ["schema/prompt/bookkeeping bundle", "five declared provider-only constraint omissions with strict local rejection",
                                      "one scored attempt per case", "resized memory envelope"]}
    packet = {"version": "intake-extraction-human-review-v3", "profile": e.PROFILE, "reportSha256": e.digest(e.compact(report)),
              "inputOrigin": run["inputOrigin"], "independentHuman": None, "reviewedBy": None, "cases": human,
              "protocol": definition, "artifacts": artifacts, "productionAdmission": "closed"}
    private.private_write(directory / "report.json", e.compact(report))
    private.private_write(directory / "human-review.json", e.compact(packet))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    old = os.umask(0o077)
    try:
        replay(args.directory, dt.datetime.now(dt.timezone.utc))
    except Exception:
        print("V3 offline replay refused; inspect private evidence. No inference.")
        return 1
    finally:
        os.umask(old)
    print("Private v3 replay complete. No inference; human scores absent; admission closed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
