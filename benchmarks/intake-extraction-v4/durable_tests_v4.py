"""Detached, bounded offline test execution and explicit repair-ready reruns.

Only fixed, allowlisted unittest selections are executable. No model/VM/service operations or
arbitrary command payloads. A failed assertion is not retried unchanged.
"""
import argparse
import datetime as dt
import os
from pathlib import Path
import selectors
import subprocess
import sys
import tempfile
import time
import uuid

import campaign_v4 as c
import lifecycle_v4 as life
import owned_processes_v4 as owned

SUITES = {"v4": ("benchmarks/intake-extraction-v4", "test*.py"),
          "recovery-checks": ("benchmarks/intake-extraction-v4", "test_*.py"),
          "kotlin": ("benchmarks/intake-extraction-v4", "test_*.py"),
          "v5-kotlin": ("benchmarks/intake-extraction-v5", "test_replay_v5.py"),
          "v5": ("benchmarks/intake-extraction-v5", "test*.py"),
          "continuation": ("benchmarks/intake-extraction-v4", "test_campaign_v4.py"),
          "shared": ("benchmarks/scoped-profiles", "test_mac_runtime*.py")}


def fingerprint(spec):
    # Discovery and dependency inventories are recomputed, so added/deleted tests
    # invalidate an attempt just like edits to already known files.
    paths = set(c.tooling())
    for directory in ("intake-extraction-v5", "intake-extraction-v4-2", "intake-extraction-v4-1", "intake-extraction-v4", "intake-extraction-v3", "intake-extraction-v2", "scoped-profiles"):
        for pattern in ("*.py", "*.json"):
            paths.update(str(path.relative_to(c.e.ROOT)) for path in (c.e.ROOT / "benchmarks" / directory).glob(pattern))
    for pattern in ("backend/src/**/*.kt", "backend/src/**/*.json", "*.gradle.kts", "backend/*.gradle.kts"):
        paths.update(str(path.relative_to(c.e.ROOT)) for path in c.e.ROOT.glob(pattern) if "build" not in path.parts and ".gradle" not in path.parts)
    paths.update(name for name in ("gradlew", "gradle/wrapper/gradle-wrapper.properties", "gradle/libs.versions.toml") if (c.e.ROOT / name).is_file())
    if spec.get("suite") == "v5":
        from suite_selection_v4 import select
        selected = select("context-intake-extraction-v5")
        prompt = Path(spec["candidatePromptFile"])
        life.need(c.e.digest(c.private.private_read(prompt)) == selected.revision.suite.PROMPT_SHA256)
        paths.add(str(prompt))
    return {name: c.e.digest((c.e.ROOT / name).read_bytes()) for name in sorted(paths)}


def command(suite):
    life.need(suite in SUITES)
    if suite == "continuation":
        return [sys.executable, "-m", "unittest",
                "test_campaign_v4.CampaignTest.test_separate_process_continues_only_completed_failure_on_same_clock", "-v"]
    if suite == "recovery-checks":
        return [sys.executable, "-m", "unittest", "test_lifecycle_v4", "test_durable_tests_v4",
                "test_recovery_v4", "test_owned_processes_v4", "-v"]
    if suite == "kotlin":
        return [sys.executable, "-m", "unittest", "test_replay_v4.KotlinIntegrationTest",
                 "test_runtime_v4.RuntimeIntegration", "-v"]
    if suite == "v5-kotlin":
        return [sys.executable, "-m", "unittest", "test_replay_v5.KotlinIntegrationV5", "-v"]
    path, pattern = SUITES[suite]
    return [sys.executable, "-m", "unittest", "discover", "-s", path, "-p", pattern, "-v"]


