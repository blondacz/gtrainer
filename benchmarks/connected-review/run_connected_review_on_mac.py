#!/usr/bin/env python3
"""Synthetic-only reference on the separate Intel Mac Lima/K3s cluster.

Uses V3 packets and generation settings, but a 900-second attempt timeout and
6.5 GiB model-container budget. Captures stay private; no quality self-scoring.
Models must already be downloaded and runtime egress blocked before invocation.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import urllib.request
import uuid

from connected_review_packets_v2 import ROOT, load_cases, prompt
from run_connected_review_v3_on_pi import OPTIONS, NON_CORRECTABLE_FLAGS, output_schema, validate_output

MODELS = {
    "qwen3:8b": "500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41",
    "llama3.1:8b": "46e0c10c039e019119339687c3c1757cc81b9da49709a3b3924863ba87ca666e",
}
URL = "http://127.0.0.1:11435"
KUBECTL = "/usr/local/bin/gtrainer-kubectl"
ATTEMPT_SECONDS = 900
MAX_RESPONSE_BYTES = 24576


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def kube(*args):
    return subprocess.check_output([KUBECTL, *args], timeout=30)


def request(path, body=None, timeout=15):
    data = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(URL + path, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        raw = response.read(65537)
    if len(raw) > 65536:
        raise RuntimeError("provider_response_wrapper_too_large")
    return raw


def resource_sample():
    pods = json.loads(kube("-n", "gtrainer-models", "get", "pods", "-l", "app=ollama", "-o", "json"))["items"]
    if len(pods) != 1:
        raise RuntimeError("unexpected_model_pods")
    pod = pods[0]
    statuses = pod.get("status", {}).get("containerStatuses", [])
    if len(statuses) != 1 or not statuses[0]["ready"] or statuses[0]["restartCount"] != 0:
        raise RuntimeError("model_pod_unhealthy")
    values = kube("-n", "gtrainer-models", "exec", pod["metadata"]["name"], "--", "sh", "-c",
                  "cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak").splitlines()
    return {"pod_uid": pod["metadata"]["uid"], "pod_restarts": 0,
            "ollama_current_mib": round(int(values[0]) / 1024**2, 1),
            "ollama_peak_mib": round(int(values[1]) / 1024**2, 1)}


def preflight(models):
    nodes = json.loads(kube("get", "nodes", "-o", "json"))["items"]
    if len(nodes) != 1 or nodes[0]["metadata"]["name"] != "gtrainer-mac-vm":
        raise RuntimeError("not_the_owned_mac_cluster")
    if json.loads(request("/api/version"))["version"] != "0.35.0":
        raise RuntimeError("ollama_version_mismatch")
    tags = {m["name"]: m["digest"] for m in json.loads(request("/api/tags"))["models"]}
    if any(tags.get(m) != MODELS[m] for m in models):
        raise RuntimeError("model_missing_or_digest_mismatch")
    policy = json.loads(kube("-n", "gtrainer-models", "get", "networkpolicy", "no-runtime-egress", "-o", "json"))
    if policy["spec"].get("egress") or policy["spec"].get("podSelector") != {} or "Egress" not in policy["spec"].get("policyTypes", []):
        raise RuntimeError("runtime_egress_policy_invalid")
    probe = subprocess.run([KUBECTL, "-n", "gtrainer-models", "exec", "deployment/ollama", "--",
                            "timeout", "3", "bash", "-c", "exec 3<>/dev/tcp/1.1.1.1/443"],
                           capture_output=True, timeout=30)
    # K3s may actively REJECT the connection instead of silently dropping it.
    blocked = probe.returncode == 124 or (probe.returncode == 1 and b"Connection refused" in probe.stderr)
    if not blocked:
        raise RuntimeError("runtime_egress_block_not_confirmed")
    return resource_sample()


def run_model(model, directory):
    path = directory / (model.replace(":", "-") + ".jsonl")
    with path.open("x") as stream:
        def emit(event):
            stream.write(json.dumps(event, ensure_ascii=False, allow_nan=False) + "\n")
            stream.flush()

        emit({"phase": "evaluation_started", "model": model, "model_digest": MODELS[model],
              "evaluation_version": "connected-review-mac-cpu-reference-v1", "synthetic_only": True,
              "case_set": "connected-review-synthetic-cases-v2", "validator_version": "connected-review-validator-v3",
              "settings": OPTIONS, "think": False, "attempt_timeout_seconds": ATTEMPT_SECONDS,
              "maximum_attempts_per_case": 2, "max_response_bytes": MAX_RESPONSE_BYTES,
              "runtime_egress_blocked": True, "vm_cpus": 4, "vm_memory_mib": 8192,
              "container_cpu_limit": 3, "container_memory_limit_mib": 6656,
              "ollama_version": "0.35.0", "cost_usd": None,
              "artifact_sha256": {name: sha((ROOT / name).read_bytes()) for name in
                  ("cases-v2.json", "evaluation-v3.json", "connected_review_validator_v3.py", "run_connected_review_on_mac.py")}})
        completed = 0
        try:
            initial = resource_sample()
            for case in load_cases():
                data = prompt(case)
                packet = json.loads(data["userPacketJson"])
                feedback = None
                for attempt in (1, 2):
                    current = resource_sample()
                    if current["pod_uid"] != initial["pod_uid"]:
                        raise RuntimeError("model_pod_replaced")
                    messages = [{"role": "system", "content": data["systemInstructions"]},
                                {"role": "user", "content": data["userPacketJson"]}]
                    if feedback is not None:
                        messages.append({"role": "user", "content": "The independent whole-draft validator rejected the prior draft with these fixed categories: " + ", ".join(feedback) + ". Return a corrected complete JSON draft grounded only in the same packet. Do not add unsupported claims."})
                    payload = {"model": model, "messages": messages, "format": output_schema(packet),
                               "options": OPTIONS, "stream": False, "think": False, "keep_alive": "0s"}
                    raw_request = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()
                    if len(raw_request) > 32768:
                        raise RuntimeError("input_budget_exceeded")
                    binding = {"case": case["id"], "attempt": attempt,
                               "request_sha256": sha(raw_request), "request_base64": base64.b64encode(raw_request).decode(),
                               "packet_sha256": sha(data["userPacketJson"].encode()),
                               "prompt_sha256": sha(json.dumps(messages, separators=(",", ":"), ensure_ascii=False).encode())}
                    print(f"{model}: {case['id']} attempt {attempt}", flush=True)
                    started = time.monotonic()
                    holder = {}
                    def work():
                        try:
                            holder["raw"] = request("/api/chat", payload, timeout=ATTEMPT_SECONDS)
                        except Exception as error:
                            holder["error"] = error
                    thread = threading.Thread(target=work, daemon=True)
                    thread.start()
                    try:
                        while thread.is_alive():
                            thread.join(timeout=10)
                            sample = resource_sample()
                            emit({"phase": "resource_sample", **sample})
                            if sample["pod_uid"] != initial["pod_uid"]:
                                raise RuntimeError("model_pod_replaced")
                            if time.monotonic() - started >= ATTEMPT_SECONDS:
                                raise TimeoutError("attempt_timeout")
                        if "error" in holder:
                            raise holder["error"]
                        raw = holder["raw"]
                        response = json.loads(raw)
                    except Exception as error:
                        emit({"phase": "attempt_failure", **binding, "reason": type(error).__name__,
                              "latency_seconds": round(time.monotonic() - started, 3)})
                        raise
                    message = response.get("message", {})
                    content = message.get("content")
                    if not isinstance(content, str):
                        feedback = ["invalid_response_shape"]
                    elif len(content.encode()) > MAX_RESPONSE_BYTES:
                        feedback = ["response_budget_exceeded"]
                    else:
                        feedback = validate_output(content, packet)
                    if response.get("done") is not True or response.get("done_reason") != "stop":
                        feedback += ["incomplete_generation"]
                        if response.get("done_reason") == "length":
                            feedback += ["generation_limit_reached"]
                    if message.get("thinking"):
                        feedback += ["unexpected_thinking"]
                    feedback = sorted(set(feedback))
                    status = "rejected" if feedback else "accepted"
                    emit({"phase": "attempt_result", **binding, "status": status, "flags": feedback,
                          "response_sha256": sha(raw), "response_base64": base64.b64encode(raw).decode(),
                          "latency_seconds": round(time.monotonic() - started, 3), "cost_usd": None,
                          **{k: response.get(k) for k in ("prompt_eval_count", "eval_count", "total_duration", "load_duration", "eval_duration")}})
                    print(f"  {status}: {feedback}", flush=True)
                    if not feedback:
                        break
                    if set(feedback) & NON_CORRECTABLE_FLAGS:
                        status = "failed"
                        break
                    if attempt == 1:
                        emit({"phase": "correction_started", "case": case["id"], "attempt": 2,
                              "validator_categories": feedback, "rejected_output_in_correction_prompt": False})
                emit({"phase": "case_complete", "case": case["id"], "status": status, "attempts": attempt})
                completed += 1
            emit({"phase": "candidate_complete", "model": model, "completed_cases": completed})
        except Exception as error:
            emit({"phase": "candidate_failed", "model": model, "completed_cases": completed,
                  "reason": type(error).__name__})
            # Cancel an in-flight request as well as unloading weights. PVC is retained.
            kube("-n", "gtrainer-models", "scale", "deployment/ollama", "--replicas=0")
            raise
    print(f"Private capture: {path}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", nargs="+", choices=tuple(MODELS), required=True)
    parser.add_argument("--approve-synthetic-run", action="store_true")
    args = parser.parse_args()
    if not args.approve_synthetic_run:
        parser.error("Explicit --approve-synthetic-run is required")
    if len(set(args.models)) != len(args.models):
        parser.error("Duplicate candidate selection")
    os.umask(0o077)
    with (Path.home() / ".gtrainer-mac-model-run.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        preflight(args.models)
        directory = Path.home() / (".gtrainer-mac-review-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
        directory.mkdir(mode=0o700)
        print(f"Private run directory: {directory}", flush=True)
        for model in args.models:
            run_model(model, directory)


if __name__ == "__main__":
    main()
