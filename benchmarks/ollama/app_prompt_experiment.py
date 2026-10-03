"""Synthetic-only A/B instruction-card experiment; not model training or deployment."""
import hashlib
import json
from pathlib import Path

BASELINE_PROMPT_SHA256 = '9e544322e7eb0c976dbcef4c2973b3b0859fb5e5505701d3f76c549ca558e314'
PROFILES = ('baseline-v1', 'card-v2-unseen')


def skill_prompt(revision='v1'):
    # Packaged SSH modules receive the exact source text rather than local paths.
    if revision not in ('v1', 'v2'):
        raise ValueError('Only versioned synthetic instruction cards are supported')
    if revision == 'v1' and 'SKILL_TEXT' in globals():
        return SKILL_TEXT
    folder = 'grounded-trend-selection' if revision == 'v1' else 'grounded-trend-selection-v2'
    return (Path(__file__).parent / 'skills' / folder / 'SKILL.md').read_text().strip()


def prompts(profile='baseline-v1'):
    if profile not in PROFILES:
        raise ValueError('Unknown synthetic prompt experiment profile')
    return (None, skill_prompt()) if profile == 'baseline-v1' else (skill_prompt(), skill_prompt('v2'))


def fixture_cases(profile='baseline-v1'):
    if profile not in PROFILES:
        raise ValueError('Unknown synthetic prompt experiment profile')
    if profile == 'baseline-v1':
        from app_fixtures import cases
    else:
        from app_unseen_fixtures import cases
    return cases()


def instrument_proxy_source(proxy_source, experiment_source):
    prelude = 'import sys,types\nm=types.ModuleType("app_prompt_experiment")\n'
    prelude += 'sys.modules["app_prompt_experiment"]=m\nexec(' + repr(experiment_source) + ',m.__dict__)\n'
    return prelude + proxy_source


def sequence(all_cases):
    """Six usable paired fixtures, alternating order; sparse gates run once each."""
    output = []
    for index, case in enumerate(all_cases[:6]):
        for arm in (('A', 'B') if index % 2 == 0 else ('B', 'A')):
            output.append((case, 'prompt_arm_' + arm))
    output += [(case, 'prompt_arm_A') for case in all_cases[6:]]
    return output


def rewrite_body(body, arm, skill, control=None):
    """Change only the system message; A is byte-identical, B is explicitly labeled."""
    if arm not in ('A', 'B') or not skill:
        raise ValueError('Explicit synthetic prompt arm and skill required')
    payload = json.loads(body)
    messages = payload['messages']
    if len(messages) != 2 or [m['role'] for m in messages] != ['system', 'user']:
        raise ValueError('Unexpected synthetic messages')
    if hashlib.sha256(messages[0]['content'].encode()).hexdigest() != BASELINE_PROMPT_SHA256:
        raise ValueError('Unpinned original prompt')
    if arm == 'A' and control is None:
        return body
    if control is not None and not control:
        raise ValueError('Nonempty control instructions required')
    messages[0]['content'] = control if arm == 'A' else skill
    return json.dumps(payload, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()
