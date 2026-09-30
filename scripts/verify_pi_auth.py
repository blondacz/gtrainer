#!/usr/bin/env python3
"""Operator-only auth checks. No imported records are requested or displayed."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request

from provision_pi_secrets import private_file
from verify_pi_deployment import SSH, VerificationError, kubectl, require


ORIGIN = 'http://127.0.0.1:8080'
# A separate loopback port lets this check coexist with the user's UI tunnel.
BASE = 'http://127.0.0.1:18083'


def request(path, method='GET', body=None, cookie=None, csrf=None, origin=None):
    headers = {}
    if cookie:
        headers['Cookie'] = cookie
    if csrf:
        headers['X-CSRF-Token'] = csrf
    if origin:
        headers['Origin'] = origin
    if body is not None:
        body = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = response.read(4096)
        return response.code, response.headers, data


def verify():
    require(not os.environ.get('GITHUB_ACTIONS'), 'CI must never perform private cluster authentication.')
    password = private_file(Path.home() / '.config/gtrainer/dashboard-password')
    key = private_file(Path.home() / '.intervals/api_key')
    require(request('/api/session')[0] == 401, 'Unauthenticated session lookup was not rejected.')
    require(request('/api/trends')[0] == 401, 'Unauthenticated private API was not rejected.')
    require(request('/api/login', 'POST', {'password': password}, origin='https://attacker.example')[0] == 403,
            'Cross-origin login was not rejected.')
    require(request('/api/login', 'POST', {'password': 'synthetic-incorrect-password'}, origin=ORIGIN)[0] == 401,
            'Incorrect password was not rejected.')
    code, headers, body = request('/api/login', 'POST', {'password': password}, origin=ORIGIN)
    require(code == 200, 'Configured login failed; response withheld.')
    require(headers.get('Cache-Control') == 'no-store', 'Login must disable caching.')
    cookie = headers.get('Set-Cookie', '')
    require('httponly' in cookie.lower() and 'samesite=strict' in cookie.lower(),
            'Session cookie privacy flags are missing.')
    cookie = cookie.split(';')[0]
    session = json.loads(body)
    require(session.get('authenticated') is True and session.get('intervalsConfigured') is True,
            'Authenticated source-configuration status is incorrect.')
    csrf = session['csrfToken']
    try:
        require(request('/api/session', cookie=cookie)[0] == 200, 'Authorized session was not recognized.')
        require(request('/api/logout', 'POST', cookie=cookie, origin=ORIGIN)[0] == 403,
                'Missing CSRF token was not rejected.')
        require(request('/api/logout', 'POST', cookie=cookie, csrf=csrf, origin='https://attacker.example')[0] == 403,
                'Cross-origin logout was not rejected.')
    finally:
        require(request('/api/logout', 'POST', cookie=cookie, csrf=csrf, origin=ORIGIN)[0] == 200,
                'Session logout failed.')
    require(request('/api/session', cookie=cookie)[0] == 401, 'Logged-out session remains authorized.')

    pods = kubectl('-n', 'gtrainer', 'get', 'pods', '-o', 'json')['items']
    require(len(pods) == 1, 'Expected exactly one app pod.')
    pod = pods[0]
    result = subprocess.run(SSH + [f'sudo -n k3s kubectl -n gtrainer logs {pod["metadata"]["name"]} --tail=1000'],
                            capture_output=True, timeout=30)
    require(result.returncode == 0, 'App log inspection failed; logs withheld.')
    for sensitive in (password, key, csrf, cookie.split('=', 1)[-1]):
        require(sensitive.encode() not in result.stdout, 'Credential/session material was found in logs; values withheld.')
    require(pod['spec'].get('automountServiceAccountToken') is False, 'App received a cluster token.')
    volumes = [v for v in pod['spec']['volumes'] if 'secret' in v]
    require(len(volumes) == 1 and volumes[0]['secret']['secretName'] == 'gtrainer-runtime',
            'Expected externally provisioned runtime secret.')
    print('Private login/session/logout pass; anonymous, wrong-password, Origin, and CSRF requests are denied.')
    print('Source credential is configured; inspected app logs contain no raw credential or session values.')
    print('No source import, health record request, or model call was made.')


def main():
    require(not os.environ.get('GITHUB_ACTIONS'), 'CI must not connect to the home cluster.')
    process = subprocess.Popen(SSH[:-1] + ['-tt', '-o', 'ExitOnForwardFailure=yes',
        '-L', '127.0.0.1:18083:127.0.0.1:18083', SSH[-1],
        'timeout 90 sudo -n k3s kubectl -n gtrainer port-forward --address=127.0.0.1 service/gtrainer 18083:8080'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(20):
            try:
                if request('/healthz')[0] == 200:
                    break
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(.5)
        else:
            raise VerificationError('Private verification tunnel did not become ready.')
        verify()
    finally:
        process.terminate()
        process.wait(timeout=10)


if __name__ == '__main__':
    try:
        main()
    except (VerificationError, ValueError) as error:
        # Errors may derive from JSON/network/private files: never echo payloads.
        print('Private authentication verification failed; all response/credential details withheld.', file=sys.stderr)
        sys.exit(1)
    except (OSError, KeyError, TypeError, subprocess.TimeoutExpired):
        print('Private authentication verification could not complete; details withheld.', file=sys.stderr)
        sys.exit(1)
