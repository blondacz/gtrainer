"""Frozen synthetic focus-selection experiment; not the production app contract.

Code owns complete facts and supported relationships before inference. The model
only selects a focus; grammar success is not usefulness or sports qualification.
"""
import hashlib
import json

PROFILE = 'prepared-focus-v1'
MODELS = {
    'qwen3:4b-instruct': '0edcdef34593eac1aa2be9c7d06c432dcf81945adca5eca2f27662c18f168ba0',
    'qwen3.5:4b': '2a654d98e6fba55d452b7043684e9b57a947e393bbffa62485a7aac05ee4eefd',
}
OPTIONS = {'num_ctx': 2048, 'num_thread': 3, 'num_predict': 256, 'temperature': 0, 'seed': 42}
CALL_SECONDS = 240
JOB_SECONDS = 600
SYSTEM = """Select a descriptive review focus from a synthetic code-prepared packet.
The application already grouped every required fact and will show them all,
including missing comparisons, independently of your answer. Do not recalculate,
regroup, copy facts, invent relationships, or generate personal prose.
Select one or two candidate IDs that directly answer the requested focus. Prefer
the most relevant candidate, not the first ID. Do not select unrelated candidates
merely to cover facts: factual coverage is already guaranteed by code.
daily_combined: choose a concurrent activity/wellness pattern when supported.
activity_balance: choose the sport-mix pattern when supported.
wellness: choose wellness pattern when supported, or its coverage gap otherwise.
missing_wellness: choose the wellness coverage gap when supplied.
Do not infer cause, recovery, illness, readiness, missed sessions, safety,
proprietary Garmin scores or workout prescriptions from descriptive directions.
Return only {"focus_ids":["candidate ID"]}. No other fields or text.
"""

# Private evaluation rubric: never sent to the model. This is task relevance,
# not a clinical/sporting ground truth. Existing known fixtures are intentionally
# reused; do not describe this suite as an unseen external holdout.
REQUESTS = {
    'unseen_opposing_sleep_up': ('daily_combined', {'cross_metric_pattern'}),
    'unseen_steady_with_hrv_drop': ('wellness', {'wellness_pattern'}),
    'unseen_two_sports_both_increase': ('activity_balance', {'sport_mix'}),
    'unseen_two_sports_sleep_unknown': ('missing_wellness', {'coverage_gap'}),
    'unseen_sleep_previous_unknown': ('missing_wellness', {'coverage_gap'}),
    'unseen_zero_with_hrv_up': ('daily_combined', {'cross_metric_pattern'}),
}