def run_attempt(directory, spec, index):
    """Return completed subprocess status, retaining bounded private output."""
    environment = {k: v for k, v in os.environ.items() if not k.startswith(("GTRAINER_", "INTAKE_V4_"))}
    environment["PYTHONPATH"] = str(c.e.HERE) + os.pathsep + environment.get("PYTHONPATH", "")
    if spec["suite"] in ("v5", "v5-kotlin"):
        environment["PYTHONPATH"] = str(c.e.HERE.parent / "intake-extraction-v5") + os.pathsep + environment["PYTHONPATH"]
    if spec["suite"] == "v5":
        environment["INTAKE_V5_TEST_PROMPT"] = spec["candidatePromptFile"]
    environment[owned.ENV] = str(directory)
    if spec["kotlin"]:
        retained = directory / f"kotlin-{index:02}"
        retained.mkdir(mode=0o700)
        environment.update(INTAKE_V4_KOTLIN="1", INTAKE_V4_RETAIN_INTEGRATION=str(retained))
    try:
        process, request = owned.launch(command(spec["suite"]), environment, directory)
    except OSError:
        return {"classification": "launcher_failure", "exitCode": None}
    size = 0
    with (directory / f"attempt-{index:02}.log").open("xb") as log:
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while selector.get_map():
                    life.need(c.native_clock.monotonic() < spec["workCutoff"] and time.time() < spec["expiresUnix"])
                    life.need(not (directory / "cancel-request.json").exists())
                    for key, _ in selector.select(0.1):
                        chunk = os.read(key.fileobj.fileno(), 65536)
                        if not chunk: selector.unregister(key.fileobj); continue
                        size += len(chunk); life.need(size <= 1024 * 1024)
                        log.write(chunk)
            code = c.private.read_json(owned.sibling(request, ".result.json"))["exitCode"]
        finally:
            life.need(owned.finish(process, request, time.monotonic() + 5)); process.stdout.close()
    return {"classification": "passed" if code == 0 else "test_failure" if code > 0 else "process_lost",
            "exitCode": code}


def execute(directory, spec, *, attempt=run_attempt, sleep=time.sleep):
    """One fixed outer lease; repair waiting consumes it. No attempt refunds."""
    run = {"runId": "offline-tests-" + spec["nonce"]}
    life.write(directory / "binding.json", {"run": run})
    started = c.native_clock.monotonic()
    life.write(directory / "phase-0001.json", {"name": "tests", "started": started, "deadline": spec["workCutoff"]})
    source = spec.get("testSourceSha256", spec["toolingSha256"])
    final = "incomplete"
    offset = spec.get("attemptOffset", 0)
    validate_budget(directory, spec)
    for index in range(offset + 1, 4):
        life.need(c.native_clock.monotonic() < spec["workCutoff"] and time.time() < spec["expiresUnix"])
        life.need(not (directory / "cancel-request.json").exists())
        life.need(fingerprint(spec) == source)
        life.write(directory / f"attempt-{index:02}-intent.json", {"index": index,
            "sourceSha256": source, "deadline": spec["workCutoff"], "suite": spec["suite"]})
        result = attempt(directory, spec, index)
        if fingerprint(spec) != source: result = dict(result, classification="source_changed_during_attempt")
        life.write(directory / f"attempt-{index:02}-result.json", dict(result, sourceSha256=source))
        if result["classification"] == "passed": final = "passed"; break
        if index == 3: final = "attempt_budget_exhausted"; break
        if result["classification"] == "launcher_failure": continue
        # Unknown process fate may include escaped compiler clients: never
        # automatically restart it. Assertions/compiler failures need a repair.
        if result["classification"] == "process_lost": final = "process_lost"; break
        repair_path = directory / f"repair-{index:02}.json"
        while not repair_path.exists():
            life.need(c.native_clock.monotonic() < spec["workCutoff"] and time.time() < spec["expiresUnix"])
            life.need(not (directory / "cancel-request.json").exists())
            sleep(0.2)
        repair = c.private.read_json(repair_path)
        life.need(repair["jobSha256"] == c.e.digest(c.private.private_read(directory / "job.json")))
        life.need(repair["previousAttempt"] == index and repair["sourceSha256"] != source)
        life.need(repair["sourceSha256"] == fingerprint(spec))
        source = repair["sourceSha256"]
    life.write(directory / "test-result.json", {"classification": final,
        "attempts": offset + len(list(directory.glob("attempt-*-intent.json"))),
        "localAttempts": len(list(directory.glob("attempt-*-intent.json"))), "newInferenceRequests": 0})
    life.write(directory / "cleanup-request.json", {"runSha256": c.e.digest(c.e.compact(run)),
        "nativeEnd": c.native_clock.monotonic() + 60, "wallEnd": time.time() + 60})
    end = time.monotonic() + 65
    while not (directory / "cleanup-result.json").exists():
        life.need(time.monotonic() < end); sleep(0.05)


