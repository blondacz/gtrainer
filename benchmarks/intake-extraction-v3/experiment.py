"""Immutable synthetic v3 artifacts. No provider, runtime or model access."""
import argparse
import copy
import importlib.util
import os
from pathlib import Path
import re
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
spec = importlib.util.spec_from_file_location("intake_frozen_v2", ROOT / "benchmarks/intake-extraction-v2/experiment.py")
v2 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v2)
baseline = v2.baseline
compact, decode, digest = v2.compact, v2.decode, v2.digest
PROFILE = "context-intake-extraction-v3"
PREFIX = "benchmarks/intake-extraction-v3/"
IDS = v2.IDS
GENERATED = ("protocol-v3.json", "artifacts-v3.json", "ownership-v3.json", "context-proof-v3.json")
PINNED = [PREFIX + n for n in (*GENERATED, "experiment.py", "test_experiment.py")]
PINNED += ["backend/src/test/kotlin/com/gtrainer/" + n for n in ("IntakeExtractionV3.kt", "IntakeExtractionV3Test.kt")]
OLLAMA_COMMIT = "cc4069396f3ad2c370c53eed2e4a42ac13adab84"
LLAMA_COMMIT = "161755f29e415e2c33efe906e91843c068efd664"
TEMPLATE = "ae370d884f108d16e7cc8fd5259ebc5773a0afa6e078b11f4ed7e39a27e0dfc4"
IMAGE = "ollama/ollama@sha256:9c1dc45ea758396139ec0adfa52947714c61f8d1e4537a6e2daef8138e7a64a9"
MODEL = "500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41"
TOKEN_EVIDENCE = "f6e9dc27bcdc0abe2802d602ba5da1ce174d066aaf83c54e4a3b03762604ad86"
COUNTS = [798, 802, 805, 796, 815, 816, 816, 794]
BASE = "/properties/suggestions/items/anyOf/"
REMOVALS = {BASE + str(i) + "/properties/unresolvedFields/uniqueItems": True for i in (0, 1)}
REMOVALS.update({BASE + str(i) + "/properties/sourceExcerpt/maxLength": 4000 for i in (0, 1)})
REMOVALS[BASE + "0/properties/context/properties/content/maxLength"] = 4000
SOURCE_HASHES = {
    "common/json-schema.cpp": "68ba2adbb6e0578c0d89e99cc9a9a7aa9c0b554b555bcd3908da71789045509d",
    "common/json-schema.h": "57933a181ec0e84cbba1abea7fd898dc03e452d7de1a35795b787cee679d02af",
    "common/json-schema-to-grammar.cpp": "d5a82fff5d611abd1198ff27c328b20376ffe9f110b4771d579c8bd20c70541f",
    "src/llama-grammar.cpp": "668d8ad4bd9066551061e1ccd38c6a681a84c50ddc05ae92d565a473d1239986",
    "src/llama-grammar.h": "db730e5aff77f96274aba4a43670f036400d89c7ed44b6e98d3ced41c7b9d193",
    "src/llama-sampler.cpp": "30b739d8d2bab2c57d5bdb9d082cb115f9942d9668871501506360b55c3360a0",
    "common/sampling.cpp": "f23f8d663932bfd73abac1bd5e99055fc6efb07e21ef27dd7e25af2f2da40bff"}


def need(condition):
    if not condition:
        raise ValueError("V3 experimental integrity refused")


def provider_schema(original):
    schema = copy.deepcopy(original)
    for pointer, expected in REMOVALS.items():
        parts = pointer[1:].split("/")
        node = schema
        for part in parts[:-1]:
            node = node[int(part)] if type(node) is list else node[part]
        need(compact(node.pop(parts[-1])) == compact(expected))
    return schema