def compact(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def digest(value):
    return hashlib.sha256(compact(value)).hexdigest()


def decode(raw):
    if not isinstance(raw, str) or len(raw.encode()) > 32768:
        raise ValueError('Bounded JSON text required')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError('Nonfinite JSON constant')

    value = json.loads(raw, object_pairs_hook=unique, parse_constant=invalid_constant)

    def bounded(item, depth=0):
        if depth > 16:
            raise ValueError('Excessive nesting')
        if isinstance(item, dict):
            for child in item.values():
                bounded(child, depth + 1)
        elif isinstance(item, list):
            for child in item:
                bounded(child, depth + 1)

    bounded(value)
    return value


def sufficient(packet, summary):
    facts = {(f['period'], f['sport'], f['metric']): f for f in summary['facts']}
    usable = [e for e in packet['evidence'] if e['preparedState'] != 'unavailable' and 'sparse_comparison' not in e['flags'] and
              all(facts[(p, e['sport'], e['metric'])]['sampleCount'] >= 2 for p in ('previous', 'current'))]
    return any(e['sport'] is not None for e in usable) and any(e['sport'] is None for e in usable)


def prepare(packet, requested_focus, rotation=0):
    if requested_focus not in {v[0] for v in REQUESTS.values()}:
        raise ValueError('Unsupported synthetic requested focus')
    evidence = packet['evidence']
    if not evidence or len({e['id'] for e in evidence}) != len(evidence):
        raise ValueError('Unique nonempty evidence required')
    groups = []
    for e in evidence:
        if e['preparedState'] not in ('increased', 'decreased', 'unchanged', 'unavailable'):
            raise ValueError('Unsupported prepared state')
        period = tuple(e[k] for k in ('previousOldest', 'previousNewest', 'currentOldest', 'currentNewest'))
        unavailable = e['preparedState'] == 'unavailable'
        group = next((g for g in groups if tuple(g['period']) == period and g['unavailable'] == unavailable), None)
        if group is None:
            group = {'id': f'g{len(groups)}', 'period': list(period), 'unavailable': unavailable, 'evidence': []}
            groups.append(group)
        group['evidence'].append(json.loads(compact(e)))
    candidates = []
    for group in groups:
        items = group['evidence']
        activity = [e for e in items if e['sport'] is not None]
        wellness = [e for e in items if e['sport'] is None]
        kinds = []
        if group['unavailable']:
            if wellness:
                kinds.append(('coverage_gap', wellness))
        else:
            if activity:
                kinds.append(('activity_pattern', activity))
            if wellness:
                kinds.append(('wellness_pattern', wellness))
            if activity and wellness:
                kinds.append(('cross_metric_pattern', items))
            if len({e['sport'] for e in activity}) >= 2:
                kinds.append(('sport_mix', activity))
        for kind, selected in kinds:
            candidates.append({'kind': kind, 'group_id': group['id'],
                               'evidence_ids': [e['id'] for e in selected],
                               'states': [e['preparedState'] for e in selected]})
    if not candidates:
        raise ValueError('No supported focus candidates')
    offset = rotation % len(candidates)
    candidates = candidates[offset:] + candidates[:offset]
    for index, candidate in enumerate(candidates):
        candidate['id'] = f'f{index}'
    result = {'profile': PROFILE, 'synthetic_only': True, 'requested_focus': requested_focus,
              'groups': groups, 'candidates': candidates,
              'limitations': ['descriptive_not_causal', 'not_readiness_or_training_advice',
                              'missing_is_unknown', 'recorded_time_not_intensity']}
    assert {e['id'] for g in groups for e in g['evidence']} == {e['id'] for e in evidence}
    assert sum(len(g['evidence']) for g in groups) == len(evidence)
    return result


def schema(prepared):
    return {'type': 'object', 'additionalProperties': False,
            'properties': {'focus_ids': {'type': 'array', 'minItems': 1, 'maxItems': 2,
                                       'items': {'type': 'string', 'enum': [c['id'] for c in prepared['candidates']]}}},
            'required': ['focus_ids']}


def validate(raw, prepared, expected_hash):
    if digest(prepared) != expected_hash:
        return ['evidence_changed']
    try:
        output = decode(raw)
    except (ValueError, TypeError, RecursionError):
        return ['invalid_json']
    if not isinstance(output, dict) or set(output) != {'focus_ids'}:
        return ['invalid_response_shape']
    ids = output['focus_ids']
    if not isinstance(ids, list) or not 1 <= len(ids) <= 2 or any(not isinstance(i, str) for i in ids):
        return ['invalid_focus_count']
    if any(i not in {c['id'] for c in prepared['candidates']} for i in ids):
        return ['unsupported_focus']
    if len(set(ids)) != len(ids):
        return ['duplicate_focus']
    return []


def relevance_flags(raw, prepared, accepted_kinds):
    if validate(raw, prepared, digest(prepared)):
        return ['not_validated']
    selected = set(decode(raw)['focus_ids'])
    kinds = {c['kind'] for c in prepared['candidates'] if c['id'] in selected}
    return [] if kinds and kinds.issubset(accepted_kinds) else ['requested_focus_not_answered']


def factual_rendering(prepared):
    """All prose/values/dates/support are authoritative code, not model fragments."""
    facts = []
    for group in prepared['groups']:
        for e in group['evidence']:
            label = f"{e['sport'] or 'wellness'} {e['metric']}"
            description = ('comparison unavailable; missing is unknown, not zero' if group['unavailable'] else
                           f"{e['preparedState']}: {e['previousValue']} to {e['currentValue']} {e['unit']}")
            facts.append({'evidence_id': e['id'], 'group_id': group['id'], 'period': group['period'],
                          'text': label + ': ' + description, 'support': e})
    return {'application_generated': True, 'facts': facts, 'limitations': prepared['limitations']}


def focus_rendering(raw, prepared, expected_hash):
    if validate(raw, prepared, expected_hash):
        return None
    labels = {
        'activity_pattern': 'Recorded activity changes; time does not establish effort.',
        'wellness_pattern': 'Observed wellness directions; no recovery or illness conclusion.',
        'cross_metric_pattern': 'Activity and wellness observations cover the same periods; no cause is established.',
        'sport_mix': 'Recorded time across sports is not interchangeable training stimulus.',
        'coverage_gap': 'Wellness comparison is unavailable; missing measurements remain unknown.',
    }
    by_id = {c['id']: c for c in prepared['candidates']}
    return {'model_selected_code_rendered': True, 'focuses': [
        {**by_id[i], 'text': labels[by_id[i]['kind']]} for i in decode(raw)['focus_ids']]}


def request(model, prepared, feedback=None):
    if model not in MODELS or prepared.get('synthetic_only') is not True:
        raise ValueError('Explicit supported model and synthetic packet required')
    messages = [{'role': 'system', 'content': SYSTEM}, {'role': 'user', 'content': compact(prepared).decode()}]
    if feedback is not None:
        allowed = {'invalid_json', 'invalid_response_shape', 'invalid_focus_count', 'unsupported_focus',
                   'duplicate_focus', 'requested_focus_not_answered'}
        if not feedback or any(f not in allowed for f in feedback):
            raise ValueError('Only bounded validation feedback permits correction')
        messages.append({'role': 'user', 'content': 'Previous attempt was rejected: ' + ', '.join(sorted(set(feedback))) +
                         '. Return a new complete focus selection for the SAME packet and requested focus.'})
    return {'model': model, 'stream': False, 'think': False, 'keep_alive': '0s',
            'format': schema(prepared), 'messages': messages, 'options': OPTIONS.copy()}


def can_retry(flags, attempt, elapsed_seconds):
    allowed = {'invalid_json', 'invalid_response_shape', 'invalid_focus_count', 'unsupported_focus',
               'duplicate_focus', 'requested_focus_not_answered'}
    return bool(flags) and set(flags).issubset(allowed) and attempt == 1 and elapsed_seconds < JOB_SECONDS - CALL_SECONDS
