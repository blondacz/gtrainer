"""One per-run subreaper keeper, with a private inherited control channel."""

from dataclasses import asdict, dataclass
import json
import os
from pathlib import Path
import select
import socket
import subprocess
import sys
import time
import uuid

from linux_children import BoundaryFailure, OwnedChildren, SignalWakeup, start_ticks

MAX_FRAME = 4096


@dataclass(frozen=True)
class ScopeIdentity:
    run_id: str
    epoch: int
    generation: int
    container_id: str

    def __post_init__(self):
        if str(uuid.UUID(self.run_id)) != self.run_id or type(self.epoch) is not int or self.epoch <= 0:
            raise BoundaryFailure()
        if type(self.generation) is not int or self.generation <= 0:
            raise BoundaryFailure()
        if len(self.container_id) != 64 or any(c not in "0123456789abcdef" for c in self.container_id):
            raise BoundaryFailure()


def send_frame(channel, payload):
    data = json.dumps(payload, separators=(",", ":")).encode() + b"\n"
    if len(data) > MAX_FRAME:
        raise BoundaryFailure()
    channel.sendall(data)


def receive_frame(channel, deadline=None):
    previous_timeout = channel.gettimeout()
    if deadline is None and previous_timeout is not None:
        deadline = time.monotonic() + previous_timeout
    data = bytearray()
    while len(data) <= MAX_FRAME:
        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise BoundaryFailure()
            channel.settimeout(remaining)
        value = channel.recv(1)
        if not value:
            raise BoundaryFailure()
        if value == b"\n":
            result = json.loads(data.decode("utf-8"), object_pairs_hook=_unique_fields)
            if not isinstance(result, dict):
                raise BoundaryFailure()
            return result
        data.extend(value)
    raise BoundaryFailure()


def _unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise BoundaryFailure()
        result[key] = value
    return result


class RuntimeScope:
    """Controller owns only its keeper; the keeper owns/reaps the entire runtime branch."""

    def __init__(self, identity, command, environment):
        self.identity = identity
        self.channel, child = socket.socketpair()
        self.channel.settimeout(5)
        self.process = None
        try:
            self.process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), str(child.fileno())],
                pass_fds=(child.fileno(),), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, close_fds=True)
            child.close()
            send_frame(self.channel, {"identity": asdict(identity), "command": list(command), "environment": environment})
            ready = receive_frame(self.channel)
            if ready.get("identity") != asdict(identity) or ready.get("keeper_pid") != self.process.pid or ready.get("ready") is not True:
                raise BoundaryFailure()
            self.keeper_start_ticks = ready["keeper_start_ticks"]
        except Exception:
            child.close()
            self.channel.close()  # Keeper observes EOF and performs bounded branch cleanup.
            if self.process is not None:
                try:
                    self.process.wait(timeout=62)
                except subprocess.TimeoutExpired:
                    pass  # Unconfirmed keeper is retained by the PID-1 parent; never claim a stop.
            raise BoundaryFailure() from None

    def status(self):
        return self._request({"op": "status"})

    def stop(self, total_seconds=60.0, term_seconds=5.0):
        if type(total_seconds) not in (int, float) or type(term_seconds) not in (int, float) or not 0 < total_seconds <= 60 or not 0 <= term_seconds <= total_seconds:
            raise BoundaryFailure()
        deadline = time.monotonic() + total_seconds
        cleanup_budget = max(0.001, total_seconds - 0.05)
        self.channel.settimeout(total_seconds)
        receipt = self._request({"op": "stop", "total_seconds": cleanup_budget,
            "term_seconds": min(term_seconds, cleanup_budget)}, deadline)
        if receipt.get("confirmed") is True:
            self.process.wait(timeout=max(0.001, deadline - time.monotonic()))
            if self.process.returncode != 0 or receipt.get("mechanism") != "linux_subreaper_waitpid_echild":
                raise BoundaryFailure()
            self.channel.close()
        return receipt

    def _request(self, request, deadline=None):
        try:
            if deadline is None:
                deadline = time.monotonic() + 5
            self.channel.settimeout(max(0.001, deadline - time.monotonic()))
            send_frame(self.channel, request)
            result = receive_frame(self.channel, deadline)
            if result.get("identity") != asdict(self.identity) or result.get("keeper_pid") != self.process.pid:
                raise BoundaryFailure()
            if result.get("keeper_start_ticks") != self.keeper_start_ticks:
                raise BoundaryFailure()
            return result
        except Exception:
            raise BoundaryFailure() from None


def _stop_receipt(channel, children, identity, total_seconds=60.0, term_seconds=5.0):
    result = children.stop(total_seconds, term_seconds)
    receipt = {"identity": asdict(identity), "keeper_pid": os.getpid(), "keeper_start_ticks": start_ticks(os.getpid()),
        "confirmed": result.confirmed, "reaped": result.reaped, "escalated": result.escalated,
        "elapsed_millis": result.elapsed_millis, "mechanism": "linux_subreaper_waitpid_echild" if result.confirmed else "unconfirmed"}
    if channel is not None:
        send_frame(channel, receipt)
    return result.confirmed


def keeper(channel):
    children = OwnedChildren()
    bootstrap = receive_frame(channel)
    identity = ScopeIdentity(**bootstrap["identity"])
    command = bootstrap["command"]
    environment = bootstrap["environment"]
    if not isinstance(command, list) or not command or not all(isinstance(arg, str) for arg in command):
        raise BoundaryFailure()
    with SignalWakeup() as wakeup:
        try:
            runtime = subprocess.Popen(command, env=environment, stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True)
            send_frame(channel, {"ready": True, "identity": asdict(identity), "keeper_pid": os.getpid(),
                "keeper_start_ticks": start_ticks(os.getpid()), "runtime_pid": runtime.pid})
            while not wakeup.shutdown:
                readable, _, _ = select.select([channel, wakeup.read_fd], [], [])
                if wakeup.read_fd in readable:
                    wakeup.drain()
                if channel in readable:
                    channel.settimeout(2)  # Partial frames cannot strand the keeper indefinitely.
                    request = receive_frame(channel)
                    channel.settimeout(None)
                    if request.get("op") == "stop":
                        if _stop_receipt(channel, children, identity, request["total_seconds"], request["term_seconds"]):
                            return
                    elif request.get("op") == "status":
                        send_frame(channel, {"identity": asdict(identity), "keeper_pid": os.getpid(),
                            "keeper_start_ticks": start_ticks(os.getpid()), "runtime_exited": runtime.poll() is not None,
                            "confirmed": False})
                    else:
                        raise BoundaryFailure()
        finally:
            # Parent EOF, shutdown or protocol error: still stop/reap, without exposing raw failures.
            children.stop()


def main():
    channel = None
    try:
        channel = socket.socket(fileno=int(sys.argv[1]))
        channel.settimeout(5)
        keeper(channel)
        return 0
    except Exception:
        return 2
    finally:
        if channel is not None:
            channel.close()


if __name__ == "__main__":
    sys.exit(main())
