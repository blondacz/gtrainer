#!/usr/bin/env python3
"""Operator-only stdin provisioning; no raw secrets in Git, argv, or output."""
import argparse
import base64
import getpass
import hashlib
import json
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import sys

from verify_pi_deployment import SSH


class ProvisionError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ProvisionError(message)


def private_file(path):
    require(not path.is_symlink() and path.is_file(), 'Expected an existing private regular file.')
    require(path.stat().st_mode & 0o077 == 0, 'Credential file must not be group/world accessible.')
    require(path.stat().st_size in range(1, 1025), 'Credential file exceeds its size bound.')
    return path.read_text().strip()


def encoded_verifier(password, salt=None):
    require(12 <= len(password) <= 256, 'Use a dashboard password between 12 and 256 characters.')
    salt = secrets.token_bytes(16) if salt is None else salt
    require(len(salt) == 16, 'Password salt must be 16 bytes.')
    key = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt, 600_000, dklen=32)
    encode = lambda value: base64.urlsafe_b64encode(value).decode().rstrip('=')
    return f'pbkdf2-sha256$600000${encode(salt)}${encode(key)}'


def secret_document(verifier, api_key):
    require(0 < len(api_key) <= 256 and all(33 <= ord(c) <= 126 for c in api_key),
            'Source key must be a nonempty bounded single-line credential.')
    encode = lambda value: base64.b64encode(value.encode()).decode()
    return {'apiVersion': 'v1', 'kind': 'Secret',
            'metadata': {'name': 'gtrainer-runtime', 'namespace': 'gtrainer'},
            'type': 'Opaque', 'data': {
                'password-verifier': encode(verifier), 'intervals-api-key': encode(api_key)}}


def provision(document, rotate=False):
    require(not os.environ.get('GITHUB_ACTIONS'), 'CI must never provision home-cluster secrets.')
    # Only existence is queried. Do not retrieve the secret or its values.
    check = subprocess.run(SSH + [shlex.join(['sudo', '-n', 'k3s', 'kubectl', '-n', 'gtrainer',
        'get', 'secret', 'gtrainer-runtime', '--ignore-not-found', '-o', 'name'])],
        capture_output=True, timeout=30)
    require(check.returncode == 0, 'Remote secret existence check failed; details withheld.')
    require(rotate or not check.stdout.strip(), 'Runtime secret exists; explicit --rotate is required.')
    # Server-side apply avoids embedding a base64 secret copy in a last-applied annotation.
    command = ['sudo', '-n', 'k3s', 'kubectl', 'apply', '--server-side',
               '--field-manager=gtrainer-operator', '-f', '-']
    result = subprocess.run(SSH + [shlex.join(command)], input=json.dumps(document).encode(),
                            capture_output=True, timeout=45)
    require(result.returncode == 0, 'Remote secret provisioning failed; diagnostics withheld.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--generate', action='store_true', help='Create a private Mac password file, without displaying it')
    parser.add_argument('--password-file', type=Path, default=Path.home() / '.config/gtrainer/dashboard-password')
    parser.add_argument('--intervals-key-file', type=Path, default=Path.home() / '.intervals/api_key')
    parser.add_argument('--rotate', action='store_true', help='Explicitly replace the existing Pi runtime secret')
    args = parser.parse_args()
    require(not os.environ.get('GITHUB_ACTIONS'), 'This command is for the operator Mac, not CI.')
    workspace = Path(__file__).resolve().parents[1]
    require(not args.password_file.resolve().is_relative_to(workspace)
            and not args.intervals_key_file.resolve().is_relative_to(workspace),
            'Runtime credential files must stay outside the repository.')
    key = private_file(args.intervals_key_file)
    if args.generate:
        require(not args.password_file.exists(), 'Password file exists; refusing to overwrite it.')
        args.password_file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        require(args.password_file.parent.stat().st_mode & 0o077 == 0,
                'Password directory must not be group/world accessible.')
        password = secrets.token_urlsafe(32)
        fd = os.open(args.password_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as target:
            target.write(password + '\n')
    elif args.password_file.exists():
        password = private_file(args.password_file)
    else:
        password = getpass.getpass('Dashboard password (not displayed): ')
        confirmation = getpass.getpass('Repeat dashboard password: ')
        require(password == confirmation, 'Passwords did not match.')
    provision(secret_document(encoded_verifier(password), key), args.rotate)
    print('Runtime verifier and source key provisioned via authenticated SSH/stdin; values withheld.')
    if args.generate:
        print('Generated dashboard password is stored only in:', args.password_file)
    print('After rotation, restart the app pod to invalidate sessions/load the new verifier.')


if __name__ == '__main__':
    try:
        main()
    except ProvisionError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except (OSError, ValueError, subprocess.TimeoutExpired, EOFError, KeyboardInterrupt):
        print('Secret provisioning did not complete; all credential details withheld.', file=sys.stderr)
        sys.exit(1)
