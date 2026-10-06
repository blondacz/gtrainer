import base64
import csv
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from connected_review_capture_chain import FROZEN_FILES, IDENTITY_FIELDS, PROFILE, digest, reconcile
from connected_review_packets_v2 import ROOT, load_cases, prompt


class CaptureChainTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.case_ids = [case["id"] for case in load_cases()]
        self.header = dict.fromkeys(IDENTITY_FIELDS, "synthetic-identity")
        self.header.update(phase="evaluation_started", evaluation_version=PROFILE,
                           attempt_timeout_seconds=600, case_total_timeout_seconds=1240,
                           settings={"num_ctx": 2048}, artifact_sha256={
                               key: digest(path.read_bytes()) for key, path in FROZEN_FILES.items()})

    def attempt(self, case_index=0, status="accepted", number=1):
        case = load_cases()[case_index]
        packet = prompt(case)["userPacketJson"]
        request = json.dumps({"messages": [{}, {"content": packet}]}).encode()
        response = json.dumps({"message": {"content": json.dumps({
            "profile": "connected-review-v1", "interpretations": [], "questions": []})}}).encode()
        return {"phase": "attempt_result", "case": case["id"], "attempt": number,
                "status": status, "flags": [], "request_base64": base64.b64encode(request).decode(),
                "request_sha256": digest(request), "packet_sha256": digest(packet.encode()),
                "response_base64": base64.b64encode(response).decode(), "response_sha256": digest(response)}

    def failure(self, case_index=0):
        event = self.attempt(case_index)
        for field in ("response_base64", "response_sha256", "status", "flags"):
            event.pop(field)
        event.update(phase="attempt_failure", reason="attempt_timeout")
        return event

    def complete(self, case_index=0):
        return {"phase": "case_complete", "case": self.case_ids[case_index], "status": "accepted"}

    def write(self, name, events):
        path = self.root / name
        path.write_text("\n".join(json.dumps(event) for event in events) + "\n")
        return path

    def part(self, name, events, previous=None, start=0, header=None):
        records = [dict(self.header if header is None else header)]
        if previous:
            records.append({"phase": "evaluation_resumed", "previous_capture_sha256": digest(previous.read_bytes()),
                            "start_case": self.case_ids[start], "selected_case_ids": self.case_ids[start:],
                            "previous_outcomes_not_copied": True})
        records.extend(events)
        records.append({"phase": "owned_cleanup_complete", "namespace_removed": True})
        return self.write(name, records)

    def rewrite(self, path, mutate):
        records = [json.loads(line) for line in path.read_text().splitlines()]
        mutate(records)
        return self.write(path.name, records)

    def chain(self):
        first = self.part("retry.jsonl", [self.attempt(), self.complete(), self.failure(1)])
        second = self.part("continuation.jsonl", [self.attempt(1), self.complete(1)], first, 1)
        return first, second

    def test_reconciles_linked_prefix_without_erasing_prior_failure(self):
        paths = self.chain()
        before = [path.read_bytes() for path in paths]
        result = reconcile(list(paths))
        self.assertEqual(result["accepted_cases"], self.case_ids[:2])
        self.assertEqual(result["not_run_cases"], self.case_ids[2:])
        self.assertEqual([item["event"]["phase"] for item in result["attempts"]],
                         ["attempt_result", "attempt_failure", "attempt_result"])
        self.assertEqual([item["capture_part"] for item in result["attempts"]], [1, 1, 2])
        self.assertEqual(before, [path.read_bytes() for path in paths])

    def test_initial_timeout_is_an_independent_incomplete_candidate(self):
        first = self.part("initial.jsonl", [self.failure()])
        result = reconcile([first])
        self.assertEqual(result["accepted_cases"], [])
        self.assertEqual(result["not_run_cases"], self.case_ids[1:])

    def test_source_runner_versions_may_differ_but_remain_preserved(self):
        first, second = self.chain()
        self.rewrite(second, lambda records: records[0]["artifact_sha256"].update(runner="different-preserved-runner"))
        self.assertEqual(len(reconcile([first, second])["accepted_cases"]), 2)

    def test_rejects_missing_reordered_or_duplicate_sources(self):
        first, second = self.chain()
        for paths in ([], [second], [second, first], [first, first]):
            with self.subTest(paths=paths), self.assertRaises(ValueError):
                reconcile(paths)

    def test_rejects_wrong_parent_hash_start_suffix_or_copied_outcomes(self):
        for field, value in (("previous_capture_sha256", "0" * 64), ("start_case", self.case_ids[2]),
                             ("selected_case_ids", self.case_ids[2:]), ("previous_outcomes_not_copied", False)):
            first, second = self.chain()
            self.rewrite(second, lambda records: records[1].update({field: value}))
            with self.subTest(field=field), self.assertRaises(ValueError):
                reconcile([first, second])

    def test_rejects_changed_runtime_model_or_budget_identity(self):
        for field in IDENTITY_FIELDS:
            first, second = self.chain()
            self.rewrite(second, lambda records: records[0].update({field: "changed"}))
            with self.subTest(field=field), self.assertRaises(ValueError):
                reconcile([first, second])

    def test_rejects_missing_identity_or_frozen_artifact(self):
        for field in ("model_digest", "system_prompt_sha256", "artifact_sha256"):
            first, _ = self.chain()
            self.rewrite(first, lambda records: records[0].pop(field))
            with self.subTest(field=field), self.assertRaises(ValueError):
                reconcile([first])

    def test_rejects_changed_frozen_artifact_hash(self):
        for field in FROZEN_FILES:
            first, _ = self.chain()
            self.rewrite(first, lambda records: records[0]["artifact_sha256"].update({field: "0" * 64}))
            with self.subTest(field=field), self.assertRaises(ValueError):
                reconcile([first])

    def test_rejects_missing_or_uncertain_cleanup_and_duplicate_headers(self):
        mutations = (lambda records: records.pop(),
                     lambda records: records[-1].update(namespace_removed=False),
                     lambda records: records.append(records[0]))
        for mutate in mutations:
            first, _ = self.chain()
            self.rewrite(first, mutate)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                reconcile([first])

    def test_rejects_noncontiguous_duplicate_or_rejected_completed_prefix(self):
        for events in ([self.attempt(1), self.complete(1)],
                       [self.attempt(), self.complete(), self.complete()],
                       [self.attempt(status="rejected"), self.complete()],
                       [self.complete()], [self.attempt(2)]):
            first = self.part("bad.jsonl", events)
            with self.subTest(events=events), self.assertRaises(ValueError):
                reconcile([first])

    def test_rejects_attempt_number_reset_inside_one_capture(self):
        first = self.part("bad.jsonl", [self.attempt(), self.attempt(), self.complete()])
        with self.assertRaises(ValueError):
            reconcile([first])

    def test_preserves_true_validator_correction(self):
        first = self.part("corrected.jsonl", [self.attempt(status="rejected"), self.attempt(number=2), self.complete()])
        self.assertEqual([item["event"]["attempt"] for item in reconcile([first])["attempts"]], [1, 2])

    def test_rejects_malformed_json_or_nonobject_records(self):
        for text in ("not-json", "[]"):
            first = self.root / "bad.jsonl"
            first.write_text(text)
            with self.subTest(text=text), self.assertRaises(ValueError):
                reconcile([first])

    def test_review_pack_keeps_restart_role_source_hash_and_blank_human_scores(self):
        first = self.part("retry.jsonl", [self.failure()])
        second = self.part("continuation.jsonl", [self.attempt(), self.complete()], first)
        ordinary = self.part("ordinary.jsonl", [])
        environment = dict(os.environ, HOME=str(self.root))
        result = subprocess.run([sys.executable, str(ROOT / "prepare_human_review.py"),
                                 "--capture", str(ordinary), "--capture-chain", "q3-retry", str(first), str(second)],
                                env=environment, check=True, capture_output=True, text=True, timeout=10)
        out = Path(result.stdout.strip())
        with (out / "scores.csv").open() as source:
            rows = list(csv.DictReader(source))
        attempts = [row for row in rows if row["candidate"] == "q3-retry" and row["case"] == self.case_ids[0]]
        self.assertEqual([row["role"] for row in attempts], ["failure", "first_pass"])
        self.assertEqual([row["final_for_case"] for row in attempts], ["false", "true"])
        self.assertEqual([row["capture_sha256"] for row in attempts], [digest(first.read_bytes()), digest(second.read_bytes())])
        self.assertEqual(attempts[-1]["grounding"], "")
        self.assertEqual(attempts[0]["grounding"], "N/A")
        metadata = json.loads((out / "metadata.json").read_text())
        self.assertFalse(metadata["inference_performed"])
        self.assertFalse(metadata["quality_scores_generated"])
        self.assertEqual(metadata["capture_chains"][0]["accepted_cases"], self.case_ids[:1])
        self.assertEqual(out.stat().st_mode & 0o777, 0o700)
        self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in out.iterdir()))


if __name__ == "__main__":
    unittest.main()
