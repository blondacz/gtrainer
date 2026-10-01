#!/usr/bin/env python3
"""Published-image checks with synthetic credentials; never import upstream."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import urllib.request
import urllib.error


PASSWORD = 'synthetic-container-password-not-a-real-credential'
ORIGIN = 'http://127.0.0.1:8080'


def prepare(directory):
    salt = bytes(range(16))
    encode = lambda value: base64.urlsafe_b64encode(value).decode().rstrip('=')
    key = hashlib.pbkdf2_hmac('sha256', PASSWORD.encode(), salt, 600_000, dklen=32)
    files = {'password-verifier': f'pbkdf2-sha256$600000${encode(salt)}${encode(key)}',
             'intervals-api-key': 'synthetic-container-source-key-never-used-upstream'}
    # Readable by the non-root container UID; these are only public synthetic
    # test fixtures, not actual secrets, and never enter the build context.
    directory.chmod(0o755)
    for name, value in files.items():
        target = directory / name
        target.write_text(value)
        target.chmod(0o444)


def check():
    def request(path, method='GET', body=None, cookie=None, csrf=None):
        headers = {'Origin': ORIGIN}
        if cookie:
            headers['Cookie'] = cookie
        if csrf:
            headers['X-CSRF-Token'] = csrf
        if body is not None:
            headers['Content-Type'] = 'application/json'
            body = json.dumps(body).encode()
        try:
            response = urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18080' + path,
                data=body, headers=headers, method=method), timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read(65537)
            assert len(payload) <= 65536
            return response.code, response.headers, payload
    assert request('/api/imports')[0] == 401
    status, headers, body = request('/api/login', 'POST', {'password': PASSWORD})
    assert status == 200
    cookie = headers['Set-Cookie'].split(';')[0]
    csrf = json.loads(body)['csrfToken']
    try:
        status, headers, body = request('/api/imports', cookie=cookie)
        assert status == 200 and headers['Cache-Control'] == 'no-store'
        categories = json.loads(body)
        assert len(categories) == 2 and all(item['recordCount'] == 0 for item in categories)
        status, _, body = request('/api/history?oldest=2020-01-01&newest=2020-12-31', cookie=cookie)
        assert status == 200 and json.loads(body) == {'activities': [], 'wellness': []}
        status, headers, body = request('/api/trends?oldest=2020-01-01&newest=2020-01-02', cookie=cookie)
        report = json.loads(body)
        report_digest = hashlib.sha256(body).hexdigest()
        assert status == 200 and headers['Cache-Control'] == 'no-store'
        assert report['current']['activityRecords'] == 0 and report['current']['wellnessRecords'] == 0
        assert all(metric['value'] is None for metric in report['current']['wellness'])
        assert report['previous']['oldest'] == '2019-12-30' and len(report['unavailable']) == 3
        status, _, body = request('/api/analysis-input?oldest=2020-01-01&newest=2020-01-02', cookie=cookie)
        summary = json.loads(body)
        assert status == 200 and summary['schemaVersion'] == 1
        assert summary['evidenceReportSha256'] == report_digest and all(fact['value'] is None for fact in summary['facts'])
        # No real API request: missing CSRF must reject before source retrieval.
        assert request('/api/sync', 'POST', {'oldest': '2020-01-01', 'newest': '2020-02-01'}, cookie)[0] == 403
        assert request('/api/imports', 'DELETE', {'confirmation': 'remove-local-imports'}, cookie, csrf)[0] == 200
    finally:
        assert request('/api/logout', 'POST', cookie=cookie, csrf=csrf)[0] == 200
    assert request('/api/imports', cookie=cookie)[0] == 401
    print('Immutable ARM64 image passes native SQLite migration/read/delete, private auth, and empty trend/summary checks; no upstream/model read.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'check'))
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    try:
        if args.action == 'prepare':
            assert args.directory is not None
            prepare(args.directory)
        else:
            check()
    except Exception:
        raise SystemExit('Published-image API/SQLite verification failed; payloads/tokens withheld.')
