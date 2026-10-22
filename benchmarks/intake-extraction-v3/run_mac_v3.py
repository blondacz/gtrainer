"""Separately approved v3 synthetic acquisition. Importing performs no operations."""
import argparse
import datetime as dt
import fcntl
import os
from pathlib import Path
import stat
import time

import replay
import runtime_v3


def validate_one(directory, case_id, raw, deadline):
    replay.need(case_id in replay.e.IDS and type(raw) is str)
    remaining = deadline - time.monotonic()
    replay.need(remaining > 0)
    target = directory / ("validator-" + case_id)
    target.mkdir(mode=0o700)
    requests = [{"caseId": case_id, "raw": raw, "rawSha256": replay.e.digest(raw.encode())}]
    rows = replay.kotlin_validate(target, requests, timeout_seconds=min(600, remaining))
    replay.check_results(rows, requests)
    replay.need(time.monotonic() < deadline)
    return rows[0]


def execute(directory):
    import acquire_v3
    directory = replay.private.private_directory(directory)
    run = replay.private.read_json(directory / "acquisition-run.json")
    approval = replay.private.read_json(directory / "inference-approval.json")
    acquire_v3.authorize(run, approval, dt.datetime.now(dt.timezone.utc))
    replay.need(run["executionMode"] == "live")
    descriptor = os.open(Path.home() / ".gtrainer-mac-model-run.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "r+b") as lock:
        info = os.fstat(lock.fileno())
        replay.need(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and stat.S_IMODE(info.st_mode) == 0o600 and info.st_uid == os.getuid())
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        runtime = runtime_v3.make_runtime(run)
        try:
            return acquire_v3.run_acquisition(directory, run, approval, runtime,
                lambda case, raw, deadline: validate_one(directory, case, raw, deadline))
        except BaseException:
            if (runtime.pod or runtime.pending_pod or runtime.paused or runtime.pause_pending or runtime.restore_pending) and not runtime.cancellation_attempted:
                verified = False
                try:
                    verified = runtime.cancel(run, time.monotonic() + 60, lambda event: None).get("verified") is True
                finally:
                    replay.private.private_write(directory / "emergency-stop.json", replay.e.compact({"verified": verified}))
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    old = os.umask(0o077)
    try:
        result = execute(args.directory)
        print("V3 acquisition:", result["status"], "; no replay or qualification granted.")
        return 0 if result["status"] == "completed" else 2
    except (Exception, KeyboardInterrupt):
        print("V3 acquisition refused or failed; inspect private evidence. Admission remains closed.")
        return 2
    finally:
        os.umask(old)


if __name__ == "__main__":
    raise SystemExit(main())
