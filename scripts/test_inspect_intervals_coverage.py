import json
from datetime import date
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from inspect_intervals_coverage import ApiReadError, fetch_records, known_origins, load_key, summarize


class CoverageInspectionTests(unittest.TestCase):
    def test_summary_never_exposes_record_values_or_arbitrary_fields(self):
        records = [{"id": "synthetic-private-id", "name": "private free text",
                    "weight": 73.456789, "bodyFat": None, "source": "GARMIN_CONNECT",
                    "private_custom_field": "private custom value"}]
        output = summarize(records, "wellness", date(2026, 9, 30))
        serialized = json.dumps(output)
        for forbidden in ("synthetic-private-id", "private free text", "73.456789",
                          "private_custom_field", "private custom value"):
            self.assertNotIn(forbidden, serialized)
        self.assertEqual(output["known_origin_labels"], ["GARMIN_CONNECT"])
        fields = {item["field"]: item for item in output["fields"]}
        self.assertEqual(fields["weight"]["non_null"], 1)
        self.assertEqual(fields["bodyFat"]["non_null"], 0)
        self.assertEqual(output["scale_device_origin"], "unverified")

    def test_missing_zero_and_false_are_distinct(self):
        result = summarize([{}, {"steps": 0}, {"steps": None}, {"steps": False}],
                           "wellness", date(2026, 9, 30))
        steps = next(item for item in result["fields"] if item["field"] == "steps")
        self.assertEqual(steps, {"field": "steps", "present": 3, "non_null": 2})

    def test_dates_are_reduced_to_freshness_not_output(self):
        result = summarize([{"start_date_local": "2026-09-29T15:00:00"}],
                           "activities", date(2026, 9, 30))
        self.assertEqual(result["freshness_band"], "latest observed date within 1 day")
        self.assertNotIn("2026-09-29", json.dumps(result))
        self.assertEqual(result["upstream_sync_status"], "unverified")

    def test_unknown_source_free_text_is_not_output(self):
        self.assertEqual(known_origins({"source": "private source", "nested": ["garmin"]}),
                         {"GARMIN"})

    def test_auth_uses_stdin_get_only_and_does_not_follow_redirects(self):
        with patch('inspect_intervals_coverage.subprocess.run',
                   return_value=SimpleNamespace(returncode=0, stdout='[]\n200')) as run:
            self.assertEqual(fetch_records('synthetic-key', 'activities',
                                           date(2026, 9, 1), date(2026, 9, 30)), [])
        args, kwargs = run.call_args
        self.assertNotIn('synthetic-key', str(args))
        self.assertIn('Authorization: Basic', kwargs['input'])
        self.assertNotIn('--location', args[0])
        self.assertIn('GET', args[0])

    def test_error_body_is_not_exposed(self):
        with patch('inspect_intervals_coverage.subprocess.run',
                   return_value=SimpleNamespace(returncode=0, stdout='private error body\n403')):
            with self.assertRaises(ApiReadError) as caught:
                fetch_records('synthetic-key', 'wellness', date(2026, 9, 1), date(2026, 9, 30))
        self.assertEqual(caught.exception.status, '403')
        self.assertNotIn('private error body', str(caught.exception))

    def test_key_requires_private_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "key"
            path.write_text("synthetic-key\n")
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                load_key(path)
            path.chmod(0o600)
            self.assertEqual(load_key(path), "synthetic-key")


if __name__ == "__main__":
    unittest.main()
