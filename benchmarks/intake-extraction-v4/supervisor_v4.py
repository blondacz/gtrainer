"""Temporary launchd-owned batch supervisor. Never installs a persistent service."""
import argparse
import datetime as dt
import os
from pathlib import Path
import plistlib
import signal
import subprocess
import sys
import tempfile
import time
import uuid

import campaign_v4 as c
import lifecycle_v4 as life

BASE = Path("/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode")


def validate(spec):
    life.need(spec["mode"] in ("live", "synthetic_fake", "offline_tests"))
    life.need(spec["clockIdentity"] == c.clock_identity())
    profile = spec.get("profile", c.e.PROFILE)
    life.need(spec["toolingSha256"] == c.tooling(profile))
    life.need(c.native_clock.monotonic() < spec["workCutoff"] and time.time() < spec["expiresUnix"])
    if spec["mode"] == "live":
        scope = c.private.read_json(Path(spec["campaign"]) / "campaign.json")
        life.need(scope["profile"] == profile)
        life.need(scope["executionMode"] == "live" and scope["toolingSha256"] == spec["toolingSha256"])
        life.need(spec["campaignSha256"] == c.e.digest(c.private.private_read(Path(spec["campaign"]) / "campaign.json")))
        life.need(scope["workCutoffMonotonic"] == spec["workCutoff"])
        approval = c.private.read_json(Path(spec["campaign"]) / "scope-approval.json")
        life.need(c.private.timestamp(approval["expiresUtc"]).timestamp() == spec["expiresUnix"])
    elif spec["mode"] == "offline_tests":
        from durable_tests_v4 import SUITES, fingerprint
        life.need(spec["suite"] in SUITES and type(spec["kotlin"]) is bool and spec["maxAttempts"] == 3)
        life.need(spec["workCutoff"] == spec["startMonotonic"] + 3540)
        life.need(spec["expiresUnix"] == spec["startedUnix"] + 3600)
        life.need(spec["testSourceSha256"] == fingerprint(spec))


def gap(previous_native, previous_wall, native, wall):
    # Scheduling latency is not revoked authority. Both fixed clocks/deadlines
    # still advance; only clock regression/divergence is itself a failure.
    return native < previous_native or abs((wall - previous_wall) - (native - previous_native)) > 2


def phase_rows(directory, spec, startup=None):
    rows = life.records(directory, "phase")
    if spec.get("mode") == "offline_tests":
        life.need(len(rows) <= 1)
        for row in rows:
            life.need(row["name"] == "tests" and row["started"] < row["deadline"] == spec["workCutoff"])
            if startup is not None: life.need(row["started"] < startup["deadline"])
        return rows
    ids = c.v.suites.select(spec.get("profile", c.e.PROFILE)).ids
    prior = startup
    for row in rows:
        name, started, deadline = row["name"], row["started"], row["deadline"]
        maximum = {"preflight": 120, "probe": 900, "validation": 600}.get(name, 900)
        life.need(name in ("preflight", "probe", "validation", *ids))
        life.need(started < deadline <= min(spec["workCutoff"], started + maximum))
        if prior is None or prior["name"] == "startup":
            life.need(name == "preflight")
            if prior is not None: life.need(started < prior["deadline"])
        else:
            life.need(started >= prior["started"])
            life.need(started < prior["deadline"])
            previous = prior["name"]
            if name == "probe": life.need(previous == "preflight")
            elif name == "validation": life.need(previous in ids)
            elif name in ids:
                scored = [r["name"] for r in rows[:rows.index(row)] if r["name"] in ids]
                life.need(name == ids[len(scored)] and previous not in ("preflight",))
            else: life.need(False)
        prior = row
    return rows


def cleanup_ends(directory, native, wall):
    request = directory / "cleanup-request.json"
    if not request.exists(): return native + 60, wall + 60
    row = c.private.read_json(request)
    # Never restart the worker's already-started final cancellation budget.
    life.need(row["nativeEnd"] <= native + 60 and row["wallEnd"] <= wall + 60)
    return row["nativeEnd"], row["wallEnd"]


