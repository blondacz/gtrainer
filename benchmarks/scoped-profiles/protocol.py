"""Offline frozen-artifact integrity and rubric arithmetic. No provider or runtime access."""
import argparse
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PROFILES = {"scoped-descriptive-v1": ("d", 12), "context-intake-suggestion-v1": ("i", 8),
            "plan-amendment-selection-v1": ("a", 8)}
GATES = {"completeUsefulFinalForEveryCase": True, "minimumEveryCaseDimension": 2,
         "weightedMeanPercent": 80, "maximumCriticalFailures": 0,
         "independentHumanScoringRequired": True, "noDroppedCasesOrDimensions": True,
         "schemaValidityIsNotUsefulness": True}
SOURCES = ["ScopedEvaluationFixtures.kt", "ScopedEvaluationArtifactsTest.kt"]
PRODUCTION = ["ScopedDescriptiveContractV1.kt", "ContextIntakeSuggestionContractV1.kt",
              "AmendmentSelectionContractV1.kt", "ConnectedReviewInvocation.kt",
              "ScopedEvidencePreparation.kt", "ScopedEvidenceBudget.kt", "ScopedReviewScopes.kt",
              "ScopedTrends.kt", "ScopedReviewEvidence.kt", "ScopedLatestWellness.kt",
              "ScopedWellnessEvidence.kt", "ScopedContextRetrieval.kt", "PlanAmendmentPolicy.kt",
              "CommitmentProjection.kt", "HealthRecords.kt"]
PINNED = (["benchmarks/scoped-profiles/" + name for name in
           ("protocols-v1.json", "artifacts-v1.json", "protocol.py", "test_protocol.py", "README.md")]
          + ["backend/src/test/kotlin/com/gtrainer/" + name for name in SOURCES]
          + ["backend/src/main/kotlin/com/gtrainer/" + name for name in PRODUCTION])


def require(condition, message):
    if not condition:
        raise ValueError(message)


