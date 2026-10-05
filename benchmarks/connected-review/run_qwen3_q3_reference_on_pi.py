#!/usr/bin/env python3
"""Approved synthetic Pi Qwen3 Q3/2048 reference with persistent microSD storage."""
import argparse
import base64
import fcntl
import inspect
import json
from pathlib import Path
import subprocess
import sys

import run_connected_review_v3_on_pi as runner


def stage_gguf():
    """Runs as root on the Pi; only touches the dedicated public model directory."""
    directory = Path(MODEL["storage_path"])
    source = subprocess.check_output(["findmnt", "-n", "-o", "SOURCE", "-T", "/var/lib"], text=True).strip()
    if not source.startswith("/dev/mmcblk"):
        raise RuntimeError("approved_microsd_storage_changed")
    namespaces = json.loads(subprocess.check_output([
        "k3s", "kubectl", "get", "namespaces", "-l",
        "app.kubernetes.io/managed-by=gtrainer-connected-review-evaluation", "-o", "json"], timeout=60))
    if namespaces["items"]:
        raise RuntimeError("another_pi_benchmark_exists")

    def check_headroom():
        available = next(int(line.split()[1]) for line in Path("/proc/meminfo").read_text().splitlines()
                         if line.startswith("MemAvailable:"))
        if available < 1024 * 1024:
            raise RuntimeError("insufficient_host_headroom")

    check_headroom()
    for path in (directory.parent, directory, directory / "blobs", directory / "blobs" / "blobs"):
        if path.is_symlink() or (path.exists() and not path.is_dir()):
            raise RuntimeError("unexpected_storage_path")
        if not path.exists():
            path.mkdir(mode=0o750)
            os.chown(path, 10001, 10001)
        elif path != directory.parent and path.stat().st_uid != 10001:
            raise RuntimeError("unexpected_storage_owner")
    blob = directory / "blobs" / "blobs" / ("sha256-" + MODEL["sha256"])
    if blob.is_symlink():
        raise RuntimeError("unexpected_model_symlink")

    def file_sha(path):
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(2 * 1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    if blob.exists():
        if blob.stat().st_size != MODEL["size_bytes"] or file_sha(blob) != MODEL["sha256"]:
            raise RuntimeError("existing_model_digest_mismatch")
        print("Reusing verified persistent GGUF", flush=True)
        return
    if shutil.disk_usage(directory).free < MODEL["size_bytes"] + 1024**3:
        raise RuntimeError("insufficient_model_disk_headroom")
    partial = blob.with_name(blob.name + ".part-" + uuid.uuid4().hex)
    digest = hashlib.sha256()
    total = 0
    started = time.monotonic()
    print("Downloading pinned Q3_K_M GGUF (4.12 GB) to persistent microSD", flush=True)
    try:
        with partial.open("xb") as stream, urllib.request.urlopen(MODEL["url"], timeout=60) as response:
            os.chmod(partial, 0o640)
            for chunk in iter(lambda: response.read(2 * 1024 * 1024), b""):
                if time.monotonic() - started > 1800:
                    raise RuntimeError("model_download_timeout")
                check_headroom()
                total += len(chunk)
                if total > MODEL["size_bytes"]:
                    raise RuntimeError("model_download_size_exceeded")
                stream.write(chunk)
                digest.update(chunk)
            stream.flush()
            os.fsync(stream.fileno())
        if total != MODEL["size_bytes"] or digest.hexdigest() != MODEL["sha256"]:
            raise RuntimeError("downloaded_model_digest_mismatch")
        os.chown(partial, 10001, 10001)
        partial.rename(blob)
    except BaseException:
        # Remove only this invocation's partial public-model file.
        if partial.exists():
            partial.unlink()
        raise
    (directory / "gguf-source.json").write_text(json.dumps(MODEL, indent=2) + "\n")
    print("Verified GGUF SHA-256: " + MODEL["sha256"], flush=True)


def staging_source():
    return ("import os,json,hashlib,shutil,subprocess,time,uuid,urllib.request\n"
            "from pathlib import Path\nMODEL=" + repr(runner.QWEN3_Q3) + "\n" +
            inspect.getsource(stage_gguf) + "\nstage_gguf()\n")


def resume_details(path: Path, memory_limit_mib: int, attempt_timeout_seconds: int) -> tuple[str, str]:
    """Skip only a validated accepted prefix; retain and hash the original capture."""
    raw = path.read_bytes()
    records = [json.loads(line) for line in raw.splitlines() if line.strip()]
    evaluations = [r for r in records if r.get("phase") == "evaluation_started"]
    if len(evaluations) != 1:
        raise ValueError("Resume requires exactly one evaluation header")
    header = evaluations[0]
    config = runner.reference_config(runner.QWEN3_Q3["model"], True, memory_limit_mib, attempt_timeout_seconds)
    expected = {"settings": config["options"], "evaluation_version": config["evaluation_version"],
                "container_memory_limit_mib": memory_limit_mib, "attempt_timeout_seconds": attempt_timeout_seconds,
                "case_total_timeout_seconds": config["case_total_timeout_seconds"],
                "minimum_host_available_mib": 1024, "model_source": runner.QWEN3_Q3,
                "max_response_bytes": runner.MAX_RESPONSE_BYTES, "maximum_attempts_per_case": 2}
    if any(header.get(key) != value for key, value in expected.items()):
        raise ValueError("Resume settings or model source differ from the previous run")
    for key, file in (("cases", "cases-v2.json"), ("packet_adapter", "connected_review_packets_v2.py"),
                      ("validator", "connected_review_validator_v3.py")):
        if header.get("artifact_sha256", {}).get(key) != runner.sha((runner.ROOT / file).read_bytes()):
            raise ValueError("Resume frozen cases or validation artifacts differ")
    cleanup = [r for r in records if r.get("phase") == "owned_cleanup_complete"]
    if not cleanup or not cleanup[-1].get("namespace_removed"):
        raise ValueError("Previous owned cleanup must be confirmed before resuming")
    if any(r.get("phase") == "evaluation_resumed" for r in records):
        raise ValueError("Resume from a continuation requires explicit capture-chain reconciliation")
    cases = runner.load_cases()
    completed = [r for r in records if r.get("phase") == "case_complete"]
    if [r.get("case") for r in completed] != [case["id"] for case in cases[:len(completed)]]:
        raise ValueError("Resume requires a contiguous completed prefix")
    for case, result in zip(cases, completed):
        if result.get("status") != "accepted":
            raise ValueError("Resume skips only accepted completed cases")
        attempts = [r for r in records if r.get("phase") == "attempt_result" and r.get("case") == case["id"]]
        if not attempts or attempts[-1].get("status") != "accepted":
            raise ValueError("Missing accepted response for completed case")
        attempt = attempts[-1]
        response_raw = base64.b64decode(attempt["response_base64"], validate=True)
        request_raw = base64.b64decode(attempt["request_base64"], validate=True)
        packet = runner.materialize(case)
        if (runner.sha(response_raw) != attempt.get("response_sha256") or
                runner.sha(request_raw) != attempt.get("request_sha256") or
                json.loads(request_raw)["messages"][1]["content"] != runner.prompt(case)["userPacketJson"]):
            raise ValueError("Resume request/response integrity mismatch")
        if runner.validate_output(json.loads(response_raw)["message"]["content"], packet):
            raise ValueError("Previous accepted response no longer validates")
    if len(completed) >= len(cases):
        raise ValueError("All frozen cases already completed")
    return cases[len(completed)]["id"], runner.sha(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--approve-synthetic-run", action="store_true")
    parser.add_argument("--approve-microsd-model-storage", action="store_true")
    parser.add_argument("--model-memory-limit-mib", type=int, default=5120)
    parser.add_argument("--attempt-timeout-seconds", type=int, default=480)
    parser.add_argument("--resume-capture", type=Path, help="Preserved original capture with accepted prefix and confirmed cleanup")
    args = parser.parse_args()
    if not args.approve_synthetic_run or not args.approve_microsd_model_storage:
        parser.error("Explicit synthetic inference and microSD model-storage approval flags are required")
    runner.reference_config(runner.QWEN3_Q3["model"], True, args.model_memory_limit_mib, args.attempt_timeout_seconds)
    resume = resume_details(args.resume_capture, args.model_memory_limit_mib, args.attempt_timeout_seconds) if args.resume_capture else None
    with (Path.home() / ".gtrainer-qwen3-q3-reference.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        subprocess.run(runner.SSH + ["sudo", "-n", "python3", "-"], input=staging_source().encode(),
                       timeout=1860, check=True)
        sys.argv = ["pi-runner", "--run", "--approve-synthetic-run", "--models", runner.QWEN3_Q3["model"],
                    "--qwen3-q3-microsd-reference", "--monitor-command-timeout-seconds", "60",
                    "--model-memory-limit-mib", str(args.model_memory_limit_mib),
                    "--attempt-timeout-seconds", str(args.attempt_timeout_seconds)]
        if resume:
            sys.argv += ["--start-case", resume[0], "--resume-capture-sha256", resume[1]]
            print("Resuming at " + resume[0] + "; previous capture retained", flush=True)
        runner.main()


if __name__ == "__main__":
    main()
