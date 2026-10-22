"""Durable ownership of compiler groups and nested synthetic test supervisors.

The guardian binds its own dedicated group before launching a compiler and stays
alive until cancellation. A dead worker cannot erase the cleanup identity.
"""
import argparse
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

import lifecycle_v4 as life

ENV = "INTAKE_V4_PROCESS_OWNER_DIR"


def owner():
    value = os.environ.get(ENV)
    return life.private.private_directory(Path(value)) if value else None


def closed(directory):
    return any((directory / name).exists() for name in ("admission-closed.json", "fenced.json", "outcome.json"))


def launch(command, environment, directory):
    directory = life.private.private_directory(directory)
    life.need(not closed(directory))
    request = directory / ("process-" + uuid.uuid4().hex + ".request.json")
    argv = [sys.executable, str(Path(__file__).absolute()), "--guard", str(request)]
    life.write(request, {"command": command, "guardianArgv": argv,
        "ownerJobSha256": life.e.digest(life.private.private_read(directory / "job.json"))})
    try:
        process = subprocess.Popen(argv, cwd=life.e.ROOT, env=environment, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True, umask=0o077)
    except OSError:
        life.write(sibling(request, ".aborted.json"), {
            "requestSha256": life.e.digest(life.private.private_read(request)), "compilerStarted": False})
        raise
    return process, request


def sibling(request, suffix):
    return request.with_name(request.name.replace(".request.json", suffix))


def guard(request):
    directory = life.private.private_directory(request.parent)
    row = life.private.read_json(request)
    digest = life.e.digest(life.private.private_read(request))
    life.need(row["ownerJobSha256"] == life.e.digest(life.private.private_read(directory / "job.json")))
    life.need(type(row["command"]) is list and 0 < len(row["command"]) <= 64)
    life.need(all(type(value) is str and 0 < len(value) <= 8192 for value in row["command"]))
    life.need(os.getpid() == os.getpgrp())
    if closed(directory):
        life.write(sibling(request, ".aborted.json"), {"requestSha256": digest, "compilerStarted": False}); return
    observed = subprocess.run(["/bin/ps", "-p", str(os.getpid()), "-o", "command="],
                              capture_output=True, timeout=5).stdout.decode().strip()
    # macOS may replace the Python launcher with Python.app's executable. Bind
    # that actual command, retaining the exact helper/request arguments.
    life.need(observed.endswith(" " + " ".join(row["guardianArgv"][1:])))
    life.write(sibling(request, ".bound.json"), {"pid": os.getpid(), "pgid": os.getpgrp(),
        "requestSha256": digest, "guardianArgv": row["guardianArgv"], "guardianCommand": observed})
    if closed(directory):
        life.write(sibling(request, ".aborted.json"), {"requestSha256": digest, "compilerStarted": False}); return
    child = subprocess.Popen(row["command"], stdin=subprocess.DEVNULL)
    code = child.wait()
    life.write(sibling(request, ".result.json"), {"exitCode": code, "requestSha256": digest})
    # EOF reaches the reader, but group identity remains alive until cleanup.
    os.close(1); os.close(2)
    while True: time.sleep(60)


def absent(group):
    try: os.killpg(group, 0)
    except ProcessLookupError: return True
    except PermissionError: return False
    return False


def quiescent(group):
    if absent(group): return True
    result = subprocess.run(["/bin/ps", "-axo", "pid=,pgid=,state="], capture_output=True, timeout=5)
    life.need(result.returncode == 0 and len(result.stdout) <= 1024 * 1024)
    states = []
    for line in result.stdout.decode().splitlines():
        if not line.strip(): continue
        fields = line.split()
        life.need(len(fields) == 3 and fields[0].isdigit() and fields[1].isdigit())
        if int(fields[1]) == group: states.append(fields[2])
    # macOS may retain a reparented zombie group and return EPERM for killpg(0).
    # Zombies cannot execute/fork and have released compiler CPU/memory. Never
    # interpret EPERM alone as proof, and never signal a different live group.
    return all(state.startswith("Z") for state in states)


def stopped(request, group, local_end, wall_end):
    while time.monotonic() < local_end and time.time() < wall_end:
        if quiescent(group):
            life.write(sibling(request, ".stopped.json"), {
                "requestSha256": life.e.digest(life.private.private_read(request)),
                "groupAbsent": absent(group), "groupQuiescent": True})
            return True
        time.sleep(0.02)
    return False


def finish(process, request, local_end):
    # Direct parent has not reaped this guardian; its dedicated group is owned.
    try: os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError: pass
    except PermissionError: life.need(quiescent(process.pid))
    process.wait(timeout=max(0.01, min(5, local_end - time.monotonic())))
    return stopped(request, process.pid, local_end, time.time() + max(0, local_end - time.monotonic()))


def cleanup(directory, local_end, wall_end):
    """Called only after admission closes and the worker group is quiescent."""
    verified = True
    for request in sorted(directory.glob("process-*.request.json")):
        digest = life.e.digest(life.private.private_read(request))
        proof = sibling(request, ".stopped.json")
        if proof.exists():
            row = life.private.read_json(proof)
            verified &= (row.get("groupAbsent") is True or row.get("groupQuiescent") is True) and row.get("requestSha256") == digest
            continue
        aborted = sibling(request, ".aborted.json")
        if aborted.exists():
            row = life.private.read_json(aborted)
            verified &= row == {"requestSha256": digest, "compilerStarted": False}
            continue
        binding = sibling(request, ".bound.json")
        if not binding.exists(): verified = False; continue
        row = life.private.read_json(binding)
        life.need(row["requestSha256"] == digest and type(row["pid"]) is int and row["pid"] == row["pgid"] > 1)
        group = row["pgid"]
        if not quiescent(group):
            remaining = min(local_end - time.monotonic(), wall_end - time.time())
            life.need(remaining > 0)
            observed = subprocess.run(["/bin/ps", "-p", str(group), "-o", "command="],
                                      capture_output=True, timeout=min(5, remaining))
            # Never signal a reused PID/group based on its number alone. The
            # guardian never execs or voluntarily exits after starting children.
            life.need(observed.stdout.decode().strip() == row["guardianCommand"])
            os.killpg(group, signal.SIGKILL)
        verified &= stopped(request, group, local_end, wall_end)
    for path in sorted(directory.glob("owned-job-*.json")):
        row = life.private.read_json(path); child = life.private.private_directory(Path(row["directory"]))
        life.need(life.e.digest(life.private.private_read(child / "job.json")) == row["jobSha256"])
        spec = life.private.read_json(child / "job.json")
        life.need(spec["mode"] in ("synthetic_fake", "offline_tests") and spec["parentOwner"] == str(directory))
        import supervisor_v4 as supervisor
        if not (child / "outcome.json").exists(): supervisor.cancel(child)
        while not (child / "outcome.json").exists() and time.monotonic() < local_end and time.time() < wall_end:
            time.sleep(0.05)
        if not (child / "outcome.json").exists(): verified = False; continue
        outcome = life.private.read_json(child / "outcome.json")
        verified &= (outcome.get("cleanup") or {}).get("verified") is True
    return bool(verified)


def register_child(directory, child):
    life.need(not closed(directory))
    life.write(directory / ("owned-job-" + uuid.uuid4().hex + ".json"), {
        "directory": str(child), "jobSha256": life.e.digest(life.private.private_read(child / "job.json"))})


if __name__ == "__main__":
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--guard", type=Path, required=True)
    guard(parser.parse_args().guard)
