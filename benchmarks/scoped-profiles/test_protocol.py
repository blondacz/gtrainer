import copy
import json
import tempfile
import unittest
from pathlib import Path

import protocol


class FrozenProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.definition, cls.artifacts = protocol.verify()

    def test_complete_profile_membership_and_frozen_bytes(self):
        self.assertEqual(len(protocol.validate_documents(self.definition, self.artifacts)), 28)
        self.assertEqual(len(self.artifacts["guards"]), 3)
        self.assertEqual([p["caseCount"] for p in self.definition["profiles"]], [12, 8, 8])
        self.assertFalse(self.definition["modelCallsPermitted"])

    def test_packet_first_correction_binding_and_membership_tampering_fail(self):
        changes = [
            lambda a: a["cases"].pop(),
            lambda a: a["cases"].append(a["cases"][0]),
            lambda a: a["cases"][0].update(profile="connected-review-v1"),
            lambda a: a["cases"][0].update(consentDigest="0" * 64),
            lambda a: a["cases"][0]["exactPrompt"].update(systemInstructions="altered"),
            lambda a: a["cases"][0]["exactCorrectionPrompt"].update(systemInstructions="altered"),
            lambda a: a["cases"][0]["exactPrompt"].update(userPacketJson="{}"),
            lambda a: a["guards"].pop(),
            lambda a: a["guards"][0].update(modelCalls=1),
        ]
        for change in changes:
            with self.subTest(change=changes.index(change)):
                artifacts = copy.deepcopy(self.artifacts)
                change(artifacts)
                with self.assertRaises((ValueError, KeyError)):
                    protocol.validate_documents(self.definition, artifacts)

    def test_rubric_changes_and_missing_expectations_fail(self):
        changes = [lambda p: p["gates"].update(minimumEveryCaseDimension=1),
                   lambda p: p["gates"].update(weightedMeanPercent=79),
                   lambda p: p["profiles"][0].update(caseCount=11),
                   lambda p: p["profiles"][0]["dimensions"][0].update(weight=1),
                   lambda p: p["profiles"][0]["cases"][0].update(expect=""),
                   lambda p: p.update(modelCallsPermitted=True)]
        for change in changes:
            definition = copy.deepcopy(self.definition)
            change(definition)
            with self.assertRaises(ValueError):
                protocol.validate_documents(definition, self.artifacts)

    def test_manifest_rejects_changed_artifact_rubric_source_and_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in protocol.PINNED + ["benchmarks/scoped-profiles/manifest-v1.json"]:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((protocol.ROOT / name).read_bytes())
            protocol.verify(root)
            for name in ("benchmarks/scoped-profiles/artifacts-v1.json", "benchmarks/scoped-profiles/protocols-v1.json",
                         "backend/src/test/kotlin/com/gtrainer/ScopedEvaluationFixtures.kt"):
                target = root / name
                original = target.read_bytes()
                target.write_bytes(original + b" ")
                with self.assertRaisesRegex(ValueError, "hash differs"):
                    protocol.verify(root)
                target.write_bytes(original)
            manifest_path = root / "benchmarks/scoped-profiles/manifest-v1.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["files"]["../../outside"] = "0" * 64
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "membership"):
                protocol.verify(root)

    def test_duplicate_json_nonfinite_and_oversized_artifact_fail(self):
        for raw in (b'{"x":1,"x":2}', b'{"x":NaN}', b" " * (4 * 1024 * 1024 + 1)):
            with self.assertRaises(ValueError):
                protocol.decode(raw)

    def rows(self, profile, score=4):
        return [{"caseId": c["id"], "outcome": "accepted", "usefulFinal": True,
                 "scores": {d["id"]: score for d in profile["dimensions"]}, "criticalFailures": []}
                for c in profile["cases"]]

    def test_exact_weighted_threshold_and_independent_human_gate(self):
        profile = self.definition["profiles"][0]
        rows = self.rows(profile, 3)
        dimension = next(d["id"] for d in profile["dimensions"] if d["weight"] == 20)
        for row in rows:
            row["scores"][dimension] = 4
        result = protocol.rubric_result(profile, rows, independent_human=True)
        self.assertEqual(result["weightedMeanPercent"], 80)
        self.assertTrue(result["meetsFrozenRubric"])
        self.assertEqual(result["productionAdmission"], "closed")
        self.assertFalse(protocol.rubric_result(profile, rows)["meetsFrozenRubric"])
        self.assertFalse(protocol.rubric_result(profile, self.rows(profile, 3), True)["meetsFrozenRubric"])

    def test_no_profile_can_drop_empty_failed_unrun_or_unscored_cases(self):
        for profile in self.definition["profiles"]:
            self.assertTrue(protocol.rubric_result(profile, self.rows(profile), True)["meetsFrozenRubric"])
            missing = protocol.rubric_result(profile, self.rows(profile)[:-1], True)
            self.assertFalse(missing["meetsFrozenRubric"])
            self.assertEqual(missing["outcomes"][profile["cases"][-1]["id"]], "unrun")
            for outcome in ("rejected", "timeout", "empty", "incomplete", "unrun"):
                rows = self.rows(profile)
                rows[0]["outcome"] = outcome
                self.assertFalse(protocol.rubric_result(profile, rows, True)["meetsFrozenRubric"])
            for defect in ("minimum", "critical", "useful", "unscored"):
                rows = self.rows(profile)
                if defect == "minimum":
                    rows[0]["scores"][profile["dimensions"][0]["id"]] = 1
                elif defect == "critical":
                    rows[0]["criticalFailures"] = ["fabricated_or_misbound_fact"]
                elif defect == "useful":
                    rows[0]["usefulFinal"] = False
                else:
                    rows[0]["scores"].pop(profile["dimensions"][0]["id"])
                self.assertFalse(protocol.rubric_result(profile, rows, True)["meetsFrozenRubric"])

    def test_invalid_scores_duplicates_and_overwrite_refused(self):
        profile = self.definition["profiles"][0]
        for score in (-1, 5, True, 3.5, "4"):
            with self.assertRaises(ValueError):
                protocol.rubric_result(profile, self.rows(profile, score), True)
        rows = self.rows(profile)
        with self.assertRaises(ValueError):
            protocol.rubric_result(profile, rows + [rows[0]], True)
        with self.assertRaisesRegex(ValueError, "already exist"):
            protocol.freeze(Path("unused-no-read"))


if __name__ == "__main__":
    unittest.main()
