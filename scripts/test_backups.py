from datetime import datetime, timezone
import io
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from mac_backup import AGE, KEYGEN, BackupError, backup, canonical_schema, normalize_sql, prune, restore, setup
from pi_snapshot import snapshot


def fixture(path):
    import json
    connection = sqlite3.connect(path)
    for sql in canonical_schema():
        connection.execute(sql)
    connection.execute('PRAGMA application_id=1196707410')  # 0x47544E52
    connection.execute('PRAGMA user_version=1')
    connection.execute("INSERT INTO review_schedule(singleton,state_json) VALUES(1,?)",
                        (json.dumps({'configuration': {'enabled': False, 'paused': False, 'timeZone': 'UTC',
                          'presets': [{'id': 'synthetic', 'reviewType': 'daily_combined', 'enabled': False,
                                      'scope': {'kind': 'day', 'days': 1, 'sport': None}, 'level': 'interim',
                                      'focus': 'daily_combined', 'triggers': [],
                                      'budget': {'callTimeoutMillis': 120000, 'jobTimeoutMillis': 130000,
                                                 'allowCorrectiveAttempt': False}}],
                           'queuePolicy': {'debounceSeconds': 120, 'cooldownSeconds': 300, 'maximumDeferralSeconds': 600},
                           'purposeSlots': []},
                          'version': 0, 'cursor': 0, 'modelVersion': None, 'modelId': None, 'armedUtc': None,
                          'memories': {}, 'deferredChanges': [],
                           'queue': {'pending': [], 'running': None, 'outcomes': [], 'cooldownUntilUtc': {}, 'lastSuccess': None},
                           'purposeHighWater': {}}),))
    connection.execute('INSERT INTO scoped_review_configuration(singleton,timezone_confirmed) VALUES(1,0)')
    connection.execute('INSERT INTO plan_selection(singleton,generation,selected_plan_id,selected_revision) VALUES(1,0,NULL,NULL)')
    connection.execute('INSERT INTO scoped_queue_metadata(singleton,source_revision,plan_reason_revision) VALUES(1,0,0)')
    connection.execute("INSERT INTO personal_review_runtime_owner(singleton,epoch,generation) VALUES(1,'11111111-1111-4111-8111-111111111111',0)")
    connection.execute('INSERT INTO signal_evidence_epoch(singleton,removal_generation) VALUES(1,0)')
    connection.execute('INSERT INTO constraint_observation_metadata(singleton,mutation_sequence,deletion_generation,retained_from_sequence,available) VALUES(1,0,0,1,1)')
    connection.execute('PRAGMA journal_mode=WAL')
    connection.execute('PRAGMA wal_autocheckpoint=0')
    connection.execute("INSERT INTO activities VALUES ('synthetic', 'synthetic-activity', '2020-06-01', '{}')")
    connection.execute("INSERT INTO wellness VALUES ('synthetic', 'synthetic-wellness', '2020-06-01', '{}')")
    connection.execute("INSERT INTO events VALUES ('synthetic-event', '2020-06-01', '2020-06-02', 'synthetic sport', 'synthetic trip, not personal data', NULL)")
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
    def test_setup_copies_manifest_from_backend_resource(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.config/gtrainer').mkdir(parents=True)
            (root / '.config').chmod(0o700)
            (root / '.config/gtrainer').chmod(0o700)
            key = root / '.config/gtrainer/backup-age.key'
            key.write_text('synthetic key')
            key.chmod(0o600)
            age, keygen = root / 'age', root / 'age-keygen'
            age.touch(); keygen.touch()
            with patch.dict('os.environ', {}, clear=True), patch('mac_backup.HOME', root), \
                 patch('mac_backup.DESTINATION', root / 'Library/Application Support/gtrainer/backups'), \
                 patch('mac_backup.KEY', key), patch('mac_backup.AGE', age), patch('mac_backup.KEYGEN', keygen), \
                 patch('mac_backup.recipient', return_value='synthetic'), patch('sys.stdout', new_callable=io.StringIO):
                setup()
            copied = root / '.local/share/gtrainer/backup/history-schema.json'
            self.assertEqual(copied.read_text(), (Path(__file__).resolve().parents[1] / 'backend/src/main/resources/history-schema.json').read_text())

    def test_restore_accepts_only_current_exact_identity_and_seeded_schema(self):
        for kind in ('current', 'legacy-v1', 'legacy-v8', 'wrong-app-id', 'unknown-version',
                       'current-hosted-slot', 'invalid-preview-provider', 'missing-preview-preference',
                      'partial-current', 'missing-scheduler', 'missing-scoped', 'missing-plan-selection', 'extra-view', 'invalid-zone',
                       'missing-metadata-table', 'missing-metadata-seed', 'metadata-wrong-type', 'metadata-negative',
                        'missing-runtime-owner', 'runtime-owner-invalid-epoch', 'runtime-owner-negative',
                       'missing-signal-epoch', 'signal-epoch-invalid-type', 'signal-epoch-negative',
                       'missing-constraint-metadata', 'constraint-metadata-invalid-type', 'constraint-metadata-future-retention',
                       'constraint-metadata-invalid-available',
                       'missing-proposals', 'altered-proposal-state', 'missing-proposal-trigger',
                       'missing-approval-audit', 'missing-approval-trigger',
                      'empty-state', 'missing-current-field'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                root.chmod(0o700)
                source = root / 'synthetic.sqlite3'
                connection = fixture(source)
                expected = kind in ('current', 'current-hosted-slot')
                if kind == 'legacy-v1':
                    connection.execute('DROP TABLE scoped_review_configuration')
                    connection.execute('DROP TABLE review_schedule')
                    connection.execute('PRAGMA application_id=0')
                    connection.execute('PRAGMA user_version=1')
                elif kind == 'legacy-v8': connection.execute('PRAGMA user_version=8')
                elif kind == 'wrong-app-id': connection.execute('PRAGMA application_id=7')
                elif kind == 'unknown-version': connection.execute('PRAGMA user_version=2')
                elif kind == 'partial-current': connection.execute('DROP TABLE events')
                elif kind == 'missing-scheduler': connection.execute('DELETE FROM review_schedule')
                elif kind == 'missing-scoped': connection.execute('DELETE FROM scoped_review_configuration')
                elif kind == 'missing-plan-selection': connection.execute('DELETE FROM plan_selection')
                elif kind == 'missing-proposals': connection.execute('DROP TABLE plan_amendment_proposals')
                elif kind == 'missing-proposal-trigger': connection.execute('DROP TRIGGER plan_amendment_proposal_immutable')
                elif kind == 'missing-approval-audit': connection.execute('DROP TABLE plan_amendment_approval_audit')
                elif kind == 'missing-approval-trigger': connection.execute('DROP TRIGGER plan_amendment_approval_binding_immutable')
                elif kind == 'altered-proposal-state':
                    connection.execute('DROP TABLE plan_amendment_proposal_state')
                    connection.execute('CREATE TABLE plan_amendment_proposal_state(proposal_id TEXT PRIMARY KEY,state TEXT,reason TEXT,rejected_utc TEXT)')
                elif kind == 'missing-metadata-table': connection.execute('DROP TABLE scoped_queue_metadata')
                elif kind == 'missing-metadata-seed': connection.execute('DELETE FROM scoped_queue_metadata')
                elif kind == 'missing-runtime-owner': connection.execute('DELETE FROM personal_review_runtime_owner')
                elif kind == 'runtime-owner-invalid-epoch': connection.execute("UPDATE personal_review_runtime_owner SET epoch='bad'")
                elif kind == 'runtime-owner-negative':
                    connection.execute('PRAGMA ignore_check_constraints=ON')
                    connection.execute('UPDATE personal_review_runtime_owner SET generation=-1')
                elif kind == 'missing-signal-epoch': connection.execute('DELETE FROM signal_evidence_epoch')
                elif kind == 'signal-epoch-invalid-type':
                    connection.execute('PRAGMA ignore_check_constraints=ON')
                    connection.execute("UPDATE signal_evidence_epoch SET removal_generation='bad'")
                elif kind == 'signal-epoch-negative':
                    connection.execute('PRAGMA ignore_check_constraints=ON')
                    connection.execute('UPDATE signal_evidence_epoch SET removal_generation=-1')
                elif kind == 'missing-constraint-metadata': connection.execute('DELETE FROM constraint_observation_metadata')
                elif kind == 'constraint-metadata-invalid-type':
                    connection.execute('PRAGMA ignore_check_constraints=ON')
                    connection.execute("UPDATE constraint_observation_metadata SET mutation_sequence='bad'")
                elif kind == 'constraint-metadata-future-retention':
                    connection.execute('UPDATE constraint_observation_metadata SET retained_from_sequence=2')
                elif kind == 'constraint-metadata-invalid-available':
                    connection.execute('PRAGMA ignore_check_constraints=ON')
                    connection.execute('UPDATE constraint_observation_metadata SET available=2')
                elif kind == 'metadata-wrong-type':
                    connection.execute('PRAGMA ignore_check_constraints=ON')
                    connection.execute("UPDATE scoped_queue_metadata SET source_revision='bad'")
                elif kind == 'metadata-negative':
                    connection.execute('PRAGMA ignore_check_constraints=ON')
                    connection.execute('UPDATE scoped_queue_metadata SET plan_reason_revision=-1')
                elif kind == 'extra-view': connection.execute('CREATE VIEW unexpected_view AS SELECT 1')
                elif kind == 'invalid-zone':
                    import json
                    state = json.loads(connection.execute('SELECT state_json FROM review_schedule').fetchone()[0])
                    state['configuration']['timeZone'] = 'Not/AZone'
                    connection.execute('UPDATE review_schedule SET state_json=?', (json.dumps(state),))
                elif kind in ('current-hosted-slot', 'invalid-preview-provider', 'missing-preview-preference'):
                    import json
                    state = json.loads(connection.execute('SELECT state_json FROM review_schedule').fetchone()[0])
                    slot = {'slotId': '22222222-2222-4222-8222-222222222222', 'purpose': 'morning', 'enabled': True,
                            'localTime': '08:00', 'profile': 'scoped-descriptive-v1',
                            'providerSelection': {'providerId': 'synthetic-provider', 'modelId': 'synthetic-model', 'providerKind': 'hosted'}}
                    if kind == 'invalid-preview-provider': slot['providerSelection']['providerId'] = 'invalid provider'
                    if kind == 'missing-preview-preference': del slot['providerSelection']
                    state['configuration']['purposeSlots'] = [slot]
                    connection.execute('UPDATE review_schedule SET state_json=?', (json.dumps(state),))
                elif kind == 'empty-state': connection.execute("UPDATE review_schedule SET state_json='{}'")
                elif kind == 'missing-current-field':
                    import json
                    state = json.loads(connection.execute('SELECT state_json FROM review_schedule').fetchone()[0])
                    del state['queue']
                    connection.execute('UPDATE review_schedule SET state_json=?', (json.dumps(state),))
                connection.commit()
                connection.close()
                ciphertext = root / 'synthetic.age'
                ciphertext.write_bytes(b'synthetic ciphertext, no private data')
                output = root / 'restored.sqlite3'
                def decrypt(*args, **kwargs):
                    kwargs['stdout'].write(source.read_bytes())
                    return subprocess.CompletedProcess(args[0], 0)
                with patch.dict('os.environ', {}, clear=True), patch('mac_backup.recipient', return_value='synthetic'), \
                     patch('mac_backup.subprocess.run', side_effect=decrypt), patch('sys.stdout', new_callable=io.StringIO):
                    if expected:
                        restore(ciphertext, output, root / 'synthetic.key')
                        with sqlite3.connect(output) as database:
                            self.assertEqual(database.execute('PRAGMA user_version').fetchone(), (1,))
                            self.assertEqual(database.execute('SELECT count(*) FROM events').fetchone(), (1,))
                    else:
                        with self.assertRaises(BackupError):
                            restore(ciphertext, output, root / 'synthetic.key')
                        self.assertFalse(output.exists())
                self.assertFalse(list(root.glob('.restore-*')))

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

    def test_snapshot_preserves_proposals_and_cascade_removes_all_copies(self):
        import hashlib
        import json
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            connection = fixture(root / 'synthetic.sqlite3')
            snapshot_id = '00000000-0000-0000-0000-000000000001'
            proposal_id = '00000000-0000-0000-0000-000000000002'
            projection = json.dumps({'synthetic': 'PRIVATE_SYNTHETIC_PROPOSAL_MARKER'})
            connection.execute('PRAGMA foreign_keys=ON')
            connection.execute('INSERT INTO scoped_evidence_snapshots VALUES(?,?,?,?,?,?,?)',
                               (snapshot_id, '{}', 'a' * 64, None, None, '{}', '2026-10-15T12:00:00Z'))
            connection.execute('INSERT INTO plan_amendment_proposals VALUES(?,?,?,?,?,?,?)',
                               (proposal_id, snapshot_id, 'b' * 64, hashlib.sha256(projection.encode()).hexdigest(),
                                projection, '2026-10-15T12:00:00Z', '2026-10-16T00:00:00Z'))
            connection.execute('INSERT INTO plan_amendment_proposal_state VALUES(?,?,?,?)',
                               (proposal_id, 'pending', None, None))
            plan_id = '00000000-0000-0000-0000-000000000003'
            connection.execute("INSERT INTO plan_revisions VALUES(?,1,NULL,'{}','authenticated_user','2026-10-15T12:00:00Z')", (plan_id,))
            operation_id = '00000000-0000-0000-0000-000000000004'
            connection.execute('UPDATE plan_amendment_proposal_state SET state=? WHERE proposal_id=?', ('applied', proposal_id))
            connection.execute('INSERT INTO plan_amendment_approval_audit VALUES(?,?,?,?,?)',
                               (operation_id, proposal_id, plan_id, 1, '2026-10-15T13:00:00Z'))
            connection.execute('INSERT INTO plan_amendment_approval_bindings VALUES(?,?,?)',
                               (operation_id, proposal_id, 'd' * 64))
            connection.commit()
            try:
                output = io.BytesIO()
                snapshot(root, 'synthetic.sqlite3', output)
                restored = root / 'restored.sqlite3'
                restored.write_bytes(output.getvalue())
                with sqlite3.connect(restored) as database:
                    database.execute('PRAGMA foreign_keys=ON')
                    self.assertEqual(database.execute('PRAGMA foreign_key_check').fetchall(), [])
                    self.assertEqual(database.execute('SELECT projection_json FROM plan_amendment_proposals').fetchone(), (projection,))
                    self.assertEqual(database.execute('SELECT operation_id FROM plan_amendment_approval_audit').fetchone(), (operation_id,))
                    self.assertEqual(database.execute('SELECT request_sha256 FROM plan_amendment_approval_bindings').fetchone(), ('d' * 64,))
                    with self.assertRaises(sqlite3.IntegrityError):
                        database.execute("UPDATE plan_amendment_proposals SET projection_json='{}'")
                    database.execute('DELETE FROM scoped_evidence_snapshots WHERE snapshot_id=?', (snapshot_id,))
                    self.assertEqual(database.execute('SELECT count(*) FROM plan_amendment_proposals').fetchone(), (0,))
                    self.assertEqual(database.execute('SELECT count(*) FROM plan_amendment_proposal_state').fetchone(), (0,))
                    self.assertEqual(database.execute('SELECT count(*) FROM plan_amendment_approval_bindings').fetchone(), (0,))
                    self.assertEqual(database.execute('SELECT operation_id FROM plan_amendment_approval_audit').fetchone(), (operation_id,))
                    self.assertEqual(database.execute('SELECT count(*) FROM activities').fetchone(), (1,))
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
