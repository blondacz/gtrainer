"""Dedicated appliance PID 1. Only its exact worker PID may control a runtime."""

from dataclasses import asdict
import fcntl
import json
import os
from pathlib import Path
import selectors
import socket
import stat
import struct
import subprocess
import sys
import tempfile
import time

from linux_children import BoundaryFailure, OwnedChildren, SignalWakeup
from runtime_scope import MAX_FRAME, RuntimeScope, ScopeIdentity, _unique_fields, receive_frame, send_frame


def configuration(path):
    with open(path, "rb") as stream:
        data = stream.read(16 * 1024 + 1)
    if len(data) > 16 * 1024:
        raise BoundaryFailure()
    result = json.loads(data.decode("utf-8"), object_pairs_hook=_unique_fields)
    if result.get("enabled") is not True:
        return None
    if os.getpid() != 1:
        raise BoundaryFailure()
    if set(result) != {"enabled", "controlDirectory", "modelsDirectory", "workerCommand", "workerEnvironment"}:
        raise BoundaryFailure()
    for field in ("controlDirectory", "modelsDirectory"):
        if not Path(result[field]).is_absolute():
            raise BoundaryFailure()
    command = result["workerCommand"]
    if not isinstance(command, list) or not 1 <= len(command) <= 32 or not all(isinstance(v, str) and 0 < len(v) <= 1024 for v in command):
        raise BoundaryFailure()
    if not Path(command[0]).is_absolute():
        raise BoundaryFailure()
    allowed = {"GTRAINER_DEVELOPMENT_REVIEW_ENABLED", "GTRAINER_DEVELOPMENT_REVIEW_CONFIG_FILE"}
    if not isinstance(result["workerEnvironment"], dict) or not set(result["workerEnvironment"]) <= allowed:
        raise BoundaryFailure()
    if not all(isinstance(v, str) and len(v) <= 1024 for v in result["workerEnvironment"].values()):
        raise BoundaryFailure()
    return result


