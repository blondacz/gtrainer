"""Three-slot durable model recovery authority. No old claim is ever reopened."""
import argparse
import datetime as dt
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import time
import uuid

# Executed from the immutable private plist, before importing checkout code.
# Thus even a syntax error in this module cannot bypass restart expenditure.
BOOTSTRAP = r'''
import ctypes,fcntl,json,os,runpy,stat,sys,uuid
from pathlib import Path
root=Path(sys.argv[1]);entry=sys.argv[2];spent=False;index=0
try:
 if not root.is_dir() or root.is_symlink() or stat.S_IMODE(root.stat().st_mode)!=0o700:sys.exit(0)
 if (root/'outcome.json').exists():sys.exit(0)
 fd=os.open(root/'bootstrap.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
 fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
 rows=sorted(root.glob('coordinator-start-*.json'));index=len(rows)+1
 if index>3:sys.exit(0)
 destination=root/('coordinator-start-%02d.json'%index);temporary=root/('.bootstrap-'+uuid.uuid4().hex)
 out=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
 with os.fdopen(out,'wb') as stream:
  stream.write(json.dumps({'pid':os.getpid()}).encode());stream.flush();os.fsync(stream.fileno())
 rename=ctypes.CDLL(None,use_errno=True).renamex_np
 rename.argtypes=(ctypes.c_char_p,ctypes.c_char_p,ctypes.c_uint);rename.restype=ctypes.c_int
 if rename(os.fsencode(temporary),os.fsencode(destination),4)!=0:raise OSError(ctypes.get_errno())
 directory=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW);os.fsync(directory);os.close(directory)
 spent=True;os.close(fd)
 sys.path.insert(0,str(Path(entry).parent))
 sys.argv=[entry,'--coordinate',str(root),'--bootstrap-record',str(destination)]
 runpy.run_path(entry,run_name='__main__')
except SystemExit as error:
 sys.exit(1 if spent and index<3 and error.code not in (None,0) else 0)
except BaseException:
 sys.exit(1 if spent and index<3 else 0)
'''

import campaign_v4 as c
import lifecycle_v4 as life


def authority(binding, mode):
    root = c.private.private_directory(Path(binding["root"]))
    raw = c.private.private_read(root / "authority.json"); scope = c.e.decode(raw)
    life.need(binding["authoritySha256"] == c.e.digest(raw))
    life.need(scope["version"] == "intake-recovery-authority-v1" and scope["mode"] == mode)
    life.need(not (root / "outcome.json").exists() and not cancelled(root))
    life.need(scope["limits"] == c.LIMITS and scope["clockIdentity"] == c.clock_identity())
    paths = sorted(root.glob("reservation-*.json"))
    life.need([p.name for p in paths] == [f"reservation-{i:02}.json" for i in range(1, len(paths) + 1)] and len(paths) <= 3)
    index = binding["slot"]; life.need(type(index) is int and 1 <= index <= len(paths))
    reservation = c.private.read_json(root / f"reservation-{index:02}.json")
    life.need(binding["reservationSha256"] == c.e.digest(c.e.compact(reservation)))
    life.need(reservation["sourceSha256"] == c.tooling() and reservation["scoredReserved"] == 8 and reservation["probesReserved"] == 1)
    return scope


def consumer(binding, directory=None, campaign_id=None, *, spend=False):
    root = c.private.private_directory(Path(binding["root"]))
    row = c.private.read_json(root / f"reservation-{binding['slot']:02}.json")
    if directory is not None: life.need(str(Path(directory).resolve()) == row["campaignDirectory"])
    if campaign_id is not None: life.need(campaign_id == row["campaignId"])
    proof = {"campaignDirectory": row["campaignDirectory"], "campaignId": row["campaignId"],
             "reservationSha256": binding["reservationSha256"]}
    path = root / f"consumer-{binding['slot']:02}.json"
    if spend: life.write(path, proof)
    else: life.need(c.private.read_json(path) == proof)
    return row["campaignId"]


