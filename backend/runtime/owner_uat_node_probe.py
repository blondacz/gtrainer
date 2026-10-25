#!/usr/bin/python3 -I
"""Read-only owner-UAT descendant proof. Never deletes pods or authorizes work.

Installation and privileged invocation require separate approval. BOUNDARY_EMPTY
is only one input to the supervisor's stop proof; it does not fence Kubernetes
creation or establish that the application worker has stopped.
"""

import base64
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
import uuid

PROFILE = "owner-uat-node-boundary-v1"
MAX_INPUT = 8192
MAX_COMMAND_BYTES = 65536
MAX_FILE_BYTES = 16384
MAX_DIRECTORIES = 20000
MAX_CGROUP_BYTES = 1024 * 1024
CGROUP_ROOT = Path("/sys/fs/cgroup")
BOOT_PATH = Path("/proc/sys/kernel/random/boot_id")
ENV = {"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8"}


def need(condition):
    if not condition:
        raise ValueError("owner_node_proof_unavailable")


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result)
        result[key] = value
    return result


def document(raw):
    need(type(raw) is bytes and len(raw) <= MAX_COMMAND_BYTES)
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_fields,
                      parse_constant=lambda _: need(False))


def canonical_uuid(value):
    need(type(value) is str and str(uuid.UUID(value)) == value)
    return value


def pod_component(part, uid):
    # Exact pod components, not substring matching against an unrelated cgroup.
    return part in {"pod" + uid, "pod" + uid.replace("-", "_"),
                    "kubepods-pod" + uid.replace("-", "_") + ".slice",
                    "kubepods-burstable-pod" + uid.replace("-", "_") + ".slice",
                    "kubepods-besteffort-pod" + uid.replace("-", "_") + ".slice"}


def validate_boundary(boundary):
    need(type(boundary) is dict and set(boundary) == {
        "claimId", "launchNonce", "nodeBootId", "podUid", "containerIds", "cgroupPath"})
    for field in ("claimId", "launchNonce", "nodeBootId", "podUid"):
        canonical_uuid(boundary[field])
    containers = boundary["containerIds"]
    need(type(containers) is list and 1 <= len(containers) <= 4)
    need(all(type(value) is str and re.fullmatch("[a-f0-9]{64}", value) for value in containers))
    need(containers == sorted(set(containers)))
    raw = boundary["cgroupPath"]
    need(type(raw) is str and len(raw) <= 1024)
    path = PurePosixPath(raw)
    need(str(path) == raw and path.is_absolute() and ".." not in path.parts)
    need(path.parts[:4] == ("/", "sys", "fs", "cgroup"))
    need(pod_component(path.name, boundary["podUid"]))
    need(all(re.fullmatch(r"[A-Za-z0-9_.:-]+", part) for part in path.parts[4:]))
    return boundary


def read_file(path, maximum=MAX_FILE_BYTES):
    # A disappearing file is not evidence of emptiness. Caller must retry a whole
    # fresh observation instead of swallowing filesystem races or access failures.
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC)
    try:
        need(stat.S_ISREG(os.fstat(descriptor).st_mode))
        raw = os.read(descriptor, maximum + 1)
        need(len(raw) <= maximum)
        return raw
    finally:
        os.close(descriptor)


def verify_cgroup_mount(reader=read_file):
    raw = reader(Path("/proc/self/mountinfo"), MAX_COMMAND_BYTES)
    matches = []
    for line in raw.decode("ascii").splitlines():
        before, after = line.split(" - ", 1)
        fields = before.split()
        need(len(fields) >= 6 and len(after.split()) >= 3)
        if fields[4] == str(CGROUP_ROOT):
            matches.append(after.split()[0])
    need(matches == ["cgroup2"])
    controllers = reader(CGROUP_ROOT / "cgroup.controllers").decode("ascii").split()
    need("cpu" in controllers and "memory" in controllers)


def read_command(argv, deadline, clock=time.monotonic, process_factory=subprocess.Popen):
    """One cumulative deadline includes bounded process-group termination/reap."""
    need(deadline - clock() > 1)
    active_deadline = deadline - 1  # Reserve cleanup within the caller's budget.
    process = process_factory(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, env=ENV, close_fds=True,
                              start_new_session=True)
    result = bytearray()
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                left = active_deadline - clock()
                need(left > 0 and selector.select(left))
                block = os.read(process.stdout.fileno(), min(4096, MAX_COMMAND_BYTES + 1 - len(result)))
                if not block:
                    break
                result.extend(block)
                need(len(result) <= MAX_COMMAND_BYTES)
        need(process.wait(timeout=max(.001, active_deadline - clock())) == 0)
        return bytes(result)
    finally:
        process.stdout.close()
        # The process group belongs to this single read operation, not a workload.
        # Kill descendants even when a read-only command's parent exits first.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=max(.001, min(1, deadline - clock())))


