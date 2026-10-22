import copy
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid

import revision_v4 as r


def fixture_counts(instructions):
    """Fabricated test counts only; never written as an actual prepared revision."""
    document = r.counter_input(instructions)
    return {"version": "intake-conformance-token-evidence-v4", "tokenizer": copy.deepcopy(r.TOKENIZER),
            "inputSha256": r.e.digest(r.e.compact(document)), "tensorWeightsLoaded": False,
            "inferenceContextCreated": False, "generationCalled": False,
            "cases": [{"caseId": row["caseId"], "tokens": 100, "tokensSha256": "0" * 64,
                       "renderedSha256": r.e.digest(r.e.v3.render(row["exactPrompt"])),
                       "renderedUtf8Bytes": len(r.e.v3.render(row["exactPrompt"]))} for row in document["cases"]]}


class RevisionTest(unittest.TestCase):
    def setUp(self):
        self.instructions = r.INSTRUCTIONS_R1
        self.counts = fixture_counts(self.instructions)
        self.revision_id = str(uuid.uuid4())

    def test_all_requests_have_exact_schema_mmap_settings_and_original_packets(self):
        _, inputs, _, _ = r.e.verify()
        requests = r.expected_requests(self.instructions)
        import mac_runtime as mac
        for row in r.prompt_cases(self.instructions):
            original_packet = next(c for c in inputs["cases"] if c["caseId"] == row["caseId"])["userPacketJson"]
            self.assertEqual(row["exactPrompt"]["userPacketJson"].encode(), original_packet.encode())
            expected = mac.MacRuntime.payload(row["exactPrompt"])
            expected["format"] = inputs["schema"]; expected["options"]["use_mmap"] = True
            self.assertTrue(r.oracle_v4.same(requests[row["caseId"]], expected))
        probe = mac.MacRuntime.payload(r.PROBE); probe["options"]["use_mmap"] = True
        self.assertTrue(r.oracle_v4.same(requests["probe"], probe))

    def test_revision_binds_every_effective_request_rendering_and_prior_revision(self):
        prior = "a" * 64
        revision = r.definition(self.revision_id, self.instructions, self.counts, prior)
        requests = r.check_revision(revision, self.counts)
        self.assertEqual(revision["previousRevisionSha256"], prior)
        self.assertEqual(list(requests), r.e.IDS + ["probe"])
        self.assertEqual(revision["requestSha256"], {key: r.e.digest(r.e.compact(value)) for key, value in requests.items()})
        self.assertIsNone(revision["providerMeasuredInputTokens"])
        self.assertEqual(revision["productionAdmission"], "closed")

    def test_missing_old_wrong_typed_or_unsupported_counts_refuse(self):
        for field, value in (("version", "offline-vocab-only-inspection-v1"), ("tensorWeightsLoaded", 0),
                             ("generationCalled", True), ("inferenceContextCreated", None),
                             ("cases", self.counts["cases"][:-1]), ("tokenizer", {}), ("inputSha256", "a" * 64)):
            evidence = copy.deepcopy(self.counts); evidence[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): r.check_counts(self.instructions, evidence)
        for field, value in (("tokens", True), ("tokens", None), ("tokens", 2305), ("tokens", 100.0),
                             ("tokensSha256", None), ("renderedSha256", "a" * 64), ("renderedUtf8Bytes", 10.0),
                             ("caseId", "i02")):
            evidence = copy.deepcopy(self.counts); evidence["cases"][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError): r.check_counts(self.instructions, evidence)
        with self.assertRaises(ValueError): r.check_counts(self.instructions + " changed", self.counts)

    def test_revision_drift_fails_before_request_use(self):
        revision = r.definition(self.revision_id, self.instructions, self.counts)
        for field, value in (("profile", "context-intake-extraction-v3"), ("oracleSha256", "a" * 64),
                             ("systemInstructions", self.instructions + " changed"), ("requestSha256", {}),
                             ("toolingSha256", {}), ("runtime", {}), ("model", {}), ("contextTokens", 8192),
                             ("outputReserveTokens", 1536.0), ("extra", 1)):
            changed = copy.deepcopy(revision); changed[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): r.check_revision(changed, self.counts)
        with patch.object(r, "tooling", return_value={}):
            with self.assertRaises(ValueError): r.check_revision(revision, self.counts)

    def test_private_revision_is_exclusive_and_no_provider_callback_exists(self):
        # This temporary fixture is explicitly synthetic and deleted after the test.
        with tempfile.TemporaryDirectory(prefix="intake-v4-fake-revision-", dir="/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode") as directory:
            path = Path(directory); path.chmod(0o700)
            r.freeze(path, self.revision_id, self.instructions, self.counts)
            before = {p.name: p.read_bytes() for p in path.iterdir()}
            with self.assertRaises(ValueError): r.freeze(path, self.revision_id, self.instructions, self.counts)
            self.assertEqual(before, {p.name: p.read_bytes() for p in path.iterdir()})
            self.assertTrue(all(p.stat().st_mode & 0o777 == 0o600 for p in path.iterdir()))

    def test_counter_refuses_non_linux_before_library_loading(self):
        import count_v4 as counter
        with patch.object(counter.sys, "platform", "darwin"), patch.object(counter.C, "CDLL") as load:
            with self.assertRaises(ValueError): counter.count({}, Path("missing"), Path("missing"), {})
        load.assert_not_called()

    def test_standalone_counter_hashes_identical_ordered_unicode_input_bytes(self):
        import count_v4 as counter
        wrapper = {"input": r.counter_input(self.instructions), "tokenizer": r.TOKENIZER}
        self.assertEqual(counter.compact(wrapper), r.e.compact(wrapper))

    def test_revision_and_counter_records_do_not_alias_constants(self):
        inputs = r.counter_input(self.instructions)
        inputs["cases"][-1]["exactPrompt"]["systemInstructions"] = "mutated"
        self.assertEqual(r.PROBE["systemInstructions"], "Return JSON only.")
        prepared = r.definition(self.revision_id, self.instructions, self.counts)
        prepared["model"]["candidate"] = "mutated"
        prepared["tokenizer"]["addSpecial"] = False
        prepared["runtime"]["vmCpuCount"] = 99
        self.assertEqual(r.replay_v4.MODEL["candidate"], "qwen3:8b")
        self.assertTrue(r.TOKENIZER["addSpecial"])
        import mac_runtime as mac
        self.assertEqual(mac.IDENTITY["vmCpuCount"], 6)


if __name__ == "__main__": unittest.main()
