"""Exact new-suite requests for the unchanged candidate; no runtime operations."""
import copy
import uuid

import suite_v5 as suite
import revision_v4 as legacy
import runtime_v4 as runtime

e = suite.e
TOKENIZER = legacy.TOKENIZER
PROBE = legacy.PROBE


def need(value):
    if not value:
        raise ValueError("V5 exact revision refused")


def tooling():
    # Include every local implementation/test plus shared dependencies. New files
    # change this closure and invalidate an already prepared revision, never refresh it.
    files = [*suite.HERE.glob("*.py"), *suite.HERE.glob("*.json"),
             e.ROOT / "backend/src/test/kotlin/com/gtrainer/IntakeConformanceV5BridgeTest.kt"]
    return dict(runtime.tooling(), **{str(path.relative_to(e.ROOT)): e.digest(path.read_bytes()) for path in files})


def prompt_cases(instructions):
    need(type(instructions) is str and e.digest(instructions.encode()) == suite.PROMPT_SHA256)
    inputs = suite.verify()[1]
    return [{"caseId": row["caseId"], "exactPrompt": {
        "systemInstructions": instructions, "userPacketJson": row["userPacketJson"]}}
        for row in inputs["cases"]]


def counter_input(instructions):
    return {"version": "intake-conformance-token-input-v5",
            "cases": prompt_cases(instructions) + [{"caseId": "probe", "exactPrompt": copy.deepcopy(PROBE)}]}


def expected_requests(instructions):
    schema = suite.verify()[1]["schema"]
    return {row["caseId"]: legacy.payload(row["exactPrompt"], schema) for row in prompt_cases(instructions)} | {
        "probe": legacy.payload(PROBE, "json")}


def check_counts(instructions, evidence):
    need(type(evidence) is dict and set(evidence) == {"version", "tokenizer", "inputSha256",
         "tensorWeightsLoaded", "inferenceContextCreated", "generationCalled", "cases"})
    need(evidence["version"] == "intake-conformance-token-evidence-v5" and evidence["tokenizer"] == TOKENIZER)
    expected = counter_input(instructions)
    need(evidence["inputSha256"] == e.digest(e.compact(expected)))
    need(all(evidence[key] is False for key in ("tensorWeightsLoaded", "inferenceContextCreated", "generationCalled")))
    need(type(evidence["cases"]) is list and len(evidence["cases"]) == 9)
    for row, case in zip(evidence["cases"], expected["cases"]):
        rendered = e.v3.render(case["exactPrompt"])
        need(type(row) is dict and set(row) == {"caseId", "tokens", "tokensSha256", "renderedSha256", "renderedUtf8Bytes"})
        need(row["caseId"] == case["caseId"] and row["renderedSha256"] == e.digest(rendered))
        need(type(row["renderedUtf8Bytes"]) is int and row["renderedUtf8Bytes"] == len(rendered))
        need(type(row["tokens"]) is int and 0 < row["tokens"] <= min(2304, len(rendered)))
        need(legacy.replay_v4.private.sha(row["tokensSha256"]))


def definition(revision_id, instructions, evidence, previous_sha256=None):
    need(type(revision_id) is str and str(uuid.UUID(revision_id)) == revision_id)
    need(previous_sha256 is None)
    check_counts(instructions, evidence)
    requests = expected_requests(instructions)
    return {"version": "intake-conformance-revision-v5", "profile": suite.PROFILE,
        "revisionId": revision_id, "previousRevisionSha256": None, "systemInstructions": instructions,
        "manifestSha256": e.digest((suite.HERE / "manifest-v5.json").read_bytes()),
        "oracleSha256": e.digest((suite.HERE / "oracle-v5.json").read_bytes()),
        "inputsSha256": e.digest((suite.HERE / "inputs-v5.json").read_bytes()),
        "schemaSha256": e.digest(e.compact(requests["u01"]["format"])),
        "model": legacy.replay_v4.MODEL, "image": e.v3.IMAGE, "runtime": runtime.mac.IDENTITY,
        "toolingSha256": tooling(), "counterToolSha256": e.digest((e.HERE / "count_v4.py").read_bytes()),
        "tokenizer": TOKENIZER, "contextEvidenceSha256": e.digest(e.compact(evidence)),
        "requestSha256": {key: e.digest(e.compact(value)) for key, value in requests.items()},
        "packetSha256": {row["caseId"]: e.digest(row["exactPrompt"]["userPacketJson"].encode())
                         for row in prompt_cases(instructions)},
        "renderedSha256": {row["caseId"]: row["renderedSha256"] for row in evidence["cases"]},
        "contextTokens": 4096, "outputReserveTokens": 1536, "marginTokens": 256,
        "maximumOfflineInputTokens": max(row["tokens"] for row in evidence["cases"]),
        "providerMeasuredInputTokens": None, "productionAdmission": "closed"}


def check_revision(revision, evidence):
    need(type(revision) is dict)
    need(legacy.oracle_v4.same(revision, definition(revision.get("revisionId"),
        revision.get("systemInstructions"), evidence, revision.get("previousRevisionSha256"))))
    return expected_requests(revision["systemInstructions"])


def freeze(directory, revision_id, instructions, evidence, previous_sha256=None):
    private = legacy.replay_v4.private
    directory = private.private_directory(directory)
    prepared = definition(revision_id, instructions, evidence, previous_sha256)
    documents = {"revision.json": prepared, "context-evidence.json": evidence,
                 "requests.json": check_revision(prepared, evidence), "token-input.json": counter_input(instructions)}
    for name, value in documents.items():
        private.private_write(directory / name, e.compact(value))
    return prepared