def quiesce(worker, native_end, wall_end, *, local_end=None):
    import owned_processes_v4 as owned
    # Dedicated group contains worker and its normal subprocess clients, never caller.
    try: os.killpg(worker.pid, signal.SIGKILL)
    except ProcessLookupError: pass
    except PermissionError: life.need(owned.quiescent(worker.pid))
    # Cleanup has its own trusted process-local clock. Campaign clock failure
    # must not prevent SIGKILL/reaping or turn into an inference-clock fallback.
    if local_end is None: local_end = c.cleanup_time() + min(60, max(0, wall_end - time.time()))
    worker.wait(timeout=max(0.01, min(2, local_end - c.cleanup_time(), wall_end - time.time())))
    while c.cleanup_time() < local_end and time.time() < wall_end:
        if owned.quiescent(worker.pid): return
        time.sleep(0.02)
    life.need(False)


def bind(directory, spec):
    binding = c.private.read_json(directory / "binding.json")
    if spec["mode"] == "live":
        batch = c.private.private_directory(Path(binding["batch"]))
        life.need(batch.parent == Path(spec["campaign"]))
        claim = binding["claim"]
        life.need(c.private.read_json(batch.parent / f"claim-{claim['ordinal']:02}.json") == claim)
        life.need(c.private.read_json(batch / "run.json") == binding["run"])
        life.need(c.e.digest(c.e.compact(binding["run"])) == claim["runSha256"])
        life.need(claim["campaignSha256"] == spec["campaignSha256"])
        life.need(binding["run"]["profile"] == spec.get("profile", c.e.PROFILE))
    return binding


def fence_campaign(directory, spec, reason):
    if spec["mode"] != "live": return
    root = c.private.private_directory(Path(spec["campaign"]))
    with life.locked(root / "ledger.lock", nonblocking=True):
        terminal = root / "terminal.json"
        # Never replace an existing immutable decision. Separate supervisor record
        # still discloses abnormal lifecycle failure after a completed receipt.
        if not terminal.exists():
            life.write(terminal, {"version": "intake-conformance-decision-v4", "status": "incomplete",
                "reason": reason, "campaignSha256": spec["campaignSha256"],
                "supervisorEvidence": str(directory), "finishedMonotonic": c.native_clock.monotonic(),
                "evaluationMethod": "deterministic_extractive", "independentHuman": False,
                "humanAssessment": None, "productionAdmission": "closed"})


class Finalizer:
    """One cleanup grant, independent of fallible auxiliary evidence writes."""
    def __init__(self, directory, spec):
        self.directory, self.spec = directory, spec
        self.ends = None
        self.local_end = None
        self.healthy = True
        self.attempted = False
        self.result = None

    def start(self):
        if self.ends is not None: return
        local, wall = c.cleanup_time(), time.time()
        # Capture the bounded emergency stop grant BEFORE parsing/reporting. A
        # malformed request cannot prevent termination or grant a later retry.
        self.ends = (None, wall + 60)
        self.local_end = local + 60
        try:
            native = c.native_clock.monotonic()
            self.ends = cleanup_ends(self.directory, native, wall)
            self.local_end = min(self.local_end, local + max(0, self.ends[0] - native),
                                 local + max(0, self.ends[1] - wall))
        except BaseException: self.healthy = False
        self.record("admission-closed.json", {"closed": True})

    def record(self, name, value):
        try:
            life.write(self.directory / name, value)
            return True
        except BaseException:
            self.healthy = False
            return False

    def cancel(self):
        self.start()
        life.need(not self.attempted)
        self.attempted = True
        self.result = perform_cleanup(self.directory, self.spec, *self.ends,
                                      allow_restore=self.healthy, local_end=self.local_end)
        self.result["verified"] = self.result["verified"] and self.healthy
        if not self.record("cleanup-result.json", self.result): self.result["verified"] = False

    def stop(self, worker):
        self.start()
        try:
            # Kill is attempted even with corrupt timing/evidence. No cleanup
            # mutation starts until the worker and its mutation clients stop.
            quiesce(worker, *self.ends, local_end=self.local_end)
            if not self.attempted: self.cancel()
        except BaseException:
            self.result = {"verified": False, "reason": "quiescence_or_cleanup_unverified"}
            if not (self.directory / "cleanup-result.json").exists(): self.record("cleanup-result.json", self.result)


