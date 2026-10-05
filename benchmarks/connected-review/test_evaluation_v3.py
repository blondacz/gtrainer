import json
import argparse
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from connected_review_packets_v2 import load_cases, materialize
from connected_review_validator_v3 import validate, validate_detailed
import run_connected_review_v3_on_pi as runner
from run_connected_review_v3_on_pi import OPTIONS, _remote_core, _remote_source, validate_output, stable_app_state

ROOT = Path(__file__).parent


class EvaluationV3Test(unittest.TestCase):
    def test_monitor_timeout_is_bounded_and_forwarded_to_pi(self):
        self.assertEqual(runner.monitor_command_timeout("60"), 60)
        for value in ("9", "121"):
            with self.assertRaises(argparse.ArgumentTypeError):
                runner.monitor_command_timeout(value)
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(sys, "argv", ["runner", "--run", "--approve-synthetic-run",
                                       "--models", "gemma4:e2b", "--monitor-command-timeout-seconds", "60"]), \
             patch.object(runner.Path, "home", return_value=Path(directory)), \
             patch.object(runner.subprocess, "Popen") as process, \
             patch("builtins.print"):
            process.return_value.returncode = 0
            runner.main()
            command = process.call_args.args[0]
            self.assertEqual(command[-2:], ["--monitor-command-timeout-seconds", "60"])
        namespace = {}
        exec(_remote_source().rsplit("ns['pi_main']()", 1)[0], namespace)
        self.assertEqual(namespace["ns"]["monitor_command_timeout"]("60"), 60)
        self.assertEqual(namespace["ns"]["ATTEMPT_SECONDS"], 480)
        self.assertEqual(namespace["ns"]["CASE_SECONDS"], 1000)

    def test_app_baseline_allows_historical_restart_but_rejects_new_changes(self):
        baseline = [("app-pod", "image-digest", True, 1)]
        self.assertTrue(stable_app_state(baseline, list(baseline)))
        for current in ([], [("app-pod", "image-digest", True, 2)],
                        [("app-pod", "image-digest", False, 1)],
                        [("replacement-pod", "image-digest", True, 1)],
                        [("app-pod", "new-image", True, 1)]):
            self.assertFalse(stable_app_state(baseline, current))
        self.assertFalse(stable_app_state([], []))
        self.assertFalse(stable_app_state([("app-pod", "image-digest", False, 1)],
                                         [("app-pod", "image-digest", False, 1)]))

    def test_v3_freezes_larger_budget_without_rewriting_v2(self):
        v2 = json.loads((ROOT / "evaluation-v2.json").read_text())
        v3 = json.loads((ROOT / "evaluation-v3.json").read_text())
        self.assertEqual(v2["generationSettings"].get("num_predict") if "generationSettings" in v2 else 768, 768)
        self.assertEqual(v3["evaluationVersion"], "connected-review-evaluation-v3")
        self.assertEqual(v3["caseSetVersion"], "connected-review-synthetic-cases-v2")
        self.assertEqual(v3["dimensions"], v2["dimensions"])
        self.assertEqual(v3["numPredict"] if "numPredict" in v3 else v3["generationSettings"]["num_predict"], 1536)
        self.assertEqual(v3["generationSettings"]["maxResponseBytes"], 24576)
        self.assertEqual(v3["generationSettings"]["egressProbeCommandTimeoutSeconds"], 20)
        self.assertEqual(OPTIONS["num_predict"], 1536)
        self.assertIn("qwen3.5:4b", __import__("run_connected_review_v3_on_pi").MODELS)

    def test_shared_contract_fixtures_report_specific_categories(self):
        fixtures = json.loads((ROOT / "validator-parity-v2.json").read_text())["cases"]
        expected = {
            "baseline": set(),
            "clinician-verified": {"prohibited_claim"},
            "equivalent-decimal": set(),
            "unsupported-number": {"unsupported_numeric_claim"},
            "bad-date-scope": {"scope_outside_cited_period"},
            "unsupported-reference": {"unsupported_evidence_reference"},
            "restriction-override": {"restriction_contradiction"},
        }
        for fixture in fixtures:
            with self.subTest(case=fixture["id"]):
                errors = set(validate_detailed(fixture["rawDraft"], fixture["packet"]))
                if fixture["accepted"]:
                    self.assertEqual(errors, set())
                else:
                    self.assertTrue(expected[fixture["id"]].issubset(errors), errors)
                self.assertEqual(validate(fixture["rawDraft"], fixture["packet"]), fixture["accepted"])

    def test_truncated_output_has_parse_error_and_generation_limit_is_noncorrectable(self):
        case = load_cases()[0]
        packet = materialize(case)
        self.assertEqual(validate_detailed('{"profile":"connected-review-v1","interpretations":[', packet), ["malformed_json"])
        self.assertIn("generation_limit_reached", __import__("run_connected_review_v3_on_pi").NON_CORRECTABLE_FLAGS)

    def test_v3_pi_package_is_approval_gated_and_compiles(self):
        compile(_remote_core(), "connected_review_v3_pi_core.py", "exec")
        packaged = _remote_source()
        compile(packaged, "connected_review_v3_pi_package.py", "exec")
        namespace = {}
        exec(packaged.rsplit("ns['pi_main']()", 1)[0], namespace)
        self.assertEqual(namespace["ns"]["OPTIONS"]["num_predict"], 1536)
        self.assertIn("qwen3.5:4b", namespace["ns"]["MODELS"])
        self.assertIn('"python", "-c", probe, timeout=20', _remote_core())
        runner = ROOT / "run_connected_review_v3_on_pi.py"
        refused = subprocess.run([sys.executable, str(runner), "--run", "--models", "qwen3.5:4b"],
                                 capture_output=True, text=True, check=False)
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("Explicit synthetic inference approval", refused.stderr)


if __name__ == "__main__":
    unittest.main()
