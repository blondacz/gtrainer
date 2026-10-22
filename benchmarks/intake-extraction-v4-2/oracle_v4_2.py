"""Explicit contextual question equivalence, composed with v4.1 label casefold.

Offline comparison only. Never rewrites acquired responses or historical results.
"""
import argparse
import copy
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "intake-extraction-v4-1"))
import oracle_v4_1 as parent

e = parent.e
VERSION = "intake-conformance-oracle-v4.2-contextual-question"
PRACTICE_QUESTION = "What are the practice date, start and end times, timezone, and recurrence details?"
POLICY = {
    "version": VERSION,
    "parentPolicy": parent.VERSION,
    "additionalQuestionTexts": {"i06": {"training_unknowns": [PRACTICE_QUESTION]}},
    "otherPredicates": "unchanged-v4.1",
    "globalNounReplacement": False,
    "historicalReportsMutable": False,
}


def check_draft(case_id, draft, contract):
    expected = copy.deepcopy(contract)
    additions = POLICY["additionalQuestionTexts"].get(case_id, {})
    for case in expected["cases"]:
        if case["caseId"] != case_id:
            continue
        for role in case["questionRoles"]:
            role["texts"].extend(additions.get(role["id"], []))
    return parent.check_draft(case_id, draft, expected)


def evaluate(case_id, stages, contract=None):
    if contract is None:
        contract = e.verify()[2]
    original = parent.evaluate(case_id, stages, contract)
    if original["status"] != "failed" or "assembled_draft_invalid" in original["reasons"]:
        return original
    draft = e.decode(stages["assembledDraft"].encode())
    errors = check_draft(case_id, draft, contract)
    return {"status": "failed" if errors else "passed", "reasons": errors}


def manifest():
    return {
        "version": "intake-conformance-oracle-manifest-v4.2",
        "policy": POLICY,
        "parentManifestSha256": e.digest((parent.HERE / "manifest.json").read_bytes()),
        "files": {name: e.digest((HERE / name).read_bytes()) for name in
                  ("oracle_v4_2.py", "test_oracle_v4_2.py", "policy.json")},
    }


def verify():
    parent.verify()
    e.need(e.decode((HERE / "policy.json").read_bytes()) == POLICY)
    e.need(e.decode((HERE / "manifest.json").read_bytes()) == manifest())


def compare_report(source, destination):
    """Assess all eight responses together; retain all three policy outcomes."""
    verify()
    private = parent.replay.private
    directory = private.private_directory(destination)
    raw = private.private_read(source)
    report = e.decode(raw)
    e.need(report["version"] == "intake-conformance-batch-report-v4")
    e.need(report["classification"] in ("passed", "quality_failure"))
    e.need(report["stopVerified"] is True and report["probeVerified"] is True)
    e.need(report["errorCategory"] is None and report["deadlineBreach"] is False)
    e.need([row["caseId"] for row in report["cases"]] == e.IDS)
    contract = e.verify()[2]
    rows = []
    for row in report["cases"]:
        e.need(row["outcome"] in ("response", "empty"))
        e.need(row["rawSha256"] == (None if row["raw"] is None else e.digest(row["raw"].encode())))
        e.need(row["stagesSha256"] == e.digest(e.compact(row["stages"])))
        e.need(row["oracleResultSha256"] == e.digest(e.compact(row["oracle"])))
        rules = next(case for case in contract["cases"] if case["caseId"] == row["caseId"])
        e.need(row["predicatesSha256"] == e.digest(e.compact(rules)))
        e.need(row["oracle"] == parent.strict.evaluate(row["caseId"], row["stages"], contract))
        rows.append({
            "caseId": row["caseId"], "outcome": row["outcome"],
            "rawSha256": row["rawSha256"], "stagesSha256": row["stagesSha256"],
            "originalV4Oracle": row["oracle"],
            "v4_1Oracle": parent.evaluate(row["caseId"], row["stages"], contract),
            "v4_2Oracle": evaluate(row["caseId"], row["stages"], contract),
        })
    original_passes = sum(row["originalV4Oracle"]["status"] == "passed" for row in rows)
    e.need((report["classification"] == "passed") == (original_passes == 8))
    comparison = {
        "version": VERSION, "sourceReportSha256": e.digest(raw),
        "policyManifestSha256": e.digest((HERE / "manifest.json").read_bytes()),
        "postHocReevaluation": True, "newAcquisition": False, "newInferenceRequests": 0,
        "historicalResultUnmodified": True, "cases": rows, "totalCases": 8,
        "originalV4Passes": original_passes,
        "v4_1Passes": sum(row["v4_1Oracle"]["status"] == "passed" for row in rows),
        "v4_2Passes": sum(row["v4_2Oracle"]["status"] == "passed" for row in rows),
        "evaluationMethod": "deterministic_extractive_with_declared_contextual_equivalence",
        "independentHuman": False, "humanAssessment": None,
        "developmentSetTuning": True, "productionAdmission": "closed",
    }
    e.need(private.private_read(source) == raw)
    private.private_write(directory / "comparison.json", e.compact(comparison))
    return comparison


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = compare_report(args.source_report, args.output)
    except Exception:
        print("Versioned comparison refused; inspect private evidence.")
        raise SystemExit(2)
    print(f"Post-hoc comparison: v4 {result['originalV4Passes']}/8; "
          f"v4.1 {result['v4_1Passes']}/8; v4.2 {result['v4_2Passes']}/8. "
          "No new inference or historical rewrite.")
