import base64
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from provision_pi_secrets import ProvisionError, encoded_verifier, private_file, provision, secret_document


class SecretProvisioning(unittest.TestCase):
    def test_verifier_matches_jvm_format_and_is_not_plaintext(self):
        password = 'synthetic-password-not-a-real-credential'
        verifier = encoded_verifier(password, bytes(range(16)))
        fields = verifier.split('$')
        self.assertEqual(fields[:2], ['pbkdf2-sha256', '600000'])
        self.assertEqual(len(base64.urlsafe_b64decode(fields[2] + '==')), 16)
        self.assertEqual(len(base64.urlsafe_b64decode(fields[3] + '=')), 32)
        self.assertNotIn(password, verifier)
        with self.assertRaises(ProvisionError):
            encoded_verifier('too-short')

    def test_private_files_reject_loose_permissions_and_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic-credential'
            path.write_text('synthetic-key')
            path.chmod(0o644)
            with self.assertRaises(ProvisionError):
                private_file(path)
            path.chmod(0o600)
            self.assertEqual(private_file(path), 'synthetic-key')
            link = Path(directory) / 'link'
            link.symlink_to(path)
            with self.assertRaises(ProvisionError):
                private_file(link)

    def test_secret_is_only_stdin_not_argv_or_output(self):
        document = secret_document('synthetic-verifier', 'synthetic-source-key')
        output = io.StringIO()
        responses = [subprocess.CompletedProcess([], 0, b'', b''), subprocess.CompletedProcess([], 0, b'created', b'')]
        with patch.dict('os.environ', {}, clear=True), patch('provision_pi_secrets.subprocess.run', side_effect=responses) as run, \
             contextlib.redirect_stdout(output):
            provision(document)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertNotIn('synthetic-source-key', str(commands))
        self.assertNotIn('synthetic-verifier', str(commands))
        self.assertEqual(json.loads(run.call_args_list[1].kwargs['input']), document)
        self.assertIn('--server-side', commands[1][-1])
        self.assertEqual(output.getvalue(), '')

    def test_ci_refused_and_existing_secret_requires_explicit_rotation(self):
        document = secret_document('synthetic-verifier', 'synthetic-key')
        with patch.dict('os.environ', {'GITHUB_ACTIONS': 'true'}), patch('provision_pi_secrets.subprocess.run') as run, \
             self.assertRaises(ProvisionError):
            provision(document)
        run.assert_not_called()
        with patch.dict('os.environ', {}, clear=True), patch('provision_pi_secrets.subprocess.run',
             return_value=subprocess.CompletedProcess([], 0, b'secret/gtrainer-runtime', b'')) as run, \
             self.assertRaises(ProvisionError):
            provision(document)
        self.assertEqual(run.call_count, 1)

    def test_failures_do_not_echo_remote_secret_diagnostics(self):
        document = secret_document('synthetic-verifier', 'synthetic-key')
        with patch.dict('os.environ', {}, clear=True), patch('provision_pi_secrets.subprocess.run',
             return_value=subprocess.CompletedProcess([], 1, b'synthetic-private-output', b'synthetic-private-error')):
            with self.assertRaises(ProvisionError) as error:
                provision(document)
        self.assertNotIn('synthetic-private', str(error.exception))

    def test_manifest_uses_readonly_secret_files_not_secret_environment_values(self):
        root = Path(__file__).resolve().parents[1]
        manifest = (root / 'deploy/gtrainer/deployment.yaml').read_text()
        self.assertIn('secretName: gtrainer-runtime', manifest)
        self.assertIn('defaultMode: 0440', manifest)
        self.assertIn('mountPath: /run/gtrainer-secrets\n              readOnly: true', manifest)
        self.assertIn('GTRAINER_PASSWORD_VERIFIER_FILE', manifest)
        self.assertIn('GTRAINER_INTERVALS_KEY_FILE', manifest)
        self.assertNotIn('stringData:', manifest)
        self.assertNotIn('secretKeyRef:', manifest)


if __name__ == '__main__':
    unittest.main()
