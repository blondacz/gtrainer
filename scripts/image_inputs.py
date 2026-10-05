#!/usr/bin/env python3
"""Avoid rebuilding/re-promoting software for deployment-only Git rollbacks."""
import json
import os
from pathlib import Path
import re
import subprocess


def affects_image(paths):
    roots = ('backend/', 'frontend/', 'gradle/', '.github/workflows/', 'scripts/')
    files = {'Dockerfile', '.dockerignore', 'gradlew', 'gradlew.bat', 'build.gradle.kts',
             'settings.gradle.kts', 'gradle.properties',
             'benchmarks/connected-review/cases-v2.json',
             'benchmarks/connected-review/validator-parity-v2.json'}
    return any(path in files or path.startswith(roots) for path in paths)


def main():
    event = os.environ['GITHUB_EVENT_NAME']
    changed = True
    if event == 'push':
        before = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text()).get('before', '')
        after = os.environ['GITHUB_SHA']
        if re.fullmatch(r'[a-f0-9]{40}', before) and before != '0' * 40:
            paths = subprocess.run(['git', 'diff', '--name-only', '-z', before, after],
                                   capture_output=True, check=True).stdout.decode().split('\0')
            changed = affects_image(paths)
    elif event == 'pull_request':
        changed = False  # PRs never publish anyway; all checks still run.
    # Explicit main workflow_dispatch remains the deliberate rebuild/retry path.
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'changed={str(changed).lower()}\n')
    print('Image inputs changed.' if changed else 'Deployment/docs-only change: no image publication or promotion.')


if __name__ == '__main__':
    main()
