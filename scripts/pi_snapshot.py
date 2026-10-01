#!/usr/bin/env python3
"""Executed on the Pi: one bounded consistent SQLite snapshot, stdout only."""
import fcntl
import os
from pathlib import Path
import shutil
import signal
import sqlite3
import sys
import time
from urllib.parse import quote


LIMIT = 512 * 1024 * 1024


def snapshot(volume, database, output):
    root = Path(volume)
    if not root.is_absolute() or root.is_symlink() or not root.is_dir():
        raise ValueError('Invalid volume')
    if not database or Path(database).name != database or not database.endswith('.sqlite3'):
        raise ValueError('Invalid database name')
    source = root / database
    if source.is_symlink() or not source.is_file():
        raise FileNotFoundError('database_missing')
    lock_fd = os.open(root / '.gtrainer-backup.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    target = None
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with sqlite3.connect('file:' + quote(str(source), safe='/') + '?mode=ro', uri=True, timeout=30) as connection:
            size = connection.execute('PRAGMA page_count').fetchone()[0] * connection.execute('PRAGMA page_size').fetchone()[0]
            if size > LIMIT or shutil.disk_usage(root).free < size + 256 * 1024 * 1024:
                raise ValueError('Snapshot storage budget exceeded')
            # One reserved staging file under the lock. After a hard-killed
            # prior transfer, remove only this exact tool-owned stale snapshot.
            target = root / '.gtrainer-backup.snapshot.sqlite3'
            if target.exists():
                if target.is_symlink() or not target.is_file():
                    raise ValueError('Unexpected snapshot file')
                target.unlink()
            fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
            os.close(fd)
            started = time.monotonic()
            page_size = connection.execute('PRAGMA page_size').fetchone()[0]
            def progress(status, remaining, total):
                if total * page_size > LIMIT or time.monotonic() - started > 240:
                    raise ValueError('Snapshot exceeded size/time budget')
            with sqlite3.connect(target) as destination:
                # Includes WAL transactions correctly; never copy only the live main file.
                connection.backup(destination, pages=256, progress=progress, sleep=.1)
                # The snapshot is a standalone file, not a live WAL database.
                destination.execute('PRAGMA journal_mode=DELETE')
                if destination.execute('PRAGMA integrity_check').fetchone() != ('ok',):
                    raise ValueError('Snapshot integrity failed')
            if target.stat().st_size > LIMIT:
                raise ValueError('Snapshot exceeds size bound')
            with target.open('rb') as data:
                shutil.copyfileobj(data, output, length=1024 * 1024)
            output.flush()
    finally:
        if target is not None:
            target.unlink(missing_ok=True)
        os.close(lock_fd)


if __name__ == '__main__':
    os.umask(0o077)
    def interrupted(signum, frame):
        raise InterruptedError('Snapshot interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    try:
        snapshot(sys.argv[1], sys.argv[2], sys.stdout.buffer)
    except FileNotFoundError:
        print('database_missing', file=sys.stderr)
        sys.exit(2)
    except Exception:
        print('snapshot_failed', file=sys.stderr)
        sys.exit(1)
