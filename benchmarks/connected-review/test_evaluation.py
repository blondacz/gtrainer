import json
import subprocess
import sys
import unittest
from pathlib import Path

from connected_review_packets import load_cases, materialize, prompt
from run_connected_review_on_pi import (NON_CORRECTABLE_FLAGS, _remote_core, _remote_source,
                                       output_schema, validate_output)


ROOT = Path(__file__).parent


class ConnectedReviewEvaluationDefinitionTest(unittest.TestCase):
    def test_frozen_policy_and_matched_synthetic_case_count(self):
        policy = json.loads((ROOT / "evaluation-v1.json").read_text())
        cases = json.loads((ROOT / "cases-v1.json").read_text())
        self.assertEqual(policy["evaluationVersion"], "connected-review-evaluation-v1")
        self.assertEqual(policy["contractVersion"], "connected-review-v1")
        self.assertEqual(policy["rubricVersion"], "connected-review-rubric-v1")
        self.assertEqual(policy["caseCount"], 9)
        self.assertEqual(policy["matchedSetCount"], 3)
        self.assertEqual(policy["passThresholdPercent"], 80)
        self.assertEqual(policy["criticalFailureThreshold"], 0)
        self.assertFalse(policy["modelCallsPermitted"])
        self.assertEqual(len(cases["cases"]), 9)
        expected_variants = set(policy["matchedVariants"])
        groups = {}
        for case in cases["cases"]:
            self.assertTrue(case["id"].startswith("matched-"))
            self.assertIn(case["variant"], expected_variants)
            self.assertTrue(case["evidence"]["facts"])
            groups.setdefault(case["matchGroup"], []).append(case)
        self.assertEqual(len(groups), policy["matchedSetCount"])
        for group in groups.values():
            self.assertEqual({case["variant"] for case in group}, expected_variants)
            self.assertEqual(len({json.dumps(case["evidence"], sort_keys=True) for case in group}), 1)
        self.assertEqual(sum(dimension["weight"] for dimension in policy["dimensions"]), 100)
        self.assertGreaterEqual(len(policy["criticalFailures"]), 4)

    def test_fixture_is_synthetic_and_never_contains_execution_outputs(self):
        raw = (ROOT / "cases-v1.json").read_text().lower()
        self.assertIn("synthetic", raw)
        self.assertNotIn("password", raw)
        self.assertNotIn("credential", raw)
        cases = json.loads(raw)["cases"]
        for case in cases:
            self.assertNotIn("output", case)
            self.assertNotIn("response", case)

    def test_packets_materialize_deterministically_for_the_versioned_contract(self):
        cases = load_cases()
        packets = [materialize(case) for case in cases]
        prompts = [prompt(case) for case in cases]
        self.assertEqual(len(packets), 9)
        self.assertTrue(all(p["profile"] == "connected-review-v1" for p in packets))
        self.assertTrue(all(len(p["evidence"]["evidenceReportSha256"]) == 64 for p in packets))
        self.assertTrue(all(all(len(c["contextId"]) == 36 and c["revision"] == 1 for c in p["context"])
                             for p in packets))
        self.assertEqual(prompts, [prompt(case) for case in cases])
        self.assertNotEqual(prompts[0], prompts[1])
        self.assertEqual(prompts[0]["systemInstructions"], prompts[1]["systemInstructions"])

    def test_prompt_is_the_frozen_production_contract_instruction(self):
        source = (ROOT.parents[1] / "backend/src/main/kotlin/com/gtrainer/InterpretationContractV1.kt").read_text()
        marker = 'private const val SYSTEM_INSTRUCTIONS = """'
        self.assertIn(marker, source)
        kotlin_prompt = source.split(marker, 1)[1].split('"""', 1)[0]
        self.assertEqual(prompt(load_cases()[0])["systemInstructions"], kotlin_prompt)

    def test_runner_schema_and_validator_accept_a_grounded_draft(self):
        case = load_cases()[0]
        packet = materialize(case)
        self.assertEqual(output_schema(packet)["properties"]["profile"]["enum"], ["connected-review-v1"])
        draft = {"profile": "connected-review-v1", "interpretations": [{
            "text": "The packet reports 90 minutes of moving time during this period.",
            "sources": {"evidenceIds": ["e-run-1"], "context": []},
            "scope": {"from": "2025-01-01", "until": "2025-01-07", "sport": "run"},
            "uncertainty": "low"}], "questions": []}
        self.assertEqual(validate_output(json.dumps(draft), packet), [])
        draft["interpretations"][0]["text"] = "The 90 minutes proves the run caused recovery."
        self.assertIn("prohibited_or_invalid_claim", validate_output(json.dumps(draft), packet))

    def test_remote_runner_source_is_syntactically_valid_and_candidate_allowlisted(self):
        compile(_remote_core(), "connected_review_pi_core.py", "exec")
        packaged = _remote_source()
        compile(packaged, "connected_review_pi_package.py", "exec")
        namespace = {}
        exec(packaged.rsplit("ns['pi_main']()", 1)[0], namespace)
        packet = namespace["ns"]["materialize"](namespace["cases"][0])
        self.assertEqual(namespace["ns"]["output_schema"](packet)["type"], "object")

    def test_runner_is_default_closed_and_requires_per_run_approval(self):
        runner = ROOT / "run_connected_review_on_pi.py"
        no_run = subprocess.run([sys.executable, str(runner), "--models", "granite4.2:3b"],
                                capture_output=True, text=True, check=False)
        self.assertEqual(no_run.returncode, 0)
        self.assertIn("No inference", no_run.stdout)
        refused = subprocess.run([sys.executable, str(runner), "--run", "--models", "granite4.2:3b"],
                                 capture_output=True, text=True, check=False)
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("Explicit synthetic inference approval", refused.stderr)
        self.assertEqual(NON_CORRECTABLE_FLAGS,
                         {"response_budget_exceeded", "incomplete_generation", "unexpected_thinking"})


if __name__ == "__main__":
    unittest.main()
