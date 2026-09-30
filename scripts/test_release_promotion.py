import copy
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import zipfile

from github_release import artifact_evidence, successful_publication
from promote_image import validate_protection
from release_evidence import ReleaseError, image_from_deployment, replace_image, validate_evidence
from verify_release import main as verify_release


ROOT = Path(__file__).resolve().parents[1]


def sample():
    return {'repository': 'blondacz/gtrainer', 'source_sha': 'a' * 40, 'run_id': 123,
            'image': 'ghcr.io/blondacz/gtrainer@sha256:' + 'b' * 64}


class ReleasePromotion(unittest.TestCase):
    def test_only_exact_public_identity_fields_and_digest_are_allowed(self):
        self.assertEqual(validate_evidence(sample()), sample())
        for field, value in [('image', 'ghcr.io/blondacz/gtrainer:latest'),
                             ('source_sha', '../main'), ('run_id', True),
                             ('repository', 'untrusted/fork')]:
            with self.subTest(field=field), self.assertRaises(ReleaseError):
                validate_evidence({**sample(), field: value})
        with self.assertRaises(ReleaseError):
            validate_evidence({**sample(), 'health_records': 'synthetic-forbidden-field'})

    def test_only_image_line_changes(self):
        deployment = (ROOT / 'deploy/gtrainer/deployment.yaml').read_text()
        updated = replace_image(deployment, sample()['image'])
        self.assertEqual(image_from_deployment(updated), sample()['image'])
        self.assertEqual(updated.replace(sample()['image'], image_from_deployment(deployment)), deployment)
        with self.assertRaises(ReleaseError):
            replace_image(deployment + '\nimage: unexpected-sidecar\n', sample()['image'])

    def test_protection_rejects_bypass_missing_checks_or_unprotected_main(self):
        rules = json.loads((ROOT / '.github/promotion-ruleset.json').read_text())
        validate_protection(rules)
        for field, value in [('enforcement', 'disabled'), ('bypass_actors', [{'actor_id': 1}]),
                             ('conditions', {'ref_name': {'include': ['refs/heads/other'], 'exclude': []}})]:
            with self.subTest(field=field), self.assertRaises(ReleaseError):
                validate_protection({**rules, field: value})
        for rule in rules['rules']:
            with self.subTest(rule=rule['type']):
                if rule['type'] != 'required_linear_history':
                    with self.assertRaises(ReleaseError):
                        validate_protection({**rules, 'rules': [r for r in rules['rules'] if r != rule]})
        changed = copy.deepcopy(rules)
        status = next(r for r in changed['rules'] if r['type'] == 'required_status_checks')['parameters']
        status['required_status_checks'][0]['integration_id'] = 999
        with self.assertRaises(ReleaseError):
            validate_protection(changed)

    def test_failed_skipped_or_wrong_source_build_cannot_promote(self):
        run = {'repository': {'full_name': 'blondacz/gtrainer'},
               'head_repository': {'full_name': 'blondacz/gtrainer'},
               'head_branch': 'main', 'head_sha': 'a' * 40,
               'path': '.github/workflows/build.yml', 'event': 'push'}
        jobs = {'jobs': [{'name': name, 'conclusion': 'success'} for name in
                        ('Tests and build', 'Verify release provenance', 'Publish and verify ARM64 image')]}
        with patch('github_release.api', side_effect=[run, jobs]):
            successful_publication(sample())
        for index in range(3):
            for conclusion in ('failure', 'skipped', 'cancelled', None):
                failed = copy.deepcopy(jobs)
                failed['jobs'][index]['conclusion'] = conclusion
                with self.subTest(job=index, conclusion=conclusion), \
                     patch('github_release.api', side_effect=[run, failed]), self.assertRaises(ReleaseError):
                    successful_publication(sample())
        for field, value in [('head_branch', 'untrusted-branch'), ('head_sha', 'c' * 40),
                             ('event', 'pull_request'), ('path', '.github/workflows/other.yml')]:
            with self.subTest(field=field), patch('github_release.api', return_value={**run, field: value}), \
                 self.assertRaises(ReleaseError):
                successful_publication(sample())

    def test_artifact_must_match_exact_recorded_publication(self):
        artifacts = {'artifacts': [{'id': 1, 'name': 'verified-arm64-release', 'expired': False,
                                   'size_in_bytes': 512}]}
        def archive(evidence):
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as z:
                z.writestr('verified-image.json', json.dumps(evidence))
            return data.getvalue()
        with patch('github_release.api', side_effect=[artifacts, archive(sample())]):
            artifact_evidence(sample())
        changed = {**sample(), 'image': 'ghcr.io/blondacz/gtrainer@sha256:' + 'c' * 64}
        with patch('github_release.api', side_effect=[artifacts, archive(changed)]), self.assertRaises(ReleaseError):
            artifact_evidence(sample())
        artifacts['artifacts'][0]['expired'] = True
        with patch('github_release.api', return_value=artifacts), self.assertRaises(ReleaseError):
            artifact_evidence(sample())

    def test_workflow_promotion_has_all_successful_prerequisites(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertIn('needs: [checks, provenance, publish]', workflow)
        self.assertIn('environment: image-promotion', workflow)
        self.assertIn('persist-credentials: false', workflow)
        self.assertNotIn('secrets.', workflow)
        self.assertNotIn('pull_request_target', workflow)
        self.assertNotIn('continue-on-error', workflow)
        self.assertNotIn('always()', workflow)
        promote = (ROOT / 'scripts/promote_image.py').read_text()
        self.assertNotIn('git push', promote)
        self.assertNotIn('ssh', promote)
        self.assertNotIn('kubectl', promote)
        self.assertIn('actions/workflows/build.yml/dispatches', promote)

    def test_accepted_unchanged_main_record_survives_artifact_retention(self):
        import base64
        evidence = sample()
        main = {'object': {'sha': 'c' * 40}}
        tree = {'tree': [{'path': 'deploy/gtrainer/release.json'}]}
        record = {'content': base64.b64encode(json.dumps(evidence).encode()).decode()}
        with patch('verify_release.Path.exists', return_value=True), \
             patch('verify_release.Path.read_text', side_effect=[f"image: {evidence['image']}\n", json.dumps(evidence)]), \
             patch('verify_release.api', side_effect=[main, tree, record, {'protected': True}]), \
             patch('verify_release.artifact_evidence') as artifact, \
             patch('verify_release.successful_publication') as publication:
            verify_release()
            artifact.assert_not_called()
            publication.assert_not_called()

    def test_changed_or_initial_candidate_still_requires_artifact(self):
        evidence = sample()
        with patch('verify_release.Path.exists', return_value=True), \
             patch('verify_release.Path.read_text', side_effect=[f"image: {evidence['image']}\n", json.dumps(evidence)]), \
             patch('verify_release.api', side_effect=[{'object': {'sha': 'c' * 40}}, {'tree': []}]), \
             patch('verify_release.successful_publication') as publication, \
             patch('verify_release.artifact_evidence', side_effect=ReleaseError('Synthetic missing artifact')) as artifact, \
             self.assertRaises(ReleaseError):
            verify_release()
        publication.assert_called_once_with(evidence)
        artifact.assert_called_once_with(evidence)


if __name__ == '__main__':
    unittest.main()
