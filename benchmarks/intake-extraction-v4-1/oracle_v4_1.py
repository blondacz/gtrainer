"""Versioned label-case equivalence over the unchanged v4 acceptance contract.

Comparison copies only: never edits responses, stages, the v4 contract or reports.
No inference or runtime operations. Existing v4 acquisitions retain v4 outcomes.
"""
import argparse
import copy
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "intake-extraction-v4"))
import oracle_v4 as strict
import replay_v4 as replay

e = strict.e
VERSION = "intake-conformance-oracle-v4.1-label-casefold"
POLICY = {"version": VERSION, "baseProtocol": "intake-conformance-protocol-v4",
          "comparisonEquivalence": {"commitment.label": "unicode-casefold"},
          "otherPredicates": "unchanged-v4", "historicalReportsMutable": False}


def check_draft(case_id, draft, contract):
    actual, expected = copy.deepcopy(draft), copy.deepcopy(contract)
    if type(actual) is dict and type(actual.get("suggestions")) is list:
        for suggestion in actual["suggestions"]:
            if type(suggestion) is not dict: continue
            commitment = suggestion.get("commitment")
            if type(commitment) is dict and type(commitment.get("label")) is str:
                commitment["label"] = commitment["label"].casefold()
    for case in expected["cases"]:
        if case["caseId"] != case_id: continue
        for fields in case["suggestions"]:
            if "commitment.label" in fields:
                fields["commitment.label"] = [value.casefold() if type(value) is str else value
                                              for value in fields["commitment.label"]]
    return strict.check_draft(case_id, actual, expected)


def evaluate(case_id, stages, contract=None):
    if contract is None: contract = e.verify()[2]
    original = strict.evaluate(case_id, stages, contract)
    if original["status"] != "failed" or "assembled_draft_invalid" in original["reasons"]:
        return original
    draft = e.decode(stages["assembledDraft"].encode())
    errors = check_draft(case_id, draft, contract)
    return {"status": "failed" if errors else "passed", "reasons": errors}


def manifest():
    return {"version": "intake-conformance-oracle-manifest-v4.1",
        "policy": POLICY,
        "parentManifestSha256": e.digest((e.HERE / "manifest-v4.json").read_bytes()),
        "parentContractSha256": e.digest((e.HERE / "oracle-v4.json").read_bytes()),
        "parentOracleSourceSha256": e.digest((e.HERE / "oracle_v4.py").read_bytes()),
        "files": {name: e.digest((HERE / name).read_bytes()) for name in
                  ("oracle_v4_1.py", "test_oracle_v4_1.py", "policy.json")}}


def verify():
    e.verify()
    e.need(e.decode((HERE / "policy.json").read_bytes()) == POLICY)
    e.need(e.decode((HERE / "manifest.json").read_bytes()) == manifest())


def compare_report(source, destination):
    """Explicitly post-hoc secondary assessment, never a replacement v4 receipt."""
    verify()
    private = replay.private
    directory = private.private_directory(destination)
    raw = private.private_read(source); report = e.decode(raw)
    e.need(report["version"] == "intake-conformance-batch-report-v4")
    e.need(report["classification"] in ("passed", "quality_failure"))
    e.need(report["stopVerified"] is True and report["probeVerified"] is True and report["errorCategory"] is None)
    e.need(not report["deadlineBreach"] and [row["caseId"] for row in report["cases"]] == e.IDS)
    contract = e.verify()[2]; rows = []
    for row in report["cases"]:
        e.need(row["outcome"] in ("response", "empty"))
        e.need(row["rawSha256"] == (None if row["raw"] is None else e.digest(row["raw"].encode())))
        e.need(row["stagesSha256"] == e.digest(e.compact(row["stages"])))
        e.need(row["oracleResultSha256"] == e.digest(e.compact(row["oracle"])))
        e.need(row["oracle"] == strict.evaluate(row["caseId"], row["stages"], contract))
        rows.append({"caseId": row["caseId"], "outcome": row["outcome"],
            "rawSha256": row["rawSha256"], "stagesSha256": row["stagesSha256"],
            "originalV4Oracle": row["oracle"], "v4_1Oracle": evaluate(row["caseId"], row["stages"], contract)})
    comparison = {"version": VERSION, "sourceReportSha256": e.digest(raw),
        "policyManifestSha256": e.digest((HERE / "manifest.json").read_bytes()),
        "postHocReevaluation": True, "newAcquisition": False, "newInferenceRequests": 0,
        "historicalResultUnmodified": True, "comparisonOnlyCasefoldField": "commitment.label",
        "cases": rows, "totalCases": 8,
        "originalV4Passes": sum(row["originalV4Oracle"]["status"] == "passed" for row in rows),
        "v4_1Passes": sum(row["v4_1Oracle"]["status"] == "passed" for row in rows),
        "evaluationMethod": "deterministic_extractive_with_label_case_equivalence",
        "independentHuman": False, "humanAssessment": None,
        "developmentSetTuning": True, "productionAdmission": "closed"}
    private.private_write(directory / "comparison.json", e.compact(comparison))
    e.need(private.private_read(source) == raw)
    return comparison


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = compare_report(args.source_report, args.output)
    except BaseException:
        print("Versioned comparison refused; inspect private evidence."); raise SystemExit(2)
    print(f"Post-hoc comparison: v4 {result['originalV4Passes']}/8; v4.1 {result['v4_1Passes']}/8. No new inference or historical rewrite.")
