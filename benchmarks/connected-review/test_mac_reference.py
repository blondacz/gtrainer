import base64
import json
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_connected_review_on_mac as runner


class MacReferenceTest(unittest.TestCase):
    def test_egress_probe_rejects_success_and_execution_errors(self):
        nodes = json.dumps({"items": [{"metadata": {"name": "gtrainer-mac-vm"}}]}).encode()
        policy = json.dumps({"spec": {"podSelector": {}, "policyTypes": ["Egress"], "egress": []}}).encode()
        tags = json.dumps({"models": [{"name": "qwen3:8b", "digest": runner.MODELS["qwen3:8b"]}]}).encode()
        for code, stderr, allowed in [(0, b"", False), (127, b"bash missing", False),
                                      (1, b"pod not found", False), (124, b"", True),
                                      (1, b"bash: connect: Connection refused", True)]:
            with self.subTest(code=code, stderr=stderr), \
                 patch.object(runner, "kube", side_effect=[nodes, policy]), \
                 patch.object(runner, "request", side_effect=[b'{"version":"0.35.0"}', tags]), \
                 patch.object(runner.subprocess, "run", return_value=subprocess.CompletedProcess([], code, b"", stderr)), \
                 patch.object(runner, "resource_sample", return_value={}):
                if allowed:
                    runner.preflight(["qwen3:8b"])
                else:
                    with self.assertRaisesRegex(RuntimeError, "runtime_egress_block_not_confirmed"):
                        runner.preflight(["qwen3:8b"])

    def run_capture(self, responses):
        case = runner.load_cases()[4]
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(runner, "load_cases", return_value=[case]), \
                 patch.object(runner, "resource_sample", return_value={"pod_uid": "owned-pod"}), \
                 patch.object(runner, "request", side_effect=responses), \
                 patch.object(runner, "kube") as kube:
                error = None
                try:
                    runner.run_model("qwen3:8b", Path(directory))
                except Exception as caught:
                    error = caught
                events = [json.loads(line) for line in (Path(directory) / "qwen3-8b.jsonl").read_text().splitlines()]
        return case, events, error, kube

    def test_correction_preserves_rejection_and_frozen_packet(self):
        fixture = json.loads((runner.ROOT / "validator-parity-v2.json").read_text())["cases"][0]
        response = lambda content: json.dumps({"message": {"content": content}, "done": True, "done_reason": "stop"}).encode()
        case, events, error, kube = self.run_capture([response("{}"), response(fixture["rawDraft"])])
        self.assertIsNone(error)
        attempts = [e for e in events if e["phase"] == "attempt_result"]
        self.assertEqual([e["status"] for e in attempts], ["rejected", "accepted"])
        for event in attempts:
            raw = base64.b64decode(event["request_base64"])
            self.assertEqual(runner.sha(raw), event["request_sha256"])
            self.assertEqual(json.loads(raw)["messages"][1]["content"], runner.prompt(case)["userPacketJson"])
        kube.assert_not_called()

    def test_truncation_is_retained_without_correction(self):
        raw = json.dumps({"message": {"content": '{"profile":'}, "done": True, "done_reason": "length"}).encode()
        _, events, error, kube = self.run_capture([raw])
        self.assertIsNone(error)
        attempts = [e for e in events if e["phase"] == "attempt_result"]
        self.assertEqual(len(attempts), 1)
        self.assertEqual(base64.b64decode(attempts[0]["response_base64"]), raw)
        self.assertIn("generation_limit_reached", attempts[0]["flags"])
        self.assertEqual(next(e for e in events if e["phase"] == "case_complete")["status"], "failed")
        kube.assert_not_called()

    def test_provider_failure_retains_request_and_stops_model(self):
        _, events, error, kube = self.run_capture([TimeoutError("attempt_timeout")])
        self.assertIsInstance(error, TimeoutError)
        self.assertEqual(len([e for e in events if e["phase"] == "attempt_failure"]), 1)
        self.assertFalse(any(e["phase"] == "candidate_complete" for e in events))
        kube.assert_called_once_with("-n", "gtrainer-models", "scale", "deployment/ollama", "--replicas=0")


if __name__ == "__main__":
    unittest.main()
