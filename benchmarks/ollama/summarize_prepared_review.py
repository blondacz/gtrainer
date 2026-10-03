#!/usr/bin/env python3
"""Replay original synthetic prepared-focus captures; never repair model outputs."""
import argparse
import base64
import hashlib
import json
from pathlib import Path

import app_replay
import prepared_review as contract
from prepared_review_suites import suite_data, rubric_hash
import run_app_on_pi as infra


def independent_validity(raw, prepared, binding):
    if hashlib.sha256(contract.compact(prepared)).hexdigest() != binding:
        return ['evidence_changed']
    try:
        parsed = app_replay.decode(raw)
    except (ValueError, TypeError, RecursionError):
        return ['invalid_json']
    if not isinstance(parsed, dict) or set(parsed) != {'focus_ids'}:
        return ['invalid_response_shape']
    selection = parsed['focus_ids']
    if not isinstance(selection, list) or not 1 <= len(selection) <= 2 or any(type(i) is not str for i in selection):
        return ['invalid_focus_count']
    known = {c['id'] for c in prepared['candidates']}
    if not all(i in known for i in selection):
        return ['unsupported_focus']
    if len(selection) != len(set(selection)):
        return ['duplicate_focus']
    return []


def verify_fixture(case, packet):
    count = case['activity_samples_per_period']
    expected = [(sport, 'movingTime', 'seconds', before * count, after * count) for sport, before, after in case['sports']]
    expected += [(None, 'sleepSecs', 'seconds', *case['sleep']), (None, 'hrv', 'ms', *case['hrv'])]
    assert len(packet['evidence']) == len(expected)
    for index, (e, (sport, metric, unit, before, after)) in enumerate(zip(packet['evidence'], expected)):
        state = 'unavailable' if before is None or after is None else 'increased' if after > before else 'decreased' if after < before else 'unchanged'
        assert (e['id'], e['sport'], e['metric'], e['unit'], e['previousValue'], e['currentValue'], e['preparedState']) == (f'e{index}', sport, metric, unit, before, after, state)
        assert [e[k] for k in ('previousOldest', 'previousNewest', 'currentOldest', 'currentNewest')] == [case[k] for k in ('previousOldest', 'previousNewest', 'oldest', 'newest')]


