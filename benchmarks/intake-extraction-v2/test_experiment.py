import copy
import os
from pathlib import Path
import shutil
import tempfile
import unittest

import experiment as e


class ExperimentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        generated = {e.PREFIX + n for n in ("protocol-v2.json", "artifacts-v2.json")}
        names = set(e.PINNED) - generated
        names.update(e.baseline.PINNED)
        names.add("benchmarks/scoped-profiles/manifest-v1.json")
        for name in names:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(e.ROOT / name, target)
        self.export = Path(os.environ.get("INTAKE_V2_EXPORT_DIR", str(e.HERE)))
        e.freeze(self.export, self.root)
        self.protocol, self.artifacts = e.verify(self.root)

    def check_documents(self, protocol, artifacts):
        original, original_artifacts = e.baseline.verify(self.root)
        return e.validate_documents(protocol, artifacts, original, original_artifacts,
            (self.root / "benchmarks/scoped-profiles/manifest-v1.json").read_bytes())

    def test_all_eight_inputs_and_expectations_stay_exact(self):
        original, old_artifacts = e.baseline.verify(self.root)
        profile = next(p for p in original["profiles"] if p["profile"] == e.BASELINE_PROFILE)
        self.assertEqual(self.protocol["cases"], profile["cases"])
        self.assertEqual(self.protocol["dimensions"], profile["dimensions"])
        self.assertEqual(self.protocol["generationPolicy"]["maximumAttemptsPerCase"], 1)
        old = {c["caseId"]: c for c in old_artifacts["cases"]}
        for case in self.artifacts["cases"]:
            self.assertEqual(case["exactPrompt"]["userPacketJson"], old[case["caseId"]]["exactPrompt"]["userPacketJson"])
        self.assertEqual([c["caseId"] for c in self.artifacts["cases"]], e.IDS)

    def test_changed_sources_and_inputs_fail_integrity(self):
        for name in (e.PREFIX + "artifacts-v2.json", "backend/src/test/kotlin/com/gtrainer/IntakeExtractionV2.kt"):
            path = self.root / name
            before = path.read_bytes()
            path.write_bytes(before + b" ")
            with self.assertRaises(ValueError):
                e.verify(self.root)
            path.write_bytes(before)

    def test_protocol_weakening_or_filtering_is_refused(self):
        for key, value in (("caseIds", e.IDS[:-1]), ("caseCount", 8.0), ("cases", []),
                           ("gates", {}), ("dimensions", []),
                           ("generationPolicy", {"maximumAttemptsPerCase": 2})):
            changed = dict(self.protocol, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check_documents(changed, self.artifacts)

    def test_modified_packet_order_or_schema_is_refused(self):
        for mutate in (lambda a: a["cases"].reverse(),
                       lambda a: a["cases"][0]["exactPrompt"].update(userPacketJson="{}"),
                       lambda a: a["schema"].update(additionalProperties=True),
                       lambda a: a["schema"].update(required=[])):
            changed = copy.deepcopy(self.artifacts)
            mutate(changed)
            with self.assertRaises(ValueError):
                self.check_documents(self.protocol, changed)

    def test_example_must_be_disjoint_and_explicitly_in_prompt(self):
        changed = copy.deepcopy(self.artifacts)
        changed["example"]["packetJson"] = changed["cases"][0]["exactPrompt"]["userPacketJson"]
        with self.assertRaises(ValueError):
            self.check_documents(self.protocol, changed)

    def test_freeze_never_overwrites_existing_evidence(self):
        path = self.root / e.PREFIX / "manifest-v2.json"
        before = path.read_bytes()
        with self.assertRaises(ValueError):
            e.freeze(self.export, self.root)
        self.assertEqual(path.read_bytes(), before)

    def test_duplicate_json_keys_are_refused(self):
        with self.assertRaises(ValueError):
            e.decode(b'{"suggestions":[],"suggestions":[]}')

    def test_schema_success_without_human_assessment_never_qualifies(self):
        rows = [{"caseId": c, "outcome": "accepted", "scores": {d["id"]: 4 for d in self.protocol["dimensions"]},
                 "usefulFinal": True, "criticalFailures": []} for c in e.IDS]
        self.assertFalse(e.baseline.rubric_result(self.protocol, rows)["meetsFrozenRubric"])
        self.assertFalse(e.baseline.rubric_result(self.protocol, rows[:-1], True)["meetsFrozenRubric"])
        arithmetic = e.baseline.rubric_result(self.protocol, rows, True)
        self.assertTrue(arithmetic["meetsFrozenRubric"])
        self.assertEqual(arithmetic["productionAdmission"], "closed")


if __name__ == "__main__":
    unittest.main()
