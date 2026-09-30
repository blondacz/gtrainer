#!/usr/bin/env python3
"""Small public release descriptor, never a health-data/credential artifact."""
import json
import os
from pathlib import Path
import re
import sys


REPOSITORY = 'blondacz/gtrainer'
BOOTSTRAP_IMAGE = 'ghcr.io/blondacz/gtrainer@sha256:ccb2c1af5a44c37b15a06656e081012f108fa0e89db81dcbad2b5bbf0c768d96'
DEPLOYMENT_PATH = 'deploy/gtrainer/deployment.yaml'
EVIDENCE_PATH = 'deploy/gtrainer/release.json'
CHECKS = {'Tests and build', 'Verify release provenance'}


class ReleaseError(ValueError):
    """Only fixed safe messages; callers must not print remote payloads."""


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def validate_evidence(evidence):
    require(isinstance(evidence, dict) and set(evidence) == {'repository', 'source_sha', 'run_id', 'image'},
            'Unexpected release evidence shape.')
    require(evidence['repository'] == REPOSITORY, 'Unexpected source repository.')
    require(isinstance(evidence['source_sha'], str)
            and re.fullmatch(r'[a-f0-9]{40}', evidence['source_sha']), 'Invalid source commit.')
    require(type(evidence['run_id']) is int and evidence['run_id'] > 0, 'Invalid build run ID.')
    require(isinstance(evidence['image'], str) and re.fullmatch(
        r'ghcr\.io/blondacz/gtrainer@sha256:[a-f0-9]{64}', evidence['image']),
        'Release image must be an immutable GTrainer digest.')
    return evidence


def image_from_deployment(text):
    images = re.findall(r'^\s*image: (.+)$', text, re.MULTILINE)
    require(len(images) == 1, 'Expected exactly one app image.')
    return images[0]


def replace_image(text, image):
    image_from_deployment(text)
    return re.sub(r'^(\s*image: ).+$', lambda match: match[1] + image, text, flags=re.MULTILINE)


def main():
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'Unexpected CI repository.')
    evidence = validate_evidence({'repository': REPOSITORY,
                                 'source_sha': os.environ.get('GITHUB_SHA'),
                                 'run_id': int(os.environ['GITHUB_RUN_ID']),
                                 'image': os.environ.get('IMAGE')})
    require(len(sys.argv) == 2, 'An explicit artifact output path is required.')
    Path(sys.argv[1]).write_text(json.dumps(evidence, indent=2) + '\n')
    print('Recorded public build identity and the verified immutable image only.')


if __name__ == '__main__':
    try:
        main()
    except ReleaseError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (KeyError, ValueError, OSError):
        print('Release evidence could not be recorded; details withheld.', file=sys.stderr)
        sys.exit(1)
