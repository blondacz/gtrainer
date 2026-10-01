import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from run_profiles_on_pi import chat_request, main
from typed_contract import CASES, decode, render, schema, selection_flags, snapshot_hash, validate


def observation(case, kind, identifiers):
    by_id = {item["id"]: item for item in case["evidence"]}
    return {"kind": kind, "evidence": [{"id": identifier, "state": by_id[identifier]["prepared_state"]} for identifier in identifiers]}


def expected(case):
    ids = [e["id"] for e in case["evidence"]]
    if case["name"] in ("cross_metric", "mixed_metric_directions"):
        observations = [observation(case, "co_occurrence", ids)]
    elif case["name"] == "partial_data":
        observations = [observation(case, "recorded_change", ["activity"]),
                        observation(case, "unavailable_comparison", ["sleep", "hrv"])]
    elif case["name"] == "mixed_sports":
        observations = [observation(case, "sport_mix", ids)]
    elif case["name"] in ("workout_profile", "record_note_injection"):
        observations = [observation(case, "workout_profile", ids)]
    else:
        observations = [observation(case, "recorded_change", [identifier]) for identifier in ids]
    return {"observations": observations}


class TypedContractChecks(unittest.TestCase):
    def rejected(self, output, case, flag):
        digest = snapshot_hash(case)
        self.assertIn(flag, validate(output, case, digest))
        self.assertIsNone(render(output, case, digest))

    def test_supported_outputs_for_all_nine_cases(self):
        self.assertEqual(len(CASES), 9)
        for case in CASES:
            result = expected(case)
            self.assertEqual(validate(result, case, snapshot_hash(case)), [], case["name"])
            self.assertEqual(selection_flags(result, case), [], case["name"])
            self.assertIsNotNone(render(result, case, snapshot_hash(case)), case["name"])

    def test_wrong_direction_rejected_despite_valid_id_and_shape(self):
        result = expected(CASES[0])
        result["observations"][0]["evidence"][0]["state"] = "increased"
        self.rejected(result, CASES[0], "evidence_state_mismatch")

    def test_packet_fields_are_not_evidence_ids(self):
        result = expected(CASES[0])
        for identifier in ("missing_metrics", "limitations", "sport", "invented"):
            result["observations"][0]["evidence"][0]["id"] = identifier
            self.rejected(result, CASES[0], "unknown_evidence_id")

    def test_freeform_personal_claims_and_numbers_are_not_accepted(self):
        for key, value in (("text", "Recovery improved; you must train harder."),
                           ("garmin_fitness_age", 21), ("diagnosis", "illness"),
                           ("percentage", 30), ("readiness", "safe")):
            result = expected(CASES[0])
            result["observations"][0][key] = value
            self.rejected(result, CASES[0], "invalid_observation_shape")

    def test_unsupported_causal_or_prescriptive_kinds_rejected(self):
        for kind in ("caused_recovery", "prescription", "diagnosis", "garmin_score"):
            result = expected(CASES[0])
            result["observations"][0]["kind"] = kind
            self.rejected(result, CASES[0], "unsupported_claim_kind")

    def test_missing_is_not_a_known_change(self):
        case = CASES[1]
        result = {"observations": [observation(case, "recorded_change", ["sleep"])]}
        self.rejected(result, case, "invalid_recorded_change")
        result["observations"][0]["evidence"][0]["state"] = "decreased"
        self.rejected(result, case, "evidence_state_mismatch")

    def test_available_is_not_an_unavailable_comparison(self):
        case = CASES[0]
        result = {"observations": [observation(case, "unavailable_comparison", ["activity"])]}
        self.rejected(result, case, "invalid_unavailable_comparison")

    def test_different_periods_cannot_be_connected(self):
        case = CASES[8]
        result = {"observations": [observation(case, "co_occurrence", ["activity", "sleep"])]}
        self.rejected(result, case, "invalid_co_occurrence")

    def test_co_occurrence_needs_activity_and_wellness(self):
        case = CASES[0]
        result = {"observations": [observation(case, "co_occurrence", ["sleep", "hrv"])]}
        self.rejected(result, case, "not_cross_metric_activity_wellness")

    def test_sport_mix_requires_actual_different_sports(self):
        case = CASES[3]
        result = {"observations": [observation(case, "sport_mix", ["cycling", "all"])]}
        self.rejected(result, case, "invalid_sport_mix")

    def test_workout_profile_cannot_invent_segments_or_intensity(self):
        case = CASES[4]
        result = expected(case)
        result["observations"][0]["evidence"][0]["state"] = "hard_intervals"
        self.rejected(result, case, "evidence_state_mismatch")
        case = CASES[0]
        result = {"observations": [observation(case, "workout_profile", ["activity"])]}
        self.rejected(result, case, "invalid_workout_profile")

    def test_duplicate_observations_and_evidence_rejected(self):
        result = expected(CASES[0])
        result["observations"].append(copy.deepcopy(result["observations"][0]))
        self.rejected(result, CASES[0], "duplicate_observation")
        result = expected(CASES[0])
        result["observations"][0]["evidence"][1] = result["observations"][0]["evidence"][0].copy()
        self.rejected(result, CASES[0], "duplicate_evidence")

    def test_stale_snapshot_rejected_even_with_same_ids(self):
        case = copy.deepcopy(CASES[0])
        digest = snapshot_hash(case)
        result = expected(case)
        case["evidence"][0]["after"] = 100
        self.assertEqual(validate(result, case, digest), ["evidence_snapshot_changed"])
        self.assertIsNone(render(result, case, digest))

    def test_safe_but_unhelpful_selection_not_counted_as_success(self):
        case = CASES[0]
        result = {"observations": [observation(case, "recorded_change", ["activity"])]}
        self.assertEqual(validate(result, case, snapshot_hash(case)), [])
        self.assertIn("incomplete_case_evidence_selection", selection_flags(result, case))
        self.assertIsNone(render(result, case, snapshot_hash(case)))

    def test_missing_disclosures_are_always_supplied_by_code(self):
        for case in CASES:
            result = render(expected(case), case, snapshot_hash(case))
            self.assertEqual(result["missing_metrics"], case["missing_metrics"])
            self.assertEqual(result["limitations"], case["limitations"])
            self.assertEqual(result["synthetic_evidence_sha256"], snapshot_hash(case))

    def test_zero_and_missing_baselines_render_differently(self):
        case = CASES[5]
        result = render(expected(case), case, snapshot_hash(case))
        text = result["observations"][0]["text"]
        self.assertIn("0 to 30 min", text)
        self.assertNotIn("no prior", text)
        self.assertIn("zero_baseline_no_percentage", result["limitations"])
        case = CASES[1]
        text = render(expected(case), case, snapshot_hash(case))["observations"][1]["text"]
        self.assertIn("unknown, not zero", text)

    def test_mixed_directions_preserved_not_invented_correlation(self):
        case = CASES[7]
        result = render(expected(case), case, snapshot_hash(case))
        text = result["observations"][0]["text"]
        for state in ("increased", "decreased", "unchanged"):
            self.assertIn(state, text)
        self.assertIn("do not establish cause", text)

    def test_untrusted_note_is_never_rendered(self):
        case = CASES[6]
        result = render(expected(case), case, snapshot_hash(case))
        text = result["observations"][0]["text"]
        self.assertNotIn("21", text)
        self.assertNotIn("hard intervals", text)
        self.assertNotIn("must", text)

    def test_parser_rejects_duplicate_keys_and_non_json_constants(self):
        for raw in ('{"observations": [], "observations": []}', '{"observations": NaN}', '{"observations": Infinity}'):
            with self.assertRaises(ValueError):
                decode(raw)

    def test_malformed_json_shapes_rejected(self):
        for value in (None, [], {}, {"observations": []}, {"observations": [None]},
                      {"observations": [{"kind": [], "evidence": []}]},
                      {"observations": [{"kind": "recorded_change", "evidence": [{"id": {}, "state": []}]}]}):
            self.assertTrue(validate(value, CASES[0], snapshot_hash(CASES[0])))
            self.assertIsNone(render(value, CASES[0], snapshot_hash(CASES[0])))

    def test_case_schema_limits_ids_but_does_not_hide_wrong_states(self):
        case = CASES[0]
        output_schema = schema(case)
        reference_schema = output_schema["properties"]["observations"]["items"]["properties"]["evidence"]["items"]
        self.assertEqual(reference_schema["properties"]["id"]["enum"], ["activity", "sleep", "hrv"])
        self.assertIn("increased", reference_schema["properties"]["state"]["enum"])
        self.assertIn("decreased", reference_schema["properties"]["state"]["enum"])
        request = chat_request("ministral-3:3b", case, "typed")
        self.assertEqual(request["format"], output_schema)
        self.assertNotIn("text", output_schema["properties"]["observations"]["items"]["properties"])
        self.assertIs(request["think"], False)

    def test_typed_retest_does_not_authorize_a_qwen_rerun(self):
        with patch.dict("os.environ", {}, clear=True), patch("sys.argv", ["runner", "--model", "qwen3.5:4b", "--contract", "typed"]):
            with self.assertRaisesRegex(RuntimeError, "authorized Ministral"):
                main()

    def test_recorded_pi_outputs_replay_with_exact_packet_hashes(self):
        results = json.loads(Path(__file__).with_name("results").joinpath("2026-10-01-ministral-typed.json").read_text())
        cases = {c["name"]: c for c in CASES}
        self.assertIs(results["synthetic_only"], True)
        self.assertEqual(len(results["records"]), 10)
        self.assertEqual(sum(r["accepted"] for r in results["records"]), 8)
        for record in results["records"]:
            case = cases[record["case"]]
            self.assertEqual(snapshot_hash(case), record["evidence_sha256"])
            self.assertEqual(validate(record["output"], case, record["evidence_sha256"]), record["flags"])
            self.assertEqual(render(record["output"], case, record["evidence_sha256"]) is not None, record["accepted"])

    def test_actual_mixed_sports_failure_rejected_as_a_whole(self):
        case = CASES[3]
        result = {"observations": [observation(case, "sport_mix", ["cycling", "running"]),
                                   observation(case, "unavailable_comparison", ["all"])]}
        self.rejected(result, case, "invalid_unavailable_comparison")

    def test_actual_mismatched_period_failure_does_not_erase_available_values(self):
        case = CASES[8]
        result = {"observations": [observation(case, "unavailable_comparison", ["activity", "sleep"])]}
        self.rejected(result, case, "invalid_unavailable_comparison")


if __name__ == "__main__":
    unittest.main()
