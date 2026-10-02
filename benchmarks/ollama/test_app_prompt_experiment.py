import base64
import ast
import copy
import hashlib
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import app_capture_proxy as proxy
import app_fixtures as fixtures
import app_prompt_experiment as experiment
import app_unseen_fixtures
import app_replay
import extract_app_run
import package_app_run
import run_app_on_pi as runner
import summarize_app_run
import test_app_benchmark


class PromptExperimentTest(unittest.TestCase):
    def fixture_oracle(self, case):
        pairs = [(sport, 'movingTime', 'seconds', before * case['activity_samples_per_period'], after * case['activity_samples_per_period'])
                 for sport, before, after in case['sports']]
        pairs += [(None, 'sleepSecs', 'seconds', *case['sleep']), (None, 'hrv', 'ms', *case['hrv'])]
        evidence, facts = [], []
        for index, (sport, metric, unit, before, after) in enumerate(pairs):
            state = 'unavailable' if before is None or after is None else 'increased' if after > before else 'decreased' if after < before else 'unchanged'
            flags = [] if sport else ['incomplete_date_coverage']
            if before is None or after is None:
                flags = ['unavailable_comparison', 'partial_metric_coverage', 'sparse_comparison', 'incomplete_date_coverage']
            elif sport and before == 0:
                flags = ['zero_baseline_no_percentage']
            evidence.append({'id': f'e{index}', 'sport': sport, 'metric': metric, 'unit': unit,
                             'previousOldest': case['previousOldest'], 'previousNewest': case['previousNewest'],
                             'currentOldest': case['oldest'], 'currentNewest': case['newest'],
                             'previousValue': None if before is None else float(before), 'currentValue': None if after is None else float(after),
                             'preparedState': state, 'flags': flags})
            facts += [{'period': period, 'sport': sport, 'metric': metric, 'sampleCount': 0 if value is None else case['activity_samples_per_period'] if sport else 2}
                      for period, value in (('previous', before), ('current', after))]
        return {'evidence': evidence}, {'facts': facts}

    def payload(self):
        packet = {'evidence': [{'id': 'e0', 'preparedState': 'increased'}]}
        payload = {'model': 'qwen3:4b-instruct', 'stream': False, 'think': False, 'keep_alive': '0s',
                   'options': {'num_ctx': 2048, 'num_thread': 3, 'num_predict': 256, 'temperature': 0, 'seed': 42},
                   'messages': [{'role': 'system', 'content': test_app_benchmark.AppBenchmarkTest().system_prompt()},
                                {'role': 'user', 'content': json.dumps(packet)}],
                   'format': {'properties': {'observations': {'items': {'properties': {'evidence': {'items': {
                       'properties': {'id': {'enum': ['e0']}}}}}}}}}}
        return packet, payload, json.dumps(payload, indent=2).encode()

    def test_a_is_exact_bytes_and_b_changes_only_system_message(self):
        _, payload, raw = self.payload()
        skill = experiment.skill_prompt()
        self.assertEqual(experiment.rewrite_body(raw, 'A', skill), raw)
        changed = json.loads(experiment.rewrite_body(raw, 'B', skill))
        expected = copy.deepcopy(payload)
        expected['messages'][0]['content'] = skill
        self.assertEqual(changed, expected)
        self.assertEqual(experiment.BASELINE_PROMPT_SHA256, fixtures.SYSTEM_PROMPT_SHA256)

    def test_unpinned_prompts_unknown_arms_or_empty_skill_refused(self):
        _, payload, raw = self.payload()
        for arm, skill in [('C', 'skill'), ('B', '')]:
            with self.assertRaises(ValueError):
                experiment.rewrite_body(raw, arm, skill)
        payload['messages'][0]['content'] += ' changed'
        with self.assertRaises(ValueError):
            experiment.rewrite_body(json.dumps(payload).encode(), 'B', experiment.skill_prompt())

    def test_sequence_is_six_balanced_pairs_plus_two_no_inference_gates(self):
        sequence = experiment.sequence(fixtures.cases())
        self.assertEqual(len(sequence), 14)
        for index in range(6):
            pair = sequence[2 * index:2 * index + 2]
            self.assertEqual(pair[0][0], pair[1][0])
            self.assertEqual([arm for _, arm in pair], ['prompt_arm_A', 'prompt_arm_B'] if index % 2 == 0 else ['prompt_arm_B', 'prompt_arm_A'])
        self.assertEqual([case['name'] for case, _ in sequence[-2:]], ['sparse_activity', 'missing_current_wellness'])

    def test_experiment_manifest_preserves_limits_images_and_no_live_access(self):
        normal = runner.manifest('a' * 32, b'synthetic', '# proxy', 'qwen3:4b-instruct')
        experimental = runner.manifest('a' * 32, b'synthetic', '# proxy', 'qwen3:4b-instruct', experiment.skill_prompt())
        self.assertEqual(normal['items'][-1]['spec']['containers'][:2], experimental['items'][-1]['spec']['containers'][:2])
        self.assertEqual(experimental['items'][1]['data'].pop('skill.txt'), experiment.skill_prompt())
        experimental['items'][-1]['spec']['containers'][2]['env'].pop()
        self.assertEqual(experimental, normal)
        source = package_app_run.package('a' * 32, 'qwen3:4b-instruct', prompt_experiment=True)
        compile(source, '<synthetic-prompt-bundle>', 'exec')
        self.assertIn('--prompt-experiment', source)
        bundled = next(ast.literal_eval(node.value) for node in ast.parse(source).body
                       if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name) and node.targets[0].id == 'sources')
        self.assertEqual(bundled['skill'], experiment.skill_prompt())
        with self.assertRaises(ValueError):
            package_app_run.package('a' * 32, 'granite4.2:3b', prompt_experiment=True)

    def test_capture_oracle_requires_exact_forwarded_bytes_and_explicit_arm(self):
        packet, payload, raw = self.payload()
        skill = experiment.skill_prompt()
        original = {'event': 'request', 'id': 'synthetic', 'body_sha256': runner.digest(raw),
                    'body_base64': base64.b64encode(raw).decode()}
        forwarded = experiment.rewrite_body(raw, 'B', skill)
        entry = {'event': 'forwarded_request', 'id': 'synthetic', 'prompt_arm': 'B', 'explicit_prompt_experiment': True,
                 'body_sha256': runner.digest(forwarded), 'body_base64': base64.b64encode(forwarded).decode()}
        self.assertEqual(runner.verify_capture([original, entry], packet, payload['model'], 'B', skill), payload)
        with self.assertRaises(AssertionError):
            runner.verify_capture([original], packet, payload['model'], 'B', skill)
        with self.assertRaises(AssertionError):
            runner.verify_capture([original, entry], packet, payload['model'])
        modified = json.loads(forwarded); modified['options']['num_ctx'] = 4096
        entry['body_base64'] = base64.b64encode(json.dumps(modified).encode()).decode()
        entry['body_sha256'] = runner.digest(json.dumps(modified).encode())
        with self.assertRaises(AssertionError):
            runner.verify_capture([original, entry], packet, payload['model'], 'B', skill)

    def test_proxy_labels_actual_forwarded_prompt_and_never_modifies_response(self):
        upstream_bodies = []
        response_bytes = b'{"done":true,"synthetic-marker":"transport-only"}'

        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                upstream_bodies.append(self.rfile.read(int(self.headers['Content-Length'])))
                self.send_response(200); self.send_header('Content-Length', str(len(response_bytes)))
                self.end_headers(); self.wfile.write(response_bytes)

        upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
        observer = ThreadingHTTPServer(('127.0.0.1', 0), proxy.Proxy)
        for server in (upstream, observer):
            threading.Thread(target=server.serve_forever, daemon=True).start()
        original_connection = HTTPConnection
        with tempfile.TemporaryDirectory() as directory:
            capture, fault, arm_file, skill_file, control_file = [Path(directory) / name for name in ('capture', 'fault', 'arm', 'skill', 'control')]
            skill_file.write_text(experiment.skill_prompt())
            control_file.write_text(experiment.skill_prompt())
            _, _, body = self.payload()
            def connection(_host, _port, **kwargs):
                return original_connection('127.0.0.1', upstream.server_port, **kwargs)
            try:
                with patch.dict(os.environ, {'GTRAINER_SYNTHETIC_PROMPT_EXPERIMENT': '1'}), \
                        patch.object(proxy, 'CAPTURE', capture), patch.object(proxy, 'FAULT', fault), \
                        patch.object(proxy, 'PROMPT_ARM', arm_file), patch.object(proxy, 'SKILL_FILE', skill_file), \
                        patch.object(proxy, 'CONTROL_SKILL_FILE', control_file), \
                        patch.object(proxy, 'HTTPConnection', connection):
                    for arm, revised in (('A', False), ('B', False), ('A', True), ('B', True)):
                        os.environ['GTRAINER_SYNTHETIC_CONTROL_SKILL'] = '1' if revised else '0'
                        skill_file.write_text(experiment.skill_prompt('v2') if revised else experiment.skill_prompt())
                        arm_file.write_text(arm)
                        client = original_connection('127.0.0.1', observer.server_port)
                        client.request('POST', '/api/chat', body)
                        reply = client.getresponse()
                        self.assertEqual(reply.status, 200); self.assertEqual(reply.read(), response_bytes)
                        client.close()
                        entries = [json.loads(line) for line in capture.read_text().splitlines()][-3:]
                        self.assertEqual([e['event'] for e in entries], ['request', 'forwarded_request', 'response'])
                        self.assertEqual(base64.b64decode(entries[0]['body_base64']), body)
                        self.assertEqual(base64.b64decode(entries[1]['body_base64']), upstream_bodies[-1])
                        self.assertEqual(entries[1]['prompt_arm'], arm)
                        self.assertEqual(base64.b64decode(entries[2]['body_base64']), response_bytes)
                    self.assertEqual(upstream_bodies[0], body)
                    self.assertEqual(json.loads(upstream_bodies[1])['messages'][0]['content'], experiment.skill_prompt())
                    self.assertEqual(json.loads(upstream_bodies[2])['messages'][0]['content'], experiment.skill_prompt())
                    self.assertEqual(json.loads(upstream_bodies[3])['messages'][0]['content'], experiment.skill_prompt('v2'))
            finally:
                for server in (observer, upstream):
                    server.shutdown(); server.server_close()

    def test_revised_profile_changes_only_versioned_system_messages_and_preserves_legacy(self):
        control, skill = experiment.prompts('card-v2-unseen')
        self.assertEqual(control, experiment.skill_prompt())
        self.assertNotEqual(control, skill)
        self.assertLess(len(skill), len(control))
        self.assertEqual(hashlib.sha256(control.encode()).hexdigest(), '78f49a03d02eb3535a9a2b2bc4301afc954d1475fb5cd796a817740ea59e1850')
        self.assertEqual(hashlib.sha256(skill.encode()).hexdigest(), '0d739563727f87af8c01aa37332a4af1e50c1ff868ace6a2f0ac3cd35d53f991')
        packet, payload, raw = self.payload()
        original = {'event': 'request', 'id': 'synthetic', 'body_sha256': runner.digest(raw),
                    'body_base64': base64.b64encode(raw).decode()}
        for arm, expected_prompt in (('A', control), ('B', skill)):
            sent = experiment.rewrite_body(raw, arm, skill, control)
            expected = copy.deepcopy(payload); expected['messages'][0]['content'] = expected_prompt
            self.assertEqual(json.loads(sent), expected)
            entry = {'event': 'forwarded_request', 'id': 'synthetic', 'prompt_arm': arm,
                     'explicit_prompt_experiment': True, 'body_sha256': runner.digest(sent),
                     'body_base64': base64.b64encode(sent).decode()}
            self.assertEqual(runner.verify_capture([original, entry], packet, payload['model'], arm, skill, control), payload)
        for invalid in ('unknown', 'v3', 'cloud'):
            with self.assertRaises(ValueError):
                experiment.prompts(invalid)
            with self.assertRaises(ValueError):
                experiment.fixture_cases(invalid)
        self.assertEqual(experiment.fixture_cases(), fixtures.cases())
        self.assertEqual(experiment.fixture_cases('card-v2-unseen'), app_unseen_fixtures.cases())

    def test_revised_bundle_manifest_preserve_app_runtime_caps_and_keep_old_card(self):
        control, skill = experiment.prompts('card-v2-unseen')
        normal = runner.manifest('a' * 32, b'synthetic', '# proxy', 'qwen3:4b-instruct', experiment.skill_prompt())
        revised = runner.manifest('a' * 32, b'synthetic', '# proxy', 'qwen3:4b-instruct', skill, control)
        self.assertEqual(revised['items'][1]['data'].pop('control-skill.txt'), control)
        self.assertEqual(revised['items'][1]['data']['skill.txt'], skill)
        revised['items'][1]['data']['skill.txt'] = experiment.skill_prompt()
        revised['items'][-1]['spec']['containers'][2]['env'].pop()
        self.assertEqual(revised, normal)
        source = package_app_run.package('a' * 32, 'qwen3:4b-instruct', True, 'card-v2-unseen')
        compile(source, '<synthetic-revised-card-bundle>', 'exec')
        bundled = next(ast.literal_eval(node.value) for node in ast.parse(source).body
                       if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name) and node.targets[0].id == 'sources')
        self.assertEqual(bundled['control_skill'], control)
        self.assertEqual(bundled['skill'], skill)
        compile(bundled['app_capture_proxy'], '<synthetic-card-proxy>', 'exec')
        self.assertIn('app_unseen_fixtures', bundled)
        for experimental, profile in ((False, 'card-v2-unseen'), (True, 'unknown')):
            with self.assertRaises(ValueError):
                package_app_run.package('a' * 32, 'qwen3:4b-instruct', experimental, profile)

    def test_fresh_fixtures_are_synthetic_distinct_and_seeded_without_replacing_legacy(self):
        old_cases = fixtures.cases()
        fresh = app_unseen_fixtures.cases()
        self.assertEqual(len(fresh), 8)
        self.assertEqual(hashlib.sha256(json.dumps(fresh, separators=(',', ':'), allow_nan=False).encode()).hexdigest(),
                         '76edd08643627a114be99d46d026cacd91ddae2b16e880d254f092d23f98f9fc')
        self.assertTrue(all(c['synthetic_only'] and c['fixture_split'] == 'fresh-structural-variants-v1' for c in fresh))
        self.assertTrue({(c['oldest'], c['newest']) for c in old_cases}.isdisjoint({(c['oldest'], c['newest']) for c in fresh}))
        self.assertTrue({c['name'] for c in old_cases[:6]}.isdisjoint({c['name'] for c in fresh[:6]}))
        self.assertEqual(len(experiment.sequence(fresh)), 14)
        activities, wellness = fixtures.records(fresh)
        self.assertEqual((len(activities), len(wellness)), (38, 32))
        self.assertTrue(all(r['source'] == 'synthetic-fixture' and r['sourceRecordId'].startswith('synthetic-') for r in activities + wellness))
        self.assertTrue(all(r['startLocal'][8:10] in ('15', '21', '22', '28') for r in activities))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fresh.sqlite3'
            fixtures.database(path, fresh)
            import sqlite3
            with sqlite3.connect(path) as database:
                self.assertEqual(database.execute('SELECT count(*) FROM activities').fetchone()[0], 38)
                self.assertEqual(database.execute('SELECT count(*) FROM wellness').fetchone()[0], 32)
                self.assertEqual(database.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
            with self.assertRaises(FileExistsError):
                fixtures.database(path, fresh)
        self.assertEqual(fixtures.cases(), old_cases)

    def test_revised_card_recipe_is_satisfiable_without_relaxing_contract_on_fresh_cases(self):
        # Test-only oracle; never injected into responses or used to repair model output.
        for case in app_unseen_fixtures.cases()[:6]:
            packet, summary = self.fixture_oracle(case)
            evidence = packet['evidence']
            available = [e for e in evidence if e['preparedState'] != 'unavailable']
            group = ([e for e in available if e['sport'] is None] + [e for e in available if e['sport'] is not None])[:3]
            refs = lambda items: [{'id': e['id'], 'state': e['preparedState']} for e in items]
            observations = [{'kind': 'co_occurrence', 'evidence': refs(group)}]
            remainder = [e for e in evidence if e['id'] not in {item['id'] for item in group}]
            if remainder:
                kind = 'unavailable_comparison' if all(e['preparedState'] == 'unavailable' for e in remainder) else 'recorded_change'
                observations.append({'kind': kind, 'evidence': refs(remainder)})
            raw = json.dumps({'observations': observations})
            self.assertEqual(app_replay.validation_flags(raw, packet, summary), [], case['name'])
            self.assertEqual(sum(len(o['evidence']) for o in observations), len(evidence))

    def test_fresh_summary_requires_both_frozen_cards_and_fixture_bindings(self):
        control, skill = experiment.prompts('card-v2-unseen')
        fresh = app_unseen_fixtures.cases()
        baseline = [{'ready': True, 'restarts': 0, 'image': 'synthetic'}]
        metadata = {'phase': 'prompt_experiment', 'profile': 'card-v2-unseen',
                    'skill_prompt_sha256': hashlib.sha256(skill.encode()).hexdigest(),
                    'control_prompt_sha256': hashlib.sha256(control.encode()).hexdigest(),
                    'fixture_cases_sha256': hashlib.sha256(json.dumps(fresh, separators=(',', ':'), allow_nan=False).encode()).hexdigest()}
        records = [{'phase': 'operator_sources', 'synthetic_only': True, 'source_sha256': {}},
                   {'phase': 'preflight', 'synthetic_only': True, 'app_image': 'synthetic', 'app_revision': 'synthetic', 'baseline_live_state': baseline}, metadata]
        for case, repetition in experiment.sequence(fresh):
            sparse = case['name'] in ('sparse_activity', 'missing_current_wellness')
            arm = repetition[-1]
            records.append({'phase': 'case_result', 'synthetic_only': True, 'case': case['name'], 'repetition': repetition,
                            'prompt_arm': arm, 'wall_seconds': 1.0,
                            'app_response': {'status': 'unavailable' if sparse or arm == 'A' else 'available',
                                             'reason': 'insufficient_input' if sparse else 'unusable_model_output' if arm == 'A' else 'validated_typed_observations'},
                            'replay': {'gate': 'insufficient_input' if sparse else 'rejected_output' if arm == 'A' else 'validated',
                                       'packet_sha256': case['name'], 'flags': [], 'prompt_tokens': 900}})
        records += [{'phase': 'telemetry', 'resource_guard_failed': False,
                     'samples': [{'host_available_mib': 3000, 'live_health_ok': True, 'synthetic_health_ok': True}]},
                    {'phase': 'functional_controls'}, {'phase': 'benchmark_complete'},
                    {'phase': 'cleanup', 'temporary_namespace_removed': True, 'final_live_state': baseline}]
        with patch.object(summarize_app_run, 'replay', side_effect=lambda record: record['replay']):
            summary = summarize_app_run.summarize(records)
            self.assertEqual(summary['paired_accepted'], {'A': 0, 'B': 6})
            self.assertEqual(summary['comparable_pairs'], 6)
            for key in ('control_prompt_sha256', 'skill_prompt_sha256', 'fixture_cases_sha256'):
                saved = metadata[key]; metadata[key] = 'unbound'
                with self.assertRaises(AssertionError):
                    summarize_app_run.summarize(records)
                metadata[key] = saved

    def test_worked_examples_and_luna_reference_meet_the_unchanged_contract(self):
        examples = [json.loads(line) for line in experiment.skill_prompt().splitlines() if line.startswith('{"observations"')]
        self.assertEqual(len(examples), 3)
        for example in examples:
            refs = {ref['id']: ref['state'] for claim in example['observations'] for ref in claim['evidence']}
            evidence = [{'id': identifier, 'sport': 'Sport-' + identifier if identifier == 'e0' or (len(refs) == 4 and identifier == 'e1') else None,
                         'metric': 'movingTime' if identifier == 'e0' or (len(refs) == 4 and identifier == 'e1') else 'hrv',
                         'preparedState': state, 'previousOldest': '2021-01-01', 'previousNewest': '2021-01-07',
                         'currentOldest': '2021-01-08', 'currentNewest': '2021-01-14'} for identifier, state in refs.items()]
            facts = [{'period': period, 'sport': e['sport'], 'metric': e['metric'], 'sampleCount': 2}
                     for e in evidence for period in ('previous', 'current')]
            # Give separate wellness metrics to each evidence item, as actual app preparation does.
            for index, item in enumerate(evidence):
                if item['sport'] is None:
                    item['metric'] = 'sleepSecs' if index == len(evidence) - 2 else 'hrv'
                    for period_index in range(2):
                        facts[2 * index + period_index]['metric'] = item['metric']
            self.assertEqual(app_replay.validation_flags(json.dumps(example), {'evidence': evidence}, {'facts': facts}), [])
        reference = json.loads((Path(__file__).parent / 'results/2026-10-02-luna-conversational-reference.json').read_text())
        self.assertEqual(reference['model'], 'openai/gpt-6-luna')
        self.assertEqual(len(reference['cases']), 6)
        for record, case in zip(reference['cases'], fixtures.cases()[:6]):
            self.assertEqual(record['case'], case['name'])
            pairs = [(sport, 'movingTime', before, after) for sport, before, after in case['sports']]
            pairs += [(None, 'sleepSecs', *case['sleep']), (None, 'hrv', *case['hrv'])]
            evidence = [{'id': f'e{index}', 'sport': sport, 'metric': metric,
                         'preparedState': 'unavailable' if before is None or after is None else 'increased' if after > before else 'decreased' if after < before else 'unchanged',
                         'previousOldest': case['previousOldest'], 'previousNewest': case['previousNewest'],
                         'currentOldest': case['oldest'], 'currentNewest': case['newest']}
                        for index, (sport, metric, before, after) in enumerate(pairs)]
            facts = [{'period': period, 'sport': sport, 'metric': metric, 'sampleCount': 2}
                     for sport, metric, _, _ in pairs for period in ('previous', 'current')]
            self.assertEqual(app_replay.validation_flags(record['raw_response'], {'evidence': evidence}, {'facts': facts}), [])

    def test_summary_checks_pair_bindings_and_reports_each_arm_separately(self):
        baseline = [{'ready': True, 'restarts': 0, 'image': 'synthetic'}]
        records = [{'phase': 'operator_sources', 'synthetic_only': True, 'source_sha256': {}},
                   {'phase': 'preflight', 'synthetic_only': True, 'app_image': 'synthetic', 'app_revision': 'synthetic', 'baseline_live_state': baseline},
                   {'phase': 'prompt_experiment', 'skill_prompt_sha256': hashlib.sha256(experiment.skill_prompt().encode()).hexdigest()}]
        for case, repetition in experiment.sequence(fixtures.cases()):
            sparse = case['name'] in ('sparse_activity', 'missing_current_wellness')
            arm = repetition[-1]
            records.append({'phase': 'case_result', 'synthetic_only': True, 'case': case['name'], 'repetition': repetition,
                            'prompt_arm': arm, 'wall_seconds': 1.0,
                            'app_response': {'status': 'available' if arm == 'B' else 'unavailable',
                                             'reason': 'insufficient_input' if sparse else 'validated_typed_observations' if arm == 'B' else 'unusable_model_output'},
                            'replay': {'gate': 'insufficient_input' if sparse else 'validated' if arm == 'B' else 'rejected_output',
                                       'packet_sha256': case['name'], 'flags': [] if arm == 'B' or sparse else ['invalid_sport_mix'],
                                       'prompt_tokens': 1000 if arm == 'B' else 500}})
        records += [{'phase': 'telemetry', 'resource_guard_failed': False,
                     'samples': [{'host_available_mib': 3000, 'live_health_ok': True, 'synthetic_health_ok': True}]},
                    {'phase': 'functional_controls'}, {'phase': 'benchmark_complete'},
                    {'phase': 'cleanup', 'temporary_namespace_removed': True, 'final_live_state': baseline}]
        with patch.object(summarize_app_run, 'replay', side_effect=lambda record: record['replay']):
            summary = summarize_app_run.summarize(records)
            self.assertTrue(summary['complete'])
            self.assertEqual(summary['by_prompt_arm']['A']['inference_attempts'], 6)
            self.assertEqual(summary['by_prompt_arm']['A']['accepted'], 0)
            self.assertEqual(summary['by_prompt_arm']['B']['accepted'], 6)
            self.assertEqual(summary['by_prompt_arm']['B']['maximum_prompt_tokens'], 1000)
            self.assertEqual(summary['comparable_pairs'], 6)
            self.assertEqual(summary['paired_accepted'], {'A': 0, 'B': 6})
            records[4]['replay']['packet_sha256'] = 'changed-packet'
            with self.assertRaises(AssertionError):
                summarize_app_run.summarize(records)

    def test_received_capture_before_runner_assertion_is_counted_but_never_marked_finalized(self):
        received = {'phase': 'case_received', 'synthetic_only': True, 'case': 'zero_baseline',
                    'repetition': 'prompt_arm_B', 'wall_seconds': 85.941,
                    'app_response': {'status': 'unavailable', 'reason': 'evidence_changed', 'observations': []}}
        captured = {'phase': 'captured_transport', 'case': 'zero_baseline', 'repetition': 'prompt_arm_B',
                    'capture': ['synthetic-placeholder'], 'report': {'synthetic_only': True}}
        records = [{'phase': 'prompt_experiment', 'skill_prompt_sha256': 'synthetic-hash'}, received, captured,
                   {'phase': 'benchmark_failed', 'completed_cases': 9}]
        outcomes = summarize_app_run.collected_cases(records)
        self.assertEqual(len(outcomes), 1)
        self.assertEqual(outcomes[0]['record_status'], 'received_not_finalized')
        self.assertEqual(outcomes[0]['app_response']['reason'], 'evidence_changed')
        self.assertEqual(outcomes[0]['prompt_arm'], 'B')
        records.insert(3, {**outcomes[0], 'phase': 'case_result', 'record_status': 'finalized'})
        self.assertEqual(summarize_app_run.collected_cases(records)[0]['record_status'], 'finalized')

    def test_archived_qwen_runs_keep_actual_claims_acceptance_and_stale_gate(self):
        by_name = {c['name']: c for c in fixtures.cases()}
        for name, expected_count, expected_accepted, complete in (
                ('2026-10-02-guarded-app-qwen3-instruct.json', 8, 0, True),
                ('2026-10-02-qwen3-skill-ab-partial.json', 10, 2, False),
                ('2026-10-02-qwen3-skill-ab-complete.json', 12, 3, True)):
            archive = json.loads((Path(__file__).parent / 'results' / name).read_text())
            self.assertEqual(archive['inference_attempts'], expected_count)
            self.assertEqual(archive['accepted'], expected_accepted)
            self.assertEqual(archive['complete'], complete)
            self.assertTrue(archive['cleanup_verified'])
            for record in archive['cases']:
                if record['replay']['gate'] == 'insufficient_input':
                    self.assertNotIn('raw_model_content', record)
                    continue
                packet, summary = self.fixture_oracle(by_name[record['case']])
                self.assertEqual(hashlib.sha256(app_replay.compact(packet)).hexdigest(), record['replay']['packet_sha256'])
                raw = record['raw_model_content']
                self.assertEqual(hashlib.sha256(raw.encode()).hexdigest(), record['original_claim_sha256'])
                flags = app_replay.validation_flags(raw, packet, summary)
                self.assertEqual(flags, record['replay']['flags'])
                if record['reason'] == 'evidence_changed':
                    self.assertEqual(record['replay']['gate'], 'evidence_invalidated')
                    self.assertTrue(record['replay']['model_contract_valid_for_old_packet'])
                    self.assertEqual(record['replay']['validated_observation_count'], 0)
                    self.assertEqual(record['record_status'], 'received_not_finalized')
                    self.assertEqual(record['report_evaluated_on_utc'], record['request_started_utc'][:10])
                    self.assertNotEqual(record['report_evaluated_on_utc'], record['response_created_at'][:10])
            if not complete:
                self.assertEqual(archive['comparable_pairs'], 4)
                self.assertEqual(archive['paired_accepted'], {'A': 0, 'B': 2})
                self.assertFalse(archive['controls_exported'])
                self.assertIsNone(archive['log_marker_scan_passed'])
            elif 'skill-ab-complete' in name:
                self.assertEqual(archive['comparable_pairs'], 6)
                self.assertEqual(archive['paired_accepted'], {'A': 0, 'B': 3})
                self.assertEqual(archive['by_prompt_arm']['A']['inference_attempts'], 6)
                self.assertEqual(archive['by_prompt_arm']['B']['inference_attempts'], 6)
                self.assertTrue(archive['controls_exported'])
                self.assertTrue(archive['log_marker_scan_passed'])
                self.assertEqual(len(archive['cases']), 14)
                self.assertEqual(archive['failures'], [])
                self.assertEqual(archive['telemetry']['sample_count'], 185)

    def test_extractor_preserves_raw_claims_without_printing_validated_personal_rendering(self):
        original = {'phase': 'case_result', 'synthetic_only': True, 'case': 'synthetic', 'repetition': 'synthetic',
                    'analysis_input': {'evidenceReportSha256': 'synthetic-report', 'evaluatedOnUtc': '2021-01-01'},
                    'capture': []}
        content = ' {"observations":[]} '
        body = json.dumps({'message': {'content': content}, 'created_at': '2021-01-01T00:00:00Z'}).encode()
        original['capture'] = [{'event': 'response', 'body_sha256': hashlib.sha256(body).hexdigest(),
                                'body_base64': base64.b64encode(body).decode()}]
        summary = {'cases': [{'replay': {'rendered': [{'text': 'Synthetic rendered fact'}]}}]}
        with patch.object(extract_app_run, 'summarize', return_value=summary):
            output = extract_app_run.extract(json.dumps(original).encode())
        record = output['cases'][0]
        self.assertEqual(record['raw_model_content'], content)
        self.assertEqual(record['original_claim_sha256'], hashlib.sha256(content.encode()).hexdigest())
        self.assertEqual(record['replay']['validated_observation_count'], 1)
        self.assertNotIn('rendered', record['replay'])
        self.assertNotIn('Synthetic rendered fact', json.dumps(output))

    def test_archived_fresh_card_comparison_preserves_both_versioned_prompts_and_exact_failures(self):
        archive = json.loads((Path(__file__).parent / 'results/2026-10-02-qwen3-card-v2-unseen.json').read_text())
        self.assertTrue(archive['complete'] and archive['cleanup_verified'])
        self.assertTrue(archive['controls_exported'] and archive['log_marker_scan_passed'])
        self.assertEqual(archive['inference_attempts'], 12)
        self.assertEqual(archive['comparable_pairs'], 6)
        self.assertEqual(archive['paired_accepted'], {'A': 1, 'B': 4})
        self.assertEqual(archive['prompt_experiment']['profile'], 'card-v2-unseen')
        self.assertEqual(archive['prompt_experiment']['arm_labels'], {'A': 'instruction card v1', 'B': 'instruction card v2'})
        control, skill = experiment.prompts('card-v2-unseen')
        self.assertEqual(archive['prompt_experiment']['control_prompt_sha256'], hashlib.sha256(control.encode()).hexdigest())
        self.assertEqual(archive['prompt_experiment']['skill_prompt_sha256'], hashlib.sha256(skill.encode()).hexdigest())
        fresh = app_unseen_fixtures.cases()
        self.assertEqual(archive['prompt_experiment']['fixture_cases_sha256'], hashlib.sha256(json.dumps(fresh, separators=(',', ':'), allow_nan=False).encode()).hexdigest())
        by_name = {c['name']: c for c in fresh}
        missing_by_case = {}
        for record in archive['cases']:
            if record['replay']['gate'] == 'insufficient_input':
                self.assertNotIn('raw_model_content', record)
                continue
            packet, summary = self.fixture_oracle(by_name[record['case']])
            self.assertEqual(hashlib.sha256(app_replay.compact(packet)).hexdigest(), record['replay']['packet_sha256'])
            raw = record['raw_model_content']
            self.assertEqual(hashlib.sha256(raw.encode()).hexdigest(), record['original_claim_sha256'])
            flags = app_replay.validation_flags(raw, packet, summary)
            self.assertEqual(flags, record['replay']['flags'])
            self.assertEqual(record['replay']['gate'] == 'validated', not flags)
            if record['prompt_arm'] == 'B' and flags:
                self.assertEqual(flags, ['incomplete_evidence_selection'])
                selected = {r['id'] for o in json.loads(raw)['observations'] for r in o['evidence']}
                missing_by_case[record['case']] = {e['id'] for e in packet['evidence']} - selected
        self.assertEqual(missing_by_case, {'unseen_two_sports_both_increase': {'e3'}, 'unseen_sleep_previous_unknown': {'e1'}})
        self.assertEqual(archive['telemetry']['sample_count'], 189)
        self.assertEqual(archive['failures'], [])


if __name__ == '__main__':
    unittest.main()