def reserve(root, revision):
    scope = c.private.read_json(root / "authority.json")
    life.need(not (root / "outcome.json").exists())
    life.need(c.native_clock.monotonic() < scope["startMonotonic"] + 14400 - 240)
    life.need(time.time() < scope["expiresUnix"] and not (root / "cancel.json").exists())
    index = len(list(root.glob("reservation-*.json"))) + 1
    life.need(index <= 3)
    if index > 1:
        previous = c.private.read_json(root / f"attempt-{index-1:02}.json")
        life.need(previous["cleanupVerified"])
        old = c.private.read_json(root / f"reservation-{index-1:02}.json")
        if c.tooling() != old["sourceSha256"]:
            submitted = c.private.read_json(root / f"repair-{index-1:02}.json")
            life.need(previous.get("repairEligible") and submitted["sourceSha256"] == c.tooling())
            life.need(submitted["previousAttemptSha256"] == c.e.digest(c.e.compact(previous)))
            life.need(submitted["revision"] == str(revision))
        else: life.need(previous["retryEligible"] and str(revision) == old["revision"])
    row = {"slot": index, "revision": str(revision), "sourceSha256": c.tooling(),
           "scoredReserved": 8, "probesReserved": 1,
           "campaignDirectory": str((root / f"run-{index:02}" / "campaign").resolve()),
           "campaignId": str(uuid.uuid4())}
    life.write(root / f"reservation-{index:02}.json", row)
    return {"root": str(root), "slot": index,
        "authoritySha256": c.e.digest(c.private.private_read(root / "authority.json")),
        "reservationSha256": c.e.digest(c.e.compact(row))}


def once(root, index):
    """Fresh interpreter: repairs cannot leave stale creation code in memory."""
    import supervisor_v4 as supervisor
    row = c.private.read_json(root / f"reservation-{index:02}.json")
    binding = {"root": str(root), "slot": index,
        "authoritySha256": c.e.digest(c.private.private_read(root / "authority.json")),
        "reservationSha256": c.e.digest(c.e.compact(row))}
    scope = authority(binding, "live")
    directory = root / f"run-{index:02}"; directory.mkdir(mode=0o700)
    campaign = directory / "campaign"; campaign.mkdir(mode=0o700)
    with c.Campaign.create(campaign, "live", "primary-authorized recovery coordinator",
                           scope["userInstruction"], recovery=binding): pass
    supervisor.run(campaign, Path(row["revision"]))


def cancelled(root):
    path = root / "cancel.json"
    if not path.exists(): return False
    life.need(c.private.read_json(path)["authoritySha256"] == c.e.digest(c.private.private_read(root / "authority.json")))
    return True


def terminal(root, status, reason):
    life.write(root / "outcome.json", {"status": status, "reason": reason,
        "reservedBatchSlots": len(list(root.glob("reservation-*.json"))),
        "productionAdmission": "closed", "independentHuman": False, "humanAssessment": None})


