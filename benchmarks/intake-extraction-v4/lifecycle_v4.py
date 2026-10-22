"""Write-ahead ownership and cleanup-only takeover for supervised v4 batches.

No inference, launchd registration or recovery occurs on import. Historical runs
are never adopted. Every journal is exclusive to one newly claimed batch.
"""
import contextlib
import copy
import ctypes
import fcntl
import math
import os
from pathlib import Path
import stat
import time
import uuid

import runtime_v4 as v
import native_clock_v4 as clock

e, private = v.e, v.replay.private


def need(value):
    if not value: raise ValueError("V4 lifecycle fenced")


def publish(source, destination):
    """macOS atomic, no-overwrite publication; no hard-link visibility window.

    RENAME_EXCL=0x4 is declared in the installed SDK's sys/stdio.h. Unsupported
    hosts/filesystems fail closed rather than using an overwriting rename.
    """
    rename = ctypes.CDLL(None, use_errno=True).renamex_np
    rename.argtypes = (ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint)
    rename.restype = ctypes.c_int
    if rename(os.fsencode(source), os.fsencode(destination), 0x4) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error), str(destination))


def write(path, value):
    temporary = path.parent / (".unpublished-" + uuid.uuid4().hex)
    try:
        private.private_write(temporary, e.compact(value))
        publish(temporary, path)
    finally:
        try: temporary.unlink()
        except FileNotFoundError: pass
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try: os.fsync(fd)
    finally: os.close(fd)


@contextlib.contextmanager
def locked(path, nonblocking=False):
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        need(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600 and info.st_nlink == 1)
        fcntl.flock(fd, fcntl.LOCK_EX | (fcntl.LOCK_NB if nonblocking else 0))
        yield
    finally: os.close(fd)


def records(directory, prefix):
    paths = sorted(directory.glob(prefix + "-*.json"))
    need(len(paths) <= 1024)
    need([p.name for p in paths] == [f"{prefix}-{i:04}.json" for i in range(1, len(paths) + 1)])
    return [private.read_json(path) for path in paths]


class Worker:
    """Fixed-deadline worker channel. No authority from an environment variable."""
    def __init__(self, directory, campaign_directory):
        self.directory = private.private_directory(directory)
        self.spec = private.read_json(self.directory / "job.json")
        self.suite = v.suites.select(self.spec.get("profile", e.PROFILE))
        ready = private.read_json(self.directory / "ready.json")
        need(self.spec["mode"] == "live" and self.spec["campaign"] == str(campaign_directory))
        need(ready["jobSha256"] == e.digest(private.private_read(self.directory / "job.json")))
        need(ready["clockIdentity"] == clock.identity() and ready["launchdParent"] == 1)
        need(ready["supervisorPid"] == os.getppid())
        self.supervisor_pid = ready["supervisorPid"]
        self.index = 0
        self.guard()

    def guard(self):
        need(not (self.directory / "fenced.json").exists())
        need(os.getppid() == self.supervisor_pid)
        need(clock.identity() == self.spec["clockIdentity"])
        need(clock.monotonic() < self.spec["workCutoff"] and time.time() < self.spec["expiresUnix"])

    def attach(self, run, claim, batch):
        self.guard()
        need(run["profile"] == self.suite.profile and run["caseIds"] == self.suite.ids)
        write(self.directory / "binding.json", {"run": run, "claim": claim, "batch": str(batch)})
        self.run = run

    def phase(self, name, deadline):
        self.guard()
        need(name in ("preflight", "probe", *self.suite.ids, "validation", "report"))
        need(type(deadline) in (int, float) and math.isfinite(deadline))
        now = clock.monotonic()
        maximum = {"preflight": 120, "probe": 900, "validation": 600, "report": 30}.get(name, 900)
        need(now < deadline <= min(self.spec["workCutoff"], now + maximum))
        self.index += 1
        write(self.directory / f"phase-{self.index:04}.json", {"name": name, "deadline": deadline, "started": now})

    def cancel(self, run, deadline, emit):
        # Do not use inference guard: expiry cannot revoke already owned cleanup.
        need(run == self.run)
        native_before, wall_before = clock.monotonic(), time.time()
        remaining = min(60, deadline - time.monotonic())
        need(remaining > 0)
        write(self.directory / "cleanup-request.json", {"runSha256": e.digest(e.compact(run)),
            "nativeEnd": native_before + remaining, "wallEnd": wall_before + remaining})
        end = time.monotonic() + 65
        while time.monotonic() < end:
            if (self.directory / "cleanup-result.json").exists():
                result = private.read_json(self.directory / "cleanup-result.json")
                emit({"type": "supervisor_cleanup", "verified": result["verified"]})
                return {"verified": result["verified"]}
            time.sleep(0.05)
        return {"verified": False}


