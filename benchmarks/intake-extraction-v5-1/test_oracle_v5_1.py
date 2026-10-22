import copy
from pathlib import Path
import tempfile
import unittest

import oracle_v5_1 as oracle

TEMP_ROOT = (Path(tempfile.gettempdir()) / "opencode").resolve()
TEMP_ROOT = TEMP_ROOT if TEMP_ROOT.is_dir() else None


def stages(draft):
    return {"wire": "accepted", "binding": "accepted", "semantic": "accepted", "reason": None,
            "assembledDraft": oracle.e.compact(draft).decode()}


class WordingPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, cls.inputs, cls.contract, cls.fixtures = oracle.suite.verify()

    def draft(self, case_id="u03", revised_wording=True):
        draft = oracle.suite.assembled(case_id, self.fixtures[case_id], self.inputs)
        if revised_wording and case_id == "u03":
            draft["suggestions"][0]["commitment"]["label"] = "Rehearsal on Tuesday evening is tentative"
            draft["questions"][0]["text"] = oracle.CARRIER.format(activity="practice")
        return draft

    def case(self, case_id="u03"):
        return copy.deepcopy(next(case for case in self.contract["cases"] if case["caseId"] == case_id))

    def test_original_eight_positives_and_all_declared_alternatives(self):
        for case in self.contract["cases"]:
            case_id = case["caseId"]
            self.assertEqual(oracle.evaluate(case_id, stages(self.draft(case_id, False)), self.contract)["status"], "passed")
            for index, fields in enumerate(case["suggestions"]):
                for path, values in fields.items():
                    for value in values:
                        draft = self.draft(case_id, False)
                        container = draft["suggestions"][index]
                        keys = path.split(".")
                        for key in keys[:-1]:
                            container = container[key]
                        container[keys[-1]] = copy.deepcopy(value)
                        self.assertEqual(oracle.evaluate(case_id, stages(draft), self.contract)["status"], "passed")
            for role in case["questionRoles"]:
                for text in role["texts"]:
                    draft = self.draft(case_id, False)
                    draft["questions"] = [{"text": text}]
                    self.assertEqual(oracle.evaluate(case_id, stages(draft), self.contract)["status"], "passed")

    def test_each_original_u03_failure_and_combined_correction(self):
        draft = self.draft()
        original = oracle.suite.evaluate("u03", stages(draft), self.contract)
        self.assertEqual(original["reasons"], ["question_roles", "suggestion_0.commitment.label"])
        label_fixed = copy.deepcopy(draft)
        label_fixed["suggestions"][0]["commitment"]["label"] = "A rehearsal on Tuesday evening is tentative"
        self.assertEqual(oracle.suite.evaluate("u03", stages(label_fixed), self.contract)["reasons"], ["question_roles"])
        question_fixed = copy.deepcopy(draft)
        question_fixed["questions"][0]["text"] = oracle.CARRIER.format(activity="rehearsal")
        self.assertEqual(oracle.suite.evaluate("u03", stages(question_fixed), self.contract)["reasons"], ["suggestion_0.commitment.label"])
        self.assertEqual(oracle.evaluate("u03", stages(draft), self.contract), {"status": "passed", "reasons": []})

    def test_single_article_casefold_is_generic_and_comparison_only(self):
        before = copy.deepcopy(self.contract)
        for label in self.case()["suggestions"][0]["commitment.label"]:
            for variant in (label, label.removeprefix("A "), label.upper(), label.removeprefix("A ").swapcase()):
                draft = self.draft()
                draft["suggestions"][0]["commitment"]["label"] = variant
                state = stages(draft)
                original = copy.deepcopy(state)
                self.assertEqual(oracle.evaluate("u03", state, self.contract)["status"], "passed")
                self.assertEqual(state, original)
        self.assertEqual(oracle.label_key("AN Appointment"), "appointment")
        appointment = self.draft("u01")
        appointment["suggestions"][0]["commitment"]["label"] = "AN Appointment"
        self.assertEqual(oracle.evaluate("u01", stages(appointment), self.contract)["status"], "passed")
        self.assertEqual(oracle.label_key("A a rehearsal"), "a rehearsal")
        case = self.case()
        case["caseId"] = "synthetic-independent-id"
        self.assertEqual(oracle.evaluate(case["caseId"], stages(self.draft()), {"cases": [case]})["status"], "passed")
        self.assertEqual(self.contract, before)

    def test_label_wrong_words_whitespace_articles_types_and_punctuation_fail(self):
        for label in (" Rehearsal on Tuesday evening is tentative", "Rehearsal on Tuesday evening is tentative ",
                      "A A rehearsal on Tuesday evening is tentative", "The rehearsal on Tuesday evening is tentative",
                      "Rehearsal on evening is tentative", "Training on Tuesday evening is tentative",
                      "Travel on Tuesday evening is tentative", "Rehearsal on Tuesday evening is tentative!", None, True):
            draft = self.draft()
            draft["suggestions"][0]["commitment"]["label"] = label
            self.assertIn("suggestion_0.commitment.label", oracle.evaluate("u03", stages(draft), self.contract)["reasons"])

    def test_practice_alias_requires_role_and_is_not_transitive(self):
        for case_id, canonical, wrong in (("u02", "training", "rehearsal"), ("u03", "rehearsal", "training")):
            for noun in (canonical, "practice"):
                draft = self.draft(case_id)
                draft["questions"][0]["text"] = oracle.CARRIER.format(activity=noun)
                self.assertEqual(oracle.evaluate(case_id, stages(draft), self.contract)["status"], "passed")
            for noun in (wrong, "travel", "meeting"):
                draft = self.draft(case_id)
                draft["questions"][0]["text"] = oracle.CARRIER.format(activity=noun)
                self.assertIn("question_roles", oracle.evaluate(case_id, stages(draft), self.contract)["reasons"])

    def test_missing_details_invented_date_advice_and_extra_questions_fail(self):
        text = oracle.CARRIER.format(activity="practice")
        variants = [text.replace(detail, "") for detail in
                    ("date, ", "start and ", "end ", "timezone, and ", "recurrence ")]
        variants += [text + " It is on 2027-01-01.", text + " You should attend.", "When is practice?"]
        for question in variants:
            draft = self.draft()
            draft["questions"] = [{"text": question}]
            self.assertIn("question_roles", oracle.evaluate("u03", stages(draft), self.contract)["reasons"])
        for questions in ([], [{"text": text}] * 2, [{"text": text, "other": True}]):
            draft["questions"] = questions
            self.assertIn("question_roles", oracle.evaluate("u03", stages(draft), self.contract)["reasons"])

    def test_ambiguity_and_unsupported_expected_targets_get_no_new_alias(self):
        original = self.case()
        alias = oracle.CARRIER.format(activity="practice")
        self.assertIn(alias, oracle.question_alternatives(original)[0]["texts"])
        variants = []
        duplicate_role = copy.deepcopy(original)
        duplicate_role["questionRoles"] *= 2
        variants.append(duplicate_role)
        second_unknown = copy.deepcopy(original)
        second_unknown["suggestions"].append(copy.deepcopy(original["suggestions"][0]))
        variants.append(second_unknown)
        for source in ("Training on a known date.", "A rehearsal on a known date.", "Practice on a known date."):
            another = copy.deepcopy(original)
            known = copy.deepcopy(self.case("u01")["suggestions"][0])
            known["sourceExcerpt"] = [source]
            another["suggestions"].append(known)
            variants.append(another)
        for mutate in (lambda c: c["questionRoles"][0].update(id="unsupported_role"),
                       lambda c: c["suggestions"][0].update(sourceExcerpt=["Travel on Tuesday."]),
                       lambda c: c["suggestions"][0].update({"commitment.startsLocal": ["2026-11-01T12:00"]}),
                       lambda c: c["suggestions"][0].update(unresolvedFields=[["startsLocal"]]),
                       lambda c: c["questionRoles"][0].update(texts=["When is rehearsal?"])):
            variant = copy.deepcopy(original)
            mutate(variant)
            variants.append(variant)
        for case in variants:
            self.assertTrue(all(alias not in role["texts"] for role in oracle.question_alternatives(case)))
        self.assertNotIn(alias, original["questionRoles"][0]["texts"])

    def test_all_field_omissions_and_wrong_values_still_fail(self):
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
                        self.assertEqual(oracle.evaluate(case["caseId"], stages(draft), self.contract)["status"], "failed")
                        count += 1
        self.assertGreater(count, 200)

    def test_invented_unknown_facts_missing_duplicate_disclosures_and_suggestion_fail(self):
        for field, value in (("startsLocal", "2026-11-01T12:00"), ("endsLocal", "2026-11-01T13:00"),
                             ("timeZone", "UTC"), ("recurrence", {"frequency": "one_off"})):
            draft = self.draft()
            draft["suggestions"][0]["commitment"][field] = value
            self.assertEqual(oracle.evaluate("u03", stages(draft), self.contract)["status"], "failed")
        for field in oracle.UNKNOWN:
            for duplicate in (False, True):
                draft = self.draft()
                fields = draft["suggestions"][0]["unresolvedFields"]
                fields.append(field) if duplicate else fields.remove(field)
                self.assertEqual(oracle.evaluate("u03", stages(draft), self.contract)["status"], "failed")
        for mutate in (lambda d: d.update(intakeId="wrong"), lambda d: d.update(suggestions=[]),
                       lambda d: d["suggestions"][0].update(sourceExcerpt="Changed source")):
            draft = self.draft()
            mutate(draft)
            self.assertEqual(oracle.evaluate("u03", stages(draft), self.contract)["status"], "failed")

    def test_local_and_decode_gates_remain_strict(self):
        for stage in ("wire", "binding", "semantic"):
            state = stages(self.draft())
            state[stage] = "rejected"
            self.assertEqual(oracle.evaluate("u03", state, self.contract)["status"], "not_run")
        state = stages(self.draft())
        state["assembledDraft"] = "invalid JSON"
        self.assertEqual(oracle.evaluate("u03", state, self.contract)["reasons"], ["assembled_draft_invalid"])


