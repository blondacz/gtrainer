import unittest
from image_inputs import affects_image


class ImageInputs(unittest.TestCase):
    def test_runtime_and_build_changes_republish(self):
        for path in ['backend/src/main/kotlin/App.kt', 'frontend/src/App.tsx', 'gradle/wrapper/gradle-wrapper.jar',
                      'Dockerfile', '.dockerignore', 'build.gradle.kts', 'scripts/check.sh', '.github/workflows/build.yml']:
            with self.subTest(path=path):
                self.assertTrue(affects_image([path]))

    def test_only_bundled_connected_review_fixtures_are_image_inputs(self):
        for path in ('benchmarks/connected-review/cases-v2.json',
                     'benchmarks/connected-review/validator-parity-v2.json'):
            with self.subTest(path=path):
                self.assertTrue(affects_image([path]))
        self.assertFalse(affects_image(['benchmarks/connected-review/evaluation-v3.json',
                                       'benchmarks/connected-review/run_connected_review_on_pi.py']))

    def test_git_rollback_and_documentation_do_not_repromote_current_software(self):
        self.assertFalse(affects_image(['deploy/gtrainer/deployment.yaml', 'deploy/gtrainer/release.json',
                                      'docs/infrastructure/private-access.md', 'openspec/changes/change/tasks.md']))


if __name__ == '__main__':
    unittest.main()
