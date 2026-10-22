"""Immutable prospective corpus; no runtime or inference authority on import."""
import argparse
import copy
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE.parent / "intake-extraction-v4-2"))
import oracle_v4_2 as lineage

e = lineage.e
import corpus_v5

PROFILE = "context-intake-extraction-v5"
IDS = [f"u{i:02}" for i in range(1, 9)]
PREFIX = "benchmarks/intake-extraction-v5/"
PROMPT_SHA256 = "34ed33a07b3e52eb0d61a6152b7dc2a7d714984ddbc5b9b01b732b7f333fbd7e"
PARENT_SHA256 = "36d783d0f3c2c1ff734a3f59df9e7d2b2b341a76996d951f6200738c98f1d887"
GENERATED = ("protocol-v5.json", "inputs-v5.json", "oracle-v5.json", "fixtures-v5.json")
PINNED = [PREFIX + name for name in (*GENERATED, "corpus_v5.py", "suite_v5.py", "test_suite_v5.py")]


def documents():
    lineage.verify()
    e.need(e.digest((lineage.HERE / "manifest.json").read_bytes()) == PARENT_SHA256)
    inputs, contract, fixtures = corpus_v5.documents(e, PROFILE, IDS)
    protocol = {"version": "intake-conformance-protocol-v5", "profile": PROFILE,
        "parentV42ManifestSha256": PARENT_SHA256, "selectedPromptSha256": PROMPT_SHA256,
        "caseIds": IDS, "caseCount": 8, "requiredPassingCases": 8,
        "comparisonSemantics": "v4.2-prospective-finite-contextual-alternatives-and-label-casefold",
        "maximumBatches": 1, "maximumScoredLaunches": 8, "maximumProbeLaunches": 1,
        "campaignSeconds": 14400, "cleanupReserveSeconds": 240,
        "contextTokens": 4096, "outputReserveTokens": 1536, "marginTokens": 256,
        "promptTuningPermitted": False, "retriesPermitted": False,
        "prospectiveSyntheticEvaluation": True, "independentBlindHoldout": False,
        "independentHuman": False, "humanAssessment": None, "productionAdmission": "closed"}
    return protocol, inputs, contract, fixtures


def verify():
    manifest = e.decode((HERE / "manifest-v5.json").read_bytes())
    e.need(manifest["version"] == "intake-conformance-manifest-v5")
    e.need(manifest["profile"] == PROFILE and manifest["modelCallsPermitted"] is False)
    e.need(manifest["parentV42ManifestSha256"] == PARENT_SHA256)
    e.need(set(manifest["files"]) == set(PINNED))
    for name, digest in manifest["files"].items():
        e.need(e.digest((ROOT / name).read_bytes()) == digest)
    actual = tuple(e.decode((HERE / name).read_bytes()) for name in GENERATED)
    e.need(e.compact(actual) == e.compact(documents()))
    return actual


def freeze():
    private = lineage.parent.replay.private
    e.need(not any((HERE / name).exists() for name in (*GENERATED, "manifest-v5.json")))
    for name, value in zip(GENERATED, documents()):
        private.private_write(HERE / name, e.compact(value))
    private.private_write(HERE / "manifest-v5.json", e.compact({
        "version": "intake-conformance-manifest-v5", "profile": PROFILE,
        "modelCallsPermitted": False, "parentV42ManifestSha256": PARENT_SHA256,
        "files": {name: e.digest((ROOT / name).read_bytes()) for name in PINNED}}))
    verify()


def evaluate(case_id, stages, contract=None):
    if contract is None:
        contract = verify()[2]
    return lineage.parent.evaluate(case_id, stages, contract)


def assembled(case_id, raw, inputs):
    packet = e.decode(next(row for row in inputs["cases"] if row["caseId"] == case_id)["userPacketJson"].encode())
    draft = copy.deepcopy(raw)
    draft.update(profile="context-intake-suggestion-v1", intakeId=packet["intake"]["intakeId"],
                 intakeRevision=packet["intake"]["revision"])
    text = packet["intake"]["content"]
    for row in draft["suggestions"]:
        start = text.index(row["sourceExcerpt"])
        row.update(sourceStart=len(text[:start].encode("utf-16-le")) // 2,
                   sourceEnd=len(text[:start + len(row["sourceExcerpt"])].encode("utf-16-le")) // 2)
    return draft


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freeze", action="store_true")
    args = parser.parse_args()
    freeze() if args.freeze else verify()
    print("Eight prospective v5 cases verified; no inference authority.")