def extract(body):
    rows = [json.loads(line) for line in body.splitlines()]
    preflight = next(r for r in rows if r['phase'] == 'prepared_preflight')
    assert preflight['synthetic_only'] is True and preflight['profile'] == contract.PROFILE
    assert preflight['settings'] == contract.OPTIONS
    assert preflight['call_seconds'] == contract.CALL_SECONDS and preflight['job_seconds'] == contract.JOB_SECONDS
    model = preflight['model']['tag']
    assert preflight['model']['digest'] == contract.MODELS[model]
    assert preflight['system_sha256'] == hashlib.sha256(contract.SYSTEM.encode()).hexdigest()
    suite = preflight.get('fixture_suite', 'known-v1')
    fixture_cases, requests, rotations = suite_data(suite)
    assert preflight['fixture_sha256'] == contract.digest(fixture_cases)
    if 'rubric_sha256' in preflight:
        assert preflight['rubric_sha256'] == rubric_hash(requests)
    if suite == 'fresh-v1':
        assert model == 'qwen3.5:4b' and preflight['fresh_structural_variants'] is True
        assert preflight['external_blinded_holdout'] is False
    if 'response_export_independent_of_control' in preflight:
        assert preflight['response_export_independent_of_control'] is True
        assert preflight['control_poll_seconds'] == 10 and preflight['control_timeout_seconds'] == 10
        assert preflight['control_maximum_age_seconds'] == 25
    by_name = {c['name']: c for c in fixture_cases}
    started, received, cases, results, unloaded, sparse, outcomes = {}, {}, {}, [], set(), [], []
    for row in rows:
        phase = row['phase']
        if phase == 'prepared_case':
            name = row['case']
            case = by_name[name]
            assert name not in cases
            verify_fixture(case, row['source_packet'])
            report_raw = base64.b64decode(row['report_base64'], validate=True)
            assert hashlib.sha256(report_raw).hexdigest() == row['report_sha256'] == row['analysis_input']['evidenceReportSha256']
            assert json.loads(report_raw) == row['report']
            assert infra.prepared_packet(row['analysis_input']) == row['source_packet']
            assert contract.sufficient(row['source_packet'], row['analysis_input'])
            index = list(by_name).index(name)
            requested, expected = requests[name]
            assert set(row['expected_kinds_private_oracle']) == expected
            assert row['packet'] == contract.prepare(row['source_packet'], requested, rotations[index])
            assert row['packet_sha256'] == contract.digest(row['packet'])
            assert row['factual_rendering'] == contract.factual_rendering(row['packet'])
            flat = [e for g in row['packet']['groups'] for e in g['evidence']]
            assert sorted(flat, key=lambda e: e['id']) == sorted(row['source_packet']['evidence'], key=lambda e: e['id'])
            for group in row['packet']['groups']:
                for e in group['evidence']:
                    assert group['unavailable'] == (e['preparedState'] == 'unavailable')
                    assert group['period'] == [e[k] for k in ('previousOldest', 'previousNewest', 'currentOldest', 'currentNewest')]
            cases[name] = row
        elif phase == 'prepared_attempt_started':
            key = (row['case'], row['attempt'])
            assert key not in started and row['attempt'] in (1, 2)
            packet = cases[key[0]]['packet']
            request_raw = base64.b64decode(row['request_base64'], validate=True)
            assert hashlib.sha256(request_raw).hexdigest() == row['request_sha256']
            feedback = None
            if key[1] == 2:
                previous = next(r for r in results if (r['case'], r['attempt']) == (key[0], 1))
                assert not previous['accepted']
                feedback = previous['validity_flags'] or previous['relevance_flags']
                assert contract.can_retry(feedback, 1, received[(key[0], 1)]['seconds'])
            assert request_raw == contract.compact(contract.request(model, packet, feedback))
            started[key] = row
        elif phase == 'prepared_attempt_received':
            key = (row['case'], row['attempt'])
            assert key in started and key not in received
            response_raw = base64.b64decode(row['response_base64'], validate=True)
            assert hashlib.sha256(response_raw).hexdigest() == row['response_sha256']
            received[key] = row
        elif phase == 'prepared_attempt_result':
            key = (row['case'], row['attempt'])
            original = received[key]
            assert original['status'] == 200 and not original['error_type']
            response = json.loads(base64.b64decode(original['response_base64'], validate=True))
            assert response['message']['content'] == row['raw_model_content']
            case = cases[key[0]]
            flags = independent_validity(row['raw_model_content'], case['packet'], case['packet_sha256'])
            assert flags == contract.validate(row['raw_model_content'], case['packet'], case['packet_sha256'])
            if response.get('done') is not True or response.get('done_reason') != 'stop':
                flags.append('incomplete_generation')
            if response['message'].get('thinking'):
                flags.append('unexpected_thinking')
            # Evidence invalidation cannot be treated as successful raw selection.
            if row['report_sha256_after_generation'] != case['report_sha256']:
                flags = sorted(set(flags + ['evidence_changed']))
            assert flags == row['validity_flags']
            if flags:
                relevance = ['not_validated']
            else:
                selected = set(app_replay.decode(row['raw_model_content'])['focus_ids'])
                kinds = {c['kind'] for c in case['packet']['candidates'] if c['id'] in selected}
                relevance = [] if kinds and kinds.issubset(requests[key[0]][1]) else ['requested_focus_not_answered']
            assert relevance == row['relevance_flags']
            assert row['accepted'] == (not flags and not relevance)
            assert row['rendering'] == (contract.focus_rendering(row['raw_model_content'], case['packet'], case['packet_sha256']) if row['accepted'] else None)
            assert row['prompt_tokens'] == response.get('prompt_eval_count') and row['output_tokens'] == response.get('eval_count')
            results.append(row)
        elif phase == 'prepared_unloaded':
            assert row['verified'] is True
            unloaded.add((row['case'], row['attempt']))
        elif phase == 'prepared_case_outcome':
            assert row['job_seconds'] <= contract.JOB_SECONDS
            previous = next(r for r in reversed(results) if r['case'] == row['case'])
            assert previous['attempt'] == row['final_attempt'] and previous['accepted'] == row['accepted']
            assert (row['case'], row['final_attempt']) in unloaded
            assert not any(r['case'] == row['case'] for r in outcomes)
            outcomes.append(row)
        elif phase == 'prepared_sparse_gate':
            assert row['case'] not in requests and row['model_calls'] == 0
            verify_fixture(by_name[row['case']], row['source_packet'])
            assert not contract.sufficient(row['source_packet'], row['analysis_input'])
            sparse.append(row['case'])
    controls = next((r for r in rows if r['phase'] == 'prepared_controls'), None)
    ready = next((r for r in rows if r['phase'] == 'prepared_runtime_ready'), None)
    if controls:
        assert controls['history_unchanged'] and controls['no_app_ai_calls'] and controls['logout_verified']
        assert controls['history_sha256_after'] == ready['history_sha256_before']
    cleanup = next((r for r in rows if r['phase'] == 'prepared_cleanup'), None)
    cleanup_verified = bool(cleanup and cleanup['temporary_namespace_removed'] and cleanup['final_live_state'] == preflight['baseline_live_state'])
    final_by_case = {r['case']: r for r in outcomes}
    samples = [r for r in rows if r['phase'] == 'resource_sample']
    telemetry = {'sample_count': len(samples), 'live_health_failures': sum(not r['live_health_ok'] for r in samples),
                 'synthetic_health_failures': sum(not r['synthetic_health_ok'] for r in samples)}
    for output, source, operation in (('maximum_temperature_c', 'temperature_c', max),
                                      ('minimum_host_available_mib', 'host_available_mib', min),
                                      ('ollama_lifetime_peak_mib', 'ollama_lifetime_peak_mib', max),
                                      ('ollama_maximum_current_mib', 'ollama_current_mib', max)):
        values = [s[source] for s in samples if s.get(source) is not None]
        telemetry[output] = operation(values) if values else None
    complete = any(r['phase'] == 'prepared_complete' for r in rows)
    control_failures = [r for r in rows if r['phase'] in ('prepared_control_failure', 'prepared_capture_shutdown_failed')]
    if complete:
        assert controls and cleanup_verified and len(sparse) == 2 and set(final_by_case) == set(requests)
        assert all(key in unloaded for key in received)
        assert len(started) == len(received) == len(results)
        assert not any(r['phase'] == 'prepared_failed' for r in rows)
        assert not control_failures
        if 'response_export_independent_of_control' in preflight:
            control_samples = [r for r in rows if r['phase'] == 'prepared_control_sample']
            assert control_samples and all(r['healthy'] for r in control_samples)
    attempts = []
    for key, request in started.items():
        result = next((r for r in results if (r['case'], r['attempt']) == key), None)
        attempts.append({**request, 'received': received.get(key), 'result': result, 'unload_verified': key in unloaded})
    return {'profile': contract.PROFILE, 'synthetic_only': True, 'not_production_app_acceptance': True,
            'source_jsonl_sha256': hashlib.sha256(body).hexdigest(), 'model': model, 'model_digest': contract.MODELS[model],
            'source_sha256': rows[0]['source_sha256'], 'complete': complete, 'cleanup_verified': cleanup_verified,
            'first_pass_accepted': sum(r['accepted'] for r in results if r['attempt'] == 1),
            'final_accepted': sum(r['accepted'] for r in final_by_case.values()),
            'finalized_cases': len(final_by_case), 'planned_usable_cases': len(requests),
            **({'fixture_suite': suite, 'fixture_sha256': preflight['fixture_sha256'],
                'rubric_sha256': preflight['rubric_sha256'], 'fresh_structural_variants': True,
                'external_blinded_holdout': False} if suite == 'fresh-v1' else {}),
            'inference_attempts': len(started), 'received_attempts': len(received),
            'correction_attempts': sum(key[1] == 2 for key in started), 'sparse_no_call_gates': sparse,
            'attempts': attempts, 'prepared_cases': [{k: r[k] for k in ('case', 'packet', 'packet_sha256', 'source_packet', 'factual_rendering', 'report_sha256')} for r in cases.values()],
            'case_outcomes': outcomes,
            'telemetry': telemetry, 'controls': controls,
            **({'staging_diagnostics': [r for r in rows if r['phase'] == 'prepared_staging_diagnostic']}
               if any(r['phase'] == 'prepared_staging_diagnostic' for r in rows) else {}),
            **({'control_failures': control_failures,
                'control_sample_count': sum(r['phase'] == 'prepared_control_sample' for r in rows),
                'response_export_independent_of_control': preflight['response_export_independent_of_control']}
               if 'response_export_independent_of_control' in preflight else {}),
            'log_scan_passed': any(r['phase'] == 'prepared_log_scan' and r['passed'] for r in rows),
            'failures': [r for r in rows if r['phase'] in ('prepared_failed', 'prepared_failure_state', 'prepared_stop_state')],
            'baseline_live_state': preflight['baseline_live_state'], 'cleanup': cleanup}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    args = parser.parse_args()
    print(json.dumps(extract(args.capture.read_bytes()), indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