def repair(directory, checks):
    directory = c.private.private_directory(directory)
    spec = c.private.read_json(directory / "job.json")
    life.need(spec["mode"] == "offline_tests" and not (directory / "outcome.json").exists())
    intents = sorted(directory.glob("attempt-*-intent.json"))
    results = sorted(directory.glob("attempt-*-result.json"))
    life.need(0 < len(results) == len(intents))
    index = int(results[-1].name.split('-')[1]); life.need(index < 3)
    previous = c.private.read_json(results[-1]); source = fingerprint(spec)
    life.need(previous["classification"] in ("test_failure", "source_changed_during_attempt"))
    life.need(source != previous["sourceSha256"])
    proof = c.private.private_read(checks)
    checked = c.e.decode(proof)
    life.need(checked.get("passed") is True and checked.get("sourceSha256") == source)
    life.write(directory / f"repair-{index:02}.json", {"jobSha256": c.e.digest(c.private.private_read(directory / "job.json")),
        "previousAttempt": index, "sourceSha256": source, "checksEvidenceSha256": c.e.digest(proof)})


def validate_budget(directory, spec):
    offset = spec.get("attemptOffset", 0)
    life.need(type(offset) is int and 0 <= offset < 3)
    if not offset: return
    root = c.private.private_directory(Path(spec["budgetRoot"]))
    original = c.private.read_json(root / "job.json")
    life.need(spec["budgetAuthoritySha256"] == c.e.digest(c.private.private_read(root / "job.json")))
    for key in ("suite", "kotlin", "startMonotonic", "startedUnix", "workCutoff", "expiresUnix", "maxAttempts"):
        life.need(spec[key] == original[key])
    reservation = c.private.read_json(root / f"restart-{offset+1:02}.json")
    life.need(reservation["directory"] == str(directory) and reservation["nonce"] == spec["nonce"])


def restart(previous, checks):
    """Fresh executor after a verified repair, never a fresh test-time grant."""
    import supervisor_v4 as supervisor
    previous = c.private.private_directory(previous)
    spec = c.private.read_json(previous / "job.json")
    life.need(spec["mode"] == "offline_tests" and (previous / "outcome.json").exists())
    life.need(c.native_clock.monotonic() < spec["workCutoff"] and time.time() < spec["expiresUnix"])
    root = c.private.private_directory(Path(spec.get("budgetRoot", str(previous))))
    with life.locked(root / "restart.lock", nonblocking=True):
        directories = [root] + [Path(c.private.read_json(path)["directory"]) for path in sorted(root.glob("restart-*.json"))]
        spent = max([0] + [int(path.name.split('-')[1]) for job in directories for path in job.glob("attempt-*-intent.json")] +
                    [int(path.name.split('-')[1].split('.')[0]) for path in root.glob("restart-*.json")])
        life.need(spent < 3)
        proof = c.private.read_json(checks)
        source = fingerprint(spec)
        life.need(proof.get("passed") is True and proof.get("sourceSha256") == source)
        life.need(source != spec["testSourceSha256"])
        # Recovery is local-test-only. Preserve the original failed cleanup and
        # record this separate bounded independent verification, never upgrade it.
        worker = c.private.read_json(previous / "worker.json")
        life.need(owned.quiescent(worker["processGroup"]) and owned.closed(previous))
        life.need(owned.cleanup(previous, time.monotonic()+60, time.time()+60))
        directory = Path(tempfile.mkdtemp(prefix="v4w-", dir=supervisor.BASE))
        new = dict(spec, nonce=uuid.uuid4().hex, toolingSha256=c.tooling(), testSourceSha256=source,
            attemptOffset=spent, budgetRoot=str(root), budgetAuthoritySha256=c.e.digest(c.private.private_read(root / "job.json")))
        new["testInventory"] = sorted(str(path.relative_to(c.e.ROOT)) for path in (c.e.ROOT / SUITES[spec["suite"]][0]).glob(SUITES[spec["suite"]][1]))
        life.write(root / f"restart-{spent+1:02}.json", {"directory": str(directory), "nonce": new["nonce"],
            "previousJob": str(previous), "previousClientsIndependentlyQuiescent": True,
            "checksSha256": c.e.digest(c.private.private_read(checks))})
        supervisor.prepare(directory, new); supervisor.connect(directory).close()
        return directory