def ownership():
    return {"version": "intake-extraction-ownership-v3", "profile": PROFILE,
            "method": "pinned_source_control_flow_audit", "nativeGrammarTestPerformed": False,
            "ollamaCommit": OLLAMA_COMMIT, "llamaCommit": LLAMA_COMMIT, "sourceSha256": SOURCE_HASHES,
            "converterLibrarySha256": "b1496a95a08a5fb09b66f6b1d900b114126dc94d8daebdb63719c3e826702434",
            "converterDisassemblySha256": "ab1fbb241374baf85c9a56ad93821de368c5295ff4ba27de95879190fe1de6f4",
            "removedProviderKeywords": REMOVALS, "localValidator": "unchanged frozen IntakeExtractionV2.validate",
            "localOnly": ["unresolvedFields uniqueness", "4000 UTF-16 code-unit excerpt/content ceilings",
                          "12288-byte raw and assembled limits", "strict decoding beyond provider subset",
                          "unique exact-excerpt UTF-16 binding", "unchanged semantic authority"],
            "providerEvidence": {
                "type/properties/required/additionalProperties": "json-schema.cpp:133-172,295-320; converter:699-793,897-910",
                "items/maxItems": "json-schema.cpp:174-194; converter:18-42,924-931; grammar:463-528,621-658; maxima 8/10/20 below 2000",
                "anyOf/null": "json-schema.cpp:117-130,234-237,319-320; converter:884-886,967-968",
                "enum": "json-schema.cpp:260-269; converter:890-896; finite string literals",
                "minLength/maxLength": "json-schema.cpp:197-209; converter:246-247,933-950; grammar:621-658; remaining maxima 10/19/128/160/1000",
                "minimum/maximum": "json-schema.cpp:212-224; converter:45-155,952-961; inclusive integers 1..366",
                "nativeEnforcement": "sampling.cpp:264-275,634-666; llama-sampler.cpp:2673-2684; llama-grammar.cpp:1355-1395"},
            "limitations": ["source audit, not sampled provider reliability", "fixed generated object order",
                            "escaped surrogate pairs may be more restrictive than raw Unicode",
                            "local UTF-16 ceilings remain stricter than schema code-point length"],
            "productionAdmission": "closed"}


def render(prompt):
    return ("<|im_start|>system\n\n" + prompt["systemInstructions"] + "<|im_end|>\n<|im_start|>user\n" +
            prompt["userPacketJson"] + " /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n").encode()


def context_header(artifacts):
    return {"version": "intake-extraction-context-proof-v3", "profile": PROFILE,
            "method": "runtime_matched_offline_tokenization_rebound_to_identical_rendering",
            "modelManifestSha256": MODEL, "image": IMAGE, "ollamaCommit": OLLAMA_COMMIT, "llamaCommit": LLAMA_COMMIT,
            "templateSha256": TEMPLATE, "schemaSha256": digest(compact(artifacts["schema"])),
            "tokenizerLibrarySha256": "8dc7056ee6548c619bded63829696b439478c357df2c4f6c41594e4f86d56324",
            "tokenizerHelperSha256": "90b80e5ae4f11365e01adba9f51d142fc1217def30f6d5e46dd7804ac76aa912",
            "tokenEvidenceSha256": TOKEN_EVIDENCE, "schemaPath": "sampling_grammar_only",
            "addSpecial": True, "parseSpecial": True, "hiddenSystemHistoryTools": False,
            "maximumAuditedInputTokens": 816, "extraSafetyMarginTokens": 256, "reservedOutputTokens": 1536,
            "contextTokens": 4096, "totalWithMarginTokens": 2608, "providerMeasuredInputTokens": None,
            "productionAdmission": "closed"}


def make_context(artifacts, evidence_bytes):
    need(digest(evidence_bytes) == TOKEN_EVIDENCE)
    evidence = decode(evidence_bytes)
    need(evidence["tensorWeightsLoaded"] is False and evidence["inferenceContextCreated"] is False and evidence["generationCalled"] is False)
    need([r["caseId"] for r in evidence["cases"]] == IDS)
    proof = context_header(artifacts)
    proof["cases"] = [dict(row["goTemplate"], caseId=row["caseId"], requestSha256=digest(compact(case["exactPrompt"])))
                      for row, case in zip(evidence["cases"], artifacts["cases"])]
    check_context(proof, artifacts)
    return proof


