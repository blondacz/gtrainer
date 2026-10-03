"""Private Pi-side synthetic capture, independent of the observing SSH session.

No model requests, retries or health-data reads occur here. The unchanged owned
benchmark runner is supervised once; captures remain recoverable after SSH loss.
"""
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys

MAX_RUN_SECONDS = 7600
STOP_GRACE_SECONDS = 180
FILES = ('bootstrap.py', 'supervisor.py', 'metadata.json', 'results.jsonl', 'stderr.txt', 'supervisor-stderr.txt')


def directory(owner):
    if len(owner) != 32 or any(c not in '0123456789abcdef' for c in owner):
        raise ValueError('Unique lowercase UUID owner required')
    return Path('/run/gtrainer-prepared-capture-' + owner)


def write_private(path, body):
    with path.open('xb') as output:
        path.chmod(0o600)
        output.write(body)
        output.flush()
        os.fsync(output.fileno())


def metadata_write(path, value):
    pending = path.with_name('metadata.pending')
    write_private(pending, json.dumps(value, sort_keys=True, allow_nan=False).encode())
    pending.replace(path)


def metadata_read(owner):
    path = directory(owner)
    if path.is_symlink() or not path.is_dir() or path.stat().st_uid != 0 or path.stat().st_mode & 0o077:
        raise RuntimeError('Capture directory ownership/privacy mismatch')
    value = json.loads((path / 'metadata.json').read_bytes())
    if value['owner'] != owner:
        raise RuntimeError('Capture owner mismatch')
    return value


def launch(owner, encoded_bootstrap, expected_hash):
    if os.geteuid() != 0 or not Path('/proc/meminfo').exists() or os.environ.get('GITHUB_ACTIONS'):
        raise RuntimeError('Only an explicitly authorized Pi operator may launch')
    body = base64.b64decode(encoded_bootstrap, validate=True)
    if len(body) > 1048576 or hashlib.sha256(body).hexdigest() != expected_hash:
        raise ValueError('Frozen bootstrap mismatch')
    path = directory(owner)
    path.mkdir(mode=0o700)  # Existing owners are never relaunched or overwritten.
    os.umask(0o077)
    write_private(path / 'bootstrap.py', body)
    supervisor = Path(__file__).read_bytes() if Path(__file__).exists() else CAPTURE_SOURCE.encode()
    write_private(path / 'supervisor.py', supervisor)
    metadata_write(path / 'metadata.json', {'owner': owner, 'status': 'created',
                   'bootstrap_sha256': expected_hash, 'synthetic_only': True,
                   'created_at_utc': datetime.now(timezone.utc).isoformat()})
    with (path / 'supervisor-stderr.txt').open('xb') as errors:
        process = subprocess.Popen([sys.executable, '-u', str(path / 'supervisor.py'), owner],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                   stderr=errors, start_new_session=True, close_fds=True)
    return {'owner': owner, 'supervisor_pid': process.pid, 'capture_directory': str(path),
            'bootstrap_sha256': expected_hash, 'synthetic_only': True}