def cgroups_empty(boundary, deadline, *, root=CGROUP_ROOT, clock=time.monotonic,
                  reader=read_file, walker=os.walk):
    need(root.is_dir() and not root.is_symlink())
    if root == CGROUP_ROOT:
        verify_cgroup_mount(reader)
    expected = Path(boundary["cgroupPath"])
    # Tests supply an isolated root while retaining the canonical recorded path.
    relative = expected.relative_to(CGROUP_ROOT)
    expected = root / relative
    cursor = root
    for part in relative.parts:
        cursor /= part
        if cursor.exists() or cursor.is_symlink():
            need(cursor.is_dir() and not cursor.is_symlink())
    directories = 0
    total_bytes = 0
    def failed(error):
        raise error
    for raw, dirs, files in walker(root, topdown=True, onerror=failed, followlinks=False):
        need(clock() < deadline)
        directories += 1
        need(directories <= MAX_DIRECTORIES)
        path = Path(raw)
        # Refuse links rather than assuming an unvisited tree is empty.
        need(not path.is_symlink() and all(not (path / name).is_symlink() for name in dirs))
        owned = expected == path or expected in path.parents or any(
            pod_component(part, boundary["podUid"]) for part in path.relative_to(root).parts)
        if not owned:
            continue
        need("cgroup.procs" in files and "cgroup.events" in files)
        procs = reader(path / "cgroup.procs")
        events = reader(path / "cgroup.events")
        total_bytes += len(procs) + len(events)
        need(total_bytes <= MAX_CGROUP_BYTES and not procs.strip())
        counters = {}
        for line in events.decode("ascii").splitlines():
            key, value = line.split()
            need(key not in counters and value.isascii() and value.isdecimal())
            counters[key] = int(value)
        # populated includes descendants, not merely the immediate cgroup.procs.
        need(counters.get("populated") == 0)
    need(clock() < deadline)
    return True


def cri_empty(boundary, deadline, *, runner=read_command):
    containers = document(runner(["/usr/local/bin/k3s", "crictl", "--timeout", "10s", "ps", "-o", "json"], deadline))
    need(type(containers) is dict and type(containers.get("containers")) is list)
    for entry in containers["containers"]:
        need(type(entry) is dict and type(entry.get("id")) is str and re.fullmatch("[a-f0-9]{64}", entry["id"]))
        labels = entry.get("labels")
        need(type(labels) is dict and type(labels.get("io.kubernetes.pod.uid")) is str)
        need(entry["id"] not in boundary["containerIds"] and labels["io.kubernetes.pod.uid"] != boundary["podUid"])
    sandboxes = document(runner(["/usr/local/bin/k3s", "crictl", "--timeout", "10s", "pods", "-o", "json"], deadline))
    need(type(sandboxes) is dict and type(sandboxes.get("items")) is list)
    for entry in sandboxes["items"]:
        need(type(entry) is dict and type(entry.get("metadata")) is dict)
        need(type(entry["metadata"].get("uid")) is str)
        need(entry["metadata"]["uid"] != boundary["podUid"])
    return True


def verify(boundary, budget_millis, *, clock=time.monotonic, boot_reader=None,
           group_probe=cgroups_empty, cri_probe=cri_empty):
    """No exception, reboot, missing file or partial CRI result becomes success."""
    result = "UNCONFIRMED"
    try:
        validate_boundary(boundary)
        need(type(budget_millis) is int and 1001 <= budget_millis <= 60000)
        deadline = clock() + budget_millis / 1000
        boot_reader = boot_reader or (lambda: read_file(BOOT_PATH, 64).decode("ascii").strip())
        before = canonical_uuid(boot_reader())
        need(before == boundary["nodeBootId"])
        need(group_probe(boundary, deadline))
        need(cri_probe(boundary, deadline))
        # Recheck descendants after both CRI lists, then boot identity. Recreation
        # fencing is a separate supervisor/control-plane obligation, not inferred.
        need(group_probe(boundary, deadline))
        need(canonical_uuid(boot_reader()) == before and clock() < deadline)
        result = "BOUNDARY_EMPTY"
    except Exception:
        pass
    return {"profile": PROFILE, "boundary": boundary, "result": result}


def main():
    boundary = None
    try:
        need(sys.platform == "linux" and len(sys.argv) == 4 and sys.argv[1] == "verify")
        need(len(sys.argv[2]) <= (MAX_INPUT + 2) // 3 * 4)
        need(re.fullmatch(r"[1-9][0-9]{0,4}", sys.argv[3]) is not None)
        raw = base64.b64decode(sys.argv[2], validate=True)
        need(len(raw) <= MAX_INPUT)
        boundary = validate_boundary(document(raw))
        reply = verify(boundary, int(sys.argv[3]))
    except Exception:
        reply = {"profile": PROFILE, "boundary": boundary, "result": "UNCONFIRMED"}
    print(json.dumps(reply, separators=(",", ":")))


if __name__ == "__main__":
    main()
