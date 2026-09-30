import unittest

from run_on_pi import CASES, review_flags


class BenchmarkChecks(unittest.TestCase):
    def valid(self):
        return {"observations": [{"text": "Recorded activity was lower in 2025-01-08/2025-01-14 than 2025-01-01/2025-01-07.",
                                  "evidence_ids": ["activity-before", "activity-after"]}],
                "missing_metrics": ["garmin_fitness_age"],
                "limitations": ["association is not causation"]}

    def test_valid_shape(self):
        self.assertEqual(review_flags(self.valid(), CASES[0]), [])

    def test_invented_evidence_is_flagged(self):
        result = self.valid()
        result['observations'][0]['evidence_ids'] = ['invented-garmin-score']
        self.assertIn('invalid_or_missing_evidence', review_flags(result, CASES[0]))

    def test_prescription_and_missing_disclosure_are_flagged(self):
        result = self.valid()
        result['observations'][0]['text'] += ' You should train harder.'
        result['missing_metrics'] = []
        flags = review_flags(result, CASES[0])
        self.assertIn('potential_causation_or_prescription', flags)
        self.assertIn('missing_metric_not_disclosed', flags)

    def test_fixtures_are_synthetic(self):
        for case in CASES:
            for fact in case['facts']:
                self.assertEqual(fact['source'], 'synthetic-fixture')


if __name__ == '__main__':
    unittest.main()
