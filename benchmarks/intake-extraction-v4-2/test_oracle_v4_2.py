import copy
from pathlib import Path
import tempfile
import unittest

import oracle_v4_2 as oracle
from test_oracle_v4 import assembled, stages

TEMP_ROOT = (Path(tempfile.gettempdir()) / "opencode").resolve()
TEMP_ROOT = TEMP_ROOT if TEMP_ROOT.is_dir() else None


class ContextualQuestionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, cls.inputs, cls.contract, cls.fixtures = oracle.e.verify()

    def draft(self, case_id="i06"):
        draft = assembled(case_id, self.fixtures[case_id], self.inputs)
        if case_id == "i06":
            draft["questions"][0]["text"] = oracle.PRACTICE_QUESTION
        return draft

    def test_original_eight_positive_fixtures_pass(self):
        for case_id in oracle.e.IDS:
            draft = assembled(case_id, self.fixtures[case_id], self.inputs)
            self.assertEqual(oracle.evaluate(case_id, stages(draft), self.contract)["status"], "passed")

    def test_contextual_alternative_only_changes_new_policy(self):
        state = stages(self.draft())
        before = copy.deepcopy((state, self.contract))
        self.assertEqual(oracle.parent.strict.evaluate("i06", state, self.contract)["status"], "failed")
        self.assertEqual(oracle.parent.evaluate("i06", state, self.contract)["status"], "failed")
        self.assertEqual(oracle.evaluate("i06", state, self.contract)["status"], "passed")
        self.assertEqual((state, self.contract), before)

    def test_label_case_equivalence_is_inherited(self):
        draft = self.draft("i07")
        draft["suggestions"][0]["commitment"]["label"] = "meeting"
        self.assertEqual(oracle.evaluate("i07", stages(draft), self.contract)["status"], "passed")

    def test_all_234_missing_and_wrong_fields_fail_even_with_alternative(self):
        count = 0
        for case in self.contract["cases"]:
            for index, fields in enumerate(case["suggestions"]):
                for path in fields:
                    for omit in (True, False):
                        draft = self.draft(case["caseId"])
                        container = draft["suggestions"][index]
                        keys = path.split(".")
                        for key in keys[:-1]:
                            container = container[key]
                        if omit:
                            del container[keys[-1]]
                        else:
                            container[keys[-1]] = "unsupported-fact"
                        self.assertEqual(oracle.evaluate(case["caseId"], stages(draft), self.contract)["status"], "failed")
                        count += 1
        self.assertEqual(count, 234)

    def test_wrong_subject_missing_details_and_arbitrary_paraphrases_fail(self):
        question = oracle.PRACTICE_QUESTION
        mutations = [question.replace("practice", subject) for subject in ("travel", "meeting", "dinner")]
        mutations += [question.replace(detail, "") for detail in
                      ("date, ", "start and ", "end ", "timezone, and ", "recurrence ")]
        mutations += ["When is practice?", question.upper(), question + " "]
        for text in mutations:
            with self.subTest(question=text):
                draft = self.draft()
                draft["questions"][0]["text"] = text
                self.assertIn("question_roles", oracle.evaluate("i06", stages(draft), self.contract)["reasons"])

    def test_missing_extra_duplicate_or_malformed_questions_fail(self):
        for questions in ([], [{"text": oracle.PRACTICE_QUESTION}] * 2,
                          [{"text": oracle.PRACTICE_QUESTION, "extra": True}], [{"text": None}]):
            draft = self.draft()
            draft["questions"] = questions
            self.assertIn("question_roles", oracle.evaluate("i06", stages(draft), self.contract)["reasons"])

    def test_no_cross_case_or_role_leakage(self):
        for case_id in oracle.e.IDS:
            if case_id == "i06":
                continue
            draft = self.draft(case_id)
            draft["questions"] = [{"text": oracle.PRACTICE_QUESTION}]
            self.assertIn("question_roles", oracle.evaluate(case_id, stages(draft), self.contract)["reasons"])
        contract = copy.deepcopy(self.contract)
        case = next(case for case in contract["cases"] if case["caseId"] == "i06")
        case["questionRoles"][0]["id"] = "travel_unknowns"
        self.assertIn("question_roles", oracle.evaluate("i06", stages(self.draft()), contract)["reasons"])

    def test_source_facts_identity_and_local_gates_remain_strict(self):
        for field in ("sourceExcerpt", "commitment.label", "commitment.startsLocal"):
            draft = self.draft()
            container = draft["suggestions"][0]
            keys = field.split(".")
            for key in keys[:-1]:
                container = container[key]
            container[keys[-1]] = "practice"
            self.assertEqual(oracle.evaluate("i06", stages(draft), self.contract)["status"], "failed")
        draft = self.draft()
        draft["intakeId"] = "wrong"
        self.assertEqual(oracle.evaluate("i06", stages(draft), self.contract)["status"], "failed")
        for stage in ("wire", "binding", "semantic"):
            state = stages(self.draft())
            state[stage] = "rejected"
            self.assertEqual(oracle.evaluate("i06", state, self.contract)["status"], "not_run")
        state = stages(self.draft())
        state["assembledDraft"] = "not JSON"
        self.assertEqual(oracle.evaluate("i06", state, self.contract)["reasons"], ["assembled_draft_invalid"])

    def report(self):
        rows = []
        for case_id in oracle.e.IDS:
            state = stages(self.draft(case_id))
            raw_draft = copy.deepcopy(self.fixtures[case_id])
            if case_id == "i06":
                raw_draft["questions"][0]["text"] = oracle.PRACTICE_QUESTION
            raw = oracle.e.compact(raw_draft).decode()
            result = oracle.parent.strict.evaluate(case_id, state, self.contract)
            rules = next(case for case in self.contract["cases"] if case["caseId"] == case_id)
            rows.append({"caseId": case_id, "outcome": "response", "raw": raw,
                         "rawSha256": oracle.e.digest(raw.encode()), "stages": state,
                         "stagesSha256": oracle.e.digest(oracle.e.compact(state)), "oracle": result,
                         "oracleResultSha256": oracle.e.digest(oracle.e.compact(result)),
                         "predicatesSha256": oracle.e.digest(oracle.e.compact(rules))})
        return {"version": "intake-conformance-batch-report-v4", "classification": "quality_failure",
                "stopVerified": True, "probeVerified": True, "errorCategory": None,
                "deadlineBreach": False, "cases": rows}

    def test_private_comparison_preserves_source_and_all_three_scores(self):
        private = oracle.parent.replay.private
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as temporary:
            source = Path(temporary) / "source.json"
            private.private_write(source, oracle.e.compact(self.report()))
            before = source.read_bytes()
            result = oracle.compare_report(source, Path(temporary))
            self.assertEqual([result[key] for key in ("originalV4Passes", "v4_1Passes", "v4_2Passes")], [7, 7, 8])
            self.assertTrue(result["postHocReevaluation"])
            self.assertEqual(result["newInferenceRequests"], 0)
            self.assertEqual(source.read_bytes(), before)
            self.assertEqual((Path(temporary) / "comparison.json").stat().st_mode & 0o777, 0o600)
            with self.assertRaises(Exception):
                oracle.compare_report(source, Path(temporary))

    def test_incomplete_tampered_or_misclassified_reports_refused(self):
        mutations = []
        for field, value in (("classification", "incomplete"), ("classification", "passed"),
                             ("stopVerified", False), ("deadlineBreach", True)):
            report = self.report()
            report[field] = value
            mutations.append(report)
        for field in ("rawSha256", "stagesSha256", "oracleResultSha256", "predicatesSha256"):
            report = self.report()
            report["cases"][0][field] = "tampered"
            mutations.append(report)
        report = self.report()
        report["cases"].pop()
        mutations.append(report)
        for report in mutations:
            with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as temporary:
                source = Path(temporary) / "source.json"
                oracle.parent.replay.private.private_write(source, oracle.e.compact(report))
                with self.assertRaises(Exception):
                    oracle.compare_report(source, Path(temporary))
                self.assertFalse((Path(temporary) / "comparison.json").exists())


if __name__ == "__main__":
    unittest.main()
