#!/usr/bin/env python3
"""Open a two-file promotion PR; never push main or connect to the Pi."""
import base64
import json
import os
import subprocess
import sys
import zipfile

from github_release import PREFIX, api, artifact_evidence, successful_publication
from release_evidence import (CHECKS, DEPLOYMENT_PATH, EVIDENCE_PATH, REPOSITORY,
                              ReleaseError, replace_image, require, validate_evidence)


def validate_protection(protection):
    require(protection.get('enforcement') == 'active' and protection.get('target') == 'branch'
            and protection.get('conditions', {}).get('ref_name') == {
                'include': ['refs/heads/main'], 'exclude': []},
            'Main ruleset must be active with no excluded main branch.')
    # GitHub redacts bypass_actors from non-admin API responses, including the
    # short-lived job token. The operator verifies [] during configuration and
    # rollout audit; CI must not receive an administration credential to read it.
    # Reject a bypass if GitHub does supply the field, but do not confuse an
    # omitted field with an inactive rule or claim that omission proves [].
    if 'bypass_actors' in protection:
        require(protection['bypass_actors'] == [], 'Main ruleset must not have bypass actors.')
    rules = {rule['type']: rule for rule in protection.get('rules', [])}
    status = rules.get('required_status_checks', {}).get('parameters', {})
    checks = {c['context']: c.get('integration_id') for c in status.get('required_status_checks', [])}
    require(status.get('strict_required_status_checks_policy') is True and CHECKS <= checks.keys()
            and all(checks[name] == 15368 for name in CHECKS),
            'Main must require up-to-date test/provenance checks from GitHub Actions.')
    require('pull_request' in rules,
            'Main must require pull requests, not direct promotion pushes.')
    require({'non_fast_forward', 'deletion'} <= rules.keys(),
            'Main must reject force pushes and deletion.')


def main():
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY
            and os.environ.get('GITHUB_REF') == 'refs/heads/main',
            'Promotion only runs from this repository main branch.')
    evidence = validate_evidence({'repository': REPOSITORY, 'source_sha': os.environ.get('GITHUB_SHA'),
                                 'run_id': int(os.environ['GITHUB_RUN_ID']), 'image': os.environ.get('IMAGE')})
    # Ruleset metadata is readable by the job token; no administration token
    # is provisioned to CI. The operator creates the no-bypass ruleset once.
    rulesets = api(f'{PREFIX}/rulesets?includes_parents=false')
    matches = [rule for rule in rulesets if rule['name'] == 'Protected main']
    require(len(matches) == 1, 'Unique Protected main ruleset is required.')
    validate_protection(api(f'{PREFIX}/rulesets/{matches[0]["id"]}'))
    current = api(f'{PREFIX}/git/ref/heads/main')['object']['sha']
    if current != evidence['source_sha']:
        print('Not promoting an obsolete source commit; main advanced during CI.')
        return
    successful_publication(evidence)
    artifact_evidence(evidence)
    file = api(f'{PREFIX}/contents/{DEPLOYMENT_PATH}?ref={current}')
    original = base64.b64decode(file['content']).decode()
    deployment = replace_image(original, evidence['image'])
    if original == deployment:
        print('Verified digest is already deployed in Git; no promotion PR needed.')
        return
    base = api(f'{PREFIX}/git/commits/{current}')
    tree = api(f'{PREFIX}/git/trees', 'POST', {'base_tree': base['tree']['sha'], 'tree': [
        {'path': DEPLOYMENT_PATH, 'mode': '100644', 'type': 'blob', 'content': deployment},
        {'path': EVIDENCE_PATH, 'mode': '100644', 'type': 'blob', 'content': json.dumps(evidence, indent=2) + '\n'},
    ]})
    commit = api(f'{PREFIX}/git/commits', 'POST', {
        'message': f'Promote tested ARM64 image {current[:12]}', 'tree': tree['sha'], 'parents': [current]})
    branch = f'promote/{current[:12]}-{evidence["run_id"]}'
    # One immutable candidate branch per run: no force-pushing or rewriting an
    # existing PR that somebody may be reviewing. A rerun can choose a new run.
    api(f'{PREFIX}/git/refs', 'POST', {'ref': f'refs/heads/{branch}', 'sha': commit['sha']})
    pr = api(f'{PREFIX}/pulls', 'POST', {
        'title': f'Promote tested ARM64 image {current[:12]}', 'head': branch, 'base': 'main',
        'body': f'Tests and immutable ARM64 image execution passed in run {evidence["run_id"]}.\n\n'
                f'Image: `{evidence["image"]}`\n\n'
                'Manual release approval required: review backup/restore compatibility before explicitly merging. '
                'This workflow does not enable auto-merge.\n\n'
                'Only the app image reference and public release evidence change. '
                'No cluster credentials or personal records are involved.'})
    # GITHUB_TOKEN PR events may wait for workflow approval. Explicit dispatch
    # always triggers and checks the actual candidate SHA without a PAT/App key.
    api(f'{PREFIX}/actions/workflows/build.yml/dispatches', 'POST', {
        'ref': branch, 'inputs': {'verify_failure_gate': False}})
    print('Opened protected promotion PR awaiting manual merge:', pr['html_url'])
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write(f'Promotion PR: {pr["html_url"]}\n')


if __name__ == '__main__':
    try:
        main()
    except ReleaseError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.TimeoutExpired):
        print('Image promotion failed; remote payloads/tokens withheld.', file=sys.stderr)
        sys.exit(1)
