"""Private offline v5 Kotlin validation and deterministic oracle replay; no inference."""
import os
from pathlib import Path

import suite_v5 as suite
from suite_v5 import e
import capture as private
import replay_v4

VERSION = "intake-conformance-stage-bridge-v5"
LIMIT = 12288
RESOURCES = replay_v4.RESOURCES
check_results = replay_v4.check_results


def need(value):
    if not value:
        raise ValueError("V5 private validation refused")


def packets():
    _, inputs, _, _ = suite.verify()
    return {row["caseId"]: row["userPacketJson"] for row in inputs["cases"]}


def cases_from(source, run):
    """The shared response bounds, applied directly to distinct v5 identities."""
    need(type(source) is dict and set(source) == {"version", "cases"})
    need(source["version"] == "intake-conformance-responses-v5")
    rows = source["cases"]
    need(type(rows) is list and len(rows) <= 8)
    need(all(type(row) is dict and set(row) == {"caseId", "outcome", "raw", "resources"} for row in rows))
    need([row["caseId"] for row in rows] == suite.IDS[:len(rows)])
    stopped = False
    for row in rows:
        need(row["outcome"] in ("response", "empty", "timeout", "incomplete", "unrun"))
        need(not stopped or row["outcome"] == "unrun")
        raw = row["raw"]
        need(raw is None or type(raw) is str and len(raw.encode()) <= LIMIT)
        need(row["outcome"] != "response" or type(raw) is str and bool(raw.strip()))
        need(row["outcome"] != "empty" or raw is None or not raw.strip())
        need(row["outcome"] != "unrun" or raw is None)
        need(type(row["resources"]) is dict and set(row["resources"]) == RESOURCES)
        for key, value in row["resources"].items():
            need(value is None or private.bounded_number(value, 10**15))
            if key in ("peakRssBytes", "inputTokens", "outputTokens"):
                need(value is None or type(value) is int)
        if run["inputOrigin"] == "synthetic_fake" or row["outcome"] == "unrun":
            need(all(value is None for value in row["resources"].values()))
        stopped = stopped or row["outcome"] in ("timeout", "incomplete", "unrun")
    indexed = {row["caseId"]: row for row in rows}
    return [indexed.get(case_id, {"caseId": case_id, "outcome": "unrun", "raw": None,
            "resources": {key: None for key in RESOURCES}}) for case_id in suite.IDS]


def kotlin_results(directory, requests, *, timeout_seconds=600):
    """Explicit adapter from the packet-aware bridge to the existing ledger API."""
    checked = kotlin_validate(directory, requests, timeout_seconds=timeout_seconds)
    results = [{"caseId": row["caseId"], "rawSha256": row["rawSha256"], "stages": checked[row["caseId"]]}
               for row in requests]
    check_results(results, requests)
    return results


