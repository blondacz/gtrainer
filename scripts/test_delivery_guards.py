import io
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from check_package_visibility import check
from remove_bootstrap_package import APPROVED_TAG, CREATED_AT, verify_identity


class DeliveryGuards(unittest.TestCase):
    def test_only_private_package_is_accepted(self):
        for visibility in ('private', 'public', 'internal', None):
            with self.subTest(visibility=visibility):
                response = io.BytesIO(json.dumps({'visibility': visibility}).encode())
                with patch.dict(os.environ, {'GH_TOKEN': 'synthetic-ci-token'}), \
                     patch('check_package_visibility.urlopen', return_value=response):
                    if visibility == 'private':
                        self.assertEqual(check(), 'private')
                    else:
                        with self.assertRaises(ValueError):
                            check()

    def test_absent_package_only_allowed_before_first_publication(self):
        for allowed in (True, False):
            error = HTTPError('https://api.github.com/', 404, 'not found', {'X-OAuth-Scopes': 'read:packages'}, None)
            with patch.dict(os.environ, {'GH_TOKEN': 'synthetic-ci-token'}), \
                 patch('check_package_visibility.urlopen', side_effect=error):
                if allowed:
                    self.assertIn('absent', check(allow_missing=True))
                else:
                    with self.assertRaises(ValueError):
                        check()

    def test_auth_failure_does_not_count_as_absent_package(self):
        error = HTTPError('https://api.github.com/', 403, 'private body', {}, None)
        with patch.dict(os.environ, {'GH_TOKEN': 'synthetic-ci-token'}), \
             patch('check_package_visibility.urlopen', side_effect=error):
            with self.assertRaises(ValueError):
                check(allow_missing=True)

    def test_hidden_unauthorized_package_is_not_assumed_absent(self):
        error = HTTPError('https://api.github.com/', 404, 'not found', {}, None)
        with patch.dict(os.environ, {'GH_TOKEN': 'synthetic-ci-token'}), \
             patch('check_package_visibility.urlopen', side_effect=error):
            with self.assertRaises(ValueError):
                check(allow_missing=True)

    def test_metadata_uses_read_only_secret_not_publishing_token(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text()
        self.assertEqual(workflow.count('GH_TOKEN: ${{ secrets.GHCR_READ_TOKEN }}'), 2)
        self.assertNotIn('GH_TOKEN: ${{ github.token }}', workflow)

    def test_bootstrap_deletion_refuses_changed_or_new_package(self):
        package = {'name': 'gtrainer', 'package_type': 'container', 'visibility': 'public',
                   'created_at': CREATED_AT, 'repository': {'full_name': 'blondacz/gtrainer'},
                   'version_count': 3}
        versions = [{'created_at': CREATED_AT, 'metadata': {'container': {'tags': []}}}
                    for _ in range(3)]
        versions[0]['metadata']['container']['tags'] = [APPROVED_TAG]
        verify_identity(package, versions)
        for field, value in [('created_at', '2026-10-01T00:00:00Z'), ('visibility', 'private'),
                             ('version_count', 4), ('name', 'another-project')]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_identity({**package, field: value}, versions)
        versions[1]['metadata']['container']['tags'] = ['unrelated-user-work']
        with self.assertRaises(ValueError):
            verify_identity(package, versions)

    def test_publish_has_required_test_job_and_no_failure_override(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text()
        self.assertIn('needs: checks', workflow)
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertIn("github.event_name != 'pull_request'", workflow)
        self.assertNotIn('always()', workflow)
        self.assertNotIn('continue-on-error', workflow)
        self.assertNotIn('pull_request_target', workflow)
        self.assertNotIn('kubeconfig', workflow.lower())
        self.assertIn('inputs.verify_failure_gate', workflow)
        self.assertIn('PublicationGateVerificationTest.kt', workflow)


if __name__ == '__main__':
    unittest.main()
