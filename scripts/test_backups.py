from datetime import datetime, timezone
import io
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from mac_backup import AGE, KEYGEN, BackupError, backup, prune, restore
from pi_snapshot import snapshot


def fixture(path):
    import json
    connection = sqlite3.connect(path)
    connection.executescript('''
        PRAGMA journal_mode=WAL;
        PRAGMA wal_autocheckpoint=0;
        PRAGMA user_version=1;
        CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_utc TEXT NOT NULL);
        INSERT INTO schema_migrations VALUES (1, '2020-01-01T00:00:00Z');
        CREATE TABLE activities (source TEXT, source_id TEXT, observed_date TEXT, record_json TEXT, PRIMARY KEY(source,source_id));
        CREATE TABLE wellness (source TEXT, source_id TEXT, observed_date TEXT, record_json TEXT, PRIMARY KEY(source,source_id));
        CREATE TABLE events (id TEXT PRIMARY KEY, start_date TEXT, end_date TEXT, sport TEXT, goal TEXT, notes TEXT);
        CREATE TABLE sync_status (category TEXT PRIMARY KEY, last_attempt_utc TEXT, last_success_utc TEXT, read_status TEXT, rejected INTEGER, incomplete INTEGER);
        INSERT INTO activities VALUES ('synthetic', 'synthetic-activity', '2020-06-01', '{}');
        INSERT INTO wellness VALUES ('synthetic', 'synthetic-wellness', '2020-06-01', '{}');
        INSERT INTO events VALUES ('synthetic-event', '2020-06-01', '2020-06-02', 'synthetic sport', 'synthetic trip, not personal data', NULL);
    ''')
    activity = {'source': 'synthetic', 'sourceRecordId': 'synthetic-activity', 'upstreamSources': [],
                'sport': 'Run', 'startLocal': '2020-06-01T12:00', 'startInstant': '2020-06-01T12:00:00Z',
                'timeZone': 'UTC', 'sourceTimeZoneLabel': 'UTC', 'utcOffset': 'Z', 'timeContext': 'explicit_offset',
                'movingTime': {'value': 1234.0, 'unit': 'seconds'}, 'elapsedTime': {'value': 1234.0, 'unit': 'seconds'},
                'calories': None, 'distance': None, 'averageHeartRate': None, 'intervalsTrainingLoad': None}
    wellness = {'source': 'synthetic', 'sourceRecordId': '2020-06-01', 'date': '2020-06-01',
                'upstreamSources': [], 'measurements': {'hrv': {'value': 42.0, 'unit': 'ms'}}}
    connection.execute('UPDATE activities SET record_json=?', (json.dumps(activity),))
    connection.execute('UPDATE wellness SET source_id=?,record_json=?', ('2020-06-01', json.dumps(wellness)))
    connection.commit()
    return connection