def supervise(owner):
    os.umask(0o077)
    path = directory(owner)
    metadata = metadata_read(owner)
    if metadata['status'] != 'created':
        raise RuntimeError('Supervisor must start exactly once')
    if hashlib.sha256((path / 'bootstrap.py').read_bytes()).hexdigest() != metadata['bootstrap_sha256']:
        raise RuntimeError('Bootstrap changed before execution')
    timed_out = forced_kill = False
    with (path / 'results.jsonl').open('xb') as output, (path / 'stderr.txt').open('xb') as errors:
        process = subprocess.Popen([sys.executable, '-u', str(path / 'bootstrap.py')],
                                   stdin=subprocess.DEVNULL, stdout=output, stderr=errors, close_fds=True)
        metadata.update(status='running', supervisor_pid=os.getpid(), runner_pid=process.pid,
                        started_at_utc=datetime.now(timezone.utc).isoformat())
        metadata_write(path / 'metadata.json', metadata)
        try:
            code = process.wait(timeout=MAX_RUN_SECONDS)
        except subprocess.TimeoutExpired:
            timed_out = True
            process.send_signal(signal.SIGINT)  # The runner's finally owns cleanup.
            try:
                code = process.wait(timeout=STOP_GRACE_SECONDS)
            except subprocess.TimeoutExpired:
                forced_kill = True
                process.kill()
                code = process.wait(timeout=10)
        output.flush(); os.fsync(output.fileno())
        errors.flush(); os.fsync(errors.fileno())
    metadata.update(status='finished', returncode=code, supervisor_timeout=timed_out,
                    forced_kill_cleanup_unverified=forced_kill,
                    finished_at_utc=datetime.now(timezone.utc).isoformat())
    for name in ('results.jsonl', 'stderr.txt'):
        body = (path / name).read_bytes()
        metadata[name] = {'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest()}
    metadata_write(path / 'metadata.json', metadata)


def wait_finished(owner, pid):
    """Block on Linux process completion, not repeated status/output polling."""
    metadata = metadata_read(owner)
    if metadata['status'] != 'finished':
        if metadata.get('supervisor_pid', pid) != pid:
            raise RuntimeError('Supervisor PID ownership mismatch')
        try:
            command = Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')
        except FileNotFoundError:
            command = []
        expected = [sys.executable.encode(), b'-u', str(directory(owner) / 'supervisor.py').encode(), owner.encode(), b'']
        if command and command != expected:
            raise RuntimeError('Supervisor process identity mismatch')
        try:
            descriptor = os.pidfd_open(pid)
        except ProcessLookupError:
            descriptor = None
        if descriptor is not None:
            try:
                ready, _, _ = select.select([descriptor], [], [], MAX_RUN_SECONDS + STOP_GRACE_SECONDS + 30)
                if not ready:
                    raise RuntimeError('Observer deadline; capture remains on Pi, no retry')
            finally:
                os.close(descriptor)
    metadata = metadata_read(owner)
    if metadata['status'] != 'finished' or metadata.get('supervisor_pid') != pid:
        raise RuntimeError('No verified terminal capture; do not relaunch')
    return metadata


def remove_capture(owner, expected_results_hash):
    """Remove only an owned finished capture after its verified local copy exists."""
    metadata = metadata_read(owner)
    path = directory(owner)
    if metadata['status'] != 'finished' or metadata['results.jsonl']['sha256'] != expected_results_hash:
        raise RuntimeError('No matching terminal capture; keep files')
    files = list(path.iterdir())
    if {p.name for p in files} != set(FILES) or any(p.is_symlink() or not p.is_file() or p.stat().st_uid != 0 for p in files):
        raise RuntimeError('Unexpected capture contents; keep files')
    if hashlib.sha256((path / 'results.jsonl').read_bytes()).hexdigest() != expected_results_hash:
        raise RuntimeError('Capture changed; keep files')
    for name in FILES:
        (path / name).unlink()
    path.rmdir()


def launch_script(owner, bootstrap):
    directory(owner)
    source = Path(__file__).read_text()
    encoded = base64.b64encode(bootstrap.encode()).decode()
    digest = hashlib.sha256(bootstrap.encode()).hexdigest()
    return ('import json,types\nm=types.ModuleType("prepared_review_capture"); m.__file__="<packaged-capture>"\n'
            'exec(' + repr(source) + ',m.__dict__)\nm.CAPTURE_SOURCE=' + repr(source) + '\n'
            'print(json.dumps(m.launch(' + repr(owner) + ',' + repr(encoded) + ',' + repr(digest) + ')),flush=True)\n')


def wait_script(owner, pid):
    directory(owner)
    if type(pid) is not int or pid <= 0:
        raise ValueError('Explicit supervisor PID required')
    source = Path(__file__).read_text()
    return ('import json,types\nm=types.ModuleType("prepared_review_capture"); m.__file__="<packaged-capture>"\n'
            'exec(' + repr(source) + ',m.__dict__)\n'
            'print(json.dumps(m.wait_finished(' + repr(owner) + ',' + repr(pid) + ')),flush=True)\n')


if __name__ == '__main__':
    supervise(sys.argv[1])