class Journal:
    def __init__(self, worker):
        self.worker, self.directory = worker, worker.directory
        self.index = 0
        self.original = None
        self.armed = False
        self.last = None

    @contextlib.contextmanager
    def mutation(self):
        with locked(self.directory / "mutation.lock"):
            self.worker.guard()
            yield

    def state(self, runtime, operation):
        state = dict(runtime.safety_state(), originalDeployment=self.original)
        if state == self.last and operation == "observed": return
        self.index += 1
        need(self.index <= 1024)
        write(self.directory / f"ownership-{self.index:04}.json", {
            "operation": operation, "state": state,
            "runSha256": e.digest(e.compact(self.worker.run)), "nativeTime": clock.monotonic()})
        self.last = copy.deepcopy(state)


class JournalRuntime(v.Runtime):
    """V4-only hooks; shared v1/v3 request and stop implementations stay unchanged."""
    def __init__(self, *args, lifecycle, **kwargs):
        self.journal = Journal(lifecycle)
        super().__init__(*args, **kwargs)

    def scale_deployment(self, deployment, replicas, current):
        need(replicas == 0 and current == 1 and self.journal.original is None)
        with self.journal.mutation():
            self.journal.original = copy.deepcopy(deployment)
            self.journal.armed = True
            self.journal.state(self, "pause_pending")
            super().scale_deployment(deployment, replicas, current)
            self.paused = True
            self.journal.state(self, "pause_applied")

    def create(self, run, deadline=None):
        with self.journal.mutation():
            return super().create(run, deadline)

    def kube(self, args, body=None, timeout=15):
        if args[:2] == ["create", "-f"]:
            need(self.journal.armed and self.pending_pod is not None)
            self.journal.state(self, "create_pending")
        return super().kube(args, body, timeout)

    def bind_pending_pod(self, document):
        super().bind_pending_pod(document)
        self.journal.state(self, "create_bound")

    def pod_status(self):
        result = super().pod_status()
        if self.journal.armed: self.journal.state(self, "observed")
        return result

    def stop_pod(self, deadline, emit):
        with self.journal.mutation():
            self.journal.state(self, "probe_stop_pending")
            super().stop_pod(deadline, emit)
            self.journal.state(self, "probe_stopped")

    def cancel(self, run, deadline, emit):
        try:
            result = self.journal.worker.cancel(run, deadline, emit)
            if result["verified"]:
                self.pod = self.pending_pod = None
                self.paused = self.pause_pending = self.restore_pending = False
            return result
        finally: self.closed = True


class CleanupRuntime(v.mac.MacRuntime):
    """Loaded before admission, cleanup-only, original full-spec constrained."""
    def __init__(self, original, wall_end):
        super().__init__()
        self.original, self.wall_end = original, wall_end
        self.allow_restore = True

    def command(self, args, body=None, timeout=15):
        remaining = self.wall_end - time.time()
        need(remaining > 0)
        return super().command(args, body, min(timeout, remaining))

    def baseline(self):
        need(self.allow_restore)
        document = super().baseline()
        expected = copy.deepcopy(self.original["spec"])
        need(document["metadata"]["uid"] == self.original["metadata"]["uid"])
        expected["replicas"] = document["spec"]["replicas"]
        need(document["spec"]["replicas"] in (0, 1) and document["spec"] == expected)
        if document["spec"]["replicas"] == 0:
            # Called by shared cancel after owned stop, before restoration. Never
            # introduce a shared replica alongside an unknown/unowned workload.
            need(self.document(["get", "pods"])["items"] == [])
        return document


