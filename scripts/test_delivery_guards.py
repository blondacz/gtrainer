import io
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from check_package_visibility import check


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
            error = HTTPError('https://api.github.com/', 404, 'not found', {}, None)
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

    def test_publish_has_required_test_job_and_no_failure_override(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text()
        self.assertIn('needs: checks', workflow)
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertIn("github.event_name != 'pull_request'", workflow)
        self.assertNotIn('always()', workflow)
        self.assertNotIn('continue-on-error', workflow)
        self.assertNotIn('pull_request_target', workflow)
        self.assertNotIn('kubeconfig', workflow.lower())


if __name__ == '__main__':
    unittest.main()
