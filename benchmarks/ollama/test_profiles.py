import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from run_profiles_on_pi import CASES, MODELS, OPTIONS, SCHEMA, api, chat_request, comparison, main, review_flags


class ProfileChecks(unittest.TestCase):
    def valid(self, case):
        return {"observations": [{"text": "Recorded activity was lower, while sleep and HRV were also lower; this does not establish cause.",
                                   "evidence_ids": [e["id"] for e in case["evidence"]]}],
                "missing_metrics": case["missing_metrics"].copy(), "limitations": case["limitations"].copy()}

    def test_precomputed_directions_and_percentages(self):
        fact = CASES[0]["evidence"][0]
        self.assertEqual(fact["direction"], "decreased")
        self.assertEqual(fact["delta"], -60)
        self.assertAlmostEqual(fact["percent_change"], -100 / 3)
        self.assertEqual(CASES[3]["evidence"][2]["direction"], "unchanged")

    def test_missing_values_and_zero_baseline(self):
        missing = comparison("test", "sleep", None, 7, None, "h")
        self.assertEqual(missing["direction"], "unavailable")
        self.assertIsNone(missing["delta"])
        self.assertIsNone(missing["percent_change"])
        zero = CASES[5]["evidence"][0]
        self.assertEqual(zero["direction"], "increased")
        self.assertIsNone(zero["percent_change"])

    def test_synthetic_only_unique_evidence(self):
        self.assertEqual(len(CASES), 7)
        for case in CASES:
            self.assertEqual(len({e["id"] for e in case["evidence"]}), len(case["evidence"]))
            for evidence in case["evidence"]:
                self.assertEqual(evidence["source"], "synthetic-fixture")

    def test_shape_and_unknown_evidence(self):
        result = self.valid(CASES[0])
        self.assertEqual(review_flags(result, CASES[0]), [])
        result["observations"][0]["evidence_ids"] = ["invented"]
        self.assertIn("invalid_or_missing_evidence", review_flags(result, CASES[0]))

    def test_numbers_prescriptions_and_missing_disclosures(self):
        result = self.valid(CASES[0])
        result["observations"][0]["text"] = "You must run 50 minutes."
        result["missing_metrics"] = []
        result["limitations"] = []
        flags = review_flags(result, CASES[0])
        self.assertIn("numeric_bookkeeping_in_prose", flags)
        self.assertIn("potential_causation_or_prescription", flags)
        self.assertIn("missing_missing_metrics_disclosure", flags)
        self.assertIn("missing_limitations_disclosure", flags)

    def test_malformed_inputs_do_not_bypass_screening(self):
        for malformed in (None, [], "hello", {}, {"observations": [None]}):
            self.assertTrue(review_flags(malformed, CASES[0]))
        result = self.valid(CASES[0])
        result["observations"][0]["evidence_ids"] = [{"not": "an id"}]
        result["missing_metrics"] = [{}]
        self.assertTrue(review_flags(result, CASES[0]))

    def test_no_thinking_and_bounded_context(self):
        self.assertEqual(MODELS, ("ministral-3:3b", "qwen3.5:4b"))
        request = chat_request(MODELS[1], CASES[0])
        self.assertIs(request["think"], False)
        self.assertIs(request["stream"], False)
        self.assertEqual(request["options"], OPTIONS)
        self.assertEqual(request["format"], SCHEMA)
        self.assertEqual(OPTIONS["num_ctx"], 2048)
        self.assertEqual(OPTIONS["num_predict"], 256)

    def test_semantic_review_still_required(self):
        # Correct shape and citations cannot prove that a paraphrase is factual.
        result = self.valid(CASES[0])
        result["observations"][0]["text"] = "Recorded activity, sleep, and HRV all increased."
        self.assertEqual(review_flags(result, CASES[0]), [])

    def test_api_reader_is_bounded(self):
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b"x" * 131073
        with patch("run_profiles_on_pi.urlopen", return_value=response):
            with self.assertRaisesRegex(RuntimeError, "bounded reader"):
                api("/api/version")
        response.read.assert_called_once_with(131073)

    def test_ci_cannot_start_operator_runner(self):
        with patch.dict("os.environ", {"GITHUB_ACTIONS": "true"}):
            with self.assertRaisesRegex(RuntimeError, "CI must not"):
                main()

    def test_manifest_keeps_disposable_model_isolated(self):
        text = Path(__file__).with_name("precomputed-pi-setup.yaml").read_text()
        self.assertIn("automountServiceAccountToken: false", text)
        self.assertIn("ingress: []", text)
        self.assertIn("name: OLLAMA_NO_CLOUD", text)
        self.assertIn("memory: 5Gi", text)
        self.assertIn("emptyDir:", text)
        for forbidden in ("kind: Service\n", "kind: Ingress\n", "PersistentVolumeClaim", "hostPort:", "hostNetwork:", "secretName:"):
            self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
