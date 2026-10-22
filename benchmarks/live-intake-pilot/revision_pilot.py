"""Exact pilot request and fresh token evidence; no live calls on import."""
import copy
import uuid
import pilot_profile as p
import revision_v4 as legacy
import revision_v5 as v5
import runtime_v4 as runtime

PROBE, TOKENIZER = legacy.PROBE, legacy.TOKENIZER


def tooling():
    result = runtime.tooling()
    paths = [*p.HERE.glob("*.py"), *p.HERE.glob("*.json"), *p.HERE.glob("*.md")]
    paths += list((p.ROOT / "backend/src/test/kotlin/com/gtrainer").glob("LiveIntake*.kt"))
    paths += list((p.ROOT / "frontend/src").glob("*ive*ntake*"))
    paths += [p.ROOT / "backend/build.gradle.kts", p.ROOT / "frontend/package.json",
              p.ROOT / "frontend/vite.live-intake-pilot.config.ts",
              p.ROOT / "backend/src/test/kotlin/com/gtrainer/IntakeExtractionV2.kt",
              p.ROOT / "backend/src/main/kotlin/com/gtrainer/ContextIntakeSuggestionContractV1.kt"]
    paths += [path for path in (p.ROOT / "backend/src/main").rglob("*") if path.is_file()]
    paths += [path for path in (p.ROOT / "frontend/src").rglob("*") if path.is_file()]
    paths += [p.ROOT / "backend/src/test/kotlin/com/gtrainer/SyntheticIntakePilot.kt",
              p.ROOT / "frontend/live-intake-pilot/index.html", p.ROOT / "frontend/tsconfig.json",
              p.ROOT / "settings.gradle.kts", p.ROOT / "gradle.properties", p.ROOT / "frontend/package-lock.json"]
    paths += [path for path in (p.ROOT / "gradle").rglob("*") if path.is_file()]
    result.update({str(path.relative_to(p.ROOT)): p.e.digest(path.read_bytes()) for path in paths if path.is_file()})
    return result


def prompt_cases(instructions):
    p.need(p.e.digest(instructions.encode()) == p.parent.PROMPT_SHA256)
    row = p.verify()[1]["cases"][0]
    return [{"caseId": "u02", "exactPrompt": {"systemInstructions": instructions, "userPacketJson": row["userPacketJson"]}}]


def counter_input(instructions):
    return {"version": "intake-conformance-token-input-pilot",
            "cases": prompt_cases(instructions) + [{"caseId": "probe", "exactPrompt": copy.deepcopy(PROBE)}]}


def expected_requests(instructions):
    return {"u02": legacy.payload(prompt_cases(instructions)[0]["exactPrompt"], p.verify()[1]["schema"]),
            "probe": legacy.payload(PROBE, "json")}


def check_counts(instructions, evidence):
    p.need(type(evidence) is dict and set(evidence) == {"version", "tokenizer", "inputSha256", "tensorWeightsLoaded",
        "inferenceContextCreated", "generationCalled", "cases", "evidenceMode"})
    p.need(evidence["evidenceMode"] in ("live_vocab_only", "synthetic_fake"))
    p.need(evidence["version"] == "intake-conformance-token-evidence-pilot" and evidence["tokenizer"] == TOKENIZER)
    source = counter_input(instructions)
    p.need(evidence["inputSha256"] == p.e.digest(p.e.compact(source)))
    p.need(all(evidence[key] is False for key in ("tensorWeightsLoaded", "inferenceContextCreated", "generationCalled")))
    p.need(type(evidence["cases"]) is list and len(evidence["cases"]) == 2)
    for row, case in zip(evidence["cases"], source["cases"]):
        rendered = p.e.v3.render(case["exactPrompt"])
        p.need(set(row) == {"caseId", "tokens", "tokensSha256", "renderedSha256", "renderedUtf8Bytes"})
        p.need(row["caseId"] == case["caseId"] and row["renderedSha256"] == p.e.digest(rendered))
        p.need(type(row["tokens"]) is int and 0 < row["tokens"] <= min(2304, len(rendered)))
        p.need(row["tokens"] + 1536 + 256 <= 4096 and row["renderedUtf8Bytes"] == len(rendered))
        p.need(runtime.replay.private.sha(row["tokensSha256"]))


def definition(revision_id, instructions, evidence, previous_sha256=None):
    check_counts(instructions, evidence)
    p.need(str(uuid.UUID(revision_id)) == revision_id and previous_sha256 is None)
    requests = expected_requests(instructions)
    return {"version": "intake-conformance-revision-pilot", "profile": p.PROFILE, "revisionId": revision_id,
        "previousRevisionSha256": None, "systemInstructions": instructions,
        "manifestSha256": p.e.digest((p.HERE / "manifest-pilot.json").read_bytes()),
        "oracleSha256": p.e.digest((p.HERE / "oracle-pilot.json").read_bytes()),
        "inputsSha256": p.e.digest((p.HERE / "inputs-pilot.json").read_bytes()),
        "schemaSha256": p.e.digest(p.e.compact(requests["u02"]["format"])), "model": runtime.replay.MODEL,
        "image": p.e.v3.IMAGE, "runtime": runtime.mac.IDENTITY, "toolingSha256": tooling(),
        "counterToolSha256": p.e.digest((p.e.HERE / "count_v4.py").read_bytes()), "tokenizer": TOKENIZER,
        "contextEvidenceSha256": p.e.digest(p.e.compact(evidence)), "contextEvidenceMode": evidence["evidenceMode"],
        "requestSha256": {name: p.e.digest(p.e.compact(value)) for name, value in requests.items()},
        "packetSha256": {"u02": p.e.digest(prompt_cases(instructions)[0]["exactPrompt"]["userPacketJson"].encode())},
        "renderedSha256": {row["caseId"]: row["renderedSha256"] for row in evidence["cases"]},
        "contextTokens": 4096, "outputReserveTokens": 1536, "marginTokens": 256,
        "maximumOfflineInputTokens": max(row["tokens"] for row in evidence["cases"]),
        "providerMeasuredInputTokens": None, "productionAdmission": "closed"}


def check_revision(revision, evidence):
    p.need(revision == definition(revision.get("revisionId"), revision.get("systemInstructions"), evidence, revision.get("previousRevisionSha256")))
    return expected_requests(revision["systemInstructions"])