class Backups(unittest.TestCase):
    def test_online_snapshot_contains_wal_records_and_events_and_cleans_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            connection = fixture(root / 'synthetic.sqlite3')
            try:
                original = connection.execute('SELECT record_json FROM activities').fetchone()[0]
                wal_record = original.replace('synthetic-activity', 'synthetic-wal-activity').replace('2020-06-01', '2020-06-02')
                connection.execute("INSERT INTO activities VALUES ('synthetic', 'synthetic-wal-activity', '2020-06-02', ?)", (wal_record,))
                connection.commit()
                output = io.BytesIO()
                snapshot(root, 'synthetic.sqlite3', output)
                restored = root / 'restored.sqlite3'
                restored.write_bytes(output.getvalue())
                with sqlite3.connect(restored) as database:
                    self.assertEqual(database.execute('PRAGMA integrity_check').fetchone(), ('ok',))
                    self.assertEqual(database.execute('SELECT count(*) FROM activities').fetchone(), (2,))
                    self.assertEqual(database.execute('SELECT count(*) FROM wellness').fetchone(), (1,))
                    self.assertEqual(database.execute('SELECT count(*) FROM events').fetchone(), (1,))
                self.assertFalse((root / '.gtrainer-backup.snapshot.sqlite3').exists())
            finally:
                connection.close()

    def test_missing_source_never_creates_empty_database(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                snapshot(directory, 'missing.sqlite3', io.BytesIO())
            self.assertFalse((Path(directory) / 'missing.sqlite3').exists())

    def test_snapshot_failure_deletes_only_reserved_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            connection = fixture(root / 'synthetic.sqlite3')
            connection.close()
            class FailingOutput:
                def write(self, data):
                    raise BrokenPipeError('synthetic interruption')
            unrelated = root / 'unrelated-user-file'
            unrelated.write_text('synthetic preserved file')
            with self.assertRaises(BrokenPipeError):
                snapshot(root, 'synthetic.sqlite3', FailingOutput())
            self.assertFalse((root / '.gtrainer-backup.snapshot.sqlite3').exists())
            self.assertTrue(unrelated.exists())

    def test_source_symlinks_and_parent_traversal_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                snapshot(root, '../synthetic.sqlite3', io.BytesIO())
            (root / 'synthetic.sqlite3').symlink_to(root / 'elsewhere.sqlite3')
            with self.assertRaises(FileNotFoundError):
                snapshot(root, 'synthetic.sqlite3', io.BytesIO())

    def test_retention_is_scoped_to_exact_ciphertext_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for day in range(1, 21):
                (root / f'2020-01-{day:02d}.sqlite3.age').write_text('synthetic ciphertext')
            extra = root / 'unrelated.age'
            extra.write_text('synthetic preserved ciphertext')
            prune(root, r'\d{4}-\d{2}-\d{2}\.sqlite3\.age', 14)
            self.assertEqual(len(list(root.glob('*.sqlite3.age'))), 14)
            self.assertTrue(extra.exists())
            self.assertTrue((root / '2020-01-20.sqlite3.age').exists())

    def test_failed_or_missing_remote_snapshot_cannot_commit_a_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            transfer = MagicMock()
            transfer.stdout = io.BytesIO(b'synthetic stream')
            transfer.stderr = io.BytesIO(b'database_missing')
            transfer.wait.return_value = 2
            transfer.poll.return_value = 2
            encryption = MagicMock()
            encryption.communicate.return_value = (None, b'')
            encryption.returncode = 0
            encryption.poll.return_value = 0
            with patch.dict('os.environ', {}, clear=True), patch('mac_backup.recipient', return_value='synthetic-recipient'), \
                 patch('mac_backup.volume_path', return_value='/synthetic/volume'), \
                 patch('mac_backup.subprocess.Popen', side_effect=[transfer, encryption]), self.assertRaises(BackupError):
                backup(root)
            self.assertFalse(list(root.rglob('*.age')))
            self.assertFalse((root / 'status.json').exists())
            self.assertFalse(list(root.rglob('.pending-*')))

    @unittest.skipUnless(AGE.is_file() and KEYGEN.is_file(), 'Operator age integration test requires installed tools')
    def test_age_restore_validates_integrity_schema_wrong_key_and_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            root.chmod(0o700)
            source = root / 'synthetic.sqlite3'
            connection = fixture(source)
            connection.close()
            keys = [root / 'synthetic.key', root / 'wrong.key']
            for key in keys:
                subprocess.run([str(KEYGEN), '-o', str(key)], capture_output=True, check=True)
                key.chmod(0o600)
            public = subprocess.run([str(KEYGEN), '-y', str(keys[0])], capture_output=True, check=True).stdout.decode().strip()
            ciphertext = root / 'synthetic.age'
            subprocess.run([str(AGE), '-r', public, '-o', str(ciphertext), str(source)], capture_output=True, check=True)
            output = root / 'restored.sqlite3'
            restore(ciphertext, output, keys[0])
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            with sqlite3.connect(output) as database:
                self.assertEqual(database.execute('SELECT count(*) FROM events').fetchone(), (1,))
            with self.assertRaises(BackupError):
                restore(ciphertext, output, keys[0])
            with self.assertRaises(BackupError):
                restore(ciphertext, root / 'wrong-restore.sqlite3', keys[1])
            ciphertext.write_bytes(ciphertext.read_bytes()[:-20] + b'synthetic-corruption')
            with self.assertRaises(BackupError):
                restore(ciphertext, root / 'corrupt-restore.sqlite3', keys[0])
            self.assertFalse((root / 'corrupt-restore.sqlite3').exists())
            self.assertFalse(list(root.glob('.restore-*')))


if __name__ == '__main__':
    unittest.main()
