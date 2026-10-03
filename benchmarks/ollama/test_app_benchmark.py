import base64
import ast
import hashlib
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import sqlite3
import tempfile
import threading
import unittest
from unittest.mock import patch

import app_capture_proxy as proxy
import app_fixtures as fixtures
import run_app_on_pi as runner
import package_app_run
import app_replay
import summarize_app_run


class AppBenchmarkTest(unittest.TestCase):
    def test_only_synthetic_records_and_known_totals_missing_values_and_zero_baseline(self):
        activities, wellness = fixtures.records()
        self.assertEqual(len(activities), 34)
        self.assertEqual(len(wellness), 32)
        for record in activities + wellness:
            self.assertEqual(record['source'], 'synthetic-fixture')
            self.assertTrue(record['sourceRecordId'].startswith('synthetic-'))
            self.assertEqual(record['upstreamSources'], ['SYNTHETIC'])
        zeros = [a for a in activities if 'zero_baseline' in a['sourceRecordId']]
        self.assertEqual(sum(a['movingTime']['value'] for a in zeros[:2]), 0)
        self.assertEqual(sum(a['movingTime']['value'] for a in zeros[2:]), 1800)
        missing = [r for r in wellness if r['date'] in ('2020-04-08', '2020-04-14')]
        self.assertTrue(all('hrv' not in r['measurements'] for r in missing))

    def test_exclusive_seed_schema_matches_published_store_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.sqlite3'
            fixtures.database(path)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with sqlite3.connect(path) as connection:
                self.assertEqual(connection.execute('PRAGMA user_version').fetchone()[0], 1)
                self.assertEqual(connection.execute('PRAGMA integrity_check').fetchone()[0], 'ok')
                self.assertEqual(connection.execute('SELECT count(*) FROM events').fetchone()[0], 0)
            with self.assertRaises(FileExistsError):
                fixtures.database(path)
        source = Path(__file__).resolve().parents[2] / 'backend/src/main/kotlin/com/gtrainer/HistoryStore.kt'
        # The published experiment remains frozen on schema 1. The new app adds
        # a separate v2 scheduling migration; never change the old seed/image.
        legacy_migration = source.read_text().split('if (version == 0) transaction {', 1)[1].split('if (version < 2) transaction {', 1)[0]
        kotlin_sql = set(re.findall(r'statement.execute\("(CREATE (?:TABLE|INDEX)[^"]+)"\)', legacy_migration))
        fixture_sql = {s.strip() for s in fixtures.SCHEMA_SQL.split(';') if s.strip().startswith('CREATE ')}
        self.assertEqual(kotlin_sql, fixture_sql)

    def test_catalogue_alias_is_explicitly_same_weights_and_verifier_matches_synthetic_password(self):
        options = fixtures.catalogue()
        self.assertEqual(options['endpoint'], 'http://127.0.0.1:11434')
        self.assertEqual({m['tag'] for m in options['models']}, {fixtures.MODEL, fixtures.ALIAS})
        self.assertEqual({m['digest'] for m in options['models']}, {fixtures.MODEL_DIGEST})
        encoded = fixtures.verifier().split('$')
        decode = lambda value: base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
        actual = hashlib.pbkdf2_hmac('sha256', fixtures.PASSWORD.encode(), decode(encoded[2]), int(encoded[1]), dklen=32)
        self.assertEqual(actual, decode(encoded[3]))

    def test_granite_profile_is_explicit_pinned_and_cannot_select_arbitrary_remote_or_cloud_models(self):
        profile = fixtures.model_profile('granite4.2:3b')
        self.assertEqual(profile['digest'], '40577dc168a3a9ad34e9a1234e0c2570be86097fa75a236d4574ae985705d3c4')
        self.assertEqual(profile['label'], 'IBM Granite 4.2 3B')
        options = fixtures.catalogue('granite4.2:3b')['models']
        self.assertEqual({m['tag'] for m in options}, {'granite4.2:3b', 'granite42-synthetic:3b'})
        self.assertEqual({m['digest'] for m in options}, {profile['digest']})
        for option in options:
            self.assertRegex(option['id'], r'^[a-z0-9-]{1,64}$')
            self.assertRegex(option['label'], r'^[A-Za-z0-9 .()-]{1,80}$')
            self.assertRegex(option['tag'], r'^[a-z0-9][a-z0-9_.-]{0,63}:[a-z0-9][a-z0-9_.-]{0,63}$')
        profile['digest'] = 'changed-copy'
        self.assertNotEqual(fixtures.model_profile('granite4.2:3b')['digest'], 'changed-copy')
        for forbidden in ('https://provider.example', 'qwen3:cloud', 'unknown:3b'):
            with self.assertRaises(ValueError):
                fixtures.catalogue(forbidden)
            with self.assertRaises(ValueError):
                package_app_run.package('a' * 32, forbidden)

    def test_granite_bundle_and_manifest_keep_existing_resources_prompt_and_published_image(self):
        source = package_app_run.package('a' * 32, 'granite4.2:3b')
        compile(source, '<synthetic-bundle>', 'exec')
        arguments = next(ast.literal_eval(node.value) for node in ast.parse(source).body
                         if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Attribute) and node.targets[0].attr == 'argv')
        self.assertEqual(arguments[arguments.index('--candidate') + 1], 'granite4.2:3b')
        old = runner.manifest('a' * 32, b'synthetic-only', '# synthetic proxy')
        new = runner.manifest('a' * 32, b'synthetic-only', '# synthetic proxy', 'granite4.2:3b')
        self.assertEqual(old['items'][-1], new['items'][-1])
        options = json.loads(new['items'][1]['data']['models.json'])['models']
        self.assertEqual(options[0]['tag'], 'granite4.2:3b')
        self.assertNotEqual(options[0]['digest'], fixtures.MODEL_DIGEST)

    def test_qwen_instruct_profile_is_distinct_from_prior_qwen35_and_keeps_same_boundary(self):
        profile = fixtures.model_profile('qwen3:4b-instruct')
        self.assertEqual(profile['digest'], '0edcdef34593eac1aa2be9c7d06c432dcf81945adca5eca2f27662c18f168ba0')
        self.assertNotEqual(profile['digest'], '2a654d98e6fba55d452b7043684e9b57a947e393bbffa62485a7aac05ee4eefd')
        manifest = runner.manifest('a' * 32, b'synthetic-only', '# synthetic proxy', 'qwen3:4b-instruct')
        self.assertEqual(manifest['items'][-1], runner.manifest('a' * 32, b'synthetic-only', '# synthetic proxy')['items'][-1])
        options = json.loads(manifest['items'][1]['data']['models.json'])['models']
        self.assertEqual({m['tag'] for m in options}, {'qwen3:4b-instruct', 'qwen3-instruct-synthetic:4b'})
        compile(package_app_run.package('a' * 32, 'qwen3:4b-instruct'), '<synthetic-qwen-bundle>', 'exec')

    def system_prompt(self):
        path = Path(__file__).resolve().parents[2] / 'backend/src/main/kotlin/com/gtrainer/AnalysisClaims.kt'
        raw = re.search(r'val system = """(.*?)"""\.trimIndent\(\)', path.read_text(), re.S).group(1)
        lines = raw.splitlines()
        while lines and not lines[0].strip():
            lines.pop(0)
        while lines and not lines[-1].strip():
            lines.pop()
        indent = min(len(line) - len(line.lstrip()) for line in lines if line.strip())
        return '\n'.join(line[indent:] if line.strip() else '' for line in lines)

    def test_published_prompt_hash_stays_bound_to_checked_kotlin_source(self):
        self.assertEqual(hashlib.sha256(self.system_prompt().encode()).hexdigest(), fixtures.SYSTEM_PROMPT_SHA256)

    def test_manifest_has_no_live_mounts_ingress_service_token_or_host_network_and_fixed_model_budget(self):
        manifest = runner.manifest('a' * 32, b'synthetic-only', '# synthetic proxy')
        pod = manifest['items'][-1]
        self.assertEqual(pod['spec']['restartPolicy'], 'Never')
        self.assertFalse(pod['spec']['automountServiceAccountToken'])
        self.assertEqual(pod['spec']['activeDeadlineSeconds'], 7200)
        model = pod['spec']['containers'][0]
        self.assertEqual(model['resources']['limits']['cpu'], '3')
        self.assertEqual(model['resources']['limits']['memory'], '5Gi')
        env = {e['name']: e['value'] for e in model['env']}
        self.assertEqual(env['OLLAMA_NO_CLOUD'], '1')
        self.assertEqual(env['OLLAMA_HOST'], '127.0.0.1:11438')
        self.assertEqual(env['OLLAMA_NUM_PARALLEL'], '1')
        self.assertEqual(manifest['items'][2]['spec']['ingress'], [])
        text = json.dumps(manifest)
        for forbidden in ('secretName', 'secretKeyRef', 'persistentVolumeClaim', 'hostPath', 'hostNetwork', 'hostPort', 'GTRAINER_INTERVALS_KEY_FILE'):
            self.assertNotIn(forbidden, text)
        self.assertEqual({r['kind'] for r in manifest['items']}, {'Namespace', 'Pod', 'ConfigMap', 'NetworkPolicy'})
        for container in pod['spec']['containers'] + pod['spec']['initContainers']:
            self.assertIn('@sha256:', container['image'])
            self.assertTrue(container['securityContext']['readOnlyRootFilesystem'])
            self.assertTrue(container['securityContext']['runAsNonRoot'])
        self.assertEqual(pod['spec']['containers'][2]['readinessProbe']['exec']['command'], ['sh', '-c', 'test -f /capture/ready'])

    def test_capture_readiness_marker_is_created_only_after_binding_and_is_private(self):
        with tempfile.TemporaryDirectory() as directory:
            capture, ready, fault = [Path(directory) / name for name in ('capture', 'ready', 'fault')]
            with patch.dict(os.environ, {'GTRAINER_SYNTHETIC_BENCHMARK': '1'}), patch.object(proxy, 'CAPTURE', capture), \
                    patch.object(proxy, 'READY', ready), patch.object(proxy, 'FAULT', fault), patch.object(proxy, 'ThreadingHTTPServer') as server:
                def bound(*_):
                    self.assertFalse(ready.exists())
                    return unittest.mock.MagicMock()
                server.side_effect = bound
                proxy.main()
                self.assertEqual(ready.stat().st_mode & 0o777, 0o600)
                self.assertEqual(capture.stat().st_mode & 0o777, 0o600)

    def test_ci_cannot_start_operator_run(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'true'}):
            with self.assertRaisesRegex(RuntimeError, 'CI must not'):
                runner.main('# synthetic proxy')
            with self.assertRaisesRegex(RuntimeError, 'CI must not'):
                package_app_run.main()

    def test_operator_bundle_is_pinned_source_only_and_requires_unique_owner(self):
        source = package_app_run.package('a' * 32)
        compile(source, '<synthetic-bundle>', 'exec')
        self.assertIn('source_sha256', source)
        self.assertIn('approve-synthetic-run', source)
        with self.assertRaises(ValueError):
            package_app_run.package('bad-owner')

    def test_independent_replay_rejects_personal_prose_unsupported_state_and_incomplete_selection(self):
        packet = {'evidence': [{'id': 'e0', 'sport': 'Run', 'metric': 'movingTime', 'preparedState': 'increased',
                               'previousOldest': '2020-01-01', 'previousNewest': '2020-01-07', 'currentOldest': '2020-01-08', 'currentNewest': '2020-01-14'},
                              {'id': 'e1', 'sport': None, 'metric': 'hrv', 'preparedState': 'unchanged',
                               'previousOldest': '2020-01-01', 'previousNewest': '2020-01-07', 'currentOldest': '2020-01-08', 'currentNewest': '2020-01-14'}]}
        summary = {'facts': [{'period': period, 'sport': sport, 'metric': metric, 'sampleCount': 2}
                             for period in ('previous', 'current') for sport, metric in (('Run', 'movingTime'), (None, 'hrv'))]}
        raw = '{"observations":[{"kind":"co_occurrence","evidence":[{"id":"e0","state":"increased"},{"id":"e1","state":"unchanged"}]}]}'
        self.assertEqual(app_replay.validation_flags(raw, packet, summary), [])
        self.assertIn('state_mismatch', app_replay.validation_flags(raw.replace('increased', 'decreased'), packet, summary))
        self.assertIn('invalid_unavailable_comparison', app_replay.validation_flags(raw.replace('co_occurrence', 'unavailable_comparison'), packet, summary))
        for malformed in ('Garmin fitness age is 21', '{"observations":[]}', '{"observations":[],"observations":[]}', '{"observations":NaN}'):
            self.assertTrue(app_replay.validation_flags(malformed, packet, summary))
        incomplete = '{"observations":[{"kind":"recorded_change","evidence":[{"id":"e0","state":"increased"}]}]}'
        self.assertIn('incomplete_evidence_selection', app_replay.validation_flags(incomplete, packet, summary))

    def test_replay_bounds_duplicate_escaped_keys_and_depth(self):
        for value in ('{"observations":[],"\\u006fbservations":[]}', '[' * 18 + '0' + ']' * 18, ' ' * 32769):
            with self.assertRaises(ValueError):
                app_replay.decode(value)

    def test_independent_replay_accepts_complete_synthetic_claims_and_matches_authoritative_rendering(self):
        report = {'synthetic_only': True}
        summary = {'evidenceReportSha256': hashlib.sha256(app_replay.compact(report)).hexdigest(), 'facts': []}
        packet = {'evidence': []}
        for index, (sport, metric, label, unit, before, after, aggregation) in enumerate([
                ('Run', 'movingTime', 'Moving time', 'seconds', 1800.0, 3600.0, 'sum'),
                (None, 'sleepSecs', 'Sleep duration', 'seconds', 27000.0, 23400.0, 'mean')]):
            packet['evidence'].append({'id': f'e{index}', 'sport': sport, 'metric': metric, 'unit': unit,
                'previousOldest': '2020-01-01', 'previousNewest': '2020-01-07', 'currentOldest': '2020-01-08', 'currentNewest': '2020-01-14',
                'previousValue': before, 'currentValue': after, 'preparedState': 'increased' if after > before else 'decreased', 'flags': []})
            for period, value, oldest, newest in [('previous', before, '2020-01-01', '2020-01-07'), ('current', after, '2020-01-08', '2020-01-14')]:
                summary['facts'].append({'evidenceId': f'synthetic-{index}-{period}', 'period': period, 'sport': sport,
                    'metric': metric, 'label': label, 'unit': unit, 'value': value, 'aggregation': aggregation,
                    'sampleCount': 2, 'oldest': oldest, 'newest': newest})
        raw = '{"observations":[{"kind":"co_occurrence","evidence":[{"id":"e0","state":"increased"},{"id":"e1","state":"decreased"}]}]}'
        payload = {'model': 'synthetic:test', 'messages': [{'role': 'system', 'content': self.system_prompt()},
                   {'role': 'user', 'content': app_replay.compact(packet).decode()}]}
        response = {'model': 'synthetic:test', 'done': True, 'done_reason': 'stop', 'message': {'role': 'assistant', 'content': raw}}
        capture = []
        for event, body in [('request', payload), ('response', response)]:
            encoded = app_replay.compact(body)
            capture.append({'event': event, 'id': 'synthetic-id', 'body_base64': base64.b64encode(encoded).decode(),
                            'body_sha256': hashlib.sha256(encoded).hexdigest(), 'fault': None})
        expected = 'Run Moving time (sum of populated source records) increased: 1800.0 to 3600.0 seconds. Periods: 2020-01-01–2020-01-07 and 2020-01-08–2020-01-14. '
        expected += 'Wellness Sleep duration (mean of populated source records) decreased: 27000.0 to 23400.0 seconds. Periods: 2020-01-01–2020-01-07 and 2020-01-08–2020-01-14. '
        expected += 'These observed comparisons cover the same periods; they do not establish cause, recovery, or readiness.'
        observations = [{'text': expected, 'evidenceIds': [f['evidenceId'] for f in summary['facts']], 'supportingMetrics': summary['facts']}]
        record = {'report': report, 'analysis_input': summary, 'prepared_packet_oracle': packet, 'capture': capture,
                  'app_response': {'status': 'available', 'observations': observations}}
        result = app_replay.replay(record)
        self.assertEqual(result['gate'], 'validated')
        self.assertEqual(result['rendered'], observations)
        import app_prompt_experiment as experiment
        for arm, profile in (('A', 'baseline-v1'), ('B', 'baseline-v1'), ('A', 'card-v2-unseen'), ('B', 'card-v2-unseen')):
            control, skill = experiment.prompts(profile)
            forwarded = experiment.rewrite_body(app_replay.compact(payload), arm, skill, control)
            record.update({'prompt_arm': arm, 'skill_prompt_sha256': hashlib.sha256(skill.encode()).hexdigest()})
            record['prompt_experiment_profile'] = profile
            record['control_prompt_sha256'] = fixtures.SYSTEM_PROMPT_SHA256 if control is None else hashlib.sha256(control.encode()).hexdigest()
            capture.insert(1, {'event': 'forwarded_request', 'id': 'synthetic-id', 'prompt_arm': arm,
                              'explicit_prompt_experiment': True, 'body_base64': base64.b64encode(forwarded).decode(),
                              'body_sha256': hashlib.sha256(forwarded).hexdigest()})
            self.assertEqual(app_replay.replay(record)['gate'], 'validated')
            record['skill_prompt_sha256'] = 'unbound-skill'
            with self.assertRaises(AssertionError):
                app_replay.replay(record)
            capture.pop(1)
        record.pop('prompt_arm'); record.pop('skill_prompt_sha256')
        record.pop('prompt_experiment_profile'); record.pop('control_prompt_sha256')
        accepted_response = record['app_response']
        record['app_response'] = {'status': 'unavailable', 'reason': 'evidence_changed', 'observations': []}
        stale = app_replay.replay(record)
        self.assertEqual(stale['gate'], 'evidence_invalidated')
        self.assertTrue(stale['model_contract_valid_for_old_packet'])
        self.assertIsNone(stale['rendered'])
        record['app_response'] = accepted_response
        record['app_response']['observations'][0]['text'] = 'Synthetic unsupported recovery claim.'
        with self.assertRaises(AssertionError):
            app_replay.replay(record)
        error_bytes = b'{"error":"Synthetic runtime incompatibility"}'
        capture[-1].update({'status': 400, 'body_base64': base64.b64encode(error_bytes).decode(),
                            'body_sha256': hashlib.sha256(error_bytes).hexdigest()})
        record['app_response'] = {'status': 'unavailable', 'observations': []}
        self.assertEqual(app_replay.replay(record)['gate'], 'transport_rejected')

    def test_summary_never_calls_partial_run_complete_or_invents_cleanup(self):
        baseline = [{'ready': True, 'restarts': 0, 'image': 'synthetic'}]
        records = [{'phase': 'operator_sources', 'synthetic_only': True, 'source_sha256': {}},
                   {'phase': 'preflight', 'synthetic_only': True, 'app_image': 'synthetic', 'app_revision': 'synthetic', 'baseline_live_state': baseline}]
        result = summarize_app_run.summarize(records)
        self.assertFalse(result['complete'])
        self.assertFalse(result['cleanup_verified'])
        self.assertIsNone(result['telemetry']['maximum_temperature_c'])
        self.assertIsNone(result['telemetry']['live_health_failures'])
        records.append({'phase': 'cleanup', 'temporary_namespace_removed': True, 'final_live_state': baseline})
        self.assertTrue(summarize_app_run.summarize(records)['cleanup_verified'])
        records.append({'phase': 'benchmark_complete'})
        with self.assertRaises(AssertionError):
            summarize_app_run.summarize(records)

    def test_log_scan_retains_non_utf8_bytes_without_emitting_or_replacement_decoding(self):
        with patch.object(runner.subprocess, 'run') as process:
            process.return_value.returncode = 0
            process.return_value.stdout = b'\xff\xfe synthetic-marker'
            self.assertEqual(runner.run('synthetic-command', decode=False), b'\xff\xfe synthetic-marker')
            with self.assertRaises(UnicodeDecodeError):
                runner.run('synthetic-command')

    def test_actual_retained_outputs_still_fail_whole_response_against_fixture_packets(self):
        self.assert_actual_retained_outputs('2026-10-01-guarded-app-ministral.json', complete=False)
        self.assert_actual_retained_outputs('2026-10-02-guarded-app-granite42.json', complete=True)

    def assert_actual_retained_outputs(self, filename, complete):
        path = Path(__file__).parent / 'results' / filename
        archive = json.loads(path.read_text())
        self.assertTrue(archive['synthetic_only'])
        self.assertEqual(archive['complete'], complete)
        self.assertEqual(archive['accepted'], 0)
        self.assertEqual(archive['real_inference_attempts'], 8)
        self.assertEqual(len(archive['cases']), 10)
        by_name = {c['name']: c for c in fixtures.cases()}
        for record in archive['cases']:
            if record['reason'] == 'insufficient_input':
                self.assertFalse(record['inference_sent'])
                self.assertNotIn(record['case'], archive['outputs'])
                continue
            case = by_name[record['case']]
            comparisons = [(sport, 'movingTime', 'seconds', before * case['activity_samples_per_period'],
                            after * case['activity_samples_per_period']) for sport, before, after in case['sports']]
            comparisons += [(None, 'sleepSecs', 'seconds', *case['sleep']), (None, 'hrv', 'ms', *case['hrv'])]
            packet, facts = {'evidence': []}, []
            for index, (sport, metric, unit, before, after) in enumerate(comparisons):
                state = 'unavailable' if before is None or after is None else 'increased' if after > before else 'decreased' if after < before else 'unchanged'
                flags = [] if sport else ['incomplete_date_coverage']
                if before is None or after is None:
                    flags = ['unavailable_comparison', 'partial_metric_coverage', 'sparse_comparison', 'incomplete_date_coverage']
                elif sport and before == 0:
                    flags = ['zero_baseline_no_percentage']
                packet['evidence'].append({'id': f'e{index}', 'sport': sport, 'metric': metric, 'unit': unit,
                    'previousOldest': case['previousOldest'], 'previousNewest': case['previousNewest'],
                    'currentOldest': case['oldest'], 'currentNewest': case['newest'],
                    'previousValue': None if before is None else float(before), 'currentValue': None if after is None else float(after),
                    'preparedState': state, 'flags': flags})
                facts += [{'period': period, 'sport': sport, 'metric': metric, 'sampleCount': 0 if value is None else 2}
                          for period, value in (('previous', before), ('current', after))]
            self.assertEqual(hashlib.sha256(app_replay.compact(packet)).hexdigest(), record['packet_sha256'])
            raw = json.dumps(archive['outputs'][record['case']])
            self.assertEqual(app_replay.validation_flags(raw, packet, {'facts': facts}), record['flags'])
            self.assertIsNone(app_replay.render(raw, packet, {'facts': facts}))

    def test_capture_cannot_start_outside_explicit_synthetic_pod(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, 'explicitly synthetic'):
                proxy.main()

    def test_runtime_fault_controls_cannot_accept_arbitrary_commands_or_source_writes(self):
        with self.assertRaises(AssertionError):
            runner.fault('something-else')
        with self.assertRaises(AssertionError):
            runner.change_status('DROP TABLE activities')

    def test_request_oracle_checks_exact_settings_summary_and_bounds(self):
        packet = {'evidence': [{'id': 'e0', 'preparedState': 'increased'}]}
        payload = {'model': fixtures.MODEL, 'stream': False, 'think': False, 'keep_alive': '0s',
            'options': {'num_ctx': 2048, 'num_thread': 3, 'num_predict': 256, 'temperature': 0, 'seed': 42},
            'messages': [{'role': 'system', 'content': self.system_prompt()},
                         {'role': 'user', 'content': json.dumps(packet)}],
            'format': {'properties': {'observations': {'items': {'properties': {'evidence': {'items': {'properties': {'id': {'enum': ['e0']}}}}}}}}}}
        raw = json.dumps(payload).encode()
        entry = {'event': 'request', 'body_base64': base64.b64encode(raw).decode(), 'body_sha256': runner.digest(raw), 'id': 'synthetic-id'}
        self.assertEqual(runner.verify_capture([entry], packet, fixtures.MODEL), payload)
        with self.assertRaises(AssertionError):
            runner.verify_capture([entry], {'evidence': []}, fixtures.MODEL)
        with self.assertRaises(RuntimeError):
            runner.verify_capture([entry, entry], packet, fixtures.MODEL)

    def test_proxy_forwards_normal_payload_bytes_unchanged_and_labels_injected_non_model_outputs(self):
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
        threads = [threading.Thread(target=s.serve_forever, daemon=True) for s in (upstream, observer)]
        for thread in threads:
            thread.start()
        original_connection = HTTPConnection
        with tempfile.TemporaryDirectory() as directory:
            capture = Path(directory) / 'capture.jsonl'
            fault = Path(directory) / 'fault'
            def connection(_host, _port, **kwargs):
                return original_connection('127.0.0.1', upstream.server_port, **kwargs)
            try:
                with patch.object(proxy, 'CAPTURE', capture), patch.object(proxy, 'FAULT', fault), patch.object(proxy, 'HTTPConnection', connection):
                    client = original_connection('127.0.0.1', observer.server_port)
                    body = b'{"model":"ministral-3:3b","messages":[]}'
                    client.request('POST', '/api/chat', body, {'Content-Type': 'application/json'})
                    reply = client.getresponse()
                    self.assertEqual(reply.status, 200)
                    self.assertEqual(reply.read(), response_bytes)
                    self.assertEqual(upstream_bodies, [body])
                    entries = [json.loads(line) for line in capture.read_text().splitlines()]
                    self.assertEqual(base64.b64decode(entries[0]['body_base64']), body)
                    self.assertEqual(base64.b64decode(entries[1]['body_base64']), response_bytes)
                    self.assertIsNone(entries[1]['fault'])
                    fault.write_text('unsupported-output')
                    client.request('POST', '/api/chat', body)
                    injected = json.loads(client.getresponse().read())
                    self.assertIn('Garmin fitness age', injected['message']['content'])
                    self.assertEqual(len(upstream_bodies), 1)
                    self.assertEqual(json.loads(capture.read_text().splitlines()[-1])['fault'], 'unsupported-output')
                    client.request('POST', '/api/pull', b'{}')
                    self.assertEqual(client.getresponse().status, 403)
                    client.close()
            finally:
                for server in (observer, upstream):
                    server.shutdown(); server.server_close()


if __name__ == '__main__':
    unittest.main()