def cleanup(directory, binding, native_end, wall_end, *, factory=CleanupRuntime, allow_restore=True, local_end=None):
    """One invocation. Caller quiesces worker first on abnormal takeover."""
    rows = records(directory, "ownership")
    run = binding["run"]
    need(all(row["runSha256"] == e.digest(e.compact(run)) for row in rows))
    if not rows:
        return {"verified": True, "noMutations": True}
    state = rows[-1]["state"]
    original = state["originalDeployment"]
    need(original["spec"]["replicas"] == 1)
    runtime = factory(original, wall_end)
    runtime.allow_restore = allow_restore
    # Convert remaining duration once to the unchanged authoritative local clock.
    before = time.monotonic()
    remaining = min(60, wall_end - time.time())
    if local_end is not None: remaining = min(remaining, local_end - before)
    try:
        need(native_end is not None)
        remaining = min(remaining, native_end - clock.monotonic())
    except BaseException:
        # Separate local cleanup authority survives campaign-clock failure. This
        # permits owned stopping only, never inference or uncertain restoration.
        runtime.allow_restore = False
    need(remaining > 0)
    runtime.deadline = before + remaining
    for key, attr in (("pod", "pod"), ("pendingPod", "pending_pod"), ("paused", "paused"),
                      ("pausePending", "pause_pending"), ("restorePending", "restore_pending")):
        setattr(runtime, attr, copy.deepcopy(state[key]))
    if runtime.pending_pod is not None:
        # Shared stop_pod refuses absence and verifies name/run/token before bind.
        raw = runtime.kube(["get", "pod", runtime.pending_pod["name"], "--ignore-not-found", "-o", "json"])
        need(bool(raw.strip()))
        runtime.bind_pending_pod(e.decode(raw))
    if runtime.pod is not None:
        raw = runtime.kube(["get", "pod", runtime.pod["name"], "--ignore-not-found", "-o", "json"])
        if raw.strip():
            doc = e.decode(raw)
            need(doc["metadata"]["uid"] == runtime.pod["uid"])
            need(doc["metadata"].get("labels", {}).get("gtrainer-scoped-run") == run["runId"])
    events = []
    def emit(event):
        events.append(event)
        # Shared stop emits after cgroup/CRI proof and BEFORE any restoration.
        # A failed proof publication stops restoration, not owned termination.
        write(directory / f"cleanup-event-{len(events):04}.json", event)
    try:
        # Shared cancel stops owned execution BEFORE evaluating restoration gates.
        # Thus deployment/spec/pressure drift cannot strand a known owned model.
        # It refuses replicas=1 for pending pause, without replaying the mutation.
        result = runtime.cancel(run, runtime.deadline, emit)
    finally:
        try: write(directory / "cleanup-stop-evidence.json", {"events": events, "state": runtime.safety_state()})
        except BaseException: need(False)
    restored = runtime.baseline()
    need(restored["spec"] == original["spec"] and restored["status"]["readyReplicas"] == 1)
    pods = runtime.document(["get", "pods"])["items"]
    need(len(pods) == 1 and pods[0]["metadata"].get("labels", {}).get("app") == "ollama")
    need(pods[0]["status"]["containerStatuses"][0]["ready"])
    runtime.pod = {"name": pods[0]["metadata"]["name"], "uid": pods[0]["metadata"]["uid"]}
    try: need(runtime.api("ps")["models"] == [])
    finally: runtime.pod = None
    need(time.monotonic() <= runtime.deadline and time.time() <= wall_end)
    need(native_end is not None and clock.monotonic() <= native_end)
    return {"verified": result.get("verified") is True, "exactOriginalSpec": True,
            "oneReadySharedReplica": True, "modelUnloaded": True}