def _object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def decode(data):
    require(len(data) <= 4 * 1024 * 1024, "Artifact exceeds offline file bound")
    return json.loads(data.decode("utf-8"), object_pairs_hook=_object,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def validate_documents(protocol, artifacts):
    require(protocol["version"] == "scoped-longitudinal-protocols-v1", "Unknown protocol version")
    require(protocol["syntheticOnly"] is True and protocol["modelCallsPermitted"] is False
            and protocol["productionAdmission"] == "closed", "Admission must remain closed")
    require(protocol["gates"] == GATES, "Frozen qualification thresholds differ")
    require(protocol["scoreScale"]["minimum"] == 0 and protocol["scoreScale"]["maximum"] == 4
            and set(protocol["scoreScale"]["anchors"]) == set("01234"), "Invalid score scale")
    require(len(protocol["criticalFailures"]) >= 8 and len(set(protocol["criticalFailures"])) == len(protocol["criticalFailures"]),
            "Critical failure categories missing or duplicated")
    require(protocol["outcomes"] == ["accepted", "rejected", "timeout", "empty", "incomplete", "unrun"], "Outcome accounting differs")
    require(protocol["generationPolicy"]["maximumAttemptsPerCase"] == 2
            and protocol["generationPolicy"]["correctionRequiresSeparatePermission"] is True
            and protocol["generationPolicy"]["maximumExactPromptUtf8Bytes"] == 32768
            and protocol["generationPolicy"]["maximumDecodedDraftUtf8Bytes"] == 12288, "Generation boundaries differ")
    require(len(protocol["profiles"]) == 3 and {p["profile"] for p in protocol["profiles"]} == set(PROFILES), "Profile membership differs")
    expected = {}
    for profile in protocol["profiles"]:
        prefix, count = PROFILES[profile["profile"]]
        require(profile["caseCount"] == count and len(profile["cases"]) == count, "Case denominator differs")
        require([c["id"] for c in profile["cases"]] == [f"{prefix}{n:02d}" for n in range(1, count + 1)], "Case membership differs")
        dimensions = profile["dimensions"]
        require(len(dimensions) == 6 and len({d["id"] for d in dimensions}) == 6, "Dimension membership differs")
        require(all(type(d["weight"]) is int and d["weight"] > 0 and d["criteria"] for d in dimensions)
                and sum(d["weight"] for d in dimensions) == 100, "Dimension weights differ")
        for case in profile["cases"]:
            require(case["expect"] and case["tags"], "Case expectations missing")
            expected[case["id"]] = profile["profile"]
    descriptive = protocol["profiles"][0]
    require(descriptive["profile"] == "scoped-descriptive-v1", "Profile order differs")
    modes = {(mode, intent) for c in descriptive["cases"] for mode in ("activities", "period")
             for intent in ("review", "compare") if mode in c["tags"] and intent in c["tags"]}
    require(len(modes) == 4, "Both selection modes and intents required")
    require(artifacts["version"] == protocol["artifactVersion"], "Artifact version differs")
    rows = artifacts["cases"]
    guards = artifacts["guards"]
    require(len(guards) == len(protocol["preparationGuards"]) == 3, "Preparation guard denominator differs")
    for guard, expected_guard in zip(guards, protocol["preparationGuards"]):
        require(guard["caseId"] == expected_guard["id"] and guard["status"] == expected_guard["expected"]
                and expected_guard["reason"] in guard["blockedReasons"] and type(guard["modelCalls"]) is int
                and guard["modelCalls"] == 0, "Preparation guard differs or permits inference")
    require(len(rows) == 28 and len({r["caseId"] for r in rows}) == 28
            and {r["caseId"]: r["profile"] for r in rows} == expected, "Artifact case membership differs")
    for row in rows:
        require(set(row) == {"caseId", "profile", "checkpoint", "exactPrompt", "exactCorrectionPrompt", "consentDigest"}, "Unknown artifact fields")
        first, correction = row["exactPrompt"], row["exactCorrectionPrompt"]
        require(bool(row["checkpoint"]) and set(first) == set(correction) == {"systemInstructions", "userPacketJson"}, "Incomplete exact prompts")
        require(all(type(v) is str and v for p in (first, correction) for v in p.values()), "Prompt fields must be exact strings")
        require(first["userPacketJson"] == correction["userPacketJson"]
                and first["systemInstructions"] != correction["systemInstructions"], "Correction packet changed")
        require(max(len(compact(first)), len(compact(correction))) <= 32768, "Exact prompt budget exceeded")
        packet = decode(first["userPacketJson"].encode("utf-8"))
        require(packet["profile"] == row["profile"], "Packet profile substituted")
        binding = {"profile": row["profile"], "first": first, "correction": correction}
        require(row["consentDigest"] == digest(compact(binding)), "Production prompt binding differs")
    return expected


def verify(root=ROOT):
    folder = root / "benchmarks/scoped-profiles"
    manifest = decode((folder / "manifest-v1.json").read_bytes())
    require(manifest["version"] == "scoped-profile-manifest-v1" and manifest["modelCallsPermitted"] is False,
            "Manifest version or admission differs")
    require(set(manifest["files"]) == set(PINNED), "Manifest file membership differs")
    for name, expected in manifest["files"].items():
        require(digest((root / name).read_bytes()) == expected, "Frozen artifact/source hash differs: " + name)
    protocol = decode((folder / "protocols-v1.json").read_bytes())
    artifacts = decode((folder / "artifacts-v1.json").read_bytes())
    validate_documents(protocol, artifacts)
    return protocol, artifacts


def rubric_result(profile, rows, independent_human=False):
    """Arithmetic only; never writes admission. Missing outcomes stay in the denominator."""
    require(type(independent_human) is bool, "Independent human declaration must be explicit")
    expected = {c["id"] for c in profile["cases"]}
    require(len({r["caseId"] for r in rows}) == len(rows) and all(r["caseId"] in expected for r in rows), "Unknown or duplicate score rows")
    indexed = {r["caseId"]: r for r in rows}
    dimensions = {d["id"]: d["weight"] for d in profile["dimensions"]}
    complete = True
    minimum = True
    critical = 0
    weighted = 0.0
    outcomes = {}
    for case in profile["cases"]:
        row = indexed.get(case["id"], {"outcome": "unrun", "scores": {}, "usefulFinal": False, "criticalFailures": []})
        outcome = row["outcome"]
        require(outcome in ("accepted", "rejected", "timeout", "empty", "incomplete", "unrun"), "Unknown outcome")
        outcomes[case["id"]] = outcome
        scores = row["scores"]
        require(all(k in dimensions and type(v) is int and 0 <= v <= 4 for k, v in scores.items()), "Invalid dimension score")
        require(type(row["usefulFinal"]) is bool and isinstance(row["criticalFailures"], list), "Invalid usefulness/critical record")
        complete &= outcome == "accepted" and row["usefulFinal"] and set(scores) == set(dimensions)
        minimum &= set(scores) == set(dimensions) and all(v >= 2 for v in scores.values())
        critical += len(row["criticalFailures"])
        # Failed outcomes contribute zero even if somebody attached scores to them.
        if outcome == "accepted" and row["usefulFinal"]:
            weighted += sum(dimensions[k] * v / 4 for k, v in scores.items())
    mean = weighted / len(expected)
    return {"meetsFrozenRubric": bool(independent_human and complete and minimum and mean >= 80 and critical == 0),
            "weightedMeanPercent": mean, "criticalFailureCount": critical, "outcomes": outcomes,
            "productionAdmission": "closed"}


def freeze(staging):
    """One-time creation from a reviewed Kotlin export; never replaces frozen artifacts."""
    artifact_path, manifest_path = HERE / "artifacts-v1.json", HERE / "manifest-v1.json"
    require(not artifact_path.exists() and not manifest_path.exists(), "Frozen artifacts already exist; create a new evaluation version")
    data = (staging / "artifacts-v1.json").read_bytes()
    validate_documents(decode((HERE / "protocols-v1.json").read_bytes()), decode(data))
    hashes = {name: digest(data if name.endswith("/artifacts-v1.json") else (ROOT / name).read_bytes()) for name in PINNED}
    manifest = {"version": "scoped-profile-manifest-v1", "modelCallsPermitted": False, "files": hashes}
    with artifact_path.open("xb") as output:
        output.write(data)
    with manifest_path.open("xb") as output:
        output.write(json.dumps(manifest, indent=2).encode("utf-8") + b"\n")
    verify()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freeze-from", type=Path, help="Create NEW frozen files from a reviewed Kotlin staging export; never overwrite")
    args = parser.parse_args()
    if args.freeze_from:
        freeze(args.freeze_from)
    protocol, artifacts = verify()
    print(f"Verified {len(protocol['profiles'])} closed profiles / {len(artifacts['cases'])} frozen synthetic cases. No inference.")


if __name__ == "__main__":
    main()