def coordinate(root, bootstrap_record=None):
    """Launchd may restart this coordinator, never the cleanup supervisor."""
    with life.locked(root / "coordinator.lock", nonblocking=True):
        if (root / "outcome.json").exists(): return
        if bootstrap_record is None:
            starts = len(list(root.glob("coordinator-start-*.json"))) + 1
            if starts > 3:
                terminal(root, "incomplete", "coordinator_restart_budget_exhausted"); return
            life.write(root / f"coordinator-start-{starts:02}.json", {"pid": os.getpid()})
        else:
            life.need(bootstrap_record.parent == root and c.private.read_json(bootstrap_record)["pid"] == os.getpid())
            life.need(0 < len(list(root.glob("coordinator-start-*.json"))) <= 3)
        scope = c.private.read_json(root / "authority.json")
        revision = Path(scope["revision"])
        while True:
            if cancelled(root):
                slots = sorted(root.glob("reservation-*.json"))
                if not slots or (root / f"attempt-{len(slots):02}.json").exists():
                    terminal(root, "incomplete", "explicit_cancel"); return
            slots = sorted(root.glob("reservation-*.json"))
            if slots and not (root / f"attempt-{len(slots):02}.json").exists():
                index = len(slots)  # Attach to spent reservation. Never refund it.
            else:
                if slots:
                    previous = c.private.read_json(root / f"attempt-{len(slots):02}.json")
                    if previous["classification"] == "passed": terminal(root, "passed", "all_eight_single_attempt_passed"); return
                    if previous["classification"] == "quality_failure": terminal(root, "failed", "complete_quality_failure_not_infrastructure"); return
                    if not previous["cleanupVerified"] or not (previous["retryEligible"] or previous["repairEligible"]):
                        terminal(root, "incomplete", "unrecoverable_or_unknown_failure"); return
                    old = c.private.read_json(slots[-1]); revision = Path(old["revision"])
                    if not previous["retryEligible"] or c.tooling() != old["sourceSha256"]:
                        repair_path = root / f"repair-{len(slots):02}.json"
                        attention = root / f"attention-{len(slots):02}.json"
                        if not attention.exists(): life.write(attention, {"state": "awaiting_verified_repair", "attempt": len(slots)})
                        while not repair_path.exists():
                            if cancelled(root) or time.time() >= scope["expiresUnix"] - 240 or c.native_clock.monotonic() >= scope["startMonotonic"] + 14160:
                                terminal(root, "incomplete", "repair_wait_authority_exhausted"); return
                            time.sleep(0.2)
                        revision = Path(c.private.read_json(repair_path)["revision"])
                if cancelled(root): terminal(root, "incomplete", "explicit_cancel"); return
                if c.native_clock.monotonic() >= scope["startMonotonic"] + 14160 or time.time() >= scope["expiresUnix"]:
                    terminal(root, "incomplete", "outer_budget_exhausted"); return
                if len(slots) >= 3: terminal(root, "incomplete", "attempt_budget_exhausted"); return
                binding = reserve(root, revision); index = binding["slot"]
                # Intent is durable before spawn. If coordinator dies in this
                # window, replacement only attaches; ambiguity never double-starts.
                life.write(root / f"spawn-{index:02}.json", {"reservation": binding})
                result = subprocess.run([sys.executable, str(c.e.HERE / "recovery_v4.py"),
                    "--once", str(root), "--slot", str(index)], capture_output=True, timeout=60)
                life.write(root / f"spawn-result-{index:02}.json", {"exitCode": result.returncode})
                life.need(len(result.stdout) + len(result.stderr) <= 1024 * 1024)
                c.private.private_write(root / f"spawn-{index:02}.log", result.stdout + result.stderr)
            campaign = root / f"run-{index:02}" / "campaign"
            pointer = campaign / "supervisor-01.json"
            if not pointer.exists(): terminal(root, "incomplete", "uncertain_reserved_spawn"); return
            job = Path(c.private.read_json(pointer)["directory"])
            while not (job / "outcome.json").exists():
                if cancelled(root):
                    import supervisor_v4 as supervisor
                    supervisor.cancel(job)
                if time.time() > scope["expiresUnix"] + 60:
                    terminal(root, "incomplete", "supervisor_fate_unknown"); return
                time.sleep(0.2)
            outcome = c.private.read_json(job / "outcome.json")
            report_path = campaign / "batch-01/report.json"
            report = c.private.read_json(report_path) if report_path.exists() else None
            verified = outcome["cleanup"] is not None and outcome["cleanup"].get("verified") is True
            complete = complete_report(campaign, report)
            known_quality = complete and report["classification"] == "quality_failure"
            passed = outcome["reason"] == "normal" and verified and complete and report["classification"] == "passed"
            quality = verified and known_quality
            failure = c.private.read_json(campaign / "batch-01/failure.json")["category"] if (campaign / "batch-01/failure.json").exists() else None
            safe_error = failure in (None, "timeout", "infrastructure_or_state_failure")
            retry = verified and not known_quality and safe_error and not cancelled(root) and (outcome["reason"] == "supervisor_worker_lost" or
                generation_expired(campaign, outcome) or
                (outcome["reason"] == "normal" and report is not None and report["errorCategory"] == "timeout"))
            repairable = verified and not known_quality and safe_error and not cancelled(root) and (retry or
                (report is not None and report["errorCategory"] in ("infrastructure_or_state_failure", "runtime_construction_failed")))
            life.write(root / f"attempt-{index:02}.json", {"supervisor": str(job), "cleanupVerified": verified,
                "classification": "passed" if passed else "quality_failure" if quality else "incomplete",
                "retryEligible": retry, "repairEligible": repairable, "knownCompleteQualityFailure": known_quality,
                "supervisorReason": outcome["reason"]})
            launch = c.private.read_json(job / "launchd.json")
            subprocess.run(["/bin/launchctl", "bootout", launch["domain"] + "/" + launch["label"]], capture_output=True, timeout=10)


