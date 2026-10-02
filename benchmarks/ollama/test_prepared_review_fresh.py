import ast
import base64
import copy
from datetime import date, timedelta
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

import app_fixtures
import app_unseen_fixtures
import prepared_review as contract
import prepared_review_fresh_fixtures as fresh
import prepared_review_suites as suites
import run_app_on_pi as infra
import run_prepared_review_on_pi as runner
import summarize_prepared_review as replay
import test_app_prompt_experiment as oracle


class FreshPreparedReviewTest(unittest.TestCase):
    def test_suite_is_ten_fresh_usable_cases_and_two_no_call_gates(self):
        cases, requests, rotations = suites.suite_data('fresh-v1')
        self.assertEqual(len(cases), 12)
        self.assertEqual(len(requests), 10)
        self.assertEqual(len(rotations), 12)
        self.assertEqual(contract.digest(cases), '126e0c01af3be2a0537b2d01d113a9f7cfcc639869d0408d390fba5f56f74579')
        self.assertEqual(suites.rubric_hash(requests), 'fee3eaa5931741eb92e15d0257d9d3dd4bfb4f6e258516edfe1ea40a67ebb2f4')
        self.assertEqual({c['name'] for c in cases[:10]}, set(requests))
        self.assertTrue(set(requests).isdisjoint(contract.REQUESTS))
        self.assertTrue({(c['oldest'], c['newest']) for c in cases}.isdisjoint(
            {(c['oldest'], c['newest']) for c in app_unseen_fixtures.cases()}))
        self.assertTrue(all(c['synthetic_only'] and c['fixture_split'] == 'fresh-prepared-focus-structural-variants-v1' for c in cases))
        for case in cases:
            self.assertEqual(date.fromisoformat(case['previousOldest']), date.fromisoformat(case['oldest']) - timedelta(days=7))
            self.assertEqual(date.fromisoformat(case['previousNewest']), date.fromisoformat(case['newest']) - timedelta(days=7))
            self.assertEqual([s[0] for s in case['sports']], sorted(s[0] for s in case['sports']))
        self.assertEqual(suites.suite_data()[0], app_unseen_fixtures.cases())
        self.assertEqual(suites.suite_data()[1], contract.REQUESTS)
        with self.assertRaises(ValueError):
            suites.suite_data('unknown')

    def test_source_data_is_synthetic_independent_and_uses_only_existing_history_scope(self):
        cases = fresh.cases()
        activity, wellness = app_fixtures.records(cases)
        self.assertEqual((len(activity), len(wellness)), (58, 48))
        self.assertTrue(all(item['source'] == 'synthetic-fixture' for item in activity + wellness))
        self.assertTrue(all(item['sourceRecordId'].startswith('synthetic-') for item in activity + wellness))
        self.assertTrue(all(item['startLocal'].startswith('2020-') for item in activity))
        self.assertTrue(all(item['date'].startswith('2020-') for item in wellness))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fresh.sqlite3'
            app_fixtures.database(path, cases)
            with sqlite3.connect(path) as database:
                self.assertEqual(database.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
                self.assertEqual(database.execute('SELECT COUNT(*) FROM activities').fetchone()[0], 58)
            with self.assertRaises(FileExistsError):
                app_fixtures.database(path, cases)

    def test_complete_facts_and_feasible_rubric_with_varied_candidate_positions(self):
        cases, requests, rotations = suites.suite_data('fresh-v1')
        positions = {}
        for index, case in enumerate(cases):
            packet, summary = oracle.PromptExperimentTest().fixture_oracle(case)
            replay.verify_fixture(case, packet)
            if case['name'] not in requests:
                self.assertFalse(contract.sufficient(packet, summary))
                continue
            self.assertTrue(contract.sufficient(packet, summary))
            focus, expected = requests[case['name']]
            prepared = contract.prepare(packet, focus, rotations[index])
            facts = contract.factual_rendering(prepared)['facts']
            self.assertEqual({f['evidence_id'] for f in facts}, {e['id'] for e in packet['evidence']})
            self.assertEqual(len(facts), len(packet['evidence']))
            candidate = next(c for c in prepared['candidates'] if c['kind'] in expected)
            selection = json.dumps({'focus_ids': [candidate['id']]})
            self.assertEqual(contract.relevance_flags(selection, prepared, expected), [])
            positions.setdefault(focus, set()).add(candidate['id'])
            payload = contract.request('qwen3.5:4b', prepared)
            self.assertEqual(payload['messages'][0]['content'], contract.SYSTEM)
            self.assertNotIn('candidate_rotation', payload['messages'][1]['content'])
            self.assertNotIn('expected', payload['messages'][1]['content'])
        self.assertTrue(all(len(ids) >= 2 for ids in positions.values()))
        self.assertEqual(positions['daily_combined'], {'f0', 'f1', 'f2'})

    def test_fresh_edge_cases_cover_new_hrv_gaps_and_zero_baselines(self):
        cases = {c['name']: c for c in fresh.cases()}
        self.assertEqual(cases['fresh_gap_current_hrv']['hrv'], (71, None))
        self.assertEqual(cases['fresh_gap_previous_hrv']['hrv'], (None, 57))
        self.assertEqual(cases['fresh_daily_zero_stable']['sports'][0][1], 0)
        self.assertEqual(cases['fresh_daily_zero_opposed']['sports'][0][1], 0)
        self.assertEqual(cases['fresh_wellness_stable']['sleep'][0], cases['fresh_wellness_stable']['sleep'][1])
        self.assertEqual(cases['fresh_wellness_stable']['hrv'][0], cases['fresh_wellness_stable']['hrv'][1])

    def test_fresh_bundle_is_explicit_qwen35_only_with_unchanged_contract_and_caps(self):
        source = runner.package('a' * 32, 'qwen3.5:4b', 'fresh-v1')
        compile(source, '<fresh-review-bundle>', 'exec')
        bundled = next(ast.literal_eval(node.value) for node in ast.parse(source).body
                       if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name) and node.targets[0].id == 'sources')
        self.assertIn('prepared_review_fresh_fixtures', bundled)
        self.assertIn('prepared_review_suites', bundled)
        self.assertEqual(bundled['prepared_review'], (Path(__file__).parent / 'prepared_review.py').read_text())
        self.assertIn('--suite', source)
        self.assertIn('fresh-v1', source)
        pod = infra.manifest('a' * 32, b'synthetic', bundled['app_capture_proxy'], 'qwen3.5:4b')['items'][-1]['spec']
        self.assertEqual(pod['containers'][0]['resources']['limits']['memory'], '5Gi')
        self.assertEqual(pod['containers'][0]['resources']['limits']['cpu'], '3')
        for model, suite in (('qwen3:4b-instruct', 'fresh-v1'), ('qwen3.5:4b', 'unknown')):
            with self.assertRaises(ValueError):
                runner.package('a' * 32, model, suite)

    def test_fresh_preflight_replay_keeps_staging_failure_separate_from_quality(self):
        cases, requests, _ = suites.suite_data('fresh-v1')
        baseline = [{'ready': True, 'restarts': 0}]
        rows = [{'phase': 'operator_sources', 'source_sha256': {'synthetic': 'frozen'}},
                {'phase': 'prepared_preflight', 'synthetic_only': True, 'profile': contract.PROFILE,
                 'fixture_suite': 'fresh-v1', 'fresh_structural_variants': True, 'external_blinded_holdout': False,
                 'settings': contract.OPTIONS, 'call_seconds': contract.CALL_SECONDS, 'job_seconds': contract.JOB_SECONDS,
                 'model': app_fixtures.model_profile('qwen3.5:4b'), 'baseline_live_state': baseline,
                 'system_sha256': infra.digest(contract.SYSTEM.encode()), 'fixture_sha256': contract.digest(cases),
                 'rubric_sha256': suites.rubric_hash(requests)},
                {'phase': 'prepared_failed', 'error_type': 'RuntimeError'},
                {'phase': 'prepared_cleanup', 'temporary_namespace_removed': True, 'final_live_state': baseline}]
        summary = replay.extract(b'\n'.join(contract.compact(row) for row in rows))
        self.assertEqual(summary['fixture_suite'], 'fresh-v1')
        self.assertFalse(summary['complete'])
        self.assertTrue(summary['cleanup_verified'])
        self.assertEqual(summary['planned_usable_cases'], 10)
        self.assertEqual(summary['inference_attempts'], 0)
        changed = copy.deepcopy(rows)
        changed[1]['rubric_sha256'] = 'invalid'
        with self.assertRaises(AssertionError):
            replay.extract(b'\n'.join(contract.compact(row) for row in changed))

    def test_retained_fresh_partial_preserves_unrecorded_attempt_and_subset_counts(self):
        artifact = json.loads((Path(__file__).parent / 'results/2026-10-02-qwen35-prepared-focus-fresh-partial.json').read_text())
        self.assertFalse(artifact['complete'])
        self.assertTrue(artifact['cleanup_verified'])
        self.assertEqual(artifact['fixture_suite'], 'fresh-v1')
        self.assertEqual(artifact['source_jsonl_sha256'], 'aae4a32268d5873cca816e00c511647a050f1e757ec2b89ab9e6adbe5f25de5a')
        self.assertEqual((artifact['planned_usable_cases'], artifact['finalized_cases'], artifact['final_accepted']), (10, 4, 2))
        self.assertEqual((artifact['inference_attempts'], artifact['received_attempts'], artifact['correction_attempts']), (8, 7, 3))
        self.assertEqual(artifact['sparse_no_call_gates'], [])
        self.assertIsNone(artifact['controls'])
        self.assertFalse(artifact['log_scan_passed'])
        self.assertEqual(artifact['failures'][0]['error_type'], 'TimeoutExpired')
        self.assertTrue(any(location['function'] == 'temporary_state' for location in artifact['failures'][0]['code_locations']))
        first = [a for a in artifact['attempts'] if a['attempt'] == 1 and a['result']]
        self.assertEqual(len(first), 5)
        self.assertEqual(sum(a['result']['accepted'] for a in first), 2)
        missing = [a for a in artifact['attempts'] if a['received'] is None]
        self.assertEqual([(a['case'], a['attempt']) for a in missing], [('fresh_daily_zero_stable', 2)])
        self.assertIsNone(missing[0]['result'])
        self.assertFalse(missing[0]['unload_verified'])
        by_name = {c['case']: c for c in artifact['prepared_cases']}
        for attempt in artifact['attempts']:
            if attempt['result'] is None:
                continue
            case = by_name[attempt['case']]
            result = attempt['result']
            self.assertEqual(replay.independent_validity(result['raw_model_content'], case['packet'], case['packet_sha256']), [])
            self.assertEqual(contract.relevance_flags(result['raw_model_content'], case['packet'], fresh.REQUESTS[attempt['case']][1]),
                             result['relevance_flags'])
    def test_retained_durable_completion_keeps_validity_and_relevance_separate(self):
        artifact = json.loads((Path(__file__).parent / 'results/2026-10-02-qwen35-prepared-focus-fresh-durable-complete.json').read_text())
        self.assertTrue(artifact['complete'] and artifact['cleanup_verified'])
        self.assertEqual(artifact['source_jsonl_sha256'], '004e2b783808779d5d758b38c06f20fe3d5716e459ba595a1be2cb686a0bf7ec')
        self.assertEqual((artifact['planned_usable_cases'], artifact['finalized_cases'], artifact['first_pass_accepted'], artifact['final_accepted']), (10, 10, 5, 5))
        self.assertEqual((artifact['inference_attempts'], artifact['received_attempts'], artifact['correction_attempts']), (15, 15, 5))
        self.assertEqual(artifact['fixture_sha256'], contract.digest(fresh.cases()))
        self.assertEqual(artifact['rubric_sha256'], suites.rubric_hash(fresh.REQUESTS))
        self.assertEqual(set(artifact['sparse_no_call_gates']), {c['name'] for c in fresh.cases() if c['name'] not in fresh.REQUESTS})
        self.assertFalse(artifact['external_blinded_holdout'])
        self.assertTrue(artifact['response_export_independent_of_control'])
        self.assertEqual(artifact['control_failures'], [])
        self.assertEqual(artifact['failures'], [])
        self.assertEqual(artifact['control_sample_count'], 144)
        self.assertTrue(artifact['log_scan_passed'])
        self.assertTrue(all(artifact['controls'][k] for k in ('history_unchanged', 'no_app_ai_calls', 'logout_verified')))
        by_name = {c['case']: c for c in artifact['prepared_cases']}
        for attempt in artifact['attempts']:
            case = by_name[attempt['case']]
            result = attempt['result']
            self.assertTrue(attempt['unload_verified'])
            self.assertEqual(result['validity_flags'], [])
            self.assertEqual(replay.independent_validity(result['raw_model_content'], case['packet'], case['packet_sha256']), [])
            self.assertEqual(contract.relevance_flags(result['raw_model_content'], case['packet'], fresh.REQUESTS[attempt['case']][1]), result['relevance_flags'])
            response = json.loads(base64.b64decode(attempt['received']['response_base64'], validate=True))
            self.assertEqual(response['message']['content'], result['raw_model_content'])
            self.assertEqual(result['report_sha256_after_generation'], case['report_sha256'])
            self.assertEqual(result['rendering'], contract.focus_rendering(result['raw_model_content'], case['packet'], case['packet_sha256']) if result['accepted'] else None)
            if attempt['attempt'] == 2:
                first = next(a for a in artifact['attempts'] if a['case'] == attempt['case'] and a['attempt'] == 1)
                self.assertFalse(result['accepted'])
                self.assertEqual(result['raw_model_content'], first['result']['raw_model_content'])
        telemetry = artifact['telemetry']
        self.assertEqual((telemetry['live_health_failures'], telemetry['synthetic_health_failures']), (0, 0))
        self.assertEqual(telemetry['ollama_lifetime_peak_mib'], 5120)
        self.assertGreaterEqual(telemetry['minimum_host_available_mib'], 1200)
        self.assertLess(telemetry['maximum_temperature_c'], 80)

    def test_retained_incomplete_rerun_never_invents_terminal_records(self):
        artifact = json.loads((Path(__file__).parent / 'results/2026-10-02-qwen35-prepared-focus-fresh-rerun-incomplete.json').read_text())
        self.assertFalse(artifact['complete'] or artifact['cleanup_verified'])
        self.assertEqual((artifact['finalized_cases'], artifact['first_pass_accepted'], artifact['final_accepted']), (7, 4, 4))
        self.assertEqual((artifact['inference_attempts'], artifact['received_attempts']), (11, 10))
        self.assertIsNone(artifact['controls'])
        self.assertIsNone(artifact['cleanup'])
        self.assertEqual(artifact['sparse_no_call_gates'], [])
        self.assertEqual(artifact['failures'], [])
        self.assertFalse(artifact['log_scan_passed'])
        missing = [a for a in artifact['attempts'] if a['received'] is None]
        self.assertEqual([(a['case'], a['attempt']) for a in missing], [('fresh_gap_previous_hrv', 1)])
        self.assertIsNone(missing[0]['result'])


if __name__ == '__main__':
    unittest.main()
