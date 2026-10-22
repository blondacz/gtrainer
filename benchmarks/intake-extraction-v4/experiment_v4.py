"""Isolated v4 integrity and one-time freeze. No inference or runtime access."""
import argparse
import importlib.util
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
spec = importlib.util.spec_from_file_location("frozen_intake_v3", ROOT / "benchmarks/intake-extraction-v3/experiment.py")
v3 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v3)
compact, decode, digest = v3.compact, v3.decode, v3.digest
PROFILE = "context-intake-extraction-v4"
IDS = v3.IDS
PREFIX = "benchmarks/intake-extraction-v4/"
GENERATED = ("protocol-v4.json", "inputs-v4.json", "oracle-v4.json", "fixtures-v4.json")
PINNED = [PREFIX + n for n in (*GENERATED, "experiment_v4.py", "rules_v4.py", "fixtures_v4.py", "oracle_v4.py", "test_oracle_v4.py")]


def need(value):
    if not value:
        raise ValueError("V4 conformance integrity refused")


def documents():
    import fixtures_v4
    import rules_v4
    old_protocol, old = v3.verify()
    lineage = {name: digest((ROOT / f"benchmarks/{folder}/manifest-{name}.json").read_bytes())
               for name, folder in (("v1", "scoped-profiles"), ("v2", "intake-extraction-v2"), ("v3", "intake-extraction-v3"))}
    protocol = {"version": "intake-conformance-protocol-v4", "profile": PROFILE, "previousManifests": lineage,
                "caseIds": IDS, "caseCount": 8, "caseExpectations": old_protocol["cases"],
                "evaluationMethod": "deterministic_extractive", "humanScoringRequired": False,
                "independentHuman": False, "productionAdmission": "closed", "requiredPassingCases": 8,
                "contextTokens": 4096, "outputReserveTokens": 1536, "marginTokens": 256,
                "maxBatches": 3, "campaignSeconds": 14400, "cleanupReserveSeconds": 240,
                "developmentSetTuning": True, "unseenInputReliabilityProven": False}
    inputs = {"version": "intake-conformance-inputs-v4", "profile": PROFILE, "schema": old["schema"],
              "cases": [{"caseId": c["caseId"], "userPacketJson": c["exactPrompt"]["userPacketJson"]} for c in old["cases"]]}
    return protocol, inputs, rules_v4.definition(old), fixtures_v4.vectors(old)


def verify():
    expected = documents()
    manifest = decode((HERE / "manifest-v4.json").read_bytes())
    need(set(manifest) == {"version", "profile", "modelCallsPermitted", "files"})
    need(manifest["version"] == "intake-conformance-manifest-v4" and manifest["profile"] == PROFILE)
    need(manifest["modelCallsPermitted"] is False and set(manifest["files"]) == set(PINNED))
    for name, h in manifest["files"].items():
        need(digest((ROOT / name).read_bytes()) == h)
    actual = tuple(decode((HERE / n).read_bytes()) for n in GENERATED)
    need(compact(actual) == compact(expected))
    return actual


def freeze():
    # Exclusive files preserve any interrupted freeze instead of overwriting evidence.
    import capture
    values = documents()
    need(all(not (HERE / n).exists() and not (HERE / n).is_symlink() for n in (*GENERATED, "manifest-v4.json")))
    for name, value in zip(GENERATED, values):
        capture.private_write(HERE / name, compact(value))
    manifest = {"version": "intake-conformance-manifest-v4", "profile": PROFILE, "modelCallsPermitted": False,
                "files": {name: digest((ROOT / name).read_bytes()) for name in PINNED}}
    capture.private_write(HERE / "manifest-v4.json", compact(manifest))
    verify()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freeze", action="store_true")
    args = parser.parse_args()
    if args.freeze: freeze()
    else: verify()
    print("Eight frozen v4 synthetic cases verified. No inference; production closed.")
