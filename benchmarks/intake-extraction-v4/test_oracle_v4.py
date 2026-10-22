import copy
import unittest

import experiment_v4 as e
import oracle_v4 as oracle


def assembled(case_id, raw, inputs):
    packet = e.decode(next(c for c in inputs["cases"] if c["caseId"] == case_id)["userPacketJson"].encode())
    draft = copy.deepcopy(raw)
    draft.update(profile="context-intake-suggestion-v1", intakeId=packet["intake"]["intakeId"], intakeRevision=packet["intake"]["revision"])
    text = packet["intake"]["content"]
    for s in draft["suggestions"]:
        start = text.index(s["sourceExcerpt"])
        s.update(sourceStart=len(text[:start].encode("utf-16-le")) // 2,
                 sourceEnd=len(text[:start + len(s["sourceExcerpt"])].encode("utf-16-le")) // 2)
    return draft


def stages(draft):
    return {"wire": "accepted", "binding": "accepted", "semantic": "accepted", "reason": None,
            "assembledDraft": e.compact(draft).decode()}


class OracleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.protocol, cls.inputs, cls.contract, cls.fixtures = e.documents()

    def draft(self, index):
        case_id = e.IDS[index]
        return assembled(case_id, self.fixtures[case_id], self.inputs)

    def test_all_eight_handwritten_positive_witnesses(self):
        for i, case_id in enumerate(e.IDS):
            with self.subTest(case=case_id):
                self.assertEqual(oracle.evaluate(case_id, stages(self.draft(i)), self.contract), {"status": "passed", "reasons": []})
        self.assertFalse(self.protocol["humanScoringRequired"])
        self.assertFalse(self.protocol["independentHuman"])
        self.assertEqual(self.protocol["requiredPassingCases"], 8)

    def test_every_declared_field_rejects_omission_and_wrong_value(self):
        mutations = 0
        for i, case in enumerate(self.contract["cases"]):
            for j, fields in enumerate(case["suggestions"]):
                for path in fields:
                    for remove in (False, True):
                        draft = self.draft(i); parent = draft["suggestions"][j]
                        keys = path.split(".")
                        for key in keys[:-1]: parent = parent[key]
                        if remove: del parent[keys[-1]]
                        else: parent[keys[-1]] = "unsupported-v4-mutation"
                        with self.subTest(case=case["caseId"], field=path, remove=remove):
                            self.assertEqual(oracle.evaluate(case["caseId"], stages(draft), self.contract)["status"], "failed")
                        mutations += 1
        self.assertEqual(mutations, 234)

    def test_wrong_identity_missing_extra_and_duplicate_suggestions_fail(self):
        for i, case_id in enumerate(e.IDS):
            for field, value in (("profile", "wrong"), ("intakeId", "wrong"), ("intakeRevision", True)):
                draft = self.draft(i); draft[field] = value
                self.assertEqual(oracle.evaluate(case_id, stages(draft), self.contract)["status"], "failed")
            draft = self.draft(i); draft["suggestions"] = []
            self.assertEqual(oracle.evaluate(case_id, stages(draft), self.contract)["status"], "failed")
            draft = self.draft(i); draft["suggestions"].append(copy.deepcopy(draft["suggestions"][0]))
            self.assertEqual(oracle.evaluate(case_id, stages(draft), self.contract)["status"], "failed")

    def test_order_aliases_and_explicit_zero_seconds_are_comparisons_only(self):
        for i, case_id in enumerate(e.IDS):
            draft = self.draft(i)
            draft["suggestions"].reverse()
            for s in draft["suggestions"]:
                s["unresolvedFields"].reverse()
                if s["commitment"]:
                    for key in ("startsLocal", "endsLocal"):
                        if s["commitment"][key]: s["commitment"][key] += ":00"
            if draft["questions"]:
                draft["questions"][0]["text"] = self.contract["cases"][i]["questionRoles"][0]["texts"][1]
            before = e.compact(draft)
            self.assertEqual(oracle.evaluate(case_id, stages(draft), self.contract)["status"], "passed")
            self.assertEqual(e.compact(draft), before)

    def test_every_predeclared_field_alternative_and_duplicate_facts(self):
        for i, case in enumerate(self.contract["cases"]):
            for j, fields in enumerate(case["suggestions"]):
                for path, alternatives in fields.items():
                    for alternative in alternatives:
                        draft = self.draft(i); parent = draft["suggestions"][j]
                        keys = path.split(".")
                        for key in keys[:-1]: parent = parent[key]
                        parent[keys[-1]] = copy.deepcopy(alternative)
                        self.assertEqual(oracle.evaluate(case["caseId"], stages(draft), self.contract)["status"], "passed")
            if len(case["suggestions"]) > 1:
                draft = self.draft(i); draft["suggestions"][1] = copy.deepcopy(draft["suggestions"][0])
                self.assertEqual(oracle.evaluate(case["caseId"], stages(draft), self.contract)["status"], "failed")

    def test_excerpt_alone_does_not_cover_missing_fact_negation_or_attribution(self):
        for i, bad in ((0, "no running"), (1, "Illness ended on 2026-10-05."),
                       (2, "The first coach was wrong; run."), (7, "Readiness score 90")):
            draft = self.draft(i); draft["suggestions"][0]["context"]["content"] = bad
            self.assertEqual(oracle.evaluate(e.IDS[i], stages(draft), self.contract)["status"], "failed")

    def test_wire_valid_wrong_kind_and_unnecessary_questions_fail(self):
        for i in (3, 4, 6):
            draft = self.draft(i); draft["suggestions"][0]["kind"] = "context"
            self.assertEqual(oracle.evaluate(e.IDS[i], stages(draft), self.contract)["status"], "failed")
        for i, case_id in enumerate(e.IDS):
            draft = self.draft(i); draft["questions"] = [{"text": "What timezone or recurrence interval was provided?"}]
            self.assertEqual(oracle.evaluate(case_id, stages(draft), self.contract)["status"], "failed")

    def test_unresolved_sets_remain_unique_and_types_strict(self):
        draft = self.draft(0); draft["suggestions"][0]["unresolvedFields"] *= 2
        self.assertEqual(oracle.evaluate("i01", stages(draft), self.contract)["status"], "failed")
        draft = self.draft(4); draft["suggestions"][0]["commitment"]["recurrence"]["interval"] = 2.0
        self.assertEqual(oracle.evaluate("i05", stages(draft), self.contract)["status"], "failed")

    def test_local_rejection_never_runs_case_predicates_and_bad_assembled_fails(self):
        good = stages(self.draft(0))
        for stage in ("wire", "binding", "semantic"):
            bad = dict(good, **{stage: "rejected"})
            self.assertEqual(oracle.evaluate("i01", bad, self.contract)["status"], "not_run")
        for raw in (None, "", "{}", "x" * 12289, '{"a":1,"a":2}'):
            self.assertEqual(oracle.evaluate("i01", dict(good, assembledDraft=raw), self.contract)["status"], "failed")


if __name__ == "__main__": unittest.main()
