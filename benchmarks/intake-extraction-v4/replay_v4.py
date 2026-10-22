"""Private offline v4 local validation and conformance; no provider/runtime access."""
import argparse
import copy
import datetime as dt
import os
from pathlib import Path
import selectors
import signal
import subprocess
import time
import uuid

import experiment_v4 as e
import oracle_v4
import capture as private

TOOLING = [e.PREFIX + n for n in ("replay_v4.py", "oracle_v4.py", "experiment_v4.py", "test_replay_v4.py")]
TOOLING += ["backend/src/test/kotlin/com/gtrainer/IntakeConformanceV4BridgeTest.kt", "benchmarks/scoped-profiles/capture.py",
            "benchmarks/scoped-profiles/protocol.py", "gradlew", "build.gradle.kts", "backend/build.gradle.kts"]
MODEL = {"candidate": "qwen3:8b", "provider": "ollama_mac_cpu", "modelVersionSha256": e.v3.MODEL}
FAKE_MODEL = {"candidate": "synthetic_fake", "provider": "none", "modelVersionSha256": "0" * 64}
SETTINGS = {"temperature": 0, "seed": 42, "num_thread": 3, "num_ctx": 4096, "num_predict": 1536,
            "think": False, "stream": False, "keep_alive": "0s", "use_mmap": True}
LIMITS = {"maxResponseBytes": 12288, "maxFileBytes": 2097152, "validationTimeoutSeconds": 600}
RESOURCES = {"elapsedMs", "peakRssBytes", "inputTokens", "outputTokens", "costUsd"}


def need(value):
    if not value: raise ValueError("V4 private validation refused")


def tooling():
    return {name: e.digest((e.ROOT / name).read_bytes()) for name in TOOLING}


def make_run(run_id, origin):
    _, inputs, _, _ = e.verify()
    need(origin in ("synthetic_fake", "externally_recorded"))
    return copy.deepcopy({"version": "intake-conformance-private-run-v4", "runId": run_id, "profile": e.PROFILE,
            "executionMode": "offline_validation", "inputOrigin": origin, "caseIds": e.IDS,
            "manifestSha256": e.digest((e.HERE / "manifest-v4.json").read_bytes()), "toolingSha256": tooling(),
            "model": FAKE_MODEL if origin == "synthetic_fake" else MODEL,
            "settings": dict(SETTINGS, formatSchemaSha256=e.digest(e.compact(inputs["schema"]))), "resourceLimits": LIMITS})


def authorize(run, approval, now):
    need(type(run) is dict and type(run.get("runId")) is str and str(uuid.UUID(run["runId"])) == run["runId"])
    need(oracle_v4.same(run, make_run(run["runId"], run.get("inputOrigin"))))
    need(type(approval) is dict and set(approval) == {"version", "runSha256", "purpose", "recordedBy", "recordedAt", "expiresUtc"})
    need(approval["version"] == "intake-conformance-validation-approval-v4" and approval["purpose"] == "synthetic_offline_validation_only")
    need(approval["runSha256"] == e.digest(e.compact(run)) and private.text(approval["recordedBy"]))
    start, end = private.timestamp(approval["recordedAt"]), private.timestamp(approval["expiresUtc"])
    need(start <= now < end and dt.timedelta(0) < end - start <= dt.timedelta(hours=24))


def cases_from(source, run):
    need(type(source) is dict and set(source) == {"version", "cases"} and source["version"] == "intake-conformance-responses-v4")
    need(type(source["cases"]) is list and len(source["cases"]) <= 8)
    need(all(type(c) is dict and set(c) == {"caseId", "outcome", "raw", "resources"} for c in source["cases"]))
    need([c["caseId"] for c in source["cases"]] == e.IDS[:len(source["cases"])])
    stopped = False
    for c in source["cases"]:
        need(c["outcome"] in ("response", "empty", "timeout", "incomplete", "unrun"))
        need(not stopped or c["outcome"] == "unrun")
        raw = c["raw"]
        need(raw is None or type(raw) is str and len(raw.encode()) <= LIMITS["maxResponseBytes"])
        need(c["outcome"] != "response" or type(raw) is str and bool(raw.strip()))
        need(c["outcome"] != "empty" or raw is None or not raw.strip())
        need(c["outcome"] != "unrun" or raw is None)
        need(type(c["resources"]) is dict and set(c["resources"]) == RESOURCES)
        for key, value in c["resources"].items():
            need(value is None or private.bounded_number(value, 10**15))
            if key in ("peakRssBytes", "inputTokens", "outputTokens"): need(value is None or type(value) is int)
        if run["inputOrigin"] == "synthetic_fake" or c["outcome"] == "unrun": need(all(v is None for v in c["resources"].values()))
        stopped = stopped or c["outcome"] in ("timeout", "incomplete", "unrun")
    indexed = {c["caseId"]: c for c in source["cases"]}
    return [indexed.get(case_id, {"caseId": case_id, "outcome": "unrun", "raw": None,
                                 "resources": {k: None for k in sorted(RESOURCES)}}) for case_id in e.IDS]


