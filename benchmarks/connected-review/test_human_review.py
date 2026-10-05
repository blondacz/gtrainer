import json
from pathlib import Path
import tempfile
import unittest

from prepare_human_review import capture_runtime


class HumanReviewTest(unittest.TestCase):
    def test_mac_and_pi_provenance_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.jsonl"
            for version, kind, seconds in (("connected-review-mac-cpu-reference-v1", "mac", 900),
                                           ("connected-review-evaluation-v3", "pi", 480)):
                with self.subTest(version=version):
                    path.write_text(json.dumps({"phase": "runtime_ready"}) + "\n" + json.dumps({
                        "phase": "evaluation_started", "evaluation_version": version,
                        "attempt_timeout_seconds": seconds, "settings": {"num_ctx": 4096}}) + "\n")
                    actual_kind, runtime = capture_runtime(path)
                    self.assertEqual(actual_kind, kind)
                    self.assertEqual(runtime["evaluation_version"], version)
                    self.assertEqual(runtime["attempt_timeout_seconds"], seconds)

    def test_staging_failure_has_no_invented_evaluation_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.jsonl"
            path.write_text(json.dumps({"phase": "candidate_failed", "failure_stage": "model_staging"}))
            self.assertEqual(capture_runtime(path), ("pi", {}))


if __name__ == "__main__":
    unittest.main()
