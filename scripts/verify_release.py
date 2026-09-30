#!/usr/bin/env python3
"""Reject unverified deployment digests before protected-main merge."""
import base64
import json
from pathlib import Path
import subprocess
import sys
import zipfile

from github_release import PREFIX, api, artifact_evidence, successful_publication
from release_evidence import (BOOTSTRAP_IMAGE, DEPLOYMENT_PATH, EVIDENCE_PATH,
                              ReleaseError, image_from_deployment, require, validate_evidence)


def main():
    image = image_from_deployment(Path(DEPLOYMENT_PATH).read_text())
    path = Path(EVIDENCE_PATH)
    if not path.exists():
        require(image == BOOTSTRAP_IMAGE, 'Non-bootstrap image has no verified release evidence.')
        print('Original manually verified bootstrap digest is unchanged.')
        return
    evidence = validate_evidence(json.loads(path.read_text()))
    require(evidence['image'] == image, 'Deployment digest differs from release evidence.')
    # An unchanged record already merged through protected main remains trusted
    # even after the build artifact's 90-day retention expires. Changed/initial
    # candidates must still fetch and match the original immutable CI artifact.
    main_sha = api(f'{PREFIX}/git/ref/heads/main')['object']['sha']
    tree = api(f'{PREFIX}/git/trees/{main_sha}?recursive=1')
    require(not tree.get('truncated'), 'Main tree inspection was truncated.')
    if any(item['path'] == EVIDENCE_PATH for item in tree['tree']):
        record = api(f'{PREFIX}/contents/{EVIDENCE_PATH}?ref={main_sha}')
        trusted = validate_evidence(json.loads(base64.b64decode(record['content'])))
        if trusted == evidence:
            require(api(f'{PREFIX}/branches/main')['protected'],
                    'Previously accepted release records require protected main.')
            print('Unchanged release evidence is already accepted on protected main.')
            return
    successful_publication(evidence)
    artifact_evidence(evidence)
    print('Deployment digest matches trusted main CI tests, ARM64 execution, and recorded artifact.')


if __name__ == '__main__':
    try:
        main()
    except ReleaseError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.TimeoutExpired):
        print('Release provenance verification failed; remote payloads/tokens withheld.', file=sys.stderr)
        sys.exit(1)
