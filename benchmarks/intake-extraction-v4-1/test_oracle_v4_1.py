import copy
import unittest

import oracle_v4_1 as relaxed
from test_oracle_v4 import assembled, stages


class LabelEquivalenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, cls.inputs, cls.contract, cls.fixtures = relaxed.e.verify()

    def draft(self, case_id):
        return assembled(case_id, self.fixtures[case_id], self.inputs)

    def test_all_eight_original_positive_witnesses_still_pass(self):
        for case_id in relaxed.e.IDS:
            self.assertEqual(relaxed.evaluate(case_id, stages(self.draft(case_id)), self.contract)["status"], "passed")

    def test_label_case_variants_pass_without_mutating_input_or_contract(self):
        for case_id in relaxed.e.IDS:
            for change in (str.lower, str.upper, str.swapcase):
                draft = self.draft(case_id)
                for suggestion in draft["suggestions"]:
                    if suggestion["commitment"]: suggestion["commitment"]["label"] = change(suggestion["commitment"]["label"])
                state = stages(draft); before = copy.deepcopy((state, self.contract))
                self.assertEqual(relaxed.evaluate(case_id, state, self.contract)["status"], "passed")
                self.assertEqual((state, self.contract), before)

    def test_meeting_lowercase_fails_old_oracle_but_passes_new(self):
        draft = self.draft("i07"); draft["suggestions"][0]["commitment"]["label"] = "meeting"
        self.assertEqual(relaxed.strict.evaluate("i07", stages(draft), self.contract)["status"], "failed")
        self.assertEqual(relaxed.evaluate("i07", stages(draft), self.contract)["status"], "passed")

    def test_different_label_whitespace_punctuation_and_wrong_type_still_fail(self):
        for label in ("Dinner", "Meeting ", " Meeting", "Meeting!", None, 1, True):
            draft = self.draft("i07"); draft["suggestions"][0]["commitment"]["label"] = label
            self.assertEqual(relaxed.evaluate("i07", stages(draft), self.contract)["status"], "failed")

    def test_all_234_omission_and_wrong_fact_mutations_still_fail(self):
        count = 0
        for case in self.contract["cases"]:
            for index, fields in enumerate(case["suggestions"]):
                for path in fields:
                    for omit in (True, False):
                        draft = self.draft(case["caseId"]); parent = draft["suggestions"][index]
                        keys = path.split(".")
                        for key in keys[:-1]: parent = parent[key]
                        if omit: del parent[keys[-1]]
                        else: parent[keys[-1]] = "unsupported-fact"
                        self.assertEqual(relaxed.evaluate(case["caseId"], stages(draft), self.contract)["status"], "failed")
                        count += 1
        self.assertEqual(count, 234)

    def test_question_subject_and_capitalization_are_not_normalized(self):
        draft = self.draft("i06")
        draft["questions"][0]["text"] = "What are the practice date, start and end times, timezone, and recurrence details?"
        self.assertIn("question_roles", relaxed.evaluate("i06", stages(draft), self.contract)["reasons"])
        draft = self.draft("i06"); draft["questions"][0]["text"] = draft["questions"][0]["text"].upper()
        self.assertIn("question_roles", relaxed.evaluate("i06", stages(draft), self.contract)["reasons"])

    def test_source_text_timezone_identity_and_local_rejection_stay_strict(self):
        for field in ("sourceExcerpt", "commitment.timeZone"):
            draft = self.draft("i07"); parent = draft["suggestions"][0]; keys = field.split(".")
            for key in keys[:-1]: parent = parent[key]
            parent[keys[-1]] = parent[keys[-1]].swapcase()
            self.assertEqual(relaxed.evaluate("i07", stages(draft), self.contract)["status"], "failed")
        draft = self.draft("i07"); draft["intakeId"] = "wrong"
        self.assertEqual(relaxed.evaluate("i07", stages(draft), self.contract)["status"], "failed")
        rejected = stages(self.draft("i07")); rejected["semantic"] = "rejected"
        self.assertEqual(relaxed.evaluate("i07", rejected, self.contract)["status"], "not_run")


if __name__ == "__main__": unittest.main()
