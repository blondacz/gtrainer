from pathlib import Path
from unittest.mock import patch
import os
import tempfile
import unittest

import replay_v5 as replay
import suite_v5 as suite

TEMP_ROOT = (Path(tempfile.gettempdir()) / "opencode").resolve()
TEMP_ROOT = TEMP_ROOT if TEMP_ROOT.is_dir() else None


class OfflineReplayV5Test(unittest.TestCase):
    def compiler_result(self, environment, mutate=None):
        directory = Path(environment["GTRAINER_INTAKE_V5_VALIDATION_DIR"])
        request = replay.private.read_json(directory / "request.json")
        _, inputs, _, fixtures = suite.verify()
        results = []
        for row in request["requests"]:
            expected = next(p["userPacketJson"] for p in inputs["cases"] if p["caseId"] == row["caseId"])
            self.assertEqual(row["packetJson"], expected)
            self.assertEqual(row["packetSha256"], suite.e.digest(expected.encode()))
            raw = replay.private.private_read(directory / (row["caseId"] + ".raw"))
            self.assertEqual(row["rawSha256"], suite.e.digest(raw))
            state = {"wire": "accepted", "binding": "accepted", "semantic": "accepted", "reason": None,
                     "assembledDraft": suite.e.compact(suite.assembled(row["caseId"], fixtures[row["caseId"]], inputs)).decode()}
            results.append({"caseId": row["caseId"], "packetSha256": row["packetSha256"],
                            "rawSha256": row["rawSha256"], "stages": state})
        result = {"version": replay.VERSION, "profile": suite.PROFILE,
                  "suiteManifestSha256": request["suiteManifestSha256"], "results": results}
        if mutate:
            mutate(result)
        replay.private.private_write(directory / "result.json", suite.e.compact(result))

    def test_exact_packet_result_bindings_and_private_artifacts(self):
        fixtures = suite.verify()[3]
        raw = suite.e.compact(fixtures["u01"]).decode()
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as temporary:
            def compiler(command, environment, log, timeout):
                self.assertIn("--offline", command)
                self.assertLessEqual(timeout, 600)
                self.compiler_result(environment)
            with patch.object(replay.replay_v4, "bounded_validation", compiler):
                result = replay.kotlin_validate(Path(temporary), [{"caseId": "u01", "raw": raw, "rawSha256": suite.e.digest(raw.encode())}])
            self.assertEqual(result["u01"]["semantic"], "accepted")
            self.assertEqual((Path(temporary) / "validation/request.json").stat().st_mode & 0o777, 0o600)

    def test_cross_suite_and_packet_raw_result_substitution_refused(self):
        raw = suite.e.compact(suite.verify()[3]["u01"]).decode()
        mutations = [lambda r: r.update(profile="context-intake-extraction-v4"),
                     lambda r: r.update(suiteManifestSha256="0" * 64),
                     lambda r: r["results"][0].update(packetSha256="0" * 64),
                     lambda r: r["results"][0].update(rawSha256="0" * 64),
                     lambda r: r["results"][0].update(caseId="u02")]
        for mutate in mutations:
            with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as temporary:
                def compiler(command, environment, log, timeout):
                    self.compiler_result(environment, mutate)
                with patch.object(replay.replay_v4, "bounded_validation", compiler), self.assertRaises(ValueError):
                    replay.kotlin_validate(Path(temporary), [{"caseId": "u01", "raw": raw, "rawSha256": suite.e.digest(raw.encode())}])

    def test_kotlin_stage_to_oracle_witness_path_without_compiler(self):
        _, inputs, _, fixtures = suite.verify()
        def explicit_test_validator(directory, requests):
            self.assertEqual(requests, [{"caseId": case_id,
                "raw": suite.e.compact(fixtures[case_id]).decode(),
                "rawSha256": suite.e.digest(suite.e.compact(fixtures[case_id]))} for case_id in suite.IDS])
            return {request["caseId"]: {"wire": "accepted", "binding": "accepted", "semantic": "accepted",
                "reason": None, "assembledDraft": suite.e.compact(suite.assembled(request["caseId"], fixtures[request["caseId"]], inputs)).decode()}
                for request in requests}
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as temporary:
            report = replay.replay_witnesses(Path(temporary), validator=explicit_test_validator)
        self.assertEqual(report["suiteConformance"], "passed")
        self.assertEqual(len(report["cases"]), 8)
        self.assertTrue(all(row["oracle"]["status"] == "passed" for row in report["cases"]))
        self.assertEqual(report["modelCalls"], 0)

    def test_bad_identity_and_digest_refuse_before_compiler(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as temporary:
            directory = Path(temporary)
            directory.chmod(0o700)
            for request in (
                {"caseId": "../escape", "raw": "{}", "rawSha256": "0" * 64},
                {"caseId": "u01", "raw": "{}", "rawSha256": "0" * 64},
                {"caseId": "u01", "raw": "{}", "rawSha256": suite.e.digest(b"{}"), "extra": True},
            ):
                with self.assertRaises(ValueError):
                    replay.kotlin_validate(directory, [request])
            self.assertFalse((directory / "validation").exists())

    def test_duplicate_batch_ids_refuse(self):
        with tempfile.TemporaryDirectory(dir=TEMP_ROOT) as temporary:
            directory = Path(temporary)
            directory.chmod(0o700)
            raw = "{}"
            request = {"caseId": "u01", "raw": raw, "rawSha256": suite.e.digest(raw.encode())}
            with self.assertRaises(ValueError):
                replay.kotlin_validate(directory, [request, request])
            self.assertFalse((directory / "validation").exists())


@unittest.skipUnless(os.environ.get("INTAKE_V4_KOTLIN") == "1", "Explicit offline Kotlin integration required")
class KotlinIntegrationV5(unittest.TestCase):
    def test_all_eight_fresh_witnesses_then_real_negative_stages(self):
        retained = Path(os.environ["INTAKE_V4_RETAIN_INTEGRATION"]).resolve()
        positive = retained / "v5-positive"
        positive.mkdir(mode=0o700)
        report = replay.replay_witnesses(positive)
        replay.private.private_write(positive / "report.json", suite.e.compact(report))
        self.assertEqual(report["suiteConformance"], "passed")
        self.assertEqual(len(report["cases"]), 8)
        self.assertTrue(all(row["stages"]["semantic"] == "accepted" for row in report["cases"]))
        fixtures = suite.verify()[3]
        negatives = {}
        negatives["u01"] = suite.e.compact(fixtures["u01"]).decode().replace('"frequency":"one_off"', '"frequency":"weekly"')
        negatives["u02"] = suite.e.compact(fixtures["u02"]).decode().replace('["startsLocal","endsLocal"', '["startsLocal","startsLocal","endsLocal"')
        negatives["u03"] = suite.e.compact(fixtures["u03"]).decode().replace('A rehearsal on Tuesday evening is tentative', 'Absent source')
        negatives["u05"] = "{}"
        destination = retained / "v5-negatives"
        destination.mkdir(mode=0o700)
        requests = [{"caseId": key, "raw": raw, "rawSha256": suite.e.digest(raw.encode())} for key, raw in negatives.items()]
        result = replay.kotlin_validate(destination, requests)
        self.assertEqual(result["u01"]["semantic"], "rejected")
        self.assertEqual(result["u02"]["wire"], "rejected")
        self.assertEqual(result["u03"]["binding"], "rejected")
        self.assertEqual(result["u05"]["wire"], "rejected")
        replay.private.private_write(destination / "verified-negative-stages.json", suite.e.compact(result))


if __name__ == "__main__":
    unittest.main()