def repair(root, revision, checks):
    root = c.private.private_directory(root)
    life.need(not (root / "outcome.json").exists())
    index = len(list(root.glob("reservation-*.json")))
    life.need(0 < index < 3)
    previous = c.private.read_json(root / f"attempt-{index:02}.json")
    life.need(previous["cleanupVerified"] and previous["repairEligible"])
    source = c.tooling()
    life.need(source != c.private.read_json(root / f"reservation-{index:02}.json")["sourceSha256"])
    proof = c.private.read_json(checks)
    life.need(proof.get("passed") is True and proof.get("sourceSha256") == source)
    prepared = c.private.read_json(revision / "revision.json")
    c.v.revision.check_revision(prepared, c.private.read_json(revision / "context-evidence.json"))
    life.write(root / f"repair-{index:02}.json", {"previousAttemptSha256": c.e.digest(c.e.compact(previous)),
        "revision": str(revision), "sourceSha256": source, "checksSha256": c.e.digest(c.private.private_read(checks))})


def start(root, revision, user_instruction):
    root = c.private.private_directory(root); life.need(not any(root.iterdir()))
    now = dt.datetime.now(dt.timezone.utc)
    life.write(root / "authority.json", {"version": "intake-recovery-authority-v1", "mode": "live",
        "limits": c.LIMITS, "clockIdentity": c.clock_identity(), "startMonotonic": c.native_clock.monotonic(),
        "recordedUtc": now.isoformat().replace("+00:00", "Z"), "expiresUnix": (now + dt.timedelta(hours=4)).timestamp(),
        "revision": str(revision), "userInstruction": user_instruction})
    label = "com.gtrainer.intake-recovery." + uuid.uuid4().hex
    job = {"Label": label, "ProgramArguments": [sys.executable, "-c", BOOTSTRAP, str(root), str(c.e.HERE / "recovery_v4.py")],
        "RunAtLoad": True, "KeepAlive": {"SuccessfulExit": False}, "ThrottleInterval": 2, "Umask": 0o077,
        "WorkingDirectory": str(c.e.ROOT), "EnvironmentVariables": {"PATH": "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"},
        "StandardOutPath": str(root / "coordinator.log"), "StandardErrorPath": str(root / "coordinator.log")}
    c.private.private_write(root / "coordinator.plist", plistlib.dumps(job))
    c.private.private_write(root / "coordinator.log", b"")
    life.write(root / "launchd.json", {"domain": "gui/" + str(os.getuid()), "label": label})
    subprocess.run(["/bin/launchctl", "bootstrap", "gui/" + str(os.getuid()), str(root / "coordinator.plist")],
                   capture_output=True, timeout=10, check=True)


def restart_allowed(root):
    """Fail closed if this invocation could not durably spend its restart."""
    try:
        rows = sorted(root.glob("coordinator-start-*.json"))
        return bool(rows) and len(rows) <= 3 and not (root / "outcome.json").exists() and c.private.read_json(rows[-1])["pid"] == os.getpid()
    except BaseException: return False


