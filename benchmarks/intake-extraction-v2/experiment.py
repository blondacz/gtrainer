"""Offline integrity for the isolated intake experiment. Never invokes a model."""
import argparse
import os
from pathlib import Path
import re
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(ROOT / "benchmarks/scoped-profiles"))
import protocol as baseline

PROFILE = "context-intake-extraction-v2"
BASELINE_PROFILE = "context-intake-suggestion-v1"
IDS = [f"i{i:02}" for i in range(1, 9)]
compact, decode, digest = baseline.compact, baseline.decode, baseline.digest
PREFIX = "benchmarks/intake-extraction-v2/"
PINNED = [PREFIX + name for name in ("experiment.py", "test_experiment.py", "protocol-v2.json", "artifacts-v2.json")]
PINNED += ["backend/src/test/kotlin/com/gtrainer/" + name for name in
           ("IntakeExtractionV2.kt", "IntakeExtractionV2Test.kt", "IntakeExtractionV2ArtifactsTest.kt")]
PINNED += ["backend/src/main/kotlin/com/gtrainer/" + name for name in
           ("StrictProfileDraftJson.kt", "StrictModelJson.kt", "ContextIntake.kt")]


def require(condition):
    if not condition:
        raise ValueError("Experimental integrity refused")


def definition(original, original_manifest):
    profile = next(p for p in original["profiles"] if p["profile"] == BASELINE_PROFILE)
    return {"version": "intake-extraction-protocol-v2", "profile": PROFILE,
            "baselineProfile": BASELINE_PROFILE, "baselineManifestSha256": digest(original_manifest),
            "syntheticOnly": True, "modelCallsPermitted": False, "productionAdmission": "closed",
            "caseCount": 8, "caseIds": IDS, "dimensions": profile["dimensions"], "cases": profile["cases"],
            "scoreScale": original["scoreScale"], "gates": original["gates"],
            "criticalFailures": original["criticalFailures"],
            "generationPolicy": {"maximumAttemptsPerCase": 1, "correctionPermitted": False,
                                 "maximumExactPromptUtf8Bytes": 32768, "maximumDecodedDraftUtf8Bytes": 12288}}


def validate_documents(protocol, artifacts, original, original_artifacts, original_manifest):
    require(compact(protocol) == compact(definition(original, original_manifest)))
    require(type(artifacts) is dict and set(artifacts) == {"version", "profile", "schema", "example", "cases"})
    require(artifacts["version"] == "intake-extraction-artifacts-v2" and artifacts["profile"] == PROFILE)
    schema = artifacts["schema"]
    require(type(schema) is dict and schema.get("type") == "object")
    require(set(schema.get("properties", {})) == {"suggestions", "questions"})

    def visit(node):
        if type(node) is dict:
            if node.get("type") == "object":
                require(node.get("additionalProperties") is False)
                require(set(node.get("required", [])) == set(node.get("properties", {})))
            for value in node.values():
                visit(value)
        elif type(node) is list:
            for value in node:
                visit(value)

    visit(schema)
    example = artifacts["example"]
    require(type(example) is dict and set(example) == {"packetJson", "raw"})
    packet = decode(example["packetJson"].encode())
    raw = decode(example["raw"].encode())
    require(set(raw) == {"suggestions", "questions"})
    require({s["kind"] for s in raw["suggestions"]} == {"context", "commitment"})
    original_rows = {c["caseId"]: c for c in original_artifacts["cases"] if c["profile"] == BASELINE_PROFILE}
    require(type(artifacts["cases"]) is list and [c["caseId"] for c in artifacts["cases"]] == IDS)
    instructions = set()
    for case in artifacts["cases"]:
        require(set(case) == {"caseId", "baselineConsentDigest", "exactPrompt"})
        old = original_rows[case["caseId"]]
        prompt = case["exactPrompt"]
        require(type(prompt) is dict and set(prompt) == {"systemInstructions", "userPacketJson"})
        require(prompt["userPacketJson"] == old["exactPrompt"]["userPacketJson"])
        require(case["baselineConsentDigest"] == old["consentDigest"])
        require(len(compact(prompt)) <= 32768)
        require(example["packetJson"] in prompt["systemInstructions"] and example["raw"] in prompt["systemInstructions"])
        old_packet = decode(prompt["userPacketJson"].encode())
        require(packet["intake"]["intakeId"] != old_packet["intake"]["intakeId"])
        require(packet["intake"]["content"] != old_packet["intake"]["content"])
        require(not (set(re.findall(r"\b\d{4}-\d{2}-\d{2}\b", packet["intake"]["content"])) &
                     set(re.findall(r"\b\d{4}-\d{2}-\d{2}\b", old_packet["intake"]["content"]))))
        instructions.add(prompt["systemInstructions"])
    require(len(instructions) == 1)


def verify(root=ROOT):
    original, original_artifacts = baseline.verify(root)
    folder = root / PREFIX
    manifest = decode((folder / "manifest-v2.json").read_bytes())
    require(set(manifest) == {"version", "modelCallsPermitted", "files"})
    require(manifest["version"] == "intake-extraction-manifest-v2" and manifest["modelCallsPermitted"] is False)
    require(set(manifest["files"]) == set(PINNED))
    for name, expected in manifest["files"].items():
        require(digest((root / name).read_bytes()) == expected)
    protocol = decode((folder / "protocol-v2.json").read_bytes())
    artifacts = decode((folder / "artifacts-v2.json").read_bytes())
    validate_documents(protocol, artifacts, original, original_artifacts,
                       (root / "benchmarks/scoped-profiles/manifest-v1.json").read_bytes())
    return protocol, artifacts


def freeze(staging, root=ROOT):
    """Create reviewed initial artifacts once; never update an existing version."""
    original, original_artifacts = baseline.verify(root)
    folder = root / PREFIX
    names = ("protocol-v2.json", "artifacts-v2.json", "manifest-v2.json")
    require(all(not (folder / name).exists() and not (folder / name).is_symlink() for name in names))
    artifacts_bytes = (staging / "artifacts-v2.json").read_bytes()
    artifacts = decode(artifacts_bytes)
    protocol = definition(original, (root / "benchmarks/scoped-profiles/manifest-v1.json").read_bytes())
    validate_documents(protocol, artifacts, original, original_artifacts,
                       (root / "benchmarks/scoped-profiles/manifest-v1.json").read_bytes())
    values = {PREFIX + "protocol-v2.json": compact(protocol), PREFIX + "artifacts-v2.json": artifacts_bytes}
    manifest = {"version": "intake-extraction-manifest-v2", "modelCallsPermitted": False,
                "files": {name: digest(values[name] if name in values else (root / name).read_bytes()) for name in PINNED}}
    values[PREFIX + "manifest-v2.json"] = compact(manifest)
    for name in names:
        descriptor = os.open(folder / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
        with os.fdopen(descriptor, "wb") as output:
            output.write(values[PREFIX + name])
            output.flush()
            os.fsync(output.fileno())
    verify(root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freeze-from", type=Path)
    args = parser.parse_args()
    try:
        if args.freeze_from is not None:
            freeze(args.freeze_from)
        verify()
    except Exception:
        print("Experimental integrity refused; no inference occurred.")
        return 1
    print("Verified eight frozen experimental intake cases. No inference; admission closed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
