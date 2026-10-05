import json
import hashlib
import subprocess
import sys
import unittest
from pathlib import Path

from connected_review_packets_v2 import load_cases, materialize, prompt
from run_connected_review_v2_on_pi import _remote_core, _remote_source, output_schema
from connected_review_validator_v2 import validate

ROOT = Path(__file__).parent


class EvaluationV2Test(unittest.TestCase):
    def test_validator_parity_fixtures(self):
        fixtures = json.loads((ROOT / "validator-parity-v2.json").read_text())["cases"]
        self.assertEqual(len(fixtures), 7)
        for case in fixtures:
            with self.subTest(case=case["id"]):
                self.assertEqual(validate(case["rawDraft"], case["packet"]), case["accepted"])

    def test_maximum_duration_restriction_matches_kotlin_without_target_mention(self):
        case = next(c for c in json.loads((ROOT / "validator-parity-v2.json").read_text())["cases"]
                    if c["id"] == "restriction-override")
        packet = json.loads(json.dumps(case["packet"]))
        context = packet["context"][0]
        context.update(restrictionKind="maximum_duration", restrictionValue="20", restrictionUnit="minutes",
                       content="Synthetic user-entered guidance: maximum duration 20 minutes; 30 minutes exceeds it.")
        draft = json.loads(case["rawDraft"])
        draft["interpretations"][0]["text"] = "The session lasted 30 minutes."
        self.assertFalse(validate(json.dumps(draft), packet))
        draft["interpretations"][0]["text"] = "The session lasted 20 minutes."
        self.assertTrue(validate(json.dumps(draft), packet))

    def test_weights_and_correct_scoring_gate(self):
        policy = json.loads((ROOT / "evaluation-v2.json").read_text())
        v1 = json.loads((ROOT / "evaluation-v1.json").read_text())
        self.assertEqual(policy["dimensions"], v1["dimensions"])
        self.assertEqual(sum(d["weight"] for d in policy["dimensions"]), 100)
        self.assertTrue(policy["higherScoreIsBetter"])
        self.assertEqual(policy["criticalFailureThreshold"], 0)
        self.assertEqual(policy["contractVersion"], "connected-review-v1")
        self.assertIn("/ 4", policy["normalization"])
        self.assertIn("average weighted case scores", policy["normalization"])
        self.assertIn("every_case_dimension_score >= 2", policy["passGate"])
        self.assertIn("critical_failure_count == 0", policy["passGate"])
        scores = {d["id"]: 4 for d in policy["dimensions"]}
        percent = sum(d["weight"] * scores[d["id"]] / 4 for d in policy["dimensions"])
        self.assertEqual(percent, 100)
        scores["inappropriate_advice"] = 0
        bad_case_percent = sum(d["weight"] * scores[d["id"]] / 4 for d in policy["dimensions"])
        self.assertEqual(bad_case_percent, 85)
        self.assertFalse(min(scores.values()) >= policy["scoreScale"]["minimumAcceptablePerDimension"] and
                         bad_case_percent >= policy["passThresholdPercent"] and 0 == policy["criticalFailureThreshold"])

    def test_nine_explicit_matched_cases_and_deterministic_packets(self):
        cases = load_cases()
        self.assertEqual(len(cases), 9)
        groups = {}
        for case in cases:
            groups.setdefault(case["matchGroup"], []).append(case)
            self.assertTrue(case["packetIdentity"].startswith("connected-review-v2:"))
            self.assertIn("reportDigestSource", case)
            self.assertEqual(set(case["packet"]), {"profile", "evidence", "context"})
        self.assertEqual(len(groups), 3)
        for group in groups.values():
            self.assertEqual({c["variant"] for c in group}, {"facts_only", "context_present", "context_absent"})
            self.assertEqual(len({json.dumps(c["packet"]["evidence"], sort_keys=True) for c in group}), 1)
        packets = [materialize(c) for c in cases]
        self.assertEqual(packets, [materialize(c) for c in cases])
        self.assertTrue(all(set(p) == {"profile", "evidence", "context"} for p in packets))
        self.assertTrue(all(p["profile"] == "connected-review-v1" for p in packets))
        for case, packet in zip(cases, packets):
            expected = case["packet"]
            self.assertEqual(packet, {"profile": expected["profile"], "evidence": expected["evidence"], "context": expected["context"]})
            evidence = packet["evidence"]
            digest_spec = case["reportDigestSource"]
            fixture = digest_spec["fixture"]
            source = json.dumps(fixture, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
            self.assertEqual(hashlib.sha256(source).hexdigest(), evidence["evidenceReportSha256"])
            self.assertEqual(evidence["schemaVersion"], 1)
            self.assertEqual(evidence["facts"], fixture["facts"])
            self.assertEqual(packet["context"], expected["context"])
            self.assertFalse(any(field in packet for field in ("expected", "packetIdentity", "reportDigestSource")))
            for fact in evidence["facts"]:
                self.assertEqual(set(fact), {"evidenceId", "period", "oldest", "newest", "sport", "metric", "label",
                    "unit", "aggregation", "value", "sampleCount", "observedDays", "periodDays", "firstObservedDate",
                    "lastObservedDate", "sources", "metricOrigins", "recordOrigins", "unknownMetricOriginCount", "flags"})
        march_context = next(c["packet"]["context"][0] for c in cases if c["id"] == "matched-load-question-context-present")
        self.assertLessEqual(march_context["observedOn"], "2025-03-07")

    def test_prompt_matches_frozen_kotlin_instruction_and_excludes_rubric(self):
        source = (ROOT.parents[1] / "backend/src/main/kotlin/com/gtrainer/InterpretationContractV1.kt").read_text()
        marker = 'private const val SYSTEM_INSTRUCTIONS = """'
        kotlin_prompt = source.split(marker, 1)[1].split('"""', 1)[0]
        case = load_cases()[0]
        built = prompt(case)
        self.assertEqual(built["systemInstructions"], kotlin_prompt)
        packet = json.loads(built["userPacketJson"])
        self.assertEqual(set(packet), {"profile", "evidence", "context"})
        self.assertNotIn("expected", built["userPacketJson"])
        self.assertEqual(output_schema(packet)["properties"]["profile"]["enum"], ["connected-review-v1"])

    def test_v2_pi_package_compiles_and_runner_requires_per_run_approval(self):
        compile(_remote_core(), "connected_review_v2_pi_core.py", "exec")
        packaged = _remote_source()
        compile(packaged, "connected_review_v2_pi_package.py", "exec")
        namespace = {}
        exec(packaged.rsplit("ns['pi_main']()", 1)[0], namespace)
        self.assertEqual(namespace["ns"]["materialize"](namespace["cases"][0])["profile"], "connected-review-v1")
        runner = ROOT / "run_connected_review_v2_on_pi.py"
        no_run = subprocess.run([sys.executable, str(runner), "--models", "granite4.2:3b"],
                                capture_output=True, text=True, check=False)
        self.assertEqual(no_run.returncode, 0)
        self.assertIn("No inference", no_run.stdout)
        refused = subprocess.run([sys.executable, str(runner), "--run", "--models", "granite4.2:3b"],
                                 capture_output=True, text=True, check=False)
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn("Explicit synthetic inference approval", refused.stderr)


if __name__ == "__main__":
    unittest.main()