def kotlin_validate(directory, requests, *, timeout_seconds=600):
    need(type(timeout_seconds) in (int, float) and 0 < timeout_seconds <= 600)
    _, inputs, _, _ = suite.verify()
    directory = private.private_directory(directory)
    need(not directory.is_relative_to(suite.ROOT))
    need(not any(directory.iterdir()))
    manifest_raw = (suite.HERE / "manifest-v5.json").read_bytes()
    manifest_sha = e.digest(manifest_raw)
    input_by_id = {row["caseId"]: row["userPacketJson"] for row in inputs["cases"]}
    need(type(requests) is list and 1 <= len(requests) <= 8)
    need(all(type(request) is dict and set(request) == {"caseId", "raw", "rawSha256"} for request in requests))
    ids = [r.get("caseId") for r in requests]
    need(len(set(ids)) == len(ids) and all(case_id in suite.IDS for case_id in ids))
    rows = []
    for request in requests:
        need(type(request) is dict and set(request) == {"caseId", "raw", "rawSha256"})
        case_id, raw = request["caseId"], request["raw"]
        need(type(raw) is str and raw.strip())
        raw_bytes = raw.encode("utf-8")
        need(len(raw_bytes) <= LIMIT and request["rawSha256"] == e.digest(raw_bytes))
        packet = input_by_id[case_id]
        packet_sha = e.digest(packet.encode("utf-8"))
        rows.append({"caseId": case_id, "packetJson": packet, "packetSha256": packet_sha,
                      "rawSha256": request["rawSha256"]})
    bridge = directory / "validation"
    bridge.mkdir(mode=0o700)
    for request in requests:
        private.private_write(bridge / (request["caseId"] + ".raw"), request["raw"].encode())
    private.private_write(bridge / "request.json", e.compact({"version": VERSION, "profile": suite.PROFILE,
        "suiteManifestSha256": manifest_sha, "requests": rows}))
    environment = {k: v for k, v in os.environ.items() if not k.startswith("GTRAINER_")}
    environment["GTRAINER_INTAKE_V5_VALIDATION_DIR"] = str(bridge)
    init = directory / "private-reports.gradle"
    private.private_write(init, b'''allprojects { tasks.withType(Test).configureEach {
        reports.junitXml.outputLocation.set(file(System.getenv("GTRAINER_PRIVATE_REPORT_DIR") + "/xml"))
        reports.html.outputLocation.set(file(System.getenv("GTRAINER_PRIVATE_REPORT_DIR") + "/html"))
        binaryResultsDirectory.set(file(System.getenv("GTRAINER_PRIVATE_REPORT_DIR") + "/binary"))
    } }
''')
    reports = directory / "test-reports"
    reports.mkdir(mode=0o700)
    environment["GTRAINER_PRIVATE_REPORT_DIR"] = str(reports)
    command = [str(e.ROOT / "gradlew"), "--offline", "--no-daemon", "--max-workers=2",
        "-Pkotlin.compiler.execution.strategy=in-process", "--init-script", str(init), ":backend:test",
        "--tests", "com.gtrainer.IntakeConformanceV5BridgeTest", "--rerun"]
    fd = os.open(directory / "validation.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as log:
        replay_v4.bounded_validation(command, environment, log, timeout_seconds)
    result = private.read_json(bridge / "result.json")
    need(type(result) is dict and set(result) == {"version", "profile", "suiteManifestSha256", "results"})
    need(result["version"] == VERSION and result["profile"] == suite.PROFILE and result["suiteManifestSha256"] == manifest_sha)
    rows = result["results"]
    need(type(rows) is list and len(rows) == len(requests))
    checked = {}
    for row, request in zip(rows, requests):
        need(type(row) is dict and set(row) == {"caseId", "packetSha256", "rawSha256", "stages"})
        expected_packet = e.digest(input_by_id[request["caseId"]].encode("utf-8"))
        need(row["caseId"] == request["caseId"] and row["caseId"] not in checked)
        need(row["packetSha256"] == expected_packet and row["rawSha256"] == request["rawSha256"])
        stages = row["stages"]
        need(type(stages) is dict and set(stages) == {"wire", "binding", "semantic", "reason", "assembledDraft"})
        need((stages["wire"], stages["binding"], stages["semantic"], stages["reason"]) in (
            ("accepted", "accepted", "accepted", None), ("rejected", "not_run", "not_run", "invalid_wire"),
            ("accepted", "rejected", "not_run", "binding_failed"), ("accepted", "rejected", "not_run", "assembled_too_large"),
            ("accepted", "accepted", "rejected", "semantic_rejected")))
        need(type(stages["assembledDraft"]) is str and 0 < len(stages["assembledDraft"].encode("utf-8")) <= LIMIT
             if stages["binding"] == "accepted" else stages["assembledDraft"] is None)
        checked[row["caseId"]] = stages
    need(set(checked) == set(ids))
    suite.verify()
    need((suite.HERE / "manifest-v5.json").read_bytes() == manifest_raw)
    return checked


def replay_witnesses(directory, *, validator=kotlin_validate):
    """Run frozen synthetic witnesses through Kotlin stages then frozen oracle."""
    protocol, inputs, contract, fixtures = suite.verify()
    requests = []
    for case_id in suite.IDS:
        raw = e.compact(fixtures[case_id]).decode("utf-8")
        requests.append({"caseId": case_id, "raw": raw, "rawSha256": e.digest(raw.encode("utf-8"))})
    stages = validator(directory, requests)
    rows = []
    for request in requests:
        case_id = request["caseId"]
        verdict = suite.evaluate(case_id, stages[case_id], contract)
        rows.append({"caseId": case_id, "packetSha256": e.digest(next(r["userPacketJson"].encode() for r in inputs["cases"] if r["caseId"] == case_id)),
                     "rawSha256": request["rawSha256"], "stages": stages[case_id], "oracle": verdict})
    return {"version": "intake-conformance-offline-replay-v5", "profile": suite.PROFILE,
        "suiteManifestSha256": e.digest((suite.HERE / "manifest-v5.json").read_bytes()), "cases": rows,
        "suiteConformance": "passed" if all(r["oracle"]["status"] == "passed" for r in rows) else "failed",
        "syntheticWitnesses": True, "modelCalls": 0, "independentHuman": False,
        "independentBlindHoldout": protocol["independentBlindHoldout"], "productionAdmission": "closed"}