def serve(directory):
    directory = c.private.private_directory(directory)
    spec = c.private.read_json(directory / "job.json")
    validate(spec)
    if spec.get("parentOwner"):
        import owned_processes_v4 as owned
        if owned.closed(c.private.private_directory(Path(spec["parentOwner"]))):
            life.write(directory / "outcome.json", {"reason": "parent_closed_before_start",
                "cleanup": {"verified": True, "noMutations": True}, "cleanupInvocations": 0,
                "workerExitCode": None, "productionAdmission": "closed"})
            return
    life.need(os.getppid() == 1)
    with life.locked(directory / "supervisor.lock", nonblocking=True):
        # Durable authority is accepted before launchd starts this job. A caller
        # is only an observer: its timeout, exit or disconnection is not cancel.
        start = c.private.read_json(directory / "start-request.json")
        life.need(start == control_request(directory, spec))
        life.write(directory / "ready.json", {"jobSha256": c.e.digest(c.private.private_read(directory / "job.json")),
            "clockIdentity": c.clock_identity(), "launchdParent": os.getppid(), "supervisorPid": os.getpid()})
        worker = None; reason = None; expired_phase = None
        finalizer = Finalizer(directory, spec)
        try:
            validate(spec)
            if spec["mode"] == "live":
                selected = c.v.suites.select(spec.get("profile", c.e.PROFILE))
                command = [sys.executable, str(selected.here / ("run_mac_" + selected.edition + ".py")), "--directory", spec["campaign"],
                           "--revision", spec["revision"], "--supervised-worker", str(directory)]
            elif spec["mode"] == "offline_tests":
                command = [sys.executable, str(c.e.HERE / "durable_tests_v4.py"), "--worker", str(directory)]
            else:
                life.need(spec["scenario"] in ("normal", "delayed", "hang", "locked", "exit", "cleanup_hang", "generation_expiry"))
                command = [sys.executable, str(c.e.HERE / "supervisor_fixture_v4.py"), str(directory), spec["scenario"]]
            with open(directory / "worker.log", "xb", buffering=0) as output:
                os.chmod(directory / "worker.log", 0o600)
                environment = dict(os.environ, INTAKE_V4_PROCESS_OWNER_DIR=str(directory))
                worker = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output,
                                          stderr=output, start_new_session=True, env=environment)
            life.write(directory / "worker.json", {"pid": worker.pid, "processGroup": worker.pid})
            last_native, last_wall = c.native_clock.monotonic(), time.time()
            phase = {"name": "startup", "deadline": min(last_native + 30, spec["workCutoff"])}
            startup = dict(phase)
            phases_seen = 0
            while True:
                now, wall = c.native_clock.monotonic(), time.time()
                if gap(last_native, last_wall, now, wall):
                    reason = "supervisor_suspend_or_clock_gap"; break
                last_native, last_wall = now, wall
                if spec.get("parentOwner"):
                    import owned_processes_v4 as owned
                    if owned.closed(Path(spec["parentOwner"])):
                        reason = "owner_admission_closed"; break
                if (directory / "cancel-request.json").exists():
                    life.need(c.private.read_json(directory / "cancel-request.json") == control_request(directory, spec))
                    reason = "explicit_cancel"; break
                if spec.get("recoveryRoot"):
                    import recovery_v4
                    if recovery_v4.cancelled(Path(spec["recoveryRoot"])):
                        reason = "explicit_cancel"; break
                    if (Path(spec["recoveryRoot"]) / "outcome.json").exists():
                        reason = "outer_authority_closed"; break
                rows = phase_rows(directory, spec, startup)
                if len(rows) > phases_seen:
                    life.need(not finalizer.attempted)
                    phase = rows[-1]; phases_seen = len(rows)
                    life.need(phase["started"] <= now and phase["deadline"] <= spec["workCutoff"])
                    life.need(phase["started"] < phase["deadline"])
                if now >= min(phase["deadline"], spec["workCutoff"]) or wall >= spec["expiresUnix"]:
                    expired_phase = dict(phase)
                    expired_phase["trigger"] = "approval_expiry" if wall >= spec["expiresUnix"] else "work_cutoff" if now >= spec["workCutoff"] else "phase"
                    reason = "supervisor_deadline"; break
                if (directory / "cleanup-request.json").exists() and not finalizer.attempted:
                    # The worker started the one final cleanup timer before asking.
                    finalizer.cancel()
                    phase = {"name": "report", "deadline": min(c.native_clock.monotonic() + 30, spec["workCutoff"])}
                    last_native, last_wall = c.native_clock.monotonic(), time.time()
                if worker.poll() is not None:
                    if worker.returncode == 0 and finalizer.attempted:
                        reason = "normal"; break
                    reason = "supervisor_worker_lost"; break
                if now >= min(phase["deadline"], spec["workCutoff"]) or wall >= spec["expiresUnix"]:
                    expired_phase = dict(phase)
                    expired_phase["trigger"] = "approval_expiry" if wall >= spec["expiresUnix"] else "work_cutoff" if now >= spec["workCutoff"] else "phase"
                    reason = "supervisor_deadline"; break
                time.sleep(0.05)
        except BaseException:
            reason = reason or "supervisor_state_or_admission_failure"
        finally:
            # None of these reporting failures can skip bounded quiescence.
            finalizer.start()
            if reason != "normal":
                finalizer.record("fenced.json", {"reason": reason})
            if worker is not None: finalizer.stop(worker)
            if reason != "normal":
                try: fence_campaign(directory, spec, reason)
                except BaseException:
                    finalizer.record("terminal-fence-failure.json", {"verified": False})
            if finalizer.result is not None and not finalizer.healthy: finalizer.result["verified"] = False
            finalizer.record("outcome.json", {"reason": reason, "cleanup": finalizer.result,
                "workerExitCode": None if worker is None else worker.returncode,
                "cleanupInvocations": int(finalizer.attempted), "evidenceHealthy": finalizer.healthy,
                "expiredPhase": expired_phase,
                "productionAdmission": "closed"})