def check_context(proof, artifacts):
    need(type(proof) is dict and set(proof) == set(context_header(artifacts)) | {"cases"})
    need(compact({k: v for k, v in proof.items() if k != "cases"}) == compact(context_header(artifacts)))
    need(type(proof["cases"]) is list and len(proof["cases"]) == 8)
    for row, case, count in zip(proof["cases"], artifacts["cases"], COUNTS):
        need(set(row) == {"caseId", "tokens", "tokensSha256", "renderedSha256", "renderedUtf8Bytes", "requestSha256"})
        rendered = render(case["exactPrompt"])
        expected = dict(tokens=count, renderedSha256=digest(rendered), renderedUtf8Bytes=len(rendered),
                        caseId=case["caseId"], requestSha256=digest(compact(case["exactPrompt"])))
        need(compact({k: v for k, v in row.items() if k != "tokensSha256"}) == compact(expected))
        need(type(row["tokensSha256"]) is str and re.fullmatch("[0-9a-f]{64}", row["tokensSha256"]) is not None)
    need(max(COUNTS) + proof["reservedOutputTokens"] + proof["extraSafetyMarginTokens"] <= proof["contextTokens"])


def lineage(root):
    return {"v1": digest((root / "benchmarks/scoped-profiles/manifest-v1.json").read_bytes()),
            "v2": digest((root / "benchmarks/intake-extraction-v2/manifest-v2.json").read_bytes())}


def definition(old, root):
    return dict(old, version="intake-extraction-protocol-v3", profile=PROFILE, previousManifests=lineage(root),
                constraintOwnershipSha256=digest(compact(ownership())))


def validate_documents(protocol, artifacts, proof, owner, root):
    old_protocol, old = v2.verify(root)
    need(compact(protocol) == compact(definition(old_protocol, root)))
    expected = dict(old, version="intake-extraction-artifacts-v3", profile=PROFILE, schema=provider_schema(old["schema"]))
    need(compact(artifacts) == compact(expected))
    need(compact(owner) == compact(ownership()))
    check_context(proof, artifacts)


def verify(root=ROOT):
    v2.verify(root)
    folder = root / PREFIX
    manifest = decode((folder / "manifest-v3.json").read_bytes())
    need(set(manifest) == {"version", "modelCallsPermitted", "previousManifests", "files"})
    need(manifest["version"] == "intake-extraction-manifest-v3" and manifest["modelCallsPermitted"] is False)
    need(manifest["previousManifests"] == lineage(root) and set(manifest["files"]) == set(PINNED))
    for name, expected in manifest["files"].items():
        need(digest((root / name).read_bytes()) == expected)
    protocol, artifacts, owner, proof = [decode((folder / name).read_bytes()) for name in GENERATED]
    validate_documents(protocol, artifacts, proof, owner, root)
    return protocol, artifacts


def freeze(staging, root=ROOT):
    old, _ = v2.verify(root)
    folder = root / PREFIX
    names = (*GENERATED, "manifest-v3.json")
    need(all(not (folder / n).exists() and not (folder / n).is_symlink() for n in names))
    artifacts_bytes = (staging / "artifacts-v3.json").read_bytes()
    artifacts = decode(artifacts_bytes)
    proof = decode((staging / "context-proof-v3.json").read_bytes())
    protocol, owner = definition(old, root), ownership()
    validate_documents(protocol, artifacts, proof, owner, root)
    values = {PREFIX + name: compact(value) for name, value in zip(GENERATED, (protocol, artifacts, owner, proof))}
    values[PREFIX + "artifacts-v3.json"] = artifacts_bytes
    manifest = {"version": "intake-extraction-manifest-v3", "modelCallsPermitted": False, "previousManifests": lineage(root),
                "files": {name: digest(values[name] if name in values else (root / name).read_bytes()) for name in PINNED}}
    values[PREFIX + "manifest-v3.json"] = compact(manifest)
    for name in names:
        fd = os.open(folder / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
        with os.fdopen(fd, "wb") as output:
            output.write(values[PREFIX + name]); output.flush(); os.fsync(output.fileno())
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
        print("V3 integrity refused. No inference occurred.")
        return 1
    print("Verified eight frozen v3 cases and offline context evidence. No inference; admission closed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