def bounded_validation(command, environment, log, timeout_seconds):
    """Bound offline compiler output and lifetime; never emit response text."""
    deadline = time.monotonic() + timeout_seconds
    import owned_processes_v4 as owned
    directory = owned.owner()
    request = None
    if directory is not None:
        process, request = owned.launch(command, environment, directory)
    else:
        process = subprocess.Popen(command, cwd=e.ROOT, env=environment, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                   start_new_session=True, umask=0o077)
    size = 0
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                need(remaining > 0)
                for key, _ in selector.select(min(remaining, 0.1)):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    size += len(chunk)
                    need(size <= LIMITS["maxFileBytes"])
                    log.write(chunk)
            if request is not None:
                need(private.read_json(owned.sibling(request, ".result.json"))["exitCode"] == 0)
            else: need(process.wait(timeout=max(0.001, deadline - time.monotonic())) == 0)
    finally:
        # The private Gradle process group is owned even if its leader has exited.
        if request is not None: need(owned.finish(process, request, time.monotonic() + 5))
        else:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            process.wait(timeout=5)
        process.stdout.close()


def kotlin_validate(directory, requests, *, timeout_seconds=600):
    need(type(timeout_seconds) in (int, float) and 0 < timeout_seconds <= 600)
    bridge = directory / "validation"; bridge.mkdir(mode=0o700)
    rows = []
    for request in requests:
        private.private_write(bridge / (request["caseId"] + ".raw"), request["raw"].encode())
        rows.append({"caseId": request["caseId"], "rawSha256": request["rawSha256"]})
    private.private_write(bridge / "request.json", e.compact({"version": "intake-conformance-stage-bridge-v4", "profile": e.PROFILE, "requests": rows}))
    environment = {k: v for k, v in os.environ.items() if not k.startswith("GTRAINER_")}
    environment["GTRAINER_INTAKE_V4_VALIDATION_DIR"] = str(bridge)
    init = directory / "private-reports.gradle"
    private.private_write(init, b'''allprojects { tasks.withType(Test).configureEach {
        reports.junitXml.outputLocation.set(file(System.getenv("GTRAINER_PRIVATE_REPORT_DIR") + "/xml"))
        reports.html.outputLocation.set(file(System.getenv("GTRAINER_PRIVATE_REPORT_DIR") + "/html"))
        binaryResultsDirectory.set(file(System.getenv("GTRAINER_PRIVATE_REPORT_DIR") + "/binary"))
    } }\n''')
    reports = directory / "test-reports"; reports.mkdir(mode=0o700)
    environment["GTRAINER_PRIVATE_REPORT_DIR"] = str(reports)
    command = [str(e.ROOT / "gradlew"), "--offline", "--no-daemon", "--max-workers=2",
               "-Pkotlin.compiler.execution.strategy=in-process", "--init-script", str(init),
               ":backend:test", "--tests", "com.gtrainer.IntakeConformanceV4BridgeTest", "--rerun"]
    fd = os.open(directory / "validation.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as log:
        bounded_validation(command, environment, log, timeout_seconds)
    result = private.read_json(bridge / "result.json")
    need(set(result) == {"version", "results"} and result["version"] == "intake-conformance-stage-bridge-v4")
    return result["results"]


def check_results(results, requests):
    need(type(results) is list and len(results) == len(requests))
    for result, request in zip(results, requests):
        need(type(result) is dict and set(result) == {"caseId", "rawSha256", "stages"})
        need(result["caseId"] == request["caseId"] and result["rawSha256"] == request["rawSha256"])
        s = result["stages"]
        need(type(s) is dict and set(s) == {"wire", "binding", "semantic", "reason", "assembledDraft"})
        need((s["wire"], s["binding"], s["semantic"], s["reason"]) in (
            ("accepted", "accepted", "accepted", None), ("rejected", "not_run", "not_run", "invalid_wire"),
            ("accepted", "rejected", "not_run", "binding_failed"), ("accepted", "rejected", "not_run", "assembled_too_large"),
            ("accepted", "accepted", "rejected", "semantic_rejected")))
        need(type(s["assembledDraft"]) is str and 0 < len(s["assembledDraft"].encode()) <= 12288 if s["binding"] == "accepted" else s["assembledDraft"] is None)


def replay(directory, now, validator=kotlin_validate):
    _, _, contract, _ = e.verify()
    directory = private.private_directory(directory)
    run_raw, approval_raw = private.private_read(directory / "run.json"), private.private_read(directory / "approval.json")
    run, approval = e.decode(run_raw), e.decode(approval_raw)
    authorize(run, approval, now)
    for name in ("report.json", "validation", "validation.log", "validation.lock", "private-reports.gradle", "test-reports"):
        need(not (directory / name).exists() and not (directory / name).is_symlink())
    private.private_write(directory / "validation.lock", e.compact({"runSha256": e.digest(e.compact(run))}))
    source_raw = private.private_read(directory / "responses.json")
    cases = cases_from(e.decode(source_raw), run)
    requests = [{"caseId": c["caseId"], "raw": c["raw"], "rawSha256": e.digest(c["raw"].encode())} for c in cases if c["outcome"] == "response"]
    results = validator(directory, requests) if requests else []
    check_results(results, requests)
    # A callback cannot silently replace bound inputs, validation code or approval.
    e.verify()
    authorize(run, approval, now)
    for name, original in (("run.json", run_raw), ("approval.json", approval_raw), ("responses.json", source_raw)):
        need(private.private_read(directory / name) == original)
    indexed = {row["caseId"]: row["stages"] for row in results}
    rows = []
    for c in cases:
        stages = indexed.get(c["caseId"], {"wire": "not_run", "binding": "not_run", "semantic": "not_run", "reason": None, "assembledDraft": None})
        verdict = oracle_v4.evaluate(c["caseId"], stages, contract)
        predicates = next(case for case in contract["cases"] if case["caseId"] == c["caseId"])
        rows.append(dict(c, rawSha256=None if c["raw"] is None else e.digest(c["raw"].encode()), stages=stages,
                         stagesSha256=e.digest(e.compact(stages)), predicatesSha256=e.digest(e.compact(predicates)),
                         oracle=verdict, oracleResultSha256=e.digest(e.compact(verdict)),
                         outcome=("accepted" if stages["semantic"] == "accepted" else "rejected") if c["outcome"] == "response" else c["outcome"],
                         resourcesProvenance="unmeasured_fixture" if run["inputOrigin"] == "synthetic_fake" else "imported_unverified"))
    complete = all(c["outcome"] not in ("timeout", "incomplete", "unrun") for c in cases)
    report = {"version": "intake-conformance-report-v4", "profile": e.PROFILE, "run": run,
              "runFileSha256": e.digest(run_raw), "approvalSha256": e.digest(approval_raw), "responsesSha256": e.digest(source_raw),
              "oracleSha256": e.digest((e.HERE / "oracle-v4.json").read_bytes()), "cases": rows,
              "suiteConformance": ("passed" if all(c["oracle"]["status"] == "passed" for c in rows) else "failed") if complete else "incomplete",
              "evaluationMethod": "deterministic_extractive", "independentHuman": False, "humanAssessment": None,
              "assembledDraftProvenance": "internal validator adaptation, not provider output",
              "developmentSetTuning": True, "unseenInputReliabilityProven": False, "productionAdmission": "closed"}
    private.private_write(directory / "report.json", e.compact(report))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    old = os.umask(0o077)
    try: replay(args.directory, dt.datetime.now(dt.timezone.utc))
    except Exception:
        print("V4 private validation refused; inspect private evidence. No inference.")
        return 1
    finally: os.umask(old)
    print("V4 private deterministic validation complete. No inference or human scores; production closed.")
    return 0


if __name__ == "__main__": raise SystemExit(main())
