#!/usr/bin/env python3
"""Private operator Mac backups: SSH SQLite snapshot -> age ciphertext."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import plistlib
import re
import shlex
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from urllib.parse import quote

from verify_pi_deployment import SSH, kubectl


HOME = Path.home()
DESTINATION = HOME / 'Library/Application Support/gtrainer/backups'
KEY = HOME / '.config/gtrainer/backup-age.key'
AGE = HOME / '.local/bin/age'
KEYGEN = HOME / '.local/bin/age-keygen'
LIMIT = 512 * 1024 * 1024


class BackupError(ValueError):
    pass


def require(value, message):
    if not value:
        raise BackupError(message)


def private_directory(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(not path.is_symlink() and path.stat().st_mode & 0o077 == 0,
            'Backup directories must be private, non-symlink directories.')


def recipient(key=KEY):
    require(key.is_file() and not key.is_symlink() and key.stat().st_mode & 0o077 == 0,
            'An owner-only recovery key is required.')
    result = subprocess.run([str(KEYGEN), '-y', str(key)], capture_output=True, timeout=15)
    require(result.returncode == 0, 'Cannot derive recovery recipient; details withheld.')
    value = result.stdout.decode().strip()
    require(re.fullmatch(r'age1[a-z0-9]{58}', value), 'Unexpected recovery recipient format.')
    return value


def volume_path():
    pvc = kubectl('-n', 'gtrainer', 'get', 'pvc', 'gtrainer-data', '-o', 'json')
    pv = kubectl('get', 'pv', pvc['spec']['volumeName'], '-o', 'json')
    path = pv['spec'].get('local', pv['spec'].get('hostPath', {})).get('path', '')
    require(path.startswith('/var/lib/rancher/k3s/storage/') and '..' not in Path(path).parts,
            'Expected the inspected local-path app volume.')
    return path


def prune(folder, pattern, keep):
    files = sorted(p for p in folder.iterdir() if re.fullmatch(pattern, p.name))
    require(all(p.is_file() and not p.is_symlink() for p in files), 'Unexpected backup file type.')
    for file in files[:-keep]:
        file.unlink()


def atomic_copy(source, destination):
    fd, name = tempfile.mkstemp(prefix='.pending-', dir=destination.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, 'wb') as target, source.open('rb') as data:
            shutil.copyfileobj(data, target)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def backup(destination=DESTINATION, database='gtrainer.sqlite3', key=KEY, force=False):
    require(not os.environ.get('GITHUB_ACTIONS'), 'CI cannot pull private backups.')
    private_directory(destination)
    daily, weekly = destination / 'daily', destination / 'weekly'
    private_directory(daily)
    private_directory(weekly)
    now = datetime.now(timezone.utc)
    filename = daily / f'{now:%Y-%m-%d}.sqlite3.age'
    status_file = destination / 'status.json'
    previous = {}
    if status_file.is_file() and not status_file.is_symlink():
        try:
            previous = json.loads(status_file.read_text())
        except ValueError:
            pass
    if filename.exists() and not force:
        require(filename.is_file() and not filename.is_symlink(), 'Unexpected daily backup file.')
        if isinstance(previous, dict) and str(previous.get('last_success_utc', '')).startswith(f'{now:%Y-%m-%d}'):
            recipient(key)  # Also detect a missing/unreadable recovery key.
            print('A successful backup already exists for today; no new snapshot requested.')
            return filename
        # An encryption committed before a failed retention/status operation
        # is not a fully successful job. Retry instead of silently skipping it.
    public_key = recipient(key)
    program = Path(__file__).with_name('pi_snapshot.py').read_text()
    command = ['sudo', '-n', 'python3', '-c', program, volume_path(), database]
    fd, name = tempfile.mkstemp(prefix='.pending-', dir=daily)
    pending = Path(name)
    transfer = None
    encryption = None
    try:
        transfer = subprocess.Popen(SSH + [shlex.join(command)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        with os.fdopen(fd, 'wb') as target:
            encryption = subprocess.Popen([str(AGE), '-r', public_key], stdin=transfer.stdout,
                                          stdout=target, stderr=subprocess.PIPE)
            transfer.stdout.close()
            encryption.communicate(timeout=300)
            transfer.stderr.read(4096)
            code = transfer.wait(timeout=15)
            require(code != 2, 'No application database yet; no backup/recovery point was created.')
            require(code == 0 and encryption.returncode == 0 and pending.stat().st_size > 100,
                    'Snapshot transfer/encryption failed; no backup was committed and payloads withheld.')
            target.flush()
            os.fsync(target.fileno())
        os.replace(pending, filename)
        iso_year, week, _ = now.isocalendar()
        week_file = weekly / f'{iso_year}-W{week:02d}.sqlite3.age'
        if not week_file.exists():
            atomic_copy(filename, week_file)
        prune(daily, r'\d{4}-\d{2}-\d{2}\.sqlite3\.age', 14)
        prune(weekly, r'\d{4}-W\d{2}\.sqlite3\.age', 8)
        status = {'last_success_utc': now.isoformat(), 'database': database, 'retention_daily': 14, 'retention_weekly': 8}
        fd, name = tempfile.mkstemp(prefix='.pending-status-', dir=destination)
        with os.fdopen(fd, 'w') as target:
            json.dump(status, target)
            target.write('\n')
        os.replace(name, destination / 'status.json')
        print('Encrypted consistent backup committed; 14 daily / 8 weekly retention applied.')
        return filename
    finally:
        if transfer is not None and transfer.poll() is None:
            transfer.terminate()
            transfer.wait(timeout=10)
        if encryption is not None and encryption.poll() is None:
            encryption.kill()
            encryption.wait(timeout=10)
        pending.unlink(missing_ok=True)


def restore(ciphertext, output, key=KEY):
    require(not os.environ.get('GITHUB_ACTIONS'), 'CI cannot decrypt private backups.')
    require(ciphertext.is_file() and not ciphertext.is_symlink(), 'Expected a selected encrypted backup.')
    require(ciphertext.stat().st_size <= LIMIT + 4 * 1024 * 1024, 'Encrypted restore input exceeds its size bound.')
    recipient(key)
    private_directory(output.parent)
    require(not output.exists(), 'Restore refuses to overwrite an existing database.')
    fd, name = tempfile.mkstemp(prefix='.restore-', suffix='.sqlite3', dir=output.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, 'wb') as target:
            result = subprocess.run([str(AGE), '--decrypt', '-i', str(key), str(ciphertext)],
                                    stdout=target, stderr=subprocess.PIPE, timeout=300)
        require(result.returncode == 0, 'Backup decryption failed; plaintext/diagnostics withheld.')
        require(temporary.stat().st_size <= LIMIT, 'Restored database exceeds the size bound.')
        # Decrypted online-backup snapshots have no concurrent writer or WAL.
        # immutable=1 prevents even SQLite sidecar creation during validation.
        uri = 'file:' + quote(str(temporary), safe='/') + '?mode=ro&immutable=1'
        with sqlite3.connect(uri, uri=True) as database:
            require(database.execute('PRAGMA integrity_check').fetchone() == ('ok',), 'Restored database integrity failed.')
            require(database.execute('PRAGMA user_version').fetchone() == (1,), 'Select a compatible database schema/app version.')
            tables = {row[0] for row in database.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            require({'schema_migrations', 'activities', 'wellness', 'events', 'sync_status'} <= tables,
                    'Expected record/event/migration schema is missing.')
            for table, expected in {
                'activities': {'source', 'source_id', 'observed_date', 'record_json'},
                'wellness': {'source', 'source_id', 'observed_date', 'record_json'},
                'events': {'id', 'start_date', 'end_date', 'sport', 'goal', 'notes'},
                'sync_status': {'category', 'last_attempt_utc', 'last_success_utc', 'read_status', 'rejected', 'incomplete'},
            }.items():
                columns = {row[1] for row in database.execute(f'PRAGMA table_info({table})')}
                require(expected <= columns, 'Restored schema is not compatible with the record/event store.')
            require(database.execute('SELECT version FROM schema_migrations WHERE version=1').fetchone() == (1,),
                    'Restored migration record is missing.')
        # Exclusive publication: even a concurrent restore cannot overwrite a
        # pre-existing target after the initial existence check.
        os.link(temporary, output)
        print('Backup decrypted into an isolated private database; integrity and schema checks pass.')
    finally:
        temporary.unlink(missing_ok=True)


def setup():
    require(not os.environ.get('GITHUB_ACTIONS'), 'Backup setup is operator-only.')
    require(AGE.is_file() and KEYGEN.is_file(), 'Install the pinned age tools before backup setup.')
    private_directory(KEY.parent)
    private_directory(DESTINATION)
    if not KEY.exists():
        result = subprocess.run([str(KEYGEN), '-o', str(KEY)], capture_output=True, timeout=15)
        require(result.returncode == 0, 'Recovery-key generation failed; details withheld.')
        KEY.chmod(0o600)
    recipient()
    # Stable operator-installed copy; switching the source Git branch cannot
    # silently change the scheduled backup implementation.
    installed = HOME / '.local/share/gtrainer/backup'
    private_directory(installed)
    for name in ('mac_backup.py', 'pi_snapshot.py', 'verify_pi_deployment.py'):
        target = installed / name
        shutil.copyfile(Path(__file__).with_name(name), target)
        target.chmod(0o600)
    agents = HOME / 'Library/LaunchAgents'
    agents.mkdir(parents=True, exist_ok=True)
    path = agents / 'io.gtrainer.backup.plist'
    require(not path.is_symlink(), 'Unexpected LaunchAgent symlink.')
    config = {'Label': 'io.gtrainer.backup', 'ProgramArguments': [sys.executable, str(installed / 'mac_backup.py'), 'run'],
              'RunAtLoad': True, 'StartInterval': 3600, 'ProcessType': 'Background', 'Umask': 63,
              'StandardOutPath': str(DESTINATION / 'job.log'), 'StandardErrorPath': str(DESTINATION / 'job-error.log')}
    with path.open('wb') as target:
        plistlib.dump(config, target)
    path.chmod(0o600)
    print('Private recovery key, backup folders, stable runner, and hourly retry LaunchAgent prepared.')
    print('Load the LaunchAgent with launchctl; missing databases must not be counted as successful backups.')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('setup', 'run', 'restore'))
    parser.add_argument('--destination', type=Path, default=DESTINATION)
    parser.add_argument('--database', default='gtrainer.sqlite3')
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--backup', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    workspace = Path(__file__).resolve().parents[1]
    require(not args.destination.resolve().is_relative_to(workspace), 'Backups must stay outside the code directory.')
    require(Path(args.database).name == args.database and re.fullmatch(r'[a-zA-Z0-9_-]+\.sqlite3', args.database),
            'Expected a bounded app database filename.')
    if args.action == 'setup':
        setup()
    elif args.action == 'run':
        backup(args.destination, args.database, force=args.force)
    else:
        require(args.backup is not None and args.output is not None, 'Restore needs explicit backup and isolated output paths.')
        require(not args.output.resolve().is_relative_to(workspace), 'Plaintext restores must stay outside the code directory.')
        restore(args.backup, args.output)


if __name__ == '__main__':
    try:
        main()
    except BackupError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('Backup operation failed; all record/key/command diagnostics withheld.', file=sys.stderr)
        sys.exit(1)
