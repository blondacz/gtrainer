from pathlib import Path
import unittest


class DeliveryGuards(unittest.TestCase):
    def test_public_code_package_needs_no_personal_registry_token(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text()
        self.assertNotIn('secrets.', workflow)
        self.assertIn('password: ${{ github.token }}', workflow)
        self.assertNotIn('check_package_visibility', workflow)

    def test_temporary_deletion_workflow_is_removed(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / '.github/workflows/recreate-bootstrap-package.yml').exists())
        self.assertFalse((root / 'scripts/remove_bootstrap_package.py').exists())

    def test_build_context_is_allowlisted_and_excludes_runtime_data(self):
        rules = (Path(__file__).resolve().parents[1] / '.dockerignore').read_text().splitlines()
        self.assertEqual(next(line for line in rules if line and not line.startswith('#')), '**')
        for rule in ('**/.env*', '**/*.key', '**/*.pem', '**/*.db', '**/*.sqlite', '**/*.age'):
            self.assertIn(rule, rules)
        for directory in ('docs', 'openspec', '.opencode', '.git', 'backups'):
            self.assertFalse(any(line.startswith('!' + directory + '/') for line in rules))
        self.assertEqual({line for line in rules if line.startswith('!benchmarks/')}, {
            '!benchmarks/', '!benchmarks/connected-review/',
            '!benchmarks/connected-review/cases-v2.json',
            '!benchmarks/connected-review/validator-parity-v2.json',
        })

    def test_container_copies_only_frozen_synthetic_review_fixtures(self):
        root = Path(__file__).resolve().parents[1]
        dockerfile = (root / 'Dockerfile').read_text()
        copies = [line for line in dockerfile.splitlines() if line.startswith('COPY benchmarks/')]
        self.assertEqual(copies, [
            'COPY benchmarks/connected-review/cases-v2.json '
            'benchmarks/connected-review/validator-parity-v2.json benchmarks/connected-review/'
        ])

    def test_published_image_verification_uses_immutable_arm64_reference(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text()
        self.assertIn('platforms: linux/arm64', workflow)
        self.assertIn('IMAGE: ghcr.io/blondacz/gtrainer@${{ steps.image.outputs.digest }}', workflow)
        self.assertIn('run: bash scripts/verify_arm64_image.sh', workflow)

    def test_publish_has_required_test_job_and_no_failure_override(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text()
        self.assertIn('needs: [checks, provenance]', workflow)
        self.assertIn('needs: [checks, provenance, publish]', workflow)
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
