"""Controlled non-inference children. This file is NEVER copied into the appliance image."""

import json
import os
from pathlib import Path
import signal
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def record(directory, name):
    Path(directory, name).write_text(str(os.getpid()))


def detached(directory):
    if os.fork() == 0:
        os.setsid()
        if os.fork() == 0:
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            record(directory, "detached.pid")
            while True:
                signal.pause()
        os._exit(0)
    os.wait()


def runtime(mode, directory):
    record(directory, "runtime.pid")
    Path(directory, "runtime-env.json").write_text(json.dumps(dict(os.environ)))
    if mode in ("detached", "root-exit"):
        detached(directory)
        if mode == "root-exit":
            return
    if mode == "ignore-term":
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    if mode == "fork-on-term":
        def term(_sig, _frame):
            if os.fork() == 0:
                signal.signal(signal.SIGTERM, signal.SIG_IGN)
                record(directory, "late-child.pid")
                while True:
                    signal.pause()
            os._exit(0)
        signal.signal(signal.SIGTERM, term)
    record(directory, "ready")
    while True:
        signal.pause()


def abandon_owner(directory):
    from runtime_scope import RuntimeScope, ScopeIdentity
    scope = RuntimeScope(ScopeIdentity("00000000-0000-0000-0000-000000000001", 1, 1, "a" * 64),
        [sys.executable, __file__, "detached", directory], {"PATH": "/usr/bin:/bin"})
    Path(directory, "keeper.pid").write_text(str(scope.process.pid))
    deadline = time.monotonic() + 5
    while not Path(directory, "detached.pid").exists():
        if time.monotonic() >= deadline:
            os._exit(2)
        time.sleep(0.01)
    os._exit(17)  # No cleanup code executes; pipe EOF must trigger kernel-owned cleanup.


def control(request):
    import socket
    from runtime_scope import receive_frame, send_frame
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as channel:
        channel.settimeout(65)
        channel.connect(os.environ["GTRAINER_SUPERVISOR_SOCKET"])
        send_frame(channel, request)
        return receive_frame(channel)


def appliance_worker(directory, scenario):
    identity = {"run_id": "00000000-0000-0000-0000-000000000001", "epoch": 3, "generation": 7, "container_id": "a" * 64}
    first = control({"op": "start", "identity": identity})
    if not first.get("ok"):
        os._exit(2)
    deadline = time.monotonic() + 5
    while not Path(directory, "ready").exists():
        if time.monotonic() > deadline:
            os._exit(2)
        time.sleep(0.01)
    result = {"first": first}
    if scenario == "worker-crash":
        os._exit(17)
    if scenario == "keeper-crash":
        os.kill(first["keeper_pid"], signal.SIGKILL)
        while time.monotonic() < deadline:
            blocked = control({"op": "start", "identity": {**identity, "generation": 8}})
            if blocked.get("reason") == "runtime_stop_unconfirmed":
                result["blocked"] = blocked
                break
            time.sleep(0.01)
        else:
            os._exit(2)
    else:
        result["during_correction"] = control({"op": "status", "identity": identity})
        result["overlap"] = control({"op": "start", "identity": {**identity, "generation": 8}})
        result["wrong_identity"] = control({"op": "stop", "identity": {**identity, "epoch": 4}})
        if scenario == "foreign-peer":
            foreign = subprocess_peer(directory, identity)
            result["foreign_denied"] = foreign.returncode == 0
            result["still_running"] = control({"op": "status", "identity": identity})
        result["stop"] = control({"op": "stop", "identity": identity, "total_seconds": 3.0, "term_seconds": 0.1})
        result["repeated_stop"] = control({"op": "stop", "identity": identity})
        if scenario == "cycle":
            second = {**identity, "generation": 8}
            result["second"] = control({"op": "start", "identity": second})
            result["second_stop"] = control({"op": "stop", "identity": second})
    Path(directory, "worker-result.json").write_text(json.dumps(result))


def subprocess_peer(directory, identity):
    import subprocess
    return subprocess.run([sys.executable, __file__, "foreign-peer", directory, json.dumps(identity)], timeout=5)


if __name__ == "__main__":
    if sys.argv[1] == "appliance-worker":
        appliance_worker(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == "foreign-peer":
        try:
            control({"op": "stop", "identity": json.loads(sys.argv[3])})
        except Exception:
            sys.exit(0)
        sys.exit(2)
    elif sys.argv[1] == "abandon-owner":
        abandon_owner(sys.argv[2])
    else:
        runtime(sys.argv[1], sys.argv[2])
