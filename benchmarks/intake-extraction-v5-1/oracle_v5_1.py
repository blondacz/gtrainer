"""Bounded wording equivalence over frozen v5, for offline comparison only.

Never changes raw responses, parent contracts, live execution or historical scores.
"""
import argparse
import copy
from pathlib import Path
import re
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "intake-extraction-v5"))
import suite_v5 as suite

e = suite.e
base = suite.lineage.parent
private = base.replay.private
VERSION = "intake-conformance-oracle-v5.1-bounded-wording"
UNKNOWN = ("startsLocal", "endsLocal", "timeZone", "recurrence")
CARRIER = "What are the {activity} date, start and end times, timezone, and recurrence details?"
POLICY = {
    "version": VERSION, "parentProfile": suite.PROFILE,
    "labelComparison": "unicode-casefold-and-one-optional-leading-a-or-an",
    "activityRoles": {"training_unknowns": "training", "rehearsal_unknowns": "rehearsal"},
    "additionalActivityWord": "practice", "questionCarrier": CARRIER,
    "expansionGuards": ["one-eligible-role", "one-unresolved-commitment",
        "anchored-frozen-source-agrees-with-role", "all-four-fields-null-and-unresolved",
        "no-other-practice-compatible-activity", "canonical-carrier-predeclared"],
    "existingAlternatives": "preserved", "otherPredicates": "unchanged-v5",
    "globalNounReplacement": False, "historicalReportsMutable": False,
}


def label_key(value):
    if type(value) is not str:
        return value
    value = value.casefold()
    for article in ("a ", "an "):
        if value.startswith(article):
            return value[len(article):]
    return value


def question_alternatives(case):
    """Extend only expected text, using frozen targets, never candidate wording."""
    roles = copy.deepcopy(case["questionRoles"])
    eligible = [role for role in roles if role["id"] in POLICY["activityRoles"]]
    if len(eligible) != 1:
        return roles
    role = eligible[0]
    noun = POLICY["activityRoles"][role["id"]]
    activities = [row for row in case["suggestions"] if row.get("kind") == ["commitment"]]
    unresolved = [row for row in activities if any(row.get("unresolvedFields", []))]
    if len(unresolved) != 1:
        return roles
    target = unresolved[0]
    if any(target.get("commitment." + field) != [None] for field in UNKNOWN):
        return roles
    disclosures = target.get("unresolvedFields", [])
    if not disclosures or not all(type(option) is list and len(option) == len(UNKNOWN)
            and set(option) == set(UNKNOWN) for option in disclosures):
        return roles
    excerpts = target.get("sourceExcerpt", [])
    prefix = re.compile(r"^(?:(?:a|an) )?" + re.escape(noun) + r"\b")
    if not excerpts or not all(type(text) is str and prefix.search(text.casefold()) for text in excerpts):
        return roles
    for other in activities:
        if other is target:
            continue
        if any(type(text) is str and re.search(r"\b(?:training|rehearsal|practice)\b", text.casefold())
               for text in other.get("sourceExcerpt", [])):
            return roles
    canonical = CARRIER.format(activity=noun)
    if canonical in role["texts"]:
        alias = CARRIER.format(activity=POLICY["additionalActivityWord"])
        if alias not in role["texts"]:
            role["texts"].append(alias)
    return roles


def check_draft(case_id, draft, contract):
    actual, expected = copy.deepcopy(draft), copy.deepcopy(contract)
    if type(actual) is dict and type(actual.get("suggestions")) is list:
        for row in actual["suggestions"]:
            if type(row) is dict and type(row.get("commitment")) is dict:
                commitment = row["commitment"]
                if "label" in commitment:
                    commitment["label"] = label_key(commitment["label"])
    for case in expected["cases"]:
        if case["caseId"] != case_id:
            continue
        case["questionRoles"] = question_alternatives(case)
        for fields in case["suggestions"]:
            if "commitment.label" in fields:
                fields["commitment.label"] = [label_key(value) for value in fields["commitment.label"]]
    return base.strict.check_draft(case_id, actual, expected)