class ControlDirectory:
    """Private local boundary and lifetime lock; not a substitute for durable owner epochs."""

    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = self.directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise BoundaryFailure()
        self.lock_fd = os.open(self.directory / "owner.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.socket_path = self.directory / "supervisor.sock"
            if self.socket_path.exists():
                if not stat.S_ISSOCK(self.socket_path.lstat().st_mode):
                    raise BoundaryFailure()
                self.socket_path.unlink()  # Only a stale socket within our newly acquired lifetime lock.
            self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.listener.bind(str(self.socket_path))
            os.chmod(self.socket_path, 0o600)
            self.listener.listen(1)
        except Exception:
            if hasattr(self, "listener"):
                self.listener.close()
            os.close(self.lock_fd)
            raise BoundaryFailure() from None

    def close(self):
        self.listener.close()
        self.socket_path.unlink(missing_ok=True)
        os.close(self.lock_fd)

    def receipt(self, payload):
        data = json.dumps(payload, separators=(",", ":")).encode()
        if len(data) > MAX_FRAME:
            raise BoundaryFailure()
        name = None
        try:
            with tempfile.NamedTemporaryFile(dir=self.directory, prefix="receipt-", delete=False) as stream:
                name = stream.name
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(name, self.directory / "last-stop-receipt.json")
            descriptor = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        finally:
            if name is not None:
                Path(name).unlink(missing_ok=True)


class Appliance:
    def __init__(self, config, runtime_command=None):
        self.config = config
        self.scope = None
        self.last_generation = 0
        self.owner = None
        self.last_identity = None
        self.last_receipt = None
        self.quarantined = False
        self.runtime_command = runtime_command or ["/usr/local/bin/ollama", "serve"]
        self.children = OwnedChildren()
        self.control = ControlDirectory(config["controlDirectory"])
        self.worker = None

    def run(self):
        try:
            with SignalWakeup() as wakeup, selectors.DefaultSelector() as selector:
                selector.register(wakeup.read_fd, selectors.EVENT_READ, "signal")
                selector.register(self.control.listener, selectors.EVENT_READ, "control")
                self.worker = subprocess.Popen(self.config["workerCommand"], env=self._worker_environment(),
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True)
                while not wakeup.shutdown:
                    for key, _ in selector.select():
                        if key.data == "signal":
                            wakeup.drain()
                        else:
                            self._control_request()
                    if self.worker.poll() is not None:
                        break  # Never restart/replay an interrupted worker/runtime inside this appliance.
                    if self._keeper_failed():
                        self.quarantined = True  # Keep API/queue admission alive, but never permit another runtime.
        finally:
            self._shutdown()
            self.control.close()

    def _keeper_failed(self):
        return self.scope is not None and self.scope.process.poll() is not None

    def _worker_environment(self):
        result = {"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8",
            "GTRAINER_SUPERVISOR_SOCKET": str(self.control.socket_path)}
        result.update(self.config["workerEnvironment"])
        return result

    def _runtime_environment(self):
        return {"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8", "HOME": self.config["controlDirectory"],
            "OLLAMA_HOST": "127.0.0.1:11434", "OLLAMA_MODELS": self.config["modelsDirectory"],
            "OLLAMA_NO_CLOUD": "1", "OLLAMA_MAX_LOADED_MODELS": "1", "OLLAMA_NUM_PARALLEL": "1", "OLLAMA_KEEP_ALIVE": "0"}

    def _control_request(self):
        channel, _ = self.control.listener.accept()
        with channel:
            channel.settimeout(2)
            peer_pid, _, _ = struct.unpack("3i", channel.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            if peer_pid != self.worker.pid:
                return  # Another process cannot authorize/stop runtime work, even with the same UID.
            try:
                request = receive_frame(channel)
                response = self._dispatch(request)
            except Exception:
                response = {"ok": False, "reason": "owned_runtime_control_unavailable"}
            try:
                send_frame(channel, response)
            except OSError:
                pass  # The worker may have died; SIGCHLD still drives mandatory cleanup.

    def _dispatch(self, request):
        if self.quarantined:
            return {"ok": False, "reason": "runtime_stop_unconfirmed"}
        if request.get("op") == "start" and set(request) == {"op", "identity"}:
            return self._start(ScopeIdentity(**request["identity"]))
        keys = {"op", "identity"}
        if request.get("op") == "stop":
            allowed = set(request) in (keys, keys | {"total_seconds", "term_seconds"})
        else:
            allowed = set(request) == keys
        if request.get("op") in ("stop", "status") and allowed:
            identity = ScopeIdentity(**request["identity"])
            if self.scope is None and self.last_receipt is not None and self.last_receipt["identity"] == asdict(identity):
                return {"ok": True, **self.last_receipt}
            if self.scope is None or identity != self.scope.identity:
                raise BoundaryFailure()
            if request["op"] == "status":
                return {"ok": True, **self.scope.status()}
            receipt = self.scope.stop(request.get("total_seconds", 60), request.get("term_seconds", 5))
            if receipt["confirmed"]:
                self.control.receipt(receipt)
                self.last_receipt = receipt
                self.scope = None
            return {"ok": True, **receipt}
        raise BoundaryFailure()

    def _start(self, identity):
        owner = (identity.epoch, identity.container_id)
        if self.quarantined or self.scope is not None or identity.generation <= self.last_generation or self.owner not in (None, owner):
            raise BoundaryFailure()
        self.owner = owner
        self.last_generation = identity.generation
        self.last_identity = identity
        self.quarantined = True  # A failed startup cannot leave an untracked keeper and admit another run.
        # Worker must have durably persisted claim/start intent before this request (task 4.2 integration).
        self.scope = RuntimeScope(identity, self.runtime_command, self._runtime_environment())
        self.quarantined = False
        return {"ok": True, **self.scope.status()}

    def _shutdown(self):
        deadline = time.monotonic() + 60
        if self.scope is not None:
            try:
                remaining = max(0.001, deadline - time.monotonic())
                receipt = self.scope.stop(remaining, min(5, remaining))
                if receipt["confirmed"]:
                    self.control.receipt(receipt)
            except Exception:
                pass  # No receipt is manufactured from EOF/exception; old-container reconciliation remains required.
        remaining = deadline - time.monotonic()
        if remaining > 0:
            self.children.stop(remaining, min(5, remaining))
        # Exiting PID 1 is a final kernel boundary, not an acknowledged run receipt.


def main():
    try:
        config = configuration(sys.argv[1])
        if config is None:
            return 0
        Appliance(config).run()
        return 0
    except Exception:
        print("owned_runtime_supervisor_unavailable", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
