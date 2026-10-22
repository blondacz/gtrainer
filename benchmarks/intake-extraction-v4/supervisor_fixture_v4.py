"""Synthetic process fixture. No Mac runtime, model, kubectl or network operations."""
import os
from pathlib import Path
import signal
import sys
import time

import lifecycle_v4 as life


def main(directory, scenario):
    life.write(directory / "binding.json", {"run": {"runId": "synthetic-no-model"}})
    now = life.clock.monotonic()
    spec = life.private.read_json(directory / "job.json")
    life.write(directory / "phase-0001.json", {"name": "preflight", "started": now,
        "deadline": min(now + 30, spec["workCutoff"])})
    if scenario == "exit": return 2
    if scenario == "generation_expiry":
        now = life.clock.monotonic()
        life.write(directory/'phase-0002.json',{'name':'probe','started':now,'deadline':min(now+30,spec['workCutoff'])})
        first = life.v.suites.select(spec.get('profile', life.e.PROFILE)).ids[0]
        life.write(directory/'phase-0003.json',{'name':first,'started':now,'deadline':min(now+0.75,spec['workCutoff'])})
        os.kill(os.getpid(),signal.SIGSTOP)
        return 2
    if scenario == "locked":
        with life.locked(directory / "mutation.lock"):
            life.write(directory / "fixture-locked.json", {"locked": True})
            os.kill(os.getpid(), signal.SIGSTOP)
        return 2
    if scenario == "hang":
        os.kill(os.getpid(), signal.SIGSTOP)
        return 2
    if scenario == "delayed": time.sleep(2)
    life.write(directory / "cleanup-request.json", {"runSha256": life.e.digest(life.e.compact({"runId": "synthetic-no-model"})),
        "nativeEnd": life.clock.monotonic() + 60, "wallEnd": time.time() + 60})
    while not (directory / "cleanup-result.json").exists(): time.sleep(0.02)
    if scenario == "cleanup_hang":
        os.kill(os.getpid(), signal.SIGSTOP)
        return 2
    return 0


if __name__ == "__main__":
    os.umask(0o077)
    raise SystemExit(main(Path(sys.argv[1]), sys.argv[2]))
