import subprocess
import sys
import base64
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_connected_review_v3_on_pi as runner
import run_qwen3_q3_reference_on_pi as reference


class Qwen3Q3ReferenceTest(unittest.TestCase):
    def test_separate_profile_preserves_original_generation_budget(self):
        q3 = runner.reference_config(runner.QWEN3_Q3["model"], True)
        original = runner.reference_config("phi4-mini:3.8b", False)
        self.assertEqual(q3["options"]["num_ctx"], 2048)
        self.assertEqual(q3["options"]["num_predict"], 1536)
        self.assertEqual(q3["minimum_host_available_mib"], 1024)
        self.assertEqual(original["options"]["num_ctx"], 4096)
        self.assertEqual(original["minimum_host_available_mib"], 768)
        self.assertNotEqual(q3["evaluation_version"], original["evaluation_version"])
        for model, enabled in (("phi4-mini:3.8b", True), (runner.QWEN3_Q3["model"], False)):
            with self.assertRaises(ValueError):
                runner.reference_config(model, enabled)

    def test_remote_package_includes_pinned_reference_and_compiles(self):
        packaged = runner._remote_source()
        namespace = {}
        exec(packaged.rsplit("ns['pi_main']()", 1)[0], namespace)
        ns = namespace["ns"]
        self.assertEqual(ns["QWEN3_Q3"], runner.QWEN3_Q3)
        self.assertEqual(ns["reference_config"](runner.QWEN3_Q3["model"], True)["minimum_host_available_mib"], 1024)
        compile(reference.staging_source(), "q3-staging.py", "exec")
        self.assertIn('"type": "Directory"', runner._remote_core())
        self.assertIn('"sha256sum", blob_path', runner._remote_core())

    def test_memory_override_preserves_headroom_and_is_reference_only(self):
        config = runner.reference_config(runner.QWEN3_Q3["model"], True, 5376)
        self.assertEqual(config["container_memory_limit_mib"], 5376)
        self.assertEqual(config["minimum_host_available_mib"], 1024)
        self.assertEqual(runner.reference_config("phi4-mini:3.8b", False)["container_memory_limit_mib"], 5120)
        for model, enabled, limit in (("phi4-mini:3.8b", False, 5376),
                                      (runner.QWEN3_Q3["model"], True, 5119),
                                      (runner.QWEN3_Q3["model"], True, 5633)):
            with self.assertRaises(ValueError):
                runner.reference_config(model, enabled, limit)

    def test_ten_minute_attempt_and_case_budgets_are_reference_only(self):
        config = runner.reference_config(runner.QWEN3_Q3["model"], True, 5376, 600)
        self.assertEqual(config["attempt_timeout_seconds"], 600)
        self.assertEqual(config["case_total_timeout_seconds"], 1240)
        original = runner.reference_config("phi4-mini:3.8b", False)
        self.assertEqual(original["attempt_timeout_seconds"], 480)
        self.assertEqual(original["case_total_timeout_seconds"], 1000)
        for model, enabled, seconds in (("phi4-mini:3.8b", False, 600),
                                        (runner.QWEN3_Q3["model"], True, 479),
                                        (runner.QWEN3_Q3["model"], True, 901)):
            with self.assertRaises(ValueError):
                runner.reference_config(model, enabled, 5120, seconds)

    def test_download_and_inference_require_both_approvals(self):
        for flags in ([], ["--approve-synthetic-run"], ["--approve-microsd-model-storage"]):
            with self.subTest(flags=flags), patch.object(sys, "argv", ["reference", *flags]), \
                 patch.object(reference.subprocess, "run") as remote:
                with self.assertRaises(SystemExit):
                    reference.main()
                remote.assert_not_called()

    def test_q3_cannot_run_under_v3_profile(self):
        with patch.object(sys, "argv", ["runner", "--run", "--approve-synthetic-run", "--models", runner.QWEN3_Q3["model"]]), \
             patch.object(runner.subprocess, "Popen") as remote:
            with self.assertRaisesRegex(SystemExit, "selected alone"):
                runner.main()
            remote.assert_not_called()

    def test_resume_selection_is_reference_only_and_packaged(self):
        cases = runner.load_cases()
        start = cases[2]["id"]
        self.assertEqual(runner.selected_cases(start, "a" * 64, True), cases[2:])
        for case, digest, enabled in ((start, "a" * 64, False), ("unknown", "a" * 64, True),
                                      (start, None, True), (start, "wrong", True), (None, "a" * 64, True)):
            with self.assertRaises(ValueError):
                runner.selected_cases(case, digest, enabled)
        namespace = {}
        exec(runner._remote_source().rsplit("ns['pi_main']()", 1)[0], namespace)
        self.assertEqual(namespace["ns"]["selected_cases"](start, "a" * 64, True), cases[2:])

    def test_resume_verifies_prefix_integrity_settings_and_cleanup(self):
        case = runner.load_cases()[0]
        config = runner.reference_config(runner.QWEN3_Q3["model"], True, 5376, 600)
        header = {"phase": "evaluation_started", "settings": config["options"],
                  "evaluation_version": config["evaluation_version"], "container_memory_limit_mib": 5376,
                  "attempt_timeout_seconds": 600, "case_total_timeout_seconds": 1240,
                  "minimum_host_available_mib": 1024, "model_source": runner.QWEN3_Q3,
                  "max_response_bytes": runner.MAX_RESPONSE_BYTES, "maximum_attempts_per_case": 2,
                  "artifact_sha256": {key: runner.sha((runner.ROOT / file).read_bytes()) for key, file in (
                      ("cases", "cases-v2.json"), ("packet_adapter", "connected_review_packets_v2.py"),
                      ("validator", "connected_review_validator_v3.py"))}}
        request = json.dumps({"messages": [{}, {"content": runner.prompt(case)["userPacketJson"]}]}).encode()
        response = json.dumps({"message": {"content": json.dumps({"profile": "connected-review-v1",
                                   "interpretations": [], "questions": []})}}).encode()
        attempt = {"phase": "attempt_result", "case": case["id"], "status": "accepted",
                   "request_base64": base64.b64encode(request).decode(), "request_sha256": runner.sha(request),
                   "response_base64": base64.b64encode(response).decode(), "response_sha256": runner.sha(response)}
        records = [header, attempt, {"phase": "case_complete", "case": case["id"], "status": "accepted"},
                   {"phase": "owned_cleanup_complete", "namespace_removed": True}]
        with tempfile.TemporaryDirectory() as directory:
            capture = Path(directory) / "capture.jsonl"
            def write(items):
                capture.write_text("\n".join(json.dumps(item) for item in items) + "\n")
            write(records)
            self.assertEqual(reference.resume_details(capture, 5376, 600),
                             (runner.load_cases()[1]["id"], runner.sha(capture.read_bytes())))
            with self.assertRaisesRegex(ValueError, "settings"):
                reference.resume_details(capture, 5120, 600)
            for changed, message in ((records[:-1], "cleanup"),
                                     (records + [{"phase": "evaluation_resumed"}], "capture-chain"),
                                     ([header, dict(attempt, response_sha256="0" * 64), *records[2:]], "integrity"),
                                     ([header, attempt, dict(records[2], status="rejected"), records[3]], "accepted")):
                write(changed)
                with self.assertRaisesRegex(ValueError, message):
                    reference.resume_details(capture, 5376, 600)


if __name__ == "__main__":
    unittest.main()
