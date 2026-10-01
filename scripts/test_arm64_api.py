"""Exercise the packaged-image verification contract with synthetic HTTP replies."""
import contextlib
import hashlib
import io
import json
import unittest
from unittest.mock import patch

import verify_arm64_api


class Response(io.BytesIO):
    def __init__(self, status, body, headers=None):
        super().__init__(json.dumps(body).encode())
        self.code = status
        self.headers = {'Cache-Control': 'no-store', **(headers or {})}


class Arm64ApiTest(unittest.TestCase):
    def fake_server(self, request, timeout):
        self.assertEqual(timeout, 30)
        path = request.full_url.removeprefix('http://127.0.0.1:18080')
        self.assertNotEqual(path, request.full_url)
        self.paths.append(path)
        method = request.get_method()
        cookie = request.get_header('Cookie')
        csrf = request.get_header('X-csrf-token')
        if path == '/api/login':
            return Response(200, {'csrfToken': 'synthetic-csrf'}, {'Set-Cookie': 'gtrainer_session=synthetic-cookie;'})
        if not cookie or self.logged_out:
            return Response(401, {'error': 'authentication_required'})
        if method != 'GET' and csrf != 'synthetic-csrf':
            return Response(403, {'error': 'csrf_rejected'})
        if path == '/api/imports':
            return Response(200, [{'recordCount': 0}, {'recordCount': 0}] if method == 'GET' else {})
        if path.startswith('/api/history?'):
            return Response(200, {'activities': [], 'wellness': []})
        if path.startswith('/api/trends?'):
            return Response(200, self.report, {'X-Evidence-Report-Sha256': self.digest})
        if path.startswith('/api/analysis-input?'):
            return Response(200, {'schemaVersion': 1, 'evidenceReportSha256': self.digest, 'facts': []})
        if path == '/api/models':
            if method == 'PUT':
                return Response(400, {})
            return Response(200, {'selectedModelId': None, 'models': [], 'hostedEnabled': self.hosted_enabled,
                                  'reason': 'local_not_configured'})
        if path == '/api/analysis':
            self.assertEqual(json.loads(request.data)['evidenceReportSha256'], self.digest)
            return Response(200, {'status': 'unavailable', 'reason': 'model_not_selected', 'observations': []})
        if path == '/api/logout':
            self.logged_out = True
            return Response(200, {})
        raise AssertionError('Unexpected synthetic verification request')

    def setUp(self):
        self.paths = []
        self.logged_out = self.hosted_enabled = False
        self.report = {'current': {'activityRecords': 0, 'wellnessRecords': 0, 'wellness': [{'value': None}]},
                       'previous': {'oldest': '2019-12-30'}, 'unavailable': [None, None, None]}
        self.digest = hashlib.sha256(json.dumps(self.report).encode()).hexdigest()

    def test_model_off_and_report_hash_checks_run_without_upstream_or_inference(self):
        with patch('urllib.request.urlopen', side_effect=self.fake_server), contextlib.redirect_stdout(io.StringIO()):
            verify_arm64_api.check()
        self.assertIn('/api/analysis', self.paths)
        self.assertNotIn('/api/chat', self.paths)
        self.assertTrue(self.logged_out)

    def test_unexpected_hosted_enablement_blocks_image_verification(self):
        self.hosted_enabled = True
        with patch('urllib.request.urlopen', side_effect=self.fake_server):
            with self.assertRaises(AssertionError):
                verify_arm64_api.check()
        self.assertTrue(self.logged_out)


if __name__ == '__main__':
    unittest.main()
