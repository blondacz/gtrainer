import copy
import unittest

import suite_v5 as suite


def stages(draft):
    return {"wire": "accepted", "binding": "accepted", "semantic": "accepted", "reason": None,
            "assembledDraft": suite.e.compact(draft).decode()}


class ProspectiveSuiteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.protocol, cls.inputs, cls.contract, cls.fixtures = suite.documents()

    def draft(self, case_id):
        return suite.assembled(case_id, self.fixtures[case_id], self.inputs)

    def test_eight_new_packets_schema_prompt_and_limits(self):
        old = suite.e.verify()[1]
        self.assertEqual(self.inputs["schema"], old["schema"])
        self.assertEqual([r["caseId"] for r in self.inputs["cases"]], suite.IDS)
        self.assertTrue(set(suite.IDS).isdisjoint(suite.e.IDS))
        old_contents = {suite.e.decode(r["userPacketJson"].encode())["intake"]["content"] for r in old["cases"]}
        self.assertTrue(all(suite.e.decode(r["userPacketJson"].encode())["intake"]["content"] not in old_contents for r in self.inputs["cases"]))
        self.assertEqual(self.protocol["maximumBatches"], 1)
        self.assertEqual(self.protocol["maximumScoredLaunches"], 8)
        self.assertFalse(self.protocol["retriesPermitted"])

    def test_all_eight_witnesses_and_input_immutability(self):
        before = copy.deepcopy(self.contract)
        for case_id in suite.IDS:
            self.assertEqual(suite.evaluate(case_id, stages(self.draft(case_id)), self.contract)["status"], "passed")
        self.assertEqual(self.contract, before)

    def test_every_field_rejects_omission_and_wrong_value(self):
        count = 0
        for case in self.contract["cases"]:
            for index, fields in enumerate(case["suggestions"]):
                for path in fields:
                    for omit in (True, False):
                        draft = self.draft(case["caseId"])
                        parent = draft["suggestions"][index]
                        keys = path.split(".")
                        for key in keys[:-1]:
                            parent = parent[key]
                        if omit:
                            del parent[keys[-1]]
                        else:
                            parent[keys[-1]] = "unsupported-fact"
                        self.assertEqual(suite.evaluate(case["caseId"], stages(draft), self.contract)["status"], "failed")
                        count += 1
        self.assertGreater(count, 200)

    def test_all_declared_alternatives_and_label_casefold(self):
        for case in self.contract["cases"]:
            for role in case["questionRoles"]:
                for text in role["texts"]:
                    draft = self.draft(case["caseId"])
                    draft["questions"] = [{"text": text}]
                    self.assertEqual(suite.evaluate(case["caseId"], stages(draft), self.contract)["status"], "passed")
            for index, fields in enumerate(case["suggestions"]):
                for path, values in fields.items():
                    for value in values:
                        draft = self.draft(case["caseId"])
                        parent = draft["suggestions"][index]
                        keys = path.split(".")
                        for key in keys[:-1]:
                            parent = parent[key]
                        parent[keys[-1]] = copy.deepcopy(value)
                        self.assertEqual(suite.evaluate(case["caseId"], stages(draft), self.contract)["status"], "passed")
            draft = self.draft(case["caseId"])
            for row in draft["suggestions"]:
                if row["commitment"]:
                    row["commitment"]["label"] = row["commitment"]["label"].swapcase()
            self.assertEqual(suite.evaluate(case["caseId"], stages(draft), self.contract)["status"], "passed")

    def test_wrong_subject_missing_detail_cross_case_and_extra_questions(self):
        text = suite.corpus_v5.QUESTIONS["u02"][1][0]
        for question in (text.replace("training", "travel"), text.replace("recurrence ", ""),
                         suite.corpus_v5.QUESTIONS["u03"][1][0], "When is practice?"):
            draft = self.draft("u02")
            draft["questions"] = [{"text": question}]
            self.assertIn("question_roles", suite.evaluate("u02", stages(draft), self.contract)["reasons"])
        draft["questions"] = [{"text": text}] * 2
        self.assertIn("question_roles", suite.evaluate("u02", stages(draft), self.contract)["reasons"])

    def test_stale_correction_wrong_identity_source_kind_and_duplicate_fields(self):
        draft = self.draft("u04")
        self.assertEqual(draft["suggestions"][0]["sourceStart"], 0)
        self.assertGreater(draft["suggestions"][0]["sourceEnd"], len(draft["suggestions"][0]["sourceExcerpt"]))
        draft["suggestions"][0]["commitment"]["startsLocal"] = "2026-11-05T15:30"
        self.assertEqual(suite.evaluate("u04", stages(draft), self.contract)["status"], "failed")
        for mutate in (lambda d: d.update(intakeId="wrong"),
                       lambda d: d["suggestions"][0].update(sourceExcerpt="different source"),
                       lambda d: d["suggestions"][0].update(kind="context"),
                       lambda d: d["suggestions"][1]["unresolvedFields"].append("startsLocal")):
            draft = self.draft("u02")
            mutate(draft)
            self.assertEqual(suite.evaluate("u02", stages(draft), self.contract)["status"], "failed")

    def test_local_rejection_and_unsupported_shape_never_pass(self):
        for field in ("wire", "binding", "semantic"):
            state = stages(self.draft("u01"))
            state[field] = "rejected"
            self.assertEqual(suite.evaluate("u01", state, self.contract)["status"], "not_run")
        state = stages(self.draft("u01"))
        state["assembledDraft"] = "invalid JSON"
        self.assertEqual(suite.evaluate("u01", state, self.contract)["status"], "failed")


if __name__ == "__main__":
    unittest.main()