def complete_report(campaign, report):
    """Quality is authoritative even if later receipt/status publication failed."""
    if report is None: return False
    try:
        claim = c.private.read_json(campaign / "claim-01.json")
        life.need(report["runSha256"] == claim["runSha256"] and report["revisionSha256"] == claim["revisionSha256"])
        life.need(report["classification"] in ("passed", "quality_failure") and report["stopVerified"] and report["probeVerified"] and not report["deadlineBreach"])
        life.need(report["errorCategory"] is None)
        life.need(report["eventsSha256"] == c.event_digest(campaign / "batch-01/events.jsonl"))
        life.need([row["caseId"] for row in report["cases"]] == c.e.IDS)
        contract = c.e.verify()[2]
        for row in report["cases"]:
            life.need(row["outcome"] in ("response", "empty"))
            life.need(row["rawSha256"] == (None if row["raw"] is None else c.e.digest(row["raw"].encode())))
            life.need(row["stagesSha256"] == c.e.digest(c.e.compact(row["stages"])))
            life.need(row["oracleResultSha256"] == c.e.digest(c.e.compact(row["oracle"])))
            life.need(row["oracle"] == c.v.oracle_v4.evaluate(row["caseId"], row["stages"], contract))
        life.need((report["classification"] == "passed") == all(row["oracle"]["status"] == "passed" for row in report["cases"]))
        return True
    except BaseException: return False


def generation_expired(campaign, outcome):
    try:
        phase = outcome["expiredPhase"]
        life.need(outcome["reason"] == "supervisor_deadline" and phase["name"] in c.e.IDS)
        life.need(phase["trigger"] == "phase")
        scope = c.private.read_json(campaign / "campaign.json")
        life.need(phase["deadline"] < scope["workCutoffMonotonic"])
        intent = c.private.read_json(campaign / "batch-01" / (phase["name"] + ".intent.json"))
        launched = c.private.read_json(campaign / "batch-01" / (phase["name"] + ".launched.json"))
        life.need(intent["deadlineMonotonic"] == phase["deadline"] and intent["actionId"] == phase["name"])
        life.need(launched["intentSha256"] == c.e.digest(c.e.compact(intent)))
        return True
    except BaseException: return False


if __name__ == "__main__":
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--coordinate", type=Path); action.add_argument("--once", type=Path)
    action.add_argument("--repair", type=Path)
    action.add_argument("--status", type=Path); action.add_argument("--cancel", type=Path)
    parser.add_argument("--slot", type=int)
    parser.add_argument("--revision", type=Path); parser.add_argument("--checks", type=Path)
    parser.add_argument("--bootstrap-record", type=Path)
    args = parser.parse_args()
    if args.coordinate:
        root=c.private.private_directory(args.coordinate)
        try: coordinate(root, args.bootstrap_record)
        except BaseException:
            # Only counted, recoverable coordinator exits ask launchd to retry.
            # Persistent journal failure exits successfully to disable KeepAlive.
            raise SystemExit(1 if restart_allowed(root) else 0)
    elif args.status:
        root=c.private.private_directory(args.status)
        print(c.e.compact({"root":str(root),"reservedSlots":len(list(root.glob('reservation-*.json'))),
            "outcome":c.private.read_json(root/'outcome.json') if (root/'outcome.json').exists() else None}).decode())
    elif args.cancel:
        root=c.private.private_directory(args.cancel);path=root/'cancel.json'
        request={"authoritySha256":c.e.digest(c.private.private_read(root/'authority.json'))}
        try:life.write(path,request)
        except FileExistsError:life.need(c.private.read_json(path)==request)
    elif args.repair:
        life.need(args.revision is not None and args.checks is not None); repair(args.repair, args.revision, args.checks)
    else: once(c.private.private_directory(args.once), args.slot)