def control_request(directory, spec):
    return {"jobSha256": c.e.digest(c.private.private_read(directory / "job.json")), "nonce": spec["nonce"]}


def cancel(directory):
    directory = c.private.private_directory(directory)
    spec = c.private.read_json(directory / "job.json")
    request = control_request(directory, spec)
    path = directory / "cancel-request.json"
    try: life.write(path, request)
    except FileExistsError: life.need(c.private.read_json(path) == request)
    return "cancellation_requested"


def status(directory):
    """Read-only attachment, even after source changes. Never opens Campaign."""
    directory = c.private.private_directory(directory)
    result = {"directory": str(directory), "state": "unknown", "outcome": None}
    if (directory / "outcome.json").exists():
        result.update(state="finished", outcome=c.private.read_json(directory / "outcome.json"))
    elif (directory / "worker.json").exists():
        result.update(state="started_outcome_pending", worker=c.private.read_json(directory / "worker.json"))
    elif (directory / "ready.json").exists(): result["state"] = "ready_outcome_pending"
    if result["state"] != "finished":
        rows = sorted(directory.glob("attempt-*-result.json"))
        intents = sorted(directory.glob("attempt-*-intent.json"))
        if rows and len(rows) == len(intents) and c.private.read_json(rows[-1])["classification"] in ("test_failure", "source_changed_during_attempt"):
            result["state"] = "awaiting_repair"
    # Pending is deliberately not proof of liveness or permission to restart.
    phases = sorted(directory.glob("phase-*.json"))
    if phases: result["phase"] = c.private.read_json(phases[-1])
    return result


def perform_cleanup(directory, spec, native_end, wall_end, *, allow_restore=True, local_end=None):
    try:
        with life.locked(directory / "mutation.lock", nonblocking=True):
            healthy = allow_restore
            try: life.write(directory / "cleanup-started.json", {"nativeEnd": native_end, "wallEnd": wall_end})
            except BaseException: healthy = False
            binding = bind(directory, spec)
            import owned_processes_v4 as owned
            try:
                clients_stopped = owned.cleanup(directory, local_end if local_end is not None else time.monotonic() + max(0, wall_end-time.time()), wall_end)
            except BaseException: clients_stopped = False
            healthy = healthy and clients_stopped
            request = directory / "cleanup-request.json"
            if request.exists():
                try: life.need(c.private.read_json(request)["runSha256"] == c.e.digest(c.e.compact(binding["run"])))
                except BaseException: healthy = False
            if spec["mode"] != "live":
                # Synthetic fixtures never create a MacRuntime or invoke kubectl.
                life.write(directory / "synthetic-cleanup.json", {"newInferenceRequests": 0})
                return {"verified": healthy, "syntheticOnly": spec["mode"] == "synthetic_fake",
                        "offlineOnly": spec["mode"] == "offline_tests"}
            result = life.cleanup(directory, binding, native_end, wall_end,
                                  allow_restore=healthy, local_end=local_end)
            result["verified"] = result["verified"] and healthy
            return result
    except BaseException:
        return {"verified": False, "reason": "ownership_or_cleanup_unverified"}


