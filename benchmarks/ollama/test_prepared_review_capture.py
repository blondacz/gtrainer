import base64
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

import prepared_review_capture as capture


class PreparedCaptureTest(unittest.TestCase):
    def test_owner_paths_are_unique_and_no_arbitrary_paths_are_allowed(self):
        self.assertEqual(capture.directory('a' * 32), Path('/run/gtrainer-prepared-capture-' + 'a' * 32))
        for owner in ('', '../elsewhere', 'A' * 32, 'a' * 31):
            with self.assertRaises(ValueError):
                capture.directory(owner)

    def test_packaged_launch_and_wait_are_valid_and_wait_has_no_poll_loop(self):
        script = capture.launch_script('a' * 32, 'print("synthetic-only")')
        compile(script, '<synthetic-durable-launch>', 'exec')
        self.assertIn(base64.b64encode(b'print("synthetic-only")').decode(), script)
        self.assertIn(hashlib.sha256(b'print("synthetic-only")').hexdigest(), script)
        compile(capture.wait_script('a' * 32, 123), '<synthetic-durable-wait>', 'exec')
        for pid in (0, -1, True, '123'):
            with self.assertRaises(ValueError):
                capture.wait_script('a' * 32, pid)

    def test_private_writes_never_overwrite_existing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'capture.json'
            capture.write_private(path, b'synthetic')
            self.assertEqual(path.read_bytes(), b'synthetic')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                capture.write_private(path, b'changed')
            capture.metadata_write(Path(directory) / 'metadata.json', {'owner': 'synthetic', 'status': 'created'})
            capture.metadata_write(Path(directory) / 'metadata.json', {'owner': 'synthetic', 'status': 'finished'})
            self.assertEqual(json.loads((Path(directory) / 'metadata.json').read_text())['status'], 'finished')
            self.assertFalse((Path(directory) / 'metadata.pending').exists())

    def test_supervisor_runs_once_and_records_exact_terminal_file_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bootstrap = b'print("synthetic result", flush=True)\n'
            capture.write_private(root / 'bootstrap.py', bootstrap)
            metadata = {'owner': 'a' * 32, 'status': 'created', 'bootstrap_sha256': hashlib.sha256(bootstrap).hexdigest()}
            with patch.object(capture, 'directory', return_value=root), patch.object(capture, 'metadata_read', return_value=metadata):
                capture.supervise('a' * 32)
            recorded = json.loads((root / 'metadata.json').read_text())
            self.assertEqual(recorded['status'], 'finished')
            self.assertEqual(recorded['returncode'], 0)
            self.assertFalse(recorded['forced_kill_cleanup_unverified'])
            self.assertEqual((root / 'results.jsonl').read_bytes(), b'synthetic result\n')
            for name in ('results.jsonl', 'stderr.txt'):
                self.assertEqual(recorded[name]['sha256'], hashlib.sha256((root / name).read_bytes()).hexdigest())
            with patch.object(capture, 'directory', return_value=root), patch.object(capture, 'metadata_read', return_value=recorded):
                with self.assertRaises(RuntimeError):
                    capture.supervise('a' * 32)

    def test_finished_wait_returns_metadata_without_a_new_process_or_poll(self):
        metadata = {'status': 'finished', 'supervisor_pid': 123}
        with patch.object(capture, 'metadata_read', return_value=metadata), patch.object(capture.os, 'pidfd_open', create=True) as pidfd:
            self.assertEqual(capture.wait_finished('a' * 32, 123), metadata)
            pidfd.assert_not_called()
        with patch.object(capture, 'metadata_read', return_value={'status': 'finished', 'supervisor_pid': 124}):
            with self.assertRaises(RuntimeError):
                capture.wait_finished('a' * 32, 123)

    def test_supervisor_timeout_interrupts_once_and_records_forced_cleanup_as_unknown(self):
        for forced in (False, True):
            with self.subTest(forced=forced), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                bootstrap = b'print("synthetic only")\n'
                capture.write_private(root / 'bootstrap.py', bootstrap)
                metadata = {'owner': 'a' * 32, 'status': 'created', 'bootstrap_sha256': hashlib.sha256(bootstrap).hexdigest()}
                waits = [subprocess.TimeoutExpired('synthetic runner', capture.MAX_RUN_SECONDS)]
                if forced:
                    waits.append(subprocess.TimeoutExpired('synthetic runner', capture.STOP_GRACE_SECONDS))
                waits.append(-9 if forced else -2)
                with patch.object(capture, 'directory', return_value=root), \
                        patch.object(capture, 'metadata_read', return_value=metadata), \
                        patch.object(capture.subprocess, 'Popen') as create:
                    process = create.return_value
                    process.pid = 123
                    process.wait.side_effect = waits
                    capture.supervise('a' * 32)
                    create.assert_called_once()
                    process.send_signal.assert_called_once_with(capture.signal.SIGINT)
                    self.assertEqual(process.kill.call_count, int(forced))
                recorded = json.loads((root / 'metadata.json').read_text())
                self.assertTrue(recorded['supervisor_timeout'])
                self.assertEqual(recorded['forced_kill_cleanup_unverified'], forced)

    def test_bootstrap_mismatch_or_existing_owner_cannot_launch(self):
        body = b'print("synthetic only")\n'
        encoded = base64.b64encode(body).decode()
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(capture.os, 'geteuid', return_value=0), \
                patch.object(Path, 'exists', return_value=True), \
                patch.object(capture, 'directory', return_value=Path(directory)), \
                patch.dict(capture.os.environ, {}, clear=True), \
                patch.object(capture.subprocess, 'Popen') as create:
            with self.assertRaises(ValueError):
                capture.launch('a' * 32, encoded, 'different')
            with self.assertRaises(FileExistsError):
                capture.launch('a' * 32, encoded, hashlib.sha256(body).hexdigest())
            create.assert_not_called()

    def test_live_wait_blocks_on_owned_pidfd_and_preserves_capture_on_observer_timeout(self):
        owner = 'a' * 32
        command = b'\0'.join([sys.executable.encode(), b'-u', str(capture.directory(owner) / 'supervisor.py').encode(), owner.encode(), b''])
        running = {'status': 'running', 'supervisor_pid': 123}
        finished = {'status': 'finished', 'supervisor_pid': 123}
        with patch.object(capture, 'metadata_read', side_effect=[running, finished]), \
                patch.object(Path, 'read_bytes', return_value=command), \
                patch.object(capture.os, 'pidfd_open', return_value=456, create=True) as pidfd, \
                patch.object(capture.select, 'select', return_value=([456], [], [])) as wait, \
                patch.object(capture.os, 'close') as close:
            self.assertEqual(capture.wait_finished(owner, 123), finished)
            pidfd.assert_called_once_with(123)
            wait.assert_called_once()
            close.assert_called_once_with(456)
        with patch.object(capture, 'metadata_read', return_value=running), \
                patch.object(Path, 'read_bytes', return_value=command), \
                patch.object(capture.os, 'pidfd_open', return_value=456, create=True), \
                patch.object(capture.select, 'select', return_value=([], [], [])), \
                patch.object(capture.os, 'close'):
            with self.assertRaises(RuntimeError):
                capture.wait_finished(owner, 123)

    def test_wrong_process_identity_never_gets_observed(self):
        with patch.object(capture, 'metadata_read', return_value={'status': 'running', 'supervisor_pid': 123}), \
                patch.object(Path, 'read_bytes', return_value=b'foreign-process\0'), \
                patch.object(capture.os, 'pidfd_open', create=True) as pidfd:
            with self.assertRaises(RuntimeError):
                capture.wait_finished('a' * 32, 123)
            pidfd.assert_not_called()

    def test_cleanup_refuses_unknown_contents_or_mismatched_hash_and_removes_only_owned_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'owned'
            root.mkdir(mode=0o700)
            body = b'synthetic result\n'
            digest = hashlib.sha256(body).hexdigest()
            for name in capture.FILES:
                capture.write_private(root / name, body if name == 'results.jsonl' else b'synthetic')
            metadata = {'owner': 'a' * 32, 'status': 'finished', 'results.jsonl': {'sha256': digest}}
            original_stat = Path.stat
            def root_owned(path, *args, **kwargs):
                values = list(original_stat(path, *args, **kwargs))
                values[4] = 0
                return os.stat_result(values)
            with patch.object(capture, 'directory', return_value=root), \
                    patch.object(capture, 'metadata_read', return_value=metadata), \
                    patch.object(Path, 'stat', root_owned):
                with self.assertRaises(RuntimeError):
                    capture.remove_capture('a' * 32, 'different')
                extra = root / 'unrecognized-user-file'
                capture.write_private(extra, b'keep')
                with self.assertRaises(RuntimeError):
                    capture.remove_capture('a' * 32, digest)
                self.assertTrue(extra.exists())
                extra.unlink()
                capture.remove_capture('a' * 32, digest)
            self.assertFalse(root.exists())


if __name__ == '__main__':
    unittest.main()