def evaluate(case_id, stages, contract=None):
    if contract is None:
        contract = suite.verify()[2]
    original = suite.evaluate(case_id, stages, contract)
    if original["status"] != "failed" or "assembled_draft_invalid" in original["reasons"]:
        return original
    draft = e.decode(stages["assembledDraft"].encode())
    reasons = check_draft(case_id, draft, contract)
    return {"status": "failed" if reasons else "passed", "reasons": reasons}


def manifest():
    return {"version": "intake-conformance-oracle-manifest-v5.1", "policy": POLICY,
        "parentV5ManifestSha256": e.digest((suite.HERE / "manifest-v5.json").read_bytes()),
        "parentChecker": "suite_v5.evaluate-delegates-to-oracle_v4_1",
        "inheritedCheckerManifestSha256": e.digest((base.HERE / "manifest.json").read_bytes()),
        "files": {name: e.digest((HERE / name).read_bytes()) for name in
                  ("oracle_v5_1.py", "test_oracle_v5_1.py", "policy.json")}}


def verify():
    suite.verify()
    e.need(e.decode((HERE / "policy.json").read_bytes()) == POLICY)
    e.need(e.decode((HERE / "manifest.json").read_bytes()) == manifest())


def freeze():
    private.private_write(HERE / "policy.json", e.compact(POLICY))
    private.private_write(HERE / "manifest.json", e.compact(manifest()))
    verify()


def compare_report(source, destination):
    verify()
    directory = private.private_directory(destination)
    source = Path(source)
    private.private_directory(source.parent)
    raw = private.private_read(source)
    report = e.decode(raw)
    e.need(report["version"] == "intake-conformance-batch-report-v5" and report["profile"] == suite.PROFILE)
    e.need(report["manifestSha256"] == e.digest((suite.HERE / "manifest-v5.json").read_bytes()))
    e.need(report["classification"] in ("passed", "quality_failure"))
    e.need(report["stopVerified"] is True and report["probeVerified"] is True)
    e.need(report["errorCategory"] is None and report["deadlineBreach"] is False)
    e.need([row["caseId"] for row in report["cases"]] == suite.IDS)
    _, inputs, contract, _ = suite.verify()
    packets = {row["caseId"]: e.digest(row["userPacketJson"].encode()) for row in inputs["cases"]}
    rows = []
    for row in report["cases"]:
        e.need(row["outcome"] in ("response", "empty"))
        e.need(row["rawSha256"] == (None if row["raw"] is None else e.digest(row["raw"].encode())))
        e.need(row["stagesSha256"] == e.digest(e.compact(row["stages"])))
        e.need(row["oracleResultSha256"] == e.digest(e.compact(row["oracle"])))
        rules = next(case for case in contract["cases"] if case["caseId"] == row["caseId"])
        e.need(row["predicatesSha256"] == e.digest(e.compact(rules)))
        e.need(row["packetSha256"] == packets[row["caseId"]])
        e.need(row["oracle"] == suite.evaluate(row["caseId"], row["stages"], contract))
        rows.append({"caseId": row["caseId"], "rawSha256": row["rawSha256"],
            "stagesSha256": row["stagesSha256"], "packetSha256": row["packetSha256"],
            "originalFrozenV5Oracle": row["oracle"],
            "postHocV51Oracle": evaluate(row["caseId"], row["stages"], contract)})
    original = sum(row["originalFrozenV5Oracle"]["status"] == "passed" for row in rows)
    e.need((report["classification"] == "passed") == (original == 8))
    comparison = {"version": VERSION, "sourceReportSha256": e.digest(raw),
        "policyManifestSha256": e.digest((HERE / "manifest.json").read_bytes()),
        "cases": rows, "totalCases": 8, "originalFrozenV5Passes": original,
        "postHocV51Passes": sum(row["postHocV51Oracle"]["status"] == "passed" for row in rows),
        "postHocReevaluation": True, "newAcquisition": False, "newInferenceRequests": 0,
        "historicalResultUnmodified": True, "modelImprovementDemonstrated": False,
        "independentSemanticAssessment": False, "independentHuman": False, "humanAssessment": None,
        "productionAdmission": "closed"}
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
    print(f"Same retained responses: frozen v5 {result['originalFrozenV5Passes']}/8; "
          f"post-hoc v5.1 {result['postHocV51Passes']}/8. Zero new inference.")
