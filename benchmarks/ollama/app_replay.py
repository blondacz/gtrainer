"""Independent synthetic-only comparison/whole-response replay, not production AI.

Kotlin remains authoritative. This checks recorded app behaviour against an
independent, closed vocabulary and renderer, never salvaging rejected fragments.
"""
import base64
import hashlib
import json


def decode(raw):
    if len(raw) > 32768:
        raise ValueError('Oversized JSON')
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError('Duplicate JSON key')
            value[key] = item
        return value

    def invalid(_):
        raise ValueError('Non-finite constant')

    value = json.loads(raw, object_pairs_hook=unique, parse_constant=invalid)
    def bounded(item, depth=0):
        if depth > 16:
            raise ValueError('Excessive JSON depth')
        if isinstance(item, dict):
            for child in item.values():
                bounded(child, depth + 1)
        elif isinstance(item, list):
            for child in item:
                bounded(child, depth + 1)
    bounded(value)
    return value


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()


def validation_flags(raw, packet, summary):
    try:
        value = decode(raw)
        if not isinstance(value, dict) or set(value) != {'observations'}:
            return ['invalid_response_shape']
        claims = value['observations']
        if not isinstance(claims, list) or not 1 <= len(claims) <= 2:
            return ['invalid_observation_count']
        evidence = {e['id']: e for e in packet['evidence']}
        facts = {(f['period'], f['sport'], f['metric']): f for f in summary['facts']}
        flags, signatures, all_ids = [], set(), set()
        connected = False
        for claim in claims:
            if not isinstance(claim, dict) or set(claim) != {'kind', 'evidence'}:
                flags.append('invalid_claim_shape'); continue
            kind, references = claim['kind'], claim['evidence']
            if kind not in ('co_occurrence', 'sport_mix', 'recorded_change', 'unavailable_comparison'):
                flags.append('unsupported_kind'); continue
            if not isinstance(references, list) or not 1 <= len(references) <= 3:
                flags.append('invalid_evidence_count'); continue
            ids, selected = [], []
            for reference in references:
                if not isinstance(reference, dict) or set(reference) != {'id', 'state'}:
                    flags.append('invalid_reference_shape'); continue
                identifier, state = reference['id'], reference['state']
                if not isinstance(identifier, str) or identifier not in evidence:
                    flags.append('unknown_evidence'); continue
                item = evidence[identifier]
                if state != item['preparedState']:
                    flags.append('state_mismatch')
                ids.append(identifier); selected.append(item); all_ids.add(identifier)
            if len(selected) != len(references):
                continue
            if len(set(ids)) != len(ids):
                flags.append('duplicate_evidence')
            signature = kind, tuple(sorted(ids))
            if signature in signatures:
                flags.append('duplicate_observation')
            signatures.add(signature)
            available = all(e['preparedState'] != 'unavailable' for e in selected)
            same_periods = len({(e['previousOldest'], e['previousNewest'], e['currentOldest'], e['currentNewest']) for e in selected}) == 1
            for item in selected:
                if item['preparedState'] != 'unavailable' and any(
                        facts[(period, item['sport'], item['metric'])]['sampleCount'] < 2 for period in ('previous', 'current')):
                    flags.append('sparse_selected_evidence')
            if kind == 'recorded_change' and (len(selected) != 1 or not available):
                flags.append('invalid_recorded_change')
            elif kind == 'unavailable_comparison' and any(e['preparedState'] != 'unavailable' for e in selected):
                flags.append('invalid_unavailable_comparison')
            elif kind == 'co_occurrence':
                if not available or not same_periods or not any(e['sport'] is None for e in selected) or not any(e['sport'] is not None for e in selected):
                    flags.append('invalid_co_occurrence')
                else:
                    connected = True
            elif kind == 'sport_mix' and (not available or not same_periods or any(e['sport'] is None or e['metric'] != 'movingTime' for e in selected)
                                         or len({e['sport'] for e in selected}) < 2):
                flags.append('invalid_sport_mix')
        if not connected:
            flags.append('cross_metric_connection_missing')
        if all_ids != set(evidence):
            flags.append('incomplete_evidence_selection')
        return sorted(set(flags))
    except (ValueError, TypeError, KeyError):
        return ['invalid_json_or_shape']


def render(raw, packet, summary):
    if validation_flags(raw, packet, summary):
        return None
    by_id = {e['id']: e for e in packet['evidence']}
    by_metric = {(f['period'], f['sport'], f['metric']): f for f in summary['facts']}
    output = []
    for claim in decode(raw)['observations']:
        sentences, supports = [], []
        for ref in claim['evidence']:
            e = by_id[ref['id']]
            facts = [by_metric[(period, e['sport'], e['metric'])] for period in ('previous', 'current')]
            supports.extend(facts)
            label = facts[1]['label'] + (' (mean of populated source records)' if facts[1]['aggregation'] == 'mean' else ' (sum of populated source records)')
            value = ('comparison unavailable; missing measurements are unknown, not zero' if e['preparedState'] == 'unavailable' else
                     f"{e['preparedState']}: {float(e['previousValue'])} to {float(e['currentValue'])} {e['unit']}")
            sentences.append(f"{e['sport'] or 'Wellness'} {label} {value}. Periods: {e['previousOldest']}–{e['previousNewest']} and {e['currentOldest']}–{e['currentNewest']}.")
        if claim['kind'] == 'co_occurrence':
            sentences.append('These observed comparisons cover the same periods; they do not establish cause, recovery, or readiness.')
        elif claim['kind'] == 'sport_mix':
            sentences.append('Recorded time in different sports is not equivalent effort or training stimulus.')
        output.append({'text': ' '.join(sentences), 'evidenceIds': [f['evidenceId'] for f in supports], 'supportingMetrics': supports})
    return output


