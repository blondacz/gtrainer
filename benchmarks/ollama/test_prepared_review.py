import ast
import base64
import copy
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import unittest
from unittest.mock import patch

import app_fixtures
import app_unseen_fixtures
import prepared_review as contract
import run_app_on_pi as infra
import run_prepared_review_on_pi as runner
import summarize_prepared_review as replay
import test_app_prompt_experiment as oracle


class PreparedReviewTest(unittest.TestCase):
    def test_frozen_benchmark_contract_source_is_unchanged(self):
        # Benchmark files are intentionally excluded from the application image.
        # Check their frozen identity here, not from the standalone Kotlin build.
        source = (Path(__file__).parent / 'prepared_review.py').read_bytes()
        self.assertEqual(hashlib.sha256(source).hexdigest(),
                         '309dc4e72611f094d6fdc1b9baef81f24c5a213cbfcece9baa7053501946f93d')

    def packet(self, index=0):
        return oracle.PromptExperimentTest().fixture_oracle(app_unseen_fixtures.cases()[index])

    def prepared(self, index=0):
        case = app_unseen_fixtures.cases()[index]
        packet, _ = self.packet(index)
        return contract.prepare(packet, contract.REQUESTS[case['name']][0], index)

    def test_all_facts_are_covered_before_model_for_every_known_fixture(self):
        for index, case in enumerate(app_unseen_fixtures.cases()[:6]):
            packet, summary = self.packet(index)
            original = copy.deepcopy(packet)
            prepared = self.prepared(index)
            rendering = contract.factual_rendering(prepared)
            self.assertEqual(packet, original)
            self.assertTrue(contract.sufficient(packet, summary))
            self.assertEqual([f['evidence_id'] for f in rendering['facts']],
                             [e['id'] for g in prepared['groups'] for e in g['evidence']])
            self.assertEqual({f['evidence_id'] for f in rendering['facts']}, {e['id'] for e in packet['evidence']})
            self.assertEqual(len(rendering['facts']), len(packet['evidence']))
            self.assertTrue(rendering['application_generated'])
            self.assertNotIn('expected_kinds', contract.compact(prepared).decode())

    def test_missing_unchanged_and_zero_are_not_confused(self):
        missing = self.prepared(4)
        unknown = [g for g in missing['groups'] if g['unavailable']]
        self.assertEqual([[e['metric'] for e in g['evidence']] for g in unknown], [['sleepSecs']])
        self.assertIsNone(unknown[0]['evidence'][0]['previousValue'])
        steady = self.prepared(1)
        self.assertEqual([e['preparedState'] for g in steady['groups'] for e in g['evidence']],
                         ['unchanged', 'unchanged', 'decreased'])
        zero = self.prepared(5)
        activity = zero['groups'][0]['evidence'][0]
        self.assertEqual((activity['previousValue'], activity['preparedState']), (0.0, 'increased'))
        self.assertIn('zero_baseline_no_percentage', activity['flags'])

    def test_mismatched_periods_never_make_a_cross_metric_candidate(self):
        packet, _ = self.packet()
        for item in packet['evidence'][1:]:
            item['currentOldest'] = '2020-02-22'
            item['currentNewest'] = '2020-02-28'
        prepared = contract.prepare(packet, 'daily_combined')
        self.assertEqual(len(prepared['groups']), 2)
        self.assertNotIn('cross_metric_pattern', {c['kind'] for c in prepared['candidates']})

    def test_whole_response_rejection_leaves_independent_facts_unchanged(self):
        prepared = self.prepared()
        binding = contract.digest(prepared)
        before = contract.factual_rendering(prepared)
        malformed = ['null', '{}', '{"focus_ids":[]}', '{"focus_ids":["unknown"]}',
                     '{"focus_ids":["f0","f0"]}', '{"focus_ids":["f0"],"text":"You should train"}',
                     '{"focus_ids":["f0"],"focus_ids":["f1"]}', '{"focus_ids":[NaN]}',
                     '{"focus_ids":[{}]}', '{"focus_ids":["f0","f1","f2"]}', 'x' * 32769,
                     '[' * 17 + '0' + ']' * 17]
        for raw in malformed:
            self.assertTrue(contract.validate(raw, prepared, binding), raw[:100])
            self.assertIsNone(contract.focus_rendering(raw, prepared, binding))
            self.assertEqual(before, contract.factual_rendering(prepared))

    def test_evidence_binding_changes_invalidate_otherwise_valid_output(self):
        prepared = self.prepared()
        binding = contract.digest(prepared)
        prepared['groups'][0]['evidence'][0]['currentValue'] += 1
        raw = '{"focus_ids":["f0"]}'
        self.assertEqual(contract.validate(raw, prepared, binding), ['evidence_changed'])
        self.assertIsNone(contract.focus_rendering(raw, prepared, binding))

    def test_supported_selection_is_not_automatically_relevant(self):
        prepared = self.prepared()
        candidate = next(c for c in prepared['candidates'] if c['kind'] == 'activity_pattern')
        raw = json.dumps({'focus_ids': [candidate['id']]})
        self.assertEqual(contract.validate(raw, prepared, contract.digest(prepared)), [])
        self.assertEqual(contract.relevance_flags(raw, prepared, {'cross_metric_pattern'}), ['requested_focus_not_answered'])

    def test_private_rubric_is_feasible_and_candidate_positions_vary(self):
        positions = []
        for index, case in enumerate(app_unseen_fixtures.cases()[:6]):
            prepared = self.prepared(index)
            _, expected = contract.REQUESTS[case['name']]
            candidate = next(c for c in prepared['candidates'] if c['kind'] in expected)
            raw = json.dumps({'focus_ids': [candidate['id']]})
            self.assertEqual(contract.relevance_flags(raw, prepared, expected), [])
            rendered = contract.focus_rendering(raw, prepared, contract.digest(prepared))
            self.assertTrue(rendered['model_selected_code_rendered'])
            positions.append(candidate['id'])
        self.assertGreater(len(set(positions)), 1)

    def test_sparse_cases_make_no_calls_and_duplicate_ids_or_states_refused(self):
        for index in (6, 7):
            packet, summary = self.packet(index)
            self.assertFalse(contract.sufficient(packet, summary))
            self.assertNotIn(app_unseen_fixtures.cases()[index]['name'], contract.REQUESTS)
        packet, _ = self.packet()
        packet['evidence'].append(packet['evidence'][0])
        with self.assertRaises(ValueError):
            contract.prepare(packet, 'daily_combined')
        packet, _ = self.packet()
        packet['evidence'][0]['preparedState'] = 'ill'
        with self.assertRaises(ValueError):
            contract.prepare(packet, 'daily_combined')

    def test_correction_preserves_input_and_is_once_bounded_without_fault_retries(self):
        prepared = self.prepared()
        first = contract.request('qwen3.5:4b', prepared)
        second = contract.request('qwen3.5:4b', prepared, ['requested_focus_not_answered'])
        self.assertEqual(first['messages'], second['messages'][:2])
        self.assertEqual(first['options'], second['options'])
        self.assertEqual(first['format'], second['format'])
        self.assertFalse(first['think'])
        self.assertEqual(first['keep_alive'], '0s')
        self.assertTrue(contract.can_retry(['duplicate_focus'], 1, 100))
        self.assertFalse(contract.can_retry(['duplicate_focus'], 2, 100))
        self.assertFalse(contract.can_retry(['duplicate_focus'], 1, 360))
        for flag in ('timeout', 'oom', 'restart', 'evidence_changed', 'model_changed', 'unexpected_thinking', 'incomplete_generation'):
            self.assertFalse(contract.can_retry([flag], 1, 1))
            with self.assertRaises(ValueError):
                contract.request('qwen3.5:4b', prepared, [flag])
        with self.assertRaises(ValueError):
            contract.request('hosted', prepared)

    def test_incomplete_or_thinking_response_cannot_be_accepted_or_corrected(self):
        prepared = self.prepared()
        candidate = next(c for c in prepared['candidates'] if c['kind'] == 'cross_metric_pattern')
        response = {'done': True, 'done_reason': 'stop', 'message': {'content': json.dumps({'focus_ids': [candidate['id']]})}}
        self.assertEqual(runner.response_flags(response, prepared, contract.digest(prepared), {'cross_metric_pattern'}), ([], []))
        response['done_reason'] = 'length'
        self.assertIn('incomplete_generation', runner.response_flags(response, prepared, contract.digest(prepared), {'cross_metric_pattern'})[0])
        response['done_reason'] = 'stop'; response['message']['thinking'] = 'private reasoning'
        self.assertIn('unexpected_thinking', runner.response_flags(response, prepared, contract.digest(prepared), {'cross_metric_pattern'})[0])

    def test_qwen35_profile_manifest_and_bundle_preserve_runtime_caps_and_no_app_rewrite(self):
        for model in contract.MODELS:
            source = runner.package('a' * 32, model)
            compile(source, '<prepared-review-synthetic>', 'exec')
            bundled = next(ast.literal_eval(node.value) for node in ast.parse(source).body
                           if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name) and node.targets[0].id == 'sources')
            self.assertNotIn('app_prompt_experiment', bundled)
            manifest = infra.manifest('a' * 32, b'synthetic', bundled['app_capture_proxy'], model)
            pod = manifest['items'][-1]['spec']
            self.assertFalse(pod['automountServiceAccountToken'])
            self.assertEqual(pod['restartPolicy'], 'Never')
            self.assertEqual(pod['containers'][0]['resources']['limits']['memory'], '5Gi')
            self.assertEqual(pod['containers'][0]['resources']['limits']['cpu'], '3')
            self.assertEqual(app_fixtures.model_profile(model)['digest'], contract.MODELS[model])
        for owner, model in (('invalid', 'qwen3.5:4b'), ('a' * 32, 'cloud')):
            with self.assertRaises(ValueError):
                runner.package(owner, model)

    def test_recorded_request_bytes_are_exact_http_body_and_error_body_is_preserved(self):
        bodies = []
        class LocalHandler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                bodies.append(self.rfile.read(int(self.headers['Content-Length'])))
                self.send_response(500)
                self.end_headers()
                self.wfile.write(b'{"error":"synthetic failure"}')

        server = ThreadingHTTPServer(('127.0.0.1', 0), LocalHandler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            raw = contract.compact(contract.request('qwen3.5:4b', self.prepared()))
            with patch.object(infra, 'OLLAMA', f'http://127.0.0.1:{server.server_port}'):
                status, response = runner.chat_http(raw)
            self.assertEqual(status, 500)
            self.assertEqual(bodies, [raw])
            self.assertEqual(response, b'{"error":"synthetic failure"}')
        finally:
            server.shutdown(); server.server_close()

    def test_staging_diagnostics_classify_failure_without_exporting_urls_or_credentials(self):
        markers = {
            'disk_full': 'write /models/private-path: no space left on device',
            'digest_mismatch': 'digest mismatch, file must be downloaded again',
            'model_not_found': 'pull model manifest: file does not exist',
            'unsupported_model': 'model requires a newer version of Ollama',
            'unexpected_eof': 'download failed: unexpected EOF',
            'network_timeout': 'request timeout',
            'dns_failure': 'lookup registry: no such host',
            'tls_failure': 'x509: certificate signed by unknown authority',
            'connection_failure': 'connection reset by peer',
            'permission_denied': 'open /models/private-path: permission denied',
            'upstream_http_error': 'unexpected status code: 503',
        }
        for expected, message in markers.items():
            raw = json.dumps({'error': message + ' https://signed.example.test/private?token=synthetic-secret'}).encode()
            diagnostic = runner.staging_diagnostic(500, raw)
            self.assertEqual(diagnostic['error_class'], expected)
            self.assertEqual(diagnostic['http_status'], 500)
            self.assertEqual(diagnostic['response_bytes'], len(raw))
            self.assertEqual(diagnostic['response_sha256'], infra.digest(raw))
            self.assertNotIn('synthetic-secret', json.dumps(diagnostic))
            self.assertNotIn('signed.example', json.dumps(diagnostic))
            self.assertNotIn('private-path', json.dumps(diagnostic))
        self.assertEqual(runner.staging_diagnostic(500, b'invalid')['error_class'], 'invalid_staging_json')
        self.assertEqual(runner.staging_diagnostic(500, b'{"error":null}')['error_class'], 'unexpected_staging_response')

    def test_staging_failure_is_one_call_and_cannot_silently_retry_or_fall_back(self):
        raw = b'{"error":"unexpected status code: 503 https://signed.example/?token=synthetic-secret"}'
        with patch.object(infra, 'request', return_value=(500, {}, raw)) as request, patch.object(infra, 'emit') as emit:
            with self.assertRaises(RuntimeError):
                runner.stage_model('qwen3.5:4b')
            self.assertEqual(request.call_count, 1)
            self.assertEqual(request.call_args.args[3], {'model': 'qwen3.5:4b', 'stream': False})
            self.assertEqual(emit.call_args.args[0]['upstream_http_status'], 503)
            self.assertFalse(emit.call_args.args[0]['success'])
            self.assertNotIn('synthetic-secret', json.dumps(emit.call_args.args[0]))
        with patch.object(infra, 'request') as request:
            with self.assertRaises(ValueError):
                runner.stage_model('hosted')
            request.assert_not_called()

    def test_successful_staging_and_transport_failure_have_bounded_diagnostics(self):
        with patch.object(infra, 'request', return_value=(200, {}, b'{"status":"success"}')) as request, patch.object(infra, 'emit') as emit:
            runner.stage_model('qwen3.5:4b')
            self.assertEqual(request.call_count, 1)
            self.assertTrue(emit.call_args.args[0]['success'])
            self.assertIsNone(emit.call_args.args[0]['error_class'])
        with patch.object(infra, 'request', side_effect=TimeoutError('signed-token=synthetic-secret')) as request, patch.object(infra, 'emit') as emit:
            with self.assertRaises(RuntimeError):
                runner.stage_model('qwen3.5:4b')
            self.assertEqual(request.call_count, 1)
            self.assertEqual(emit.call_args.args[0]['exception_type'], 'TimeoutError')
            self.assertNotIn('synthetic-secret', json.dumps(emit.call_args.args[0]))

    def healthy_control_state(self):
        return {name: {'ready': True, 'restarts': 0, 'state': {'running': {}}} for name in ('app', 'ollama', 'proxy')}

    def test_bounded_control_query_and_monitor_fail_closed_without_sensitive_errors(self):
        pod = {'status': {'containerStatuses': [{'name': name, **state, 'restartCount': state['restarts']}
                                               for name, state in self.healthy_control_state().items()]}}
        with patch.object(infra, 'kubectl', return_value=json.dumps(pod)) as kubectl:
            self.assertTrue(runner.healthy_state(runner.read_control_state()))
            self.assertEqual(kubectl.call_args.kwargs['timeout'], 10)
        control = runner.ControlMonitor()
        error = subprocess.TimeoutExpired('private-token=synthetic-secret', 10)
        with patch.object(runner, 'read_control_state', side_effect=error) as query, patch.object(infra, 'emit') as emit:
            control.sample_once()
            self.assertFalse(control.healthy())
            self.assertEqual(query.call_count, 1)
            event = emit.call_args.args[0]
            self.assertEqual(event['reason'], 'control_timeout')
            self.assertEqual(event['timeout_seconds'], 10)
            self.assertNotIn('synthetic-secret', json.dumps(event))

    def test_stale_or_unhealthy_control_state_never_counts_as_healthy(self):
        control = runner.ControlMonitor()
        with patch.object(runner, 'read_control_state', return_value=self.healthy_control_state()), patch.object(infra, 'emit'):
            control.sample_once()
            self.assertTrue(control.healthy())
            control.latest_checked -= 26
            self.assertFalse(control.healthy())
            self.assertTrue(control.failed.is_set())
        for bad in ({}, {'ollama': self.healthy_control_state()['ollama']},
                    {**self.healthy_control_state(), 'ollama': {'ready': True, 'restarts': 1, 'state': {'running': {}}}},
                    {**self.healthy_control_state(), 'ollama': {'ready': False, 'restarts': 0, 'state': {'terminated': {'reason': 'OOMKilled'}}}}):
            self.assertFalse(runner.healthy_state(bad))

    def test_response_export_happens_even_when_later_control_query_times_out(self):
        request = contract.compact(contract.request('qwen3.5:4b', self.prepared()))
        raw = b'{"message":{"content":"synthetic response"},"done":true}'
        holder = {}
        events = []
        with patch.object(runner, 'chat_http', return_value=(200, raw)), patch.object(infra, 'emit', side_effect=events.append):
            runner.receive_and_export(request, 'synthetic-case', 1, holder)
            with patch.object(runner, 'read_control_state', side_effect=subprocess.TimeoutExpired('kubectl', 10)):
                control = runner.ControlMonitor()
                control.sample_once()
                self.assertFalse(control.healthy())
        self.assertTrue(holder['receipt_exported'])
        self.assertEqual([event['phase'] for event in events], ['prepared_attempt_received', 'prepared_control_failure'])
        self.assertEqual(base64.b64decode(events[0]['response_base64']), raw)
        self.assertEqual(events[0]['response_sha256'], infra.digest(raw))

    def test_response_export_retains_transport_failure_without_inventing_output(self):
        holder = {}
        with patch.object(runner, 'chat_http', side_effect=TimeoutError('synthetic-secret')), patch.object(infra, 'emit') as emit:
            runner.receive_and_export(b'synthetic request', 'synthetic-case', 1, holder)
        event = emit.call_args.args[0]
        self.assertTrue(holder['receipt_exported'])
        self.assertIsNone(event['status'])
        self.assertEqual(event['error_type'], 'TimeoutError')
        self.assertEqual(base64.b64decode(event['response_base64']), b'')
        self.assertNotIn('synthetic-secret', json.dumps(event))

    def test_blocked_control_poll_does_not_block_response_export(self):
        entered, release = threading.Event(), threading.Event()
        events = []
        def blocked_query():
            entered.set()
            if not release.wait(2):
                raise subprocess.TimeoutExpired('synthetic control', 10)
            return self.healthy_control_state()
        control = runner.ControlMonitor()
        holder = {}
        with patch.object(runner, 'read_control_state', side_effect=blocked_query), \
                patch.object(runner, 'chat_http', return_value=(200, b'{"synthetic":true}')), \
                patch.object(infra, 'emit', side_effect=events.append):
            poll = threading.Thread(target=control.sample_once)
            poll.start()
            try:
                self.assertTrue(entered.wait(1))
                receiver = threading.Thread(target=runner.receive_and_export, args=(b'synthetic', 'synthetic-case', 1, holder))
                receiver.start(); receiver.join(timeout=1)
                self.assertFalse(receiver.is_alive())
                self.assertTrue(holder['receipt_exported'])
                self.assertEqual(events[0]['phase'], 'prepared_attempt_received')
                self.assertTrue(poll.is_alive())
            finally:
                release.set(); poll.join(timeout=2)
        self.assertFalse(poll.is_alive())

    def test_independent_decoder_and_validator_agree_on_all_closed_contract_failures(self):
        prepared = self.prepared()
        binding = contract.digest(prepared)
        for raw in ('null', '{}', '{"focus_ids":[]}', '{"focus_ids":["unknown"]}',
                    '{"focus_ids":["f0","f0"]}', '{"focus_ids":["f0"]}',
                    '{"focus_ids":[NaN]}', '{"focus_ids":[{}]}',
                    '{"focus_ids":["f0"],"focus_ids":["f1"]}'):
            self.assertEqual(replay.independent_validity(raw, prepared, binding), contract.validate(raw, prepared, binding))

    def test_partial_replay_preserves_received_but_unfinalized_response_without_acceptance(self):
        case = app_unseen_fixtures.cases()[0]
        packet, original_summary = self.packet()
        summary = {'facts': [], 'comparisons': []}
        for item in packet['evidence']:
            summary['comparisons'].append({'sport': item['sport'], 'key': item['metric'], 'unit': item['unit'],
                'previousValue': item['previousValue'], 'currentValue': item['currentValue'],
                'absoluteChange': item['currentValue'] - item['previousValue'], 'flags': item['flags']})
            for period, prefix in (('previous', 'previous'), ('current', 'current')):
                summary['facts'].append({'period': period, 'sport': item['sport'], 'metric': item['metric'],
                    'oldest': item[prefix + 'Oldest'], 'newest': item[prefix + 'Newest'], 'sampleCount': 2})
        report_raw = b'{"synthetic_only":true}'
        report_hash = infra.digest(report_raw)
        summary['evidenceReportSha256'] = report_hash
        prepared = self.prepared()
        payload = contract.compact(contract.request('qwen3.5:4b', prepared))
        response = b'{"error":"synthetic OOM termination"}'
        baseline = [{'ready': True, 'restarts': 0}]
        rows = [{'phase': 'operator_sources', 'source_sha256': {'frozen': 'synthetic'}},
                {'phase': 'prepared_preflight', 'synthetic_only': True, 'profile': contract.PROFILE,
                 'settings': contract.OPTIONS, 'call_seconds': contract.CALL_SECONDS, 'job_seconds': contract.JOB_SECONDS,
                 'model': app_fixtures.model_profile('qwen3.5:4b'), 'baseline_live_state': baseline,
                 'system_sha256': infra.digest(contract.SYSTEM.encode()), 'fixture_sha256': contract.digest(app_unseen_fixtures.cases())},
                {'phase': 'prepared_case', 'case': case['name'], 'source_packet': packet, 'report_base64': base64.b64encode(report_raw).decode(),
                 'report': json.loads(report_raw), 'report_sha256': report_hash, 'analysis_input': summary,
                 'packet': prepared, 'packet_sha256': contract.digest(prepared), 'factual_rendering': contract.factual_rendering(prepared),
                 'expected_kinds_private_oracle': ['cross_metric_pattern']},
                {'phase': 'prepared_attempt_started', 'case': case['name'], 'attempt': 1,
                 'request_base64': base64.b64encode(payload).decode(), 'request_sha256': infra.digest(payload)},
                {'phase': 'prepared_attempt_received', 'case': case['name'], 'attempt': 1, 'status': 500, 'seconds': 20,
                 'error_type': None, 'response_base64': base64.b64encode(response).decode(), 'response_sha256': infra.digest(response)},
                {'phase': 'prepared_failed', 'error_type': 'RuntimeError'},
                {'phase': 'prepared_cleanup', 'temporary_namespace_removed': True, 'final_live_state': baseline}]
        body = b'\n'.join(contract.compact(r) for r in rows)
        summary = replay.extract(body)
        self.assertFalse(summary['complete'])
        self.assertTrue(summary['cleanup_verified'])
        self.assertEqual(summary['inference_attempts'], 1)
        self.assertEqual(summary['received_attempts'], 1)
        self.assertEqual(summary['finalized_cases'], 0)
        self.assertEqual(summary['first_pass_accepted'], 0)
        self.assertEqual(summary['final_accepted'], 0)
        self.assertIsNone(summary['attempts'][0]['result'])
        self.assertEqual(summary['attempts'][0]['received']['status'], 500)
        rows[3]['request_sha256'] = 'invalid'
        with self.assertRaises(AssertionError):
            replay.extract(b'\n'.join(contract.compact(r) for r in rows))

    def test_retained_real_outputs_separate_contract_validity_relevance_and_staging_failure(self):
        directory = Path(__file__).parent / 'results'
        successful = json.loads((directory / '2026-10-02-qwen3-prepared-focus.json').read_text())
        partial = json.loads((directory / '2026-10-02-qwen35-prepared-focus-staging-failed.json').read_text())
        self.assertTrue(successful['complete'] and successful['cleanup_verified'])
        self.assertEqual(successful['source_jsonl_sha256'], '72e4d4d8e7f2596cbf1dea9af536d5f1eb9bfb2f124adeab6b966727f7e7ae5f')
        self.assertEqual((successful['first_pass_accepted'], successful['final_accepted']), (4, 4))
        self.assertEqual((successful['inference_attempts'], successful['correction_attempts']), (8, 2))
        self.assertEqual(len(successful['sparse_no_call_gates']), 2)
        self.assertTrue(successful['controls']['history_unchanged'] and successful['log_scan_passed'])
        by_name = {case['case']: case for case in successful['prepared_cases']}
        for attempt in successful['attempts']:
            case = by_name[attempt['case']]
            result = attempt['result']
            self.assertTrue(attempt['unload_verified'])
            self.assertEqual(replay.independent_validity(result['raw_model_content'], case['packet'], case['packet_sha256']), [])
            expected = contract.REQUESTS[attempt['case']][1]
            self.assertEqual(contract.relevance_flags(result['raw_model_content'], case['packet'], expected), result['relevance_flags'])
            self.assertEqual(result['accepted'], not result['relevance_flags'])
            if attempt['attempt'] == 2:
                original = next(a['result'] for a in successful['attempts'] if a['case'] == attempt['case'] and a['attempt'] == 1)
                self.assertEqual(original['raw_model_content'], result['raw_model_content'])
                self.assertFalse(result['accepted'])
        self.assertFalse(partial['complete'])
        self.assertTrue(partial['cleanup_verified'])
        self.assertEqual(partial['source_jsonl_sha256'], '24762d47bbfa35ce48d9543f5ddacced2899c0bde483f3900035636320b9c196')
        self.assertEqual((partial['inference_attempts'], partial['received_attempts'], partial['finalized_cases']), (0, 0, 0))
        self.assertEqual(partial['attempts'], [])
        self.assertEqual(partial['sparse_no_call_gates'], [])
        self.assertIsNone(partial['controls'])
        self.assertFalse(partial['log_scan_passed'])
        self.assertTrue(any(location['function'] == 'model_api' for location in partial['failures'][0]['code_locations']))
        failure_state = partial['failures'][1]['temporary_state']
        self.assertTrue(all(state['ready'] and not state['restarts'] and 'running' in state['state'] for state in failure_state.values()))
        self.assertEqual(successful['baseline_live_state'], partial['baseline_live_state'])

    def test_qwen35_diagnostic_rerun_preserves_task_and_records_no_retry_improvement(self):
        directory = Path(__file__).parent / 'results'
        rerun = json.loads((directory / '2026-10-02-qwen35-prepared-focus-diagnostic-rerun.json').read_text())
        control = json.loads((directory / '2026-10-02-qwen3-prepared-focus.json').read_text())
        initial = json.loads((directory / '2026-10-02-qwen35-prepared-focus-staging-failed.json').read_text())
        self.assertTrue(rerun['complete'] and rerun['cleanup_verified'])
        self.assertEqual(rerun['source_jsonl_sha256'], '597af7235d80543f9211d0999d65402b1d512b334fa8d72665a23acf4a6718f9')
        self.assertEqual((rerun['first_pass_accepted'], rerun['final_accepted']), (5, 5))
        self.assertEqual((rerun['inference_attempts'], rerun['correction_attempts']), (7, 1))
        self.assertEqual(rerun['prepared_cases'], control['prepared_cases'])
        self.assertEqual(rerun['baseline_live_state'], control['baseline_live_state'])
        self.assertEqual({k for k in initial['source_sha256'] if initial['source_sha256'][k] != rerun['source_sha256'][k]},
                         {'run_prepared_review_on_pi'})
        self.assertEqual(len(rerun['sparse_no_call_gates']), 2)
        self.assertTrue(rerun['controls']['history_unchanged'] and rerun['log_scan_passed'])
        self.assertEqual(rerun['failures'], [])
        self.assertEqual(rerun['telemetry']['sample_count'], 137)
        self.assertEqual(rerun['telemetry']['live_health_failures'], 0)
        self.assertEqual(rerun['telemetry']['synthetic_health_failures'], 0)
        self.assertEqual(rerun['staging_diagnostics'][0]['http_status'], 200)
        self.assertTrue(rerun['staging_diagnostics'][0]['success'])
        by_name = {case['case']: case for case in rerun['prepared_cases']}
        for attempt in rerun['attempts']:
            case = by_name[attempt['case']]
            result = attempt['result']
            self.assertTrue(attempt['unload_verified'])
            self.assertEqual(replay.independent_validity(result['raw_model_content'], case['packet'], case['packet_sha256']), [])
            self.assertEqual(contract.relevance_flags(result['raw_model_content'], case['packet'], contract.REQUESTS[attempt['case']][1]),
                             result['relevance_flags'])
        corrections = [a['result'] for a in rerun['attempts'] if a['attempt'] == 2]
        self.assertEqual(len(corrections), 1)
        self.assertEqual(corrections[0]['case'], 'unseen_zero_with_hrv_up')
        self.assertEqual(corrections[0]['raw_model_content'], '{"focus_ids":["f2"]}')
        first = next(a['result'] for a in rerun['attempts'] if a['case'] == corrections[0]['case'] and a['attempt'] == 1)
        self.assertEqual(first['raw_model_content'], corrections[0]['raw_model_content'])
        self.assertFalse(corrections[0]['accepted'])


if __name__ == '__main__':
    unittest.main()