class ComparisonReportTest(unittest.TestCase):
    def report(self):
        _, inputs, contract, fixtures = oracle.suite.verify()
        cases = []
        for case_id in oracle.suite.IDS:
            raw_draft = copy.deepcopy(fixtures[case_id])
            if case_id == "u03":
                raw_draft["suggestions"][0]["commitment"]["label"] = "Rehearsal on Tuesday evening is tentative"
                raw_draft["questions"][0]["text"] = oracle.CARRIER.format(activity="practice")
            raw = oracle.e.compact(raw_draft).decode()
            state = stages(oracle.suite.assembled(case_id, raw_draft, inputs))
            verdict = oracle.suite.evaluate(case_id, state, contract)
            rules = next(row for row in contract["cases"] if row["caseId"] == case_id)
            packet = next(row["userPacketJson"] for row in inputs["cases"] if row["caseId"] == case_id)
            cases.append({"caseId": case_id, "outcome": "response", "raw": raw,
                "rawSha256": oracle.e.digest(raw.encode()), "stages": state,
                "stagesSha256": oracle.e.digest(oracle.e.compact(state)), "oracle": verdict,
                "oracleResultSha256": oracle.e.digest(oracle.e.compact(verdict)),
                "predicatesSha256": oracle.e.digest(oracle.e.compact(rules)), "packetSha256": oracle.e.digest(packet.encode())})
        return {"version": "intake-conformance-batch-report-v5", "profile": oracle.suite.PROFILE,
            "manifestSha256": oracle.e.digest((oracle.suite.HERE / "manifest-v5.json").read_bytes()),
            "classification": "quality_failure", "stopVerified": True, "probeVerified": True,
            "deadlineBreach": False, "errorCategory": None, "cases": cases}

    def test_all_eight_comparison_is_private_post_hoc_and_exclusive(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as temporary:
            directory = Path(temporary)
            source = directory / "source.json"
            raw = oracle.e.compact(self.report())
            oracle.private.private_write(source, raw)
            result = oracle.compare_report(source, directory)
            self.assertEqual((result["originalFrozenV5Passes"], result["postHocV51Passes"]), (7, 8))
            self.assertTrue(result["postHocReevaluation"])
            self.assertEqual(result["newInferenceRequests"], 0)
            self.assertFalse(result["modelImprovementDemonstrated"])
            self.assertEqual(source.read_bytes(), raw)
            self.assertEqual((directory / "comparison.json").stat().st_mode & 0o777, 0o600)
            self.assertEqual([row["caseId"] for row in result["cases"]], oracle.suite.IDS)
            self.assertEqual([row["caseId"] for row in result["cases"] if row["originalFrozenV5Oracle"] != row["postHocV51Oracle"]], ["u03"])
            with self.assertRaises(FileExistsError):
                oracle.compare_report(source, directory)

    def test_tampered_incomplete_wrong_suite_and_partial_reports_refused(self):
        mutations = []
        for field, value in (("version", "intake-conformance-batch-report-v4"),
                             ("classification", "incomplete"), ("classification", "passed"),
                             ("stopVerified", False), ("manifestSha256", "0" * 64)):
            report = self.report()
            report[field] = value
            mutations.append(report)
        for field in ("rawSha256", "stagesSha256", "oracleResultSha256", "predicatesSha256", "packetSha256"):
            report = self.report()
            report["cases"][0][field] = "0" * 64
            mutations.append(report)
        report = self.report()
        report["cases"].pop()
        mutations.append(report)
        for report in mutations:
            with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as temporary:
                directory = Path(temporary)
                source = directory / "source.json"
                oracle.private.private_write(source, oracle.e.compact(report))
                with self.assertRaises(ValueError):
                    oracle.compare_report(source, directory)
                self.assertFalse((directory / "comparison.json").exists())


if __name__ == "__main__":
    unittest.main()
