"""Run only a separately approved, fixed synthetic intake acquisition on this Mac."""
import argparse
import datetime as dt
import fcntl
import os
from pathlib import Path
import stat
import time

import acquire
import capture
import mac_runtime
import protocol


def validate_one(directory, case_id, attempt, raw):
    if case_id not in [f"i{i:02}" for i in range(1, 9)] or type(attempt) is not int or attempt not in (1, 2):
        raise ValueError("Validator input refused")
    target = directory / f"validator-{case_id}-{attempt}"
    target.mkdir(mode=0o700)
    rows = capture.kotlin_validate(target, [{"caseId": case_id, "attempt": attempt, "raw": raw}])
    if len(rows) != 1 or set(rows[0]) != {"caseId", "attempt", "status"}:
        raise ValueError("Validator result refused")
    result = rows[0]
    if result["caseId"] != case_id or type(result["attempt"]) is not int or result["attempt"] != attempt or result["status"] not in ("accepted", "rejected"):
        raise ValueError("Validator result refused")
    return result["status"] == "accepted"


def execute(directory):
    directory = capture.private_directory(directory)
    run = capture.read_json(directory / "acquisition-run.json")
    approval = capture.read_json(directory / "inference-approval.json")
    acquire.authorize(run, approval, dt.datetime.now(dt.timezone.utc))
    # Share the historical benchmark lock without changing its contents or permissions.
    lock_path = Path.home() / ".gtrainer-mac-model-run.lock"
    descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "r+b") as lock:
        info = os.fstat(lock.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.getuid():
            raise ValueError("Benchmark lock refused")
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        runtime = mac_runtime.MacRuntime()
        try:
            result = acquire.run_acquisition(directory, run, approval, runtime,
                lambda case, attempt, raw: validate_one(directory, case, attempt, raw))
        except BaseException:
            if (runtime.pod or runtime.pending_pod or runtime.paused or runtime.pause_pending or runtime.restore_pending) and not runtime.cancellation_attempted:
                verified = False
                try:
                    verified = runtime.cancel(run, time.monotonic() + 60, lambda event: None).get("verified") is True
                finally:
                    capture.private_write(directory / "emergency-stop.json", protocol.compact({"verified": verified}))
            raise
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = execute(args.directory)
        print("Synthetic intake acquisition:", result["status"], "; qualification remains incomplete/closed.")
        return 0 if result["status"] == "completed" else 2
    except (Exception, KeyboardInterrupt):
        print("Acquisition refused or failed; inspect private evidence. No qualification was granted.")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