def start(suite, *, kotlin=False, candidate_prompt=None):
    import supervisor_v4 as supervisor
    life.need(suite in SUITES)
    life.need((suite == "v5") == (candidate_prompt is not None))
    if candidate_prompt is not None:
        candidate_prompt = Path(candidate_prompt).absolute()
        from suite_selection_v4 import select
        life.need(c.e.digest(c.private.private_read(candidate_prompt)) == select("context-intake-extraction-v5").revision.suite.PROMPT_SHA256)
    kotlin = kotlin or suite in ("kotlin", "v5-kotlin", "v5")
    directory = Path(tempfile.mkdtemp(prefix="v4w-", dir=supervisor.BASE))
    native, wall = c.native_clock.monotonic(), time.time()
    spec = {"mode": "offline_tests", "suite": suite, "kotlin": kotlin,
        "nonce": uuid.uuid4().hex, "clockIdentity": c.clock_identity(), "toolingSha256": c.tooling(),
        "startMonotonic": native, "startedUnix": wall,
        "workCutoff": native + 3540, "expiresUnix": wall + 3600,
        "maxAttempts": 3}
    if candidate_prompt is not None:
        spec["candidatePromptFile"] = str(candidate_prompt)
    spec["testInventory"] = sorted(str(path.relative_to(c.e.ROOT)) for path in (c.e.ROOT / SUITES[suite][0]).glob(SUITES[suite][1]))
    spec["testSourceSha256"] = fingerprint(spec)
    supervisor.prepare(directory, spec); supervisor.connect(directory).close()
    return directory


def watch(directory, after_attempt=0):
    """Observer notification on failure, not only after the repair lease expires."""
    import supervisor_v4 as supervisor
    directory = c.private.private_directory(directory)
    spec = c.private.read_json(directory / "job.json")
    while time.time() < spec["expiresUnix"] + 70:
        if (directory / "outcome.json").exists(): return supervisor.status(directory)
        rows = sorted(directory.glob("attempt-*-result.json"))
        for path in rows:
            if int(path.name.split('-')[1]) <= after_attempt: continue
            row = c.private.read_json(path)
            if row["classification"] in ("test_failure", "source_changed_during_attempt"):
                index = int(path.name.split('-')[1])
                return {"state": "awaiting_repair" if index < 3 else "failed_no_attempts_remaining", "directory": str(directory), "attempt": index,
                        "classification": row["classification"]}
        time.sleep(0.2)
    return {"state": "unknown_outcome_after_authority", "directory": str(directory)}


if __name__ == "__main__":
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--start", choices=sorted(SUITES))
    action.add_argument("--worker", type=Path)
    action.add_argument("--repair", type=Path)
    action.add_argument("--watch", type=Path)
    action.add_argument("--restart", type=Path)
    parser.add_argument("--after-attempt", type=int, default=0)
    parser.add_argument("--checks", type=Path)
    parser.add_argument("--kotlin", action="store_true")
    parser.add_argument("--candidate-prompt", type=Path)
    args = parser.parse_args()
    if args.start: print(start(args.start, kotlin=args.kotlin, candidate_prompt=args.candidate_prompt))
    elif args.watch: print(c.e.compact(watch(args.watch, args.after_attempt)).decode())
    elif args.restart:
        life.need(args.checks is not None); print(restart(args.restart, args.checks))
    elif args.repair:
        life.need(args.checks is not None); repair(args.repair, args.checks)
    else:
        directory = c.private.private_directory(args.worker)
        execute(directory, c.private.read_json(directory / "job.json"))