def replay(record):
    summary, packet = record['analysis_input'], record['prepared_packet_oracle']
    assert hashlib.sha256(compact(record['report'])).hexdigest() == summary['evidenceReportSha256']
    entries = record['capture']
    if not entries:
        assert record['app_response']['reason'] == 'insufficient_input'
        return {'gate': 'insufficient_input', 'flags': [], 'rendered': None}
    requests = [entry for entry in entries if entry['event'] == 'request']
    responses = [entry for entry in entries if entry['event'] == 'response']
    assert len(requests) == 1
    request_bytes = base64.b64decode(requests[0]['body_base64'], validate=True)
    assert hashlib.sha256(request_bytes).hexdigest() == requests[0]['body_sha256']
    payload = decode(request_bytes)
    forwarded = [entry for entry in entries if entry['event'] == 'forwarded_request']
    if 'prompt_arm' in record:
        from app_prompt_experiment import rewrite_body, prompts
        assert len(forwarded) == 1 and forwarded[0]['id'] == requests[0]['id']
        assert forwarded[0]['prompt_arm'] == record['prompt_arm'] and forwarded[0]['explicit_prompt_experiment'] is True
        sent = base64.b64decode(forwarded[0]['body_base64'], validate=True)
        assert hashlib.sha256(sent).hexdigest() == forwarded[0]['body_sha256']
        control, skill = prompts(record.get('prompt_experiment_profile', 'baseline-v1'))
        assert hashlib.sha256(skill.encode()).hexdigest() == record['skill_prompt_sha256']
        if control is not None:
            assert hashlib.sha256(control.encode()).hexdigest() == record['control_prompt_sha256']
        assert sent == rewrite_body(request_bytes, record['prompt_arm'], skill, control)
    else:
        assert not forwarded
    assert decode(payload['messages'][1]['content']) == packet
    packet_hash = hashlib.sha256(payload['messages'][1]['content'].encode()).hexdigest()
    if not responses:
        assert record['app_response']['status'] == 'unavailable'
        return {'gate': 'transport_incomplete', 'packet_sha256': packet_hash, 'flags': [], 'rendered': None}
    assert len(responses) == 1 and responses[0]['id'] == requests[0]['id'] and responses[0]['fault'] is None
    response_bytes = base64.b64decode(responses[0]['body_base64'], validate=True)
    assert hashlib.sha256(response_bytes).hexdigest() == responses[0]['body_sha256']
    if responses[0].get('status', 200) != 200:
        assert record['app_response']['status'] == 'unavailable' and record['app_response']['observations'] == []
        return {'gate': 'transport_rejected', 'packet_sha256': packet_hash, 'http_status': responses[0]['status'],
                'flags': [], 'rendered': None}
    response = decode(response_bytes)
    assert response['model'] == payload['model']
    if response.get('done') is not True or response.get('done_reason') != 'stop' or response['message'].get('thinking'):
        assert record['app_response']['status'] == 'unavailable'
        return {'gate': 'incomplete_generation', 'packet_sha256': packet_hash, 'flags': [], 'rendered': None}
    raw = response['message']['content']
    flags = validation_flags(raw, packet, summary)
    rendered = render(raw, packet, summary)
    if record['app_response'].get('reason') == 'evidence_changed':
        assert record['app_response']['status'] == 'unavailable' and record['app_response']['observations'] == []
        return {'gate': 'evidence_invalidated', 'packet_sha256': packet_hash, 'flags': flags, 'rendered': None,
                'model_contract_valid_for_old_packet': not flags,
                'load_seconds': response.get('load_duration', 0) / 1e9, 'prompt_tokens': response.get('prompt_eval_count'),
                'output_tokens': response.get('eval_count'), 'done_reason': response.get('done_reason')}
    if flags:
        assert record['app_response']['reason'] == 'unusable_model_output' and record['app_response']['observations'] == []
    else:
        assert record['app_response']['status'] == 'available' and record['app_response']['observations'] == rendered
    return {'gate': 'validated' if not flags else 'rejected_output', 'packet_sha256': packet_hash, 'flags': flags, 'rendered': rendered,
            'load_seconds': response.get('load_duration', 0) / 1e9, 'prompt_tokens': response.get('prompt_eval_count'),
            'output_tokens': response.get('eval_count'), 'done_reason': response.get('done_reason')}
