#!/usr/bin/env python3
"""Replay operator-owned synthetic JSONL without cluster access or private reads."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from app_replay import replay


def collected_cases(records):
    """Include received/captured outcomes before a runner assertion, without inventing completion."""
    key = lambda r: (r['case'], r['repetition'])
    finalized = {key(r): r for r in records if r['phase'] == 'case_result'}
    captured = {key(r): r for r in records if r['phase'] == 'captured_transport'}
    experiment = next((r for r in records if r['phase'] == 'prompt_experiment'), None)
    output, seen = [], set()
    for record in records:
        if record['phase'] not in ('case_received', 'case_result') or key(record) in seen:
            continue
        identifier = key(record)
        if identifier in finalized:
            outcome = finalized[identifier]
        elif record['phase'] == 'case_received' and identifier in captured:
            outcome = {**captured[identifier], **record, 'phase': 'case_result',
                       'synthetic_only': True, 'record_status': 'received_not_finalized'}
            if experiment:
                outcome['prompt_arm'] = record['repetition'][-1]
                outcome['skill_prompt_sha256'] = experiment['skill_prompt_sha256']
                if 'profile' in experiment:
                    outcome['prompt_experiment_profile'] = experiment['profile']
                    outcome['control_prompt_sha256'] = experiment['control_prompt_sha256']
        else:
            continue
        output.append(outcome); seen.add(identifier)
    return output


def summarize(records):
    preflight = next(r for r in records if r['phase'] == 'preflight')
    source = next(r for r in records if r['phase'] == 'operator_sources')
    assert preflight['synthetic_only'] and source['synthetic_only']
    cases = []
    for record in collected_cases(records):
        assert record['synthetic_only']
        result = replay(record)
        cases.append({'case': record['case'], 'repetition': record['repetition'],
                       'wall_seconds': record['wall_seconds'], 'status': record['app_response']['status'],
                       'reason': record['app_response']['reason'], 'chart_probe': record.get('chart_probe'), 'replay': result})
        if 'prompt_arm' in record:
            cases[-1]['prompt_arm'] = record['prompt_arm']
        if 'record_status' in record:
            cases[-1]['record_status'] = record['record_status']
    telemetry = [r for r in records if r['phase'] in ('telemetry', 'telemetry_after_failure')]
    samples = [s for r in telemetry for s in r['samples']]
    if not telemetry:
        samples = [r for r in records if r['phase'] == 'resource_sample']
    def maximum(key):
        values = [s[key] for s in samples if key in s and s[key] is not None]
        return max(values) if values else None
    inference = [c for c in cases if c['replay']['gate'] != 'insufficient_input']
    accepted = [c for c in inference if c['replay']['gate'] == 'validated']
    complete = any(r['phase'] == 'benchmark_complete' for r in records)
    experiment = next((r for r in records if r['phase'] == 'prompt_experiment'), None)
    cleanup = [r for r in records if r['phase'] == 'cleanup']
    if cleanup:
        assert cleanup[-1]['temporary_namespace_removed']
        assert cleanup[-1]['final_live_state'] == preflight['baseline_live_state']
    if complete:
        assert len(cases) == (14 if experiment else 10) and len(inference) == (12 if experiment else 8)
        if experiment:
            from app_prompt_experiment import sequence, prompts, fixture_cases
            profile = experiment.get('profile', 'baseline-v1')
            fixtures = fixture_cases(profile)
            control, skill = prompts(profile)
            assert [(c['case'], c['repetition']) for c in cases] == [(c['name'], arm) for c, arm in sequence(fixtures)]
            assert experiment['skill_prompt_sha256'] == hashlib.sha256(skill.encode()).hexdigest()
            if control is not None:
                assert experiment['control_prompt_sha256'] == hashlib.sha256(control.encode()).hexdigest()
                assert experiment['fixture_cases_sha256'] == hashlib.sha256(json.dumps(fixtures, separators=(',', ':'), allow_nan=False).encode()).hexdigest()
            for name in {c['case'] for c in inference}:
                pair = [c for c in inference if c['case'] == name]
                assert len(pair) == 2 and {c['prompt_arm'] for c in pair} == {'A', 'B'}
                assert pair[0]['replay']['packet_sha256'] == pair[1]['replay']['packet_sha256']
        assert cleanup and samples and not any(r['resource_guard_failed'] for r in telemetry)
        assert any(r['phase'] == 'functional_controls' for r in records)
    output = {'synthetic_only': True, 'complete': complete, 'cleanup_verified': bool(cleanup),
            'app_image': preflight['app_image'], 'app_revision': preflight['app_revision'],
            'candidate': preflight.get('candidate'),
            'operator_source_sha256': source['source_sha256'], 'cases': cases,
            'inference_attempts': len(inference), 'accepted': len(accepted),
            'distinct_usable_cases': len({c['case'] for c in inference}),
            'distinct_accepted_cases': len({c['case'] for c in accepted}),
            'unavailable_reasons': dict(Counter(c['reason'] for c in cases if c['status'] == 'unavailable')),
            'telemetry': {'sample_count': len(samples), 'maximum_temperature_c': maximum('temperature_c'),
                          'minimum_host_available_mib': min((s['host_available_mib'] for s in samples), default=None),
                          'live_health_failures': sum(not s['live_health_ok'] for s in samples) if samples else None,
                          'synthetic_health_failures': sum(not s['synthetic_health_ok'] for s in samples) if samples else None,
                          'maximum_live_health_seconds': maximum('live_health_seconds'),
                          'maximum_synthetic_health_seconds': maximum('synthetic_health_seconds'),
                          **{name + '_' + field: maximum(name + '_' + field)
                             for name in ('ollama', 'app', 'proxy') for field in ('current_mib', 'lifetime_peak_mib')}},
            'failures': [r for r in records if r['phase'] == 'benchmark_failed'],
             'qualification': 'No automatic default, production resource qualification, or actual-health authorization.'}
    if experiment:
        output['prompt_experiment'] = experiment
        output['by_prompt_arm'] = {}
        for arm in ('A', 'B'):
            subset = [c for c in inference if c['prompt_arm'] == arm]
            output['by_prompt_arm'][arm] = {
                'inference_attempts': len(subset), 'accepted': sum(c['replay']['gate'] == 'validated' for c in subset),
                'latency_seconds': {'minimum': min((c['wall_seconds'] for c in subset), default=None),
                                    'maximum': max((c['wall_seconds'] for c in subset), default=None)},
                'flags': dict(Counter(flag for c in subset for flag in c['replay']['flags'])),
                'maximum_prompt_tokens': max((c['replay'].get('prompt_tokens') or 0 for c in subset), default=None)}
        comparable_names = {c['case'] for c in inference if c['replay']['gate'] == 'validated'}
        comparable_names |= {c['case'] for c in inference if c['replay']['gate'] == 'rejected_output'}
        comparable_names = {name for name in comparable_names if
                            {c['prompt_arm'] for c in inference if c['case'] == name and c['replay']['gate'] in ('validated', 'rejected_output')} == {'A', 'B'}}
        output['comparable_pairs'] = len(comparable_names)
        output['paired_accepted'] = {arm: sum(c['case'] in comparable_names and c['prompt_arm'] == arm and c['replay']['gate'] == 'validated' for c in inference)
                                     for arm in ('A', 'B')}
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('synthetic_jsonl', type=Path)
    args = parser.parse_args()
    raw = args.synthetic_jsonl.read_bytes()
    assert len(raw) <= 8 * 1024**2
    result = summarize([json.loads(line) for line in raw.splitlines()])
    result['source_jsonl_sha256'] = hashlib.sha256(raw).hexdigest()
    print(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False))


if __name__ == '__main__':
    main()