def prepare(directory, spec):
    directory = c.private.private_directory(directory)
    import owned_processes_v4 as owned
    parent = owned.owner()
    if parent is not None:
        life.need(spec["mode"] != "live" and not owned.closed(parent))
        spec = dict(spec, parentOwner=str(parent))
    life.write(directory / "job.json", spec)
    life.write(directory / "start-request.json", control_request(directory, spec))
    label = "com.gtrainer.intake-v4." + uuid.uuid4().hex
    job = {"Label": label, "ProgramArguments": [sys.executable, str(c.e.HERE / "supervisor_v4.py"), str(directory)],
        "RunAtLoad": True, "KeepAlive": False, "ProcessType": "Background", "Umask": 0o077,
        "WorkingDirectory": str(c.e.ROOT), "EnvironmentVariables": {"PATH": "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"},
        "StandardOutPath": str(directory / "supervisor.log"), "StandardErrorPath": str(directory / "supervisor.log")}
    c.private.private_write(directory / "job.plist", plistlib.dumps(job))
    # Launchd opens existing private files, rather than inheriting a managed pipe.
    c.private.private_write(directory / "supervisor.log", b"")
    domain = "gui/" + str(os.getuid())
    life.write(directory / "launchd.json", {"domain": domain, "label": label})
    if parent is not None: owned.register_child(parent, directory)
    completed = subprocess.run(["/bin/launchctl", "bootstrap", domain, str(directory / "job.plist")],
                               capture_output=True, timeout=10)
    life.need(completed.returncode == 0)
    return domain + "/" + label


class Observer:
    def close(self): pass


def connect(directory):
    """Compatibility observer handshake; closing it never changes authority."""
    end = time.monotonic() + 20
    while not (directory / "ready.json").exists():
        life.need(time.monotonic() < end)
        time.sleep(0.05)
    spec = c.private.read_json(directory / "job.json")
    ready = c.private.read_json(directory / "ready.json")
    life.need(ready["jobSha256"] == c.e.digest(c.private.private_read(directory / "job.json")))
    life.need(ready["launchdParent"] == 1 and ready["clockIdentity"] == c.clock_identity())
    return Observer()


def run(campaign, revision, *, wait=False):
    # Validate without spending a claim; worker opens the ledger only once.
    with c.Campaign(campaign) as checked:
        life.need(checked.scope["executionMode"] == "live")
        life.need(not (checked.directory / "terminal.json").exists())
        scope, approval, ordinal = checked.scope, checked.approval, len(checked.receipts) + 1
    directory = Path(tempfile.mkdtemp(prefix="v4w-", dir=BASE))
    spec = {"mode": "live", "campaign": str(campaign.absolute()), "revision": str(revision.absolute()),
        "campaignSha256": c.e.digest(c.private.private_read(campaign / "campaign.json")),
        "clockIdentity": scope["clockIdentity"], "workCutoff": scope["workCutoffMonotonic"],
        "expiresUnix": c.private.timestamp(approval["expiresUtc"]).timestamp(),
        "toolingSha256": c.tooling(scope["profile"]), "nonce": uuid.uuid4().hex}
    if scope["profile"] != c.e.PROFILE: spec["profile"] = scope["profile"]
    if scope.get("recovery"): spec["recoveryRoot"] = scope["recovery"]["root"]
    life.write(campaign / f"supervisor-{ordinal:02}.json", {"directory": str(directory)})
    target = prepare(directory, spec)
    client = connect(directory)
    if not wait:
        client.close()
        return "started; durable job " + str(directory)
    try:
        while not (directory / "outcome.json").exists(): time.sleep(0.2)
        outcome = c.private.read_json(directory / "outcome.json")
        life.need(outcome["reason"] == "normal" and outcome["cleanup"]["verified"] is True)
        report = c.private.read_json(campaign / f"batch-{ordinal:02}" / "report.json")
        return report["classification"]
    finally:
        client.close()
        # Never bootout a still-running supervisor; it must retain cleanup authority.
        if (directory / "outcome.json").exists():
            subprocess.run(["/bin/launchctl", "bootout", target], capture_output=True, timeout=10)


if __name__ == "__main__":
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--status", action="store_true")
    action.add_argument("--cancel", action="store_true")
    args = parser.parse_args()
    if args.status: print(c.e.compact(status(args.directory)).decode())
    elif args.cancel: print(cancel(args.directory))
    else: serve(args.directory)
