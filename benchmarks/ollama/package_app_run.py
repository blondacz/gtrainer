#!/usr/bin/env python3
"""Package the synthetic operator runner for SSH stdin; makes no connection."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from app_fixtures import MODEL, MODEL_PROFILES, model_profile


def package(owner, candidate=MODEL, prompt_experiment=False, experiment_profile='baseline-v1'):
    if len(owner) != 32 or any(c not in '0123456789abcdef' for c in owner):
        raise ValueError('A unique lowercase UUID hex owner is required')
    model_profile(candidate)  # No arbitrary tag/URL or automatic candidate fallback.
    if experiment_profile not in ('baseline-v1', 'card-v2-unseen') or (not prompt_experiment and experiment_profile != 'baseline-v1'):
        raise ValueError('An explicit approved synthetic prompt experiment profile is required')
    directory = Path(__file__).parent
    sources = {name: (directory / (name + '.py')).read_text()
                for name in ('app_fixtures', 'app_capture_proxy', 'run_app_on_pi')}
    if prompt_experiment:
        if candidate != 'qwen3:4b-instruct':
            raise ValueError('Prompt experiment is approved only for Qwen3 Instruct')
        from app_prompt_experiment import prompts, instrument_proxy_source
        sources['app_prompt_experiment'] = (directory / 'app_prompt_experiment.py').read_text()
        control, sources['skill'] = prompts(experiment_profile)
        if control is not None:
            sources['control_skill'] = control
            sources['app_unseen_fixtures'] = (directory / 'app_unseen_fixtures.py').read_text()
        sources['app_capture_proxy'] = instrument_proxy_source(sources['app_capture_proxy'], sources['app_prompt_experiment'])
    hashes = {name: hashlib.sha256(value.encode()).hexdigest() for name, value in sources.items()}
    script = 'import json,sys,types\n'
    script += 'sources=' + repr(sources) + '\n'
    script += 'print(json.dumps({"phase":"operator_sources","synthetic_only":True,"source_sha256":' + repr(hashes) + '}),flush=True)\n'
    modules = ('app_fixtures', 'app_prompt_experiment', 'run_app_on_pi') if prompt_experiment else ('app_fixtures', 'run_app_on_pi')
    if experiment_profile == 'card-v2-unseen':
        modules = ('app_fixtures', 'app_unseen_fixtures', 'app_prompt_experiment', 'run_app_on_pi')
    script += 'for name in ' + repr(modules) + ':\n'
    script += ' module=types.ModuleType(name); module.__file__=name+".py"; sys.modules[name]=module\n'
    script += ' exec(compile(sources[name],module.__file__,"exec"),module.__dict__)\n'
    script += 'sys.argv=["run_app_on_pi.py","--owner",' + repr(owner) + ',"--candidate",' + repr(candidate) + ',"--approve-synthetic-run"]\n'
    if prompt_experiment:
        script += 'sys.argv.append("--prompt-experiment")\n'
        script += 'sys.argv.extend(["--prompt-experiment-profile",' + repr(experiment_profile) + '])\n'
    script += 'sys.modules["run_app_on_pi"].main(sources["app_capture_proxy"], sources.get("skill"), sources.get("control_skill"))\n'
    return script


def main():
    if os.environ.get('GITHUB_ACTIONS'):
        raise RuntimeError('CI must not package a home-cluster operator run')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner', required=True)
    parser.add_argument('--approve-synthetic-run', required=True, action='store_true')
    parser.add_argument('--candidate', choices=MODEL_PROFILES, default=MODEL)
    parser.add_argument('--prompt-experiment', action='store_true')
    parser.add_argument('--prompt-experiment-profile', choices=('baseline-v1', 'card-v2-unseen'), default='baseline-v1')
    arguments = parser.parse_args()
    print(package(arguments.owner, arguments.candidate, arguments.prompt_experiment, arguments.prompt_experiment_profile), end='')


if __name__ == '__main__':
    main()
