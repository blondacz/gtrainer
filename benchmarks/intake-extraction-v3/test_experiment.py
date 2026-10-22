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
        names = set(e.PINNED) - {e.PREFIX + n for n in e.GENERATED}
        names.update(e.v2.PINNED)
        names.update(e.baseline.PINNED)
        names.update(("benchmarks/scoped-profiles/manifest-v1.json", "benchmarks/intake-extraction-v2/manifest-v2.json"))
        for name in names:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(e.ROOT / name, target)
        self.export = Path(os.environ.get("INTAKE_V3_EXPORT_DIR", str(e.HERE)))
        e.freeze(self.export, self.root)
        self.protocol, self.artifacts = e.verify(self.root)
        self.proof = e.decode((self.export / "context-proof-v3.json").read_bytes())

    def validate(self, artifacts=None, proof=None, owner=None, protocol=None):
        e.validate_documents(protocol or self.protocol, artifacts or self.artifacts,
                             proof or self.proof, owner or e.ownership(), self.root)

    def test_all_five_and_only_five_schema_changes_are_allowed(self):
        _, old = e.v2.verify(self.root)
        self.assertEqual(self.artifacts["schema"], e.provider_schema(old["schema"]))
        self.assertEqual(len(e.REMOVALS), 5)
        self.assertEqual(old["cases"], self.artifacts["cases"])
        self.assertEqual(old["example"], self.artifacts["example"])
        for mutate in (lambda a: a["schema"].update(required=[]),
                       lambda a: a["cases"].reverse(), lambda a: a["cases"][0]["exactPrompt"].update(systemInstructions="changed")):
            altered = copy.deepcopy(self.artifacts); mutate(altered)
            with self.assertRaises(ValueError): self.validate(artifacts=altered)

    def test_context_proof_rejects_every_binding_and_budget_drift(self):
        for key, value in (("contextTokens", 8192), ("reservedOutputTokens", 768), ("schemaPath", "prompt"),
                           ("providerMeasuredInputTokens", 816), ("image", "different"), ("templateSha256", "0" * 64)):
            altered = copy.deepcopy(self.proof); altered[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.validate(proof=altered)
        for key, value in (("tokens", 1), ("tokens", 798.0), ("renderedSha256", "0" * 64), ("requestSha256", "0" * 64)):
            altered = copy.deepcopy(self.proof); altered["cases"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.validate(proof=altered)
        self.assertEqual(816 + 1536 + 256, self.proof["totalWithMarginTokens"])
        self.assertIsNone(self.proof["providerMeasuredInputTokens"])

    def test_ownership_cannot_claim_native_execution_or_drop_local_protection(self):
        for key, value in (("nativeGrammarTestPerformed", True), ("localOnly", []), ("removedProviderKeywords", {})):
            owner = e.ownership(); owner[key] = value
            with self.assertRaises(ValueError): self.validate(owner=owner)

    def test_pinned_sources_both_prior_manifests_and_new_artifacts_are_verified(self):
        for name in (e.PREFIX + "artifacts-v3.json", e.PREFIX + "context-proof-v3.json",
                     "backend/src/test/kotlin/com/gtrainer/IntakeExtractionV3.kt",
                     "backend/src/test/kotlin/com/gtrainer/IntakeExtractionV2.kt",
                     "benchmarks/intake-extraction-v2/manifest-v2.json"):
            path = self.root / name; before = path.read_bytes(); path.write_bytes(before + b" ")
            with self.subTest(name=name), self.assertRaises(ValueError): e.verify(self.root)
            path.write_bytes(before)

    def test_freeze_is_exclusive_and_preserves_prior_protocol(self):
        path = self.root / e.PREFIX / "manifest-v3.json"
        original = path.read_bytes()
        with self.assertRaises(ValueError): e.freeze(self.export, self.root)
        self.assertEqual(original, path.read_bytes())
        old, _ = e.v2.verify(self.root)
        for key in ("cases", "dimensions", "gates", "generationPolicy", "criticalFailures"):
            self.assertEqual(old[key], self.protocol[key])

    def test_cross_version_and_unknown_fields_fail(self):
        for key, value in (("profile", e.v2.PROFILE), ("version", "intake-extraction-artifacts-v2"), ("extra", True)):
            altered = dict(self.artifacts, **{key: value})
            with self.assertRaises(ValueError): self.validate(artifacts=altered)
        with self.assertRaises(ValueError): e.make_context(self.artifacts, b"{}")
        with self.assertRaises(ValueError): e.decode(b'{"version":1,"version":2}')


if __name__ == "__main__":
    unittest.main()
