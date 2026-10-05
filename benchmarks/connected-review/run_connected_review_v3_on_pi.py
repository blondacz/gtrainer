#!/usr/bin/env python3
"""Run connected-review evaluation-v3 on owned Raspberry Pi pods.

Never connects to the app's model route or reads its database. Raw captures are
written only to the owner's ignored private-data directory on the operator Mac.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

from connected_review_packets_v2 import ROOT, load_cases, materialize, prompt
from connected_review_validator_v3 import validate_detailed


OLLAMA_IMAGE = "ollama/ollama@sha256:0d7a1b2e50d33428f0117535a25933fa61f8868f383c8e1d07823889412c8084"
PYTHON_IMAGE = "python@sha256:ff547c46029c9cd2dbce2f1ce5873debb58b9ccfeeac2ded49f72d15f47273e2"
MODELS = {
    "granite4.2:3b": "40577dc168a3a9ad34e9a1234e0c2570be86097fa75a236d4574ae985705d3c4",
    "ministral-3:3b": "f04aa1c738f64e13c625b82ae92504fc0260fa6723b509ed1ece0fa188179b1d",
    "qwen3.5:4b": "2a654d98e6fba55d452b7043684e9b57a947e393bbffa62485a7aac05ee4eefd",
    "deepseek-r1:1.5b": "e0979632db5a88d1a53884cb2a941772d10ff5d055aabaa6801c4e36f3a6c2d7",
    "gemma4:e2b": "b37049369adfe3d2b653af0ab301a062ca5cbe96ace6aa0d3f2559ee9b563fc2",
    "phi4-mini:3.8b": "78fad5d182a7c33065e153a5f8ba210754207ba9d91973f57dffa7f487363753",
    "gtrainer-qwen3:8b-q3_k_m": "c2c61b55d39ec6fc43f84b4cbbed4505fde386d4e03ed005087bfd8c69c502c3",
}
QWEN3_Q3 = {
    "model": "gtrainer-qwen3:8b-q3_k_m",
    "filename": "Qwen_Qwen3-8B-Q3_K_M.gguf",
    "sha256": MODELS["gtrainer-qwen3:8b-q3_k_m"],
    "size_bytes": 4124161568,
    "url": "https://huggingface.co/bartowski/Qwen_Qwen3-8B-GGUF/resolve/0b69f75b7472688e6808490aa2b85efdb81b5ce7/Qwen_Qwen3-8B-Q3_K_M.gguf",
    "storage_path": "/var/lib/gtrainer-models/qwen3-8b-q3",
}
SSH = ["ssh", "-i", str(Path("~/.ssh/gtrainer_pi").expanduser()), "-o", "IdentitiesOnly=yes",
       "-o", "StrictHostKeyChecking=yes", "-o", "HostKeyAlias=192.168.1.232", "-o", "BatchMode=yes",
       "-o", "ConnectTimeout=8", "blondacz@192.168.1.231"]
OPTIONS = {"num_ctx": 4096, "num_predict": 1536, "num_thread": 3, "temperature": 0, "seed": 42}
MAX_PACKET_BYTES = 32 * 1024
MAX_RESPONSE_BYTES = 24 * 1024
ATTEMPT_SECONDS = 480
CASE_SECONDS = 1000
NON_CORRECTABLE_FLAGS = {"response_budget_exceeded", "incomplete_generation", "generation_limit_reached", "unexpected_thinking"}


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def stable_app_state(baseline, current) -> bool:
    """Historical restarts (for example a host reboot) are not new failures."""
    return bool(baseline) and all(container[2] for container in baseline) and current == baseline


def monitor_command_timeout(value: str) -> int:
    seconds = int(value)
    if not 10 <= seconds <= 120:
        raise argparse.ArgumentTypeError("Monitor command timeout must be 10–120 seconds")
    return seconds


def reference_config(model: str, q3_reference: bool, memory_limit_mib: int = 5120,
                     attempt_timeout_seconds: int = 480) -> dict:
    if q3_reference != (model == QWEN3_Q3["model"]):
        raise ValueError("Qwen3 Q3 requires its separate reference profile")
    if not 5120 <= memory_limit_mib <= 5632 or (not q3_reference and memory_limit_mib != 5120):
        raise ValueError("Only the Q3 reference permits a 5120–5632 MiB memory limit")
    if not 480 <= attempt_timeout_seconds <= 900 or (not q3_reference and attempt_timeout_seconds != 480):
        raise ValueError("Only the Q3 reference permits a 480–900 second attempt timeout")
    return {
        "options": dict(OPTIONS, num_ctx=2048) if q3_reference else dict(OPTIONS),
        "minimum_host_available_mib": 1024 if q3_reference else 768,
        "container_memory_limit_mib": memory_limit_mib,
        "attempt_timeout_seconds": attempt_timeout_seconds,
        "case_total_timeout_seconds": 2 * attempt_timeout_seconds + 40,
        "evaluation_version": "connected-review-pi-qwen3-q3-microsd-reference-v1" if q3_reference else "connected-review-evaluation-v3",
    }


def selected_cases(start_case: "str | None", resume_capture_sha256: "str | None", q3_reference: bool) -> list:
    cases = load_cases()
    if start_case is None and resume_capture_sha256 is None:
        return cases
    if not q3_reference or start_case not in [case["id"] for case in cases]:
        raise ValueError("Only the Q3 reference permits a known resume start case")
    if resume_capture_sha256 is None or len(resume_capture_sha256) != 64 or any(
            c not in "0123456789abcdef" for c in resume_capture_sha256):
        raise ValueError("A resume requires the previous capture SHA-256")
    return cases[[case["id"] for case in cases].index(start_case):]


def output_schema(packet: dict) -> dict:
    context_ids = [item["contextId"] for item in packet["context"]]
    context_id_schema = {"type": "string"}
    if context_ids:
        context_id_schema["enum"] = context_ids
    source_schema = {
        "type": "object", "additionalProperties": False,
        "properties": {
            "evidenceIds": {"type": "array", "items": {"type": "string", "enum": [f["evidenceId"] for f in packet["evidence"]["facts"]]}},
            "context": {"type": "array", "items": {"type": "object", "additionalProperties": False,
                "properties": {"contextId": context_id_schema, "revision": {"type": "integer", "enum": [1]}},
                "required": ["contextId", "revision"]}},
        }, "required": ["evidenceIds", "context"],
    }
    claim = {"type": "object", "additionalProperties": False, "properties": {
        "text": {"type": "string"}, "sources": source_schema,
        "scope": {"type": "object", "additionalProperties": False, "properties": {
            "from": {"type": "string"}, "until": {"type": "string"},
            "sport": {"type": ["string", "null"]}}, "required": ["from", "until", "sport"]},
        "uncertainty": {"type": "string", "enum": ["low", "moderate", "high", "unknown"]},
    }, "required": ["text", "sources", "scope", "uncertainty"]}
    return {"type": "object", "additionalProperties": False, "properties": {
        "profile": {"type": "string", "enum": ["connected-review-v1"]},
        "interpretations": {"type": "array", "items": claim, "maxItems": 8},
        "questions": {"type": "array", "items": claim, "maxItems": 8},
    }, "required": ["profile", "interpretations", "questions"]}


def validate_output(raw: str, packet: dict) -> list[str]:
    """Return stable hard-validation categories; rubric remains human-scored."""
    return validate_detailed(raw, packet)


def pi_main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, choices=tuple(MODELS))
    parser.add_argument("--owner", required=True)
    parser.add_argument("--monitor-command-timeout-seconds", type=monitor_command_timeout, default=10)
    parser.add_argument("--qwen3-q3-microsd-reference", action="store_true")
    parser.add_argument("--model-memory-limit-mib", type=int, default=5120)
    parser.add_argument("--attempt-timeout-seconds", type=int, default=480)
    parser.add_argument("--start-case")
    parser.add_argument("--resume-capture-sha256")
    args = parser.parse_args()
    if len(args.owner) != 32 or any(c not in "0123456789abcdef" for c in args.owner):
        raise SystemExit("Invalid owned-resource identifier")
    model = args.model
    owner = args.owner
    monitor_timeout = args.monitor_command_timeout_seconds
    q3_reference = args.qwen3_q3_microsd_reference
    config = reference_config(model, q3_reference, args.model_memory_limit_mib, args.attempt_timeout_seconds)
    cases_to_run = selected_cases(args.start_case, args.resume_capture_sha256, q3_reference)
    generation_options = config["options"]
    minimum_host_mib = config["minimum_host_available_mib"]
    evaluation_version = config["evaluation_version"]
    attempt_seconds = config["attempt_timeout_seconds"]
    case_seconds = config["case_total_timeout_seconds"]
    namespace = "gtrainer-connected-" + owner[:12]
    labels = {"app.kubernetes.io/managed-by": "gtrainer-connected-review-evaluation",
              "gtrainer.io/benchmark-owner": owner, "gtrainer.io/synthetic-only": "true"}
    pod_name = "model"
    def free_port():
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            return listener.getsockname()[1]
    ollama_port, app_port = free_port(), free_port()
    while app_port == ollama_port:
        app_port = free_port()
    namespace_uid = None
    forwards = []
    monitor = None
    completed = 0
    stage = "baseline_health_check"

    def emit(item):
        print(json.dumps(item, separators=(",", ":"), allow_nan=False), flush=True)

    def run(*command, timeout=20, data=None):
        result = subprocess.run(command, input=data, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout)
        if result.returncode:
            raise RuntimeError("owned_pi_command_failed")
        return result.stdout

    def kubectl(*command, timeout=30, data=None):
        return run("sudo", "-n", "k3s", "kubectl", *command, timeout=timeout, data=data).decode().strip()

    def app_state():
        obj = json.loads(kubectl("-n", "gtrainer", "get", "pods", "-o", "json", timeout=monitor_timeout))
        return sorted((p["metadata"]["name"], c["image"], c["ready"], c["restartCount"])
                      for p in obj["items"] for c in p.get("status", {}).get("containerStatuses", []))

    def host_state():
        mem = {line.split(":")[0]: int(line.split()[1]) for line in Path("/proc/meminfo").read_text().splitlines()}
        thermal = Path("/sys/class/thermal/thermal_zone0/temp")
        return {"host_available_mib": round(mem["MemAvailable"] / 1024, 1),
                "temperature_c": int(thermal.read_text()) / 1000 if thermal.exists() else None}

    def request_raw(path, body=None, timeout=10):
        request_body = None if body is None else json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode()
        req = urllib.request.Request(f"http://127.0.0.1:{ollama_port}" + path, data=request_body,
                                     headers={"Content-Type": "application/json"} if body is not None else {})
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read(65537)
            if len(raw) > 65536:
                raise RuntimeError("provider_response_wrapper_too_large")
            return raw

    def request(path, body=None, timeout=10):
        return json.loads(request_raw(path, body, timeout))

    class ResourceMonitor:
        def __init__(self, expected_app):
            self.expected_app = expected_app
            self.failed = threading.Event()
            self.stop = threading.Event()
            self.samples = []
            self.thread = threading.Thread(target=self.collect, daemon=True)

        def sample(self):
            sample = host_state()
            sample["app_state_stable"] = stable_app_state(self.expected_app, app_state())
            with urllib.request.urlopen(f"http://127.0.0.1:{app_port}/healthz", timeout=3) as response:
                sample["app_health_ok"] = response.status == 200
                response.read(1024)
            state = json.loads(kubectl("-n", namespace, "get", "pod", pod_name, "-o", "json", timeout=monitor_timeout))
            statuses = state.get("status", {}).get("containerStatuses", [])
            sample["benchmark_pod_healthy"] = (state.get("status", {}).get("phase") == "Running" and
                len(statuses) == 2 and all(c.get("ready") and c.get("restartCount") == 0 for c in statuses))
            cgroup = kubectl("-n", namespace, "exec", pod_name, "-c", "ollama", "--", "sh", "-c",
                             "cat /sys/fs/cgroup/memory.current; cat /sys/fs/cgroup/memory.peak", timeout=monitor_timeout).splitlines()
            sample["ollama_current_mib"] = round(int(cgroup[0]) / 1024**2, 1)
            sample["ollama_peak_mib"] = round(int(cgroup[1]) / 1024**2, 1)
            sample["guard_ok"] = (sample["app_state_stable"] and sample["app_health_ok"] and
                sample["benchmark_pod_healthy"] and sample["host_available_mib"] >= minimum_host_mib and
                (sample["temperature_c"] is None or sample["temperature_c"] < 85))
            self.samples.append(sample)
            emit({"phase": "resource_sample", **sample})
            if not sample["guard_ok"]:
                self.failed.set()

        def collect(self):
            while not self.stop.is_set():
                try:
                    self.sample()
                except Exception as error:
                    self.failed.set()
                    emit({"phase": "resource_guard_failed", "reason": "health_or_resource_sample_unavailable",
                          "error_type": type(error).__name__})
                    return
                self.stop.wait(5)

        def start(self):
            self.sample()
            if self.failed.is_set():
                raise RuntimeError("resource_guard_failed")
            self.thread.start()

        def finish(self):
            self.stop.set()
            if self.thread.ident is not None:
                # A sample has three bounded kubectl calls and a health request.
                self.thread.join(timeout=3 * monitor_timeout + 5)
            return self.samples

    def call(payload, case_id, attempt, packet, timeout_seconds):
        raw_request = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()
        if len(raw_request) > MAX_PACKET_BYTES:
            raise RuntimeError("input_budget_exceeded")
        prompt_bytes = json.dumps(payload["messages"], separators=(",", ":"), ensure_ascii=False).encode()
        packet_bytes = payload["messages"][1]["content"].encode()
        binding = {"packet_sha256": sha(packet_bytes), "prompt_sha256": sha(prompt_bytes)}
        started = time.monotonic()
        holder = {}

        def work():
            try:
                response_raw = request_raw("/api/chat", payload, timeout=timeout_seconds)
                response = json.loads(response_raw)
                holder["response"] = response
                holder["raw"] = response_raw
            except Exception as error:
                holder["error"] = type(error).__name__

        thread = threading.Thread(target=work, daemon=True)
        thread.start()
        try:
            while thread.is_alive():
                thread.join(timeout=1)
                if monitor.failed.is_set():
                    raise RuntimeError("resource_guard_failed")
                if time.monotonic() - started >= timeout_seconds:
                    raise RuntimeError("attempt_timeout")
        except Exception as error:
            emit({"phase": "attempt_failure", "case": case_id, "attempt": attempt,
                  "reason": str(error) if str(error) in ("attempt_timeout", "resource_guard_failed") else "provider_unavailable",
                  "request_sha256": sha(raw_request), "request_base64": base64.b64encode(raw_request).decode(),
                  **binding,
                  "response_sha256": sha(holder["raw"]) if "raw" in holder else None,
                  "response_base64": base64.b64encode(holder["raw"]).decode() if "raw" in holder else None,
                  "latency_seconds": round(time.monotonic() - started, 3), "cost_usd": None})
            raise
        latency = time.monotonic() - started
        if "error" in holder:
            emit({"phase": "attempt_failure", "case": case_id, "attempt": attempt,
                  "reason": "provider_unavailable", "error_type": holder["error"], "latency_seconds": round(latency, 3),
                  "request_sha256": sha(raw_request), "request_base64": base64.b64encode(raw_request).decode(), **binding})
            raise RuntimeError("provider_unavailable")
        response = holder["response"]
        message = response.get("message") if isinstance(response, dict) else None
        content = message.get("content") if isinstance(message, dict) else None
        response_bytes = content.encode() if isinstance(content, str) else b""
        if not isinstance(content, str) or not isinstance(message, dict):
            flags = ["invalid_response_shape"]
        elif len(response_bytes) > MAX_RESPONSE_BYTES:
            flags = ["response_budget_exceeded"]
        else:
            flags = validate_output(content, packet)
        if not isinstance(response, dict) or response.get("done") is not True or response.get("done_reason") != "stop":
            reason = response.get("done_reason") if isinstance(response, dict) else None
            flags = sorted(set(flags + ["incomplete_generation"] + (["generation_limit_reached"] if reason == "length" else [])))
        if isinstance(message, dict) and message.get("thinking"):
            flags = sorted(set(flags + ["unexpected_thinking"]))
        emit({"phase": "attempt_result", "case": case_id, "attempt": attempt,
              "status": "rejected" if flags else "accepted", "flags": flags,
              "request_sha256": sha(raw_request), "request_base64": base64.b64encode(raw_request).decode(),
              **binding,
              "response_sha256": sha(holder["raw"]), "response_base64": base64.b64encode(holder["raw"]).decode(),
              "latency_seconds": round(latency, 3), "prompt_eval_count": response.get("prompt_eval_count"),
              "eval_count": response.get("eval_count"), "total_duration_ns": response.get("total_duration"),
              "load_duration_ns": response.get("load_duration"), "eval_duration_ns": response.get("eval_duration"),
              "cost_usd": None, "resource_samples": monitor.samples[-max(1, int(latency / 5) + 2):]})
        return flags

    try:
        baseline_app = app_state()
        if not stable_app_state(baseline_app, baseline_app):
            raise RuntimeError("live_app_health_baseline_failed")
        time.sleep(10)
        if not stable_app_state(baseline_app, app_state()):
            raise RuntimeError("live_app_health_baseline_unstable")
        emit({"phase": "live_app_baseline", "containers": baseline_app})
        if host_state()["host_available_mib"] < minimum_host_mib:
            raise RuntimeError("insufficient_host_headroom")
        stage = "owned_namespace_create"
        ns_obj = {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": namespace, "labels": labels}}
        kubectl("create", "-f", "-", data=json.dumps(ns_obj).encode())
        namespace_uid = json.loads(kubectl("get", "namespace", namespace, "-o", "json"))["metadata"]["uid"]
        deny_ingress = {"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy",
            "metadata": {"name": "no-inbound", "namespace": namespace, "labels": labels},
            "spec": {"podSelector": {}, "policyTypes": ["Ingress"], "ingress": []}}
        pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": pod_name, "namespace": namespace, "labels": labels},
            "spec": {"automountServiceAccountToken": False, "activeDeadlineSeconds": 7200, "restartPolicy": "Never",
                "nodeSelector": {"kubernetes.io/arch": "arm64"}, "securityContext": {"fsGroup": 10001},
                "containers": [
                    {"name": "ollama", "image": OLLAMA_IMAGE, "imagePullPolicy": "IfNotPresent",
                     "command": ["ollama", "serve"], "securityContext": {"runAsNonRoot": True, "runAsUser": 10001,
                         "runAsGroup": 10001, "allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                         "capabilities": {"drop": ["ALL"]}, "seccompProfile": {"type": "RuntimeDefault"}},
                     "env": [{"name": k, "value": v} for k, v in {"HOME": "/models", "OLLAMA_MODELS": "/models/blobs",
                         "OLLAMA_HOST": "0.0.0.0:11434", "OLLAMA_NO_CLOUD": "1", "OLLAMA_NUM_PARALLEL": "1",
                          "OLLAMA_MAX_LOADED_MODELS": "1", "OLLAMA_MAX_QUEUE": "2", "OLLAMA_CONTEXT_LENGTH": "2048"}.items()],
                     "resources": {"requests": {"cpu": "500m", "memory": "512Mi"}, "limits": {"cpu": "3", "memory": "5Gi"}},
                     "volumeMounts": [{"name": "models", "mountPath": "/models"}, {"name": "tmp", "mountPath": "/tmp"}],
                     "readinessProbe": {"exec": {"command": ["ollama", "list"]}, "initialDelaySeconds": 5, "periodSeconds": 10}},
                    {"name": "probe", "image": PYTHON_IMAGE, "imagePullPolicy": "IfNotPresent",
                     "command": ["python", "-c", "import time; time.sleep(7200)"],
                     "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "runAsGroup": 10001,
                         "allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                         "capabilities": {"drop": ["ALL"]}, "seccompProfile": {"type": "RuntimeDefault"}},
                     "resources": {"requests": {"cpu": "10m", "memory": "24Mi"}, "limits": {"cpu": "50m", "memory": "96Mi"}}}],
                 "volumes": [{"name": "models", "emptyDir": {"sizeLimit": "5Gi"}},
                             {"name": "tmp", "emptyDir": {"sizeLimit": "64Mi"}}]}}
        if q3_reference:
            pod["spec"]["volumes"][0] = {"name": "models", "hostPath": {
                "path": QWEN3_Q3["storage_path"], "type": "Directory"}}
            pod["spec"]["containers"][0]["resources"]["limits"]["memory"] = str(config["container_memory_limit_mib"]) + "Mi"
        stage = "owned_pod_create"
        kubectl("create", "-f", "-", data=json.dumps({"apiVersion": "v1", "kind": "List", "items": [deny_ingress, pod]}).encode(), timeout=60)
        stage = "owned_pod_ready"
        kubectl("-n", namespace, "wait", "--for=condition=Ready", "pod/" + pod_name, "--timeout=240s", timeout=250)
        stage = "port_forward_start"
        ollama_forward = subprocess.Popen(["sudo", "-n", "k3s", "kubectl", "-n", namespace, "port-forward", "pod/" + pod_name, f"{ollama_port}:11434", "--address=127.0.0.1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        app_forward = subprocess.Popen(["sudo", "-n", "k3s", "kubectl", "-n", "gtrainer", "port-forward", "service/gtrainer", f"{app_port}:8080", "--address=127.0.0.1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        forwards.extend([ollama_forward, app_forward])
        stage = "runtime_health_check"
        for _ in range(30):
            try:
                version = request("/api/version")["version"]
                if version != "0.35.0":
                    raise RuntimeError("ollama_version_mismatch")
                with urllib.request.urlopen(f"http://127.0.0.1:{app_port}/healthz", timeout=2) as response:
                    if response.status != 200:
                        raise RuntimeError("live_app_health_failed")
                if app_forward.poll() is not None:
                    raise RuntimeError("app_port_forward_unavailable")
                break
            except Exception:
                if ollama_forward.poll() is not None:
                    raise RuntimeError("port_forward_unavailable")
                time.sleep(1)
        else:
            raise RuntimeError("ollama_health_timeout")
        stage = "resource_monitor_start"
        monitor = ResourceMonitor(baseline_app)
        monitor.start()
        if not q3_reference and request("/api/tags").get("models") != []:
            raise RuntimeError("unexpected_preloaded_model")
        emit({"phase": "runtime_ready", "model": model, "ollama_version": version,
              "candidate_digest_expected": None if q3_reference else MODELS[model], "host": host_state(), "synthetic_only": True,
              "monitor_command_timeout_seconds": monitor_timeout,
              "minimum_host_available_mib": minimum_host_mib,
              "container_memory_limit_mib": config["container_memory_limit_mib"],
              "model_source": QWEN3_Q3 if q3_reference else None})
        stage = "model_staging"
        if q3_reference:
            blob_path = "/models/blobs/blobs/sha256-" + QWEN3_Q3["sha256"]
            blob_hash = kubectl("-n", namespace, "exec", pod_name, "-c", "ollama", "--",
                                "sha256sum", blob_path, timeout=180).split()[0]
            if blob_hash != QWEN3_Q3["sha256"]:
                raise RuntimeError("gguf_digest_mismatch")
        pull_started = time.monotonic()
        pull_result = {}
        staging_endpoint = "/api/create" if q3_reference else "/api/pull"
        staging_payload = {"model": model, "stream": False}
        if q3_reference:
            staging_payload["files"] = {QWEN3_Q3["filename"]: "sha256:" + QWEN3_Q3["sha256"]}
        pull_thread = threading.Thread(target=lambda: pull_result.update(
            value=request(staging_endpoint, staging_payload, timeout=900)), daemon=True)
        pull_thread.start()
        pull_started_mono = time.monotonic()
        while pull_thread.is_alive():
            pull_thread.join(timeout=1)
            if monitor.failed.is_set():
                raise RuntimeError("resource_guard_failed")
            if time.monotonic() - pull_started_mono >= 900:
                raise RuntimeError("model_staging_timeout")
        pulled = pull_result.get("value", {})
        if pulled.get("status") != "success":
            raise RuntimeError("model_staging_failed")
        tags = request("/api/tags").get("models", [])
        if len(tags) != 1 or tags[0].get("name") != model or (not q3_reference and tags[0].get("digest") != MODELS[model]):
            raise RuntimeError("model_digest_mismatch")
        if q3_reference:
            shown = request("/api/show", {"model": model})
            if shown.get("details", {}).get("quantization_level") != "Q3_K_M" or QWEN3_Q3["sha256"] not in shown.get("modelfile", ""):
                raise RuntimeError("imported_model_identity_mismatch")
            emit({"phase": "imported_model_metadata", "details": shown.get("details"),
                  "template_sha256": sha(shown.get("template", "").encode()),
                  "capabilities": shown.get("capabilities"), "gguf_sha256": QWEN3_Q3["sha256"]})
        emit({"phase": "model_staged", "model": model, "digest": tags[0]["digest"],
              "size_bytes": tags[0].get("size"), "staging_seconds": round(time.monotonic() - pull_started, 3)})
        stage = "runtime_egress_policy_create"
        deny_egress = {"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy",
            "metadata": {"name": "no-runtime-egress", "namespace": namespace, "labels": labels},
            "spec": {"podSelector": {}, "policyTypes": ["Egress"], "egress": []}}
        kubectl("create", "-f", "-", data=json.dumps(deny_egress).encode())
        stage = "runtime_egress_probe"
        probe = "import socket,json; s=socket.socket(); s.settimeout(2);\ntry:\n s.connect(('1.1.1.1',443)); print(json.dumps({'blocked':False})); raise SystemExit(2)\nexcept OSError:\n print(json.dumps({'blocked':True}))"
        blocked = json.loads(kubectl("-n", namespace, "exec", pod_name, "-c", "probe", "--", "python", "-c", probe, timeout=20))
        if not blocked.get("blocked"):
            raise RuntimeError("runtime_egress_not_blocked")
        stage = "evaluation_start"
        first_prompt = prompt(load_cases()[0])
        artifact_hashes = dict(ARTIFACT_HASHES, settings=sha(json.dumps(generation_options, sort_keys=True, separators=(",", ":")).encode()))
        emit({"phase": "evaluation_started", "runtime_egress_blocked": True, "settings": generation_options,
              "max_response_bytes": MAX_RESPONSE_BYTES, "attempt_timeout_seconds": attempt_seconds,
              "case_total_timeout_seconds": case_seconds,
              "monitor_command_timeout_seconds": monitor_timeout,
              "minimum_host_available_mib": minimum_host_mib,
              "container_memory_limit_mib": config["container_memory_limit_mib"],
              "model_digest": tags[0]["digest"], "model_source": QWEN3_Q3 if q3_reference else None,
              "storage_kind": "persistent-microsd-hostpath" if q3_reference else "ephemeral-emptydir",
              "maximum_attempts_per_case": 2, "inference_cost_usd": None,
              "evaluation_version": evaluation_version, "contract_version": "connected-review-v1",
              "rubric_version": "connected-review-rubric-v2", "validator_version": "connected-review-validator-v3",
              "artifact_sha256": artifact_hashes,
              "system_prompt_sha256": sha(first_prompt["systemInstructions"].encode())})
        if args.start_case is not None:
            emit({"phase": "evaluation_resumed", "previous_capture_sha256": args.resume_capture_sha256,
                  "start_case": args.start_case, "selected_case_ids": [case["id"] for case in cases_to_run],
                  "previous_outcomes_not_copied": True})
        for case in cases_to_run:
            packet = materialize(case)
            prompt_data = prompt(case)
            schema = output_schema(packet)
            case_start = time.monotonic()
            feedback = None
            final_status = "not_run"
            attempts_made = 0
            for attempt in (1, 2):
                attempts_made = attempt
                if monitor.failed.is_set():
                    raise RuntimeError("resource_guard_failed")
                if time.monotonic() - case_start >= case_seconds:
                    emit({"phase": "case_failure", "case": case["id"], "attempt": attempt,
                          "reason": "case_timeout", "previous_outcomes_retained": True})
                    final_status = "failed"
                    break
                messages = [{"role": "system", "content": prompt_data["systemInstructions"]},
                            {"role": "user", "content": prompt_data["userPacketJson"]}]
                if feedback is not None:
                    messages.append({"role": "user", "content": "The independent whole-draft validator rejected the prior draft with these fixed categories: " + ", ".join(feedback) + ". Return a corrected complete JSON draft grounded only in the same packet. Do not add unsupported claims."})
                payload = {"model": model, "messages": messages, "format": schema,
                    "options": generation_options, "stream": False, "think": False, "keep_alive": "0s"}
                if len(json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()) > MAX_PACKET_BYTES:
                    raise RuntimeError("input_budget_exceeded")
                try:
                    remaining = case_seconds - (time.monotonic() - case_start)
                    if remaining <= 0:
                        raise RuntimeError("case_timeout")
                    feedback = call(payload, case["id"], attempt, packet, min(attempt_seconds, remaining))
                except Exception as error:
                    emit({"phase": "case_failure", "case": case["id"], "attempt": attempt,
                          "reason": str(error) if str(error) in ("attempt_timeout", "resource_guard_failed", "provider_unavailable", "input_budget_exceeded") else "provider_unavailable",
                          "previous_outcomes_retained": True})
                    raise
                final_status = "rejected" if feedback else "accepted"
                if not feedback:
                    break
                if set(feedback).intersection(NON_CORRECTABLE_FLAGS):
                    final_status = "failed"
                    break
                if attempt == 1:
                    emit({"phase": "correction_started", "case": case["id"], "attempt": 2,
                          "validator_categories": feedback, "rejected_output_in_correction_prompt": False})
            emit({"phase": "case_complete", "case": case["id"], "status": final_status,
                  "attempts": attempts_made})
            completed += 1
        samples = monitor.finish()
        monitor = None
        emit({"phase": "candidate_complete", "model": model, "completed_cases": completed,
              "resource_samples": samples, "final_live_app_state": app_state(), "host": host_state()})
    except Exception as error:
        emit({"phase": "candidate_failed", "model": model, "completed_cases": completed,
              "failure_stage": stage, "failure_type": type(error).__name__,
              "reason": str(error) if str(error).isidentifier() else "bounded_execution_failed",
              "host": host_state()})
        if namespace_uid is not None:
            # Capture termination/OOM evidence before owned cleanup removes the pod.
            try:
                failed_pod = json.loads(kubectl("-n", namespace, "get", "pod", pod_name, "-o", "json", timeout=10))
                emit({"phase": "failure_diagnostics", "pod_status": failed_pod.get("status", {})})
            except Exception as diagnostic_error:
                emit({"phase": "failure_diagnostics_unavailable", "error_type": type(diagnostic_error).__name__})
            if q3_reference:
                try:
                    logs = kubectl("-n", namespace, "logs", pod_name, "-c", "ollama",
                                   "--tail=120", "--timestamps=true", "--limit-bytes=16384", timeout=60)
                    emit({"phase": "owned_ollama_failure_logs", "logs": logs[-16384:]})
                except Exception as diagnostic_error:
                    emit({"phase": "owned_ollama_failure_logs_unavailable", "error_type": type(diagnostic_error).__name__})
        raise
    finally:
        if monitor is not None:
            emit({"phase": "resource_samples_final", "samples": monitor.finish()})
        for process in forwards:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        if namespace_uid is not None:
            current = json.loads(kubectl("get", "namespace", namespace, "-o", "json"))
            if current["metadata"]["uid"] != namespace_uid or current["metadata"].get("labels", {}).get("gtrainer.io/benchmark-owner") != owner:
                raise RuntimeError("owned_namespace_identity_changed")
            kubectl("delete", "namespace", namespace, "--wait=true", "--timeout=90s", timeout=100)
            emit({"phase": "owned_cleanup_complete", "namespace_removed": True,
                  "final_live_app_state": app_state(), "host": host_state()})


def _remote_core() -> str:
    source = Path(__file__).read_text()
    start = source.index("def sha(")
    end = source.index("\ndef _remote_core(")
    return source[start:end]


def _remote_source() -> str:
    """Build a self-contained Pi-side runner without sending model outputs to the repo."""
    core = _remote_core()
    packet_source = (ROOT / "connected_review_packets_v2.py").read_text()
    validator_source = (ROOT / "connected_review_validator_v3.py").read_text()
    cases_raw = (ROOT / "cases-v2.json").read_text()
    artifacts = {
        "evaluation": sha((ROOT / "evaluation-v3.json").read_bytes()),
        "cases": sha(cases_raw.encode()),
        "packet_adapter": sha(packet_source.encode()),
        "validator": sha(validator_source.encode()),
        "contract_source": sha((ROOT.parents[1] / "backend/src/main/kotlin/com/gtrainer/InterpretationContractV1.kt").read_bytes()),
        "runner": sha(Path(__file__).read_bytes()),
        "settings": sha(json.dumps(OPTIONS, sort_keys=True, separators=(",", ":")).encode()),
    }
    return (
        "import sys,types,json\n"
        "u=__import__('urllib'); u.request=__import__('urllib.request',fromlist=['Request'])\n"
        "p=types.ModuleType('connected_review_packets_v2'); p.__file__='connected_review_packets_v2.py'; exec(compile(" + repr(packet_source) + ", p.__file__, 'exec'),p.__dict__); sys.modules[p.__name__]=p\n"
        "v=types.ModuleType('connected_review_validator_v3'); v.__file__='connected_review_validator_v3.py'; exec(compile(" + repr(validator_source) + ", v.__file__, 'exec'),v.__dict__); sys.modules[v.__name__]=v\n"
        "cases=json.loads(" + repr(cases_raw) + ")[\"cases\"]\n"
        "ns={'__name__':'pi_runner','Path':__import__('pathlib').Path,'json':json,'hashlib':__import__('hashlib'),'datetime':__import__('datetime').datetime,'timezone':__import__('datetime').timezone,'time':__import__('time'),'socket':__import__('socket'),'subprocess':__import__('subprocess'),'threading':__import__('threading'),'uuid':__import__('uuid'),'base64':__import__('base64'),'os':__import__('os'),'argparse':__import__('argparse'),'urllib':u,'MODELS':" + repr(MODELS) + ",'QWEN3_Q3':" + repr(QWEN3_Q3) + ",'OLLAMA_IMAGE':" + repr(OLLAMA_IMAGE) + ",'PYTHON_IMAGE':" + repr(PYTHON_IMAGE) + ",'OPTIONS':" + repr(OPTIONS) + ",'NON_CORRECTABLE_FLAGS':{'response_budget_exceeded','incomplete_generation','generation_limit_reached','unexpected_thinking'},'ARTIFACT_HASHES':" + repr(artifacts) + ",'MAX_PACKET_BYTES':32768,'MAX_RESPONSE_BYTES':24576,'ATTEMPT_SECONDS':480,'CASE_SECONDS':1000,'load_cases':lambda:cases,'materialize':p.materialize,'prompt':p.prompt,'validate_detailed':v.validate_detailed}\n"
        "exec(compile(" + repr(core) + ", 'connected_review_pi_core.py', 'exec'),ns)\n"
        "ns['pi_main']()\n"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--approve-synthetic-run", action="store_true")
    parser.add_argument("--models", nargs="+", choices=tuple(MODELS), required=True)
    parser.add_argument("--monitor-command-timeout-seconds", type=monitor_command_timeout, default=10,
                        help="Timeout per resource-monitor kubectl command (default: 10 seconds)")
    parser.add_argument("--qwen3-q3-microsd-reference", action="store_true",
                        help="Separate Q3/2048-context reference with persistent prepared microSD storage and 1 GiB headroom")
    parser.add_argument("--model-memory-limit-mib", type=int, default=5120,
                        help="Q3 reference only: container memory ceiling, 5120–5632 MiB")
    parser.add_argument("--attempt-timeout-seconds", type=int, default=480,
                        help="Q3 reference only: inference attempt budget including load, 480–900 seconds")
    parser.add_argument("--start-case", help="Q3 resume only: first remaining frozen case")
    parser.add_argument("--resume-capture-sha256", help="SHA-256 of the preserved previous capture")
    args = parser.parse_args()
    if not args.run:
        print("No inference. Use --run --approve-synthetic-run and explicitly selected --models.")
        return
    if not args.approve_synthetic_run:
        raise SystemExit("Explicit synthetic inference approval flag is required")
    if len(set(args.models)) != len(args.models):
        raise SystemExit("Duplicate candidate selection")
    if args.qwen3_q3_microsd_reference != (args.models == [QWEN3_Q3["model"]]) or (QWEN3_Q3["model"] in args.models and len(args.models) != 1):
        raise SystemExit("Qwen3 Q3 must be selected alone with --qwen3-q3-microsd-reference")
    for model in args.models:
        reference_config(model, args.qwen3_q3_microsd_reference, args.model_memory_limit_mib, args.attempt_timeout_seconds)
    selected_cases(args.start_case, args.resume_capture_sha256, args.qwen3_q3_microsd_reference)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    run_dir = Path.home() / (".gtrainer-connected-review-" + run_id)
    run_dir.mkdir(mode=0o700)
    os.chmod(run_dir, 0o700)
    remote = _remote_source().encode()
    for model in args.models:
        capture = run_dir / (model.replace(":", "-") + ".jsonl")
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        with capture.open("wb") as stream:
            os.chmod(capture, 0o600)
            process = subprocess.Popen(SSH + ["python3", "-", "--model", model, "--owner", uuid.uuid4().hex,
                                             "--monitor-command-timeout-seconds", str(args.monitor_command_timeout_seconds)] +
                                       (["--qwen3-q3-microsd-reference", "--model-memory-limit-mib", str(args.model_memory_limit_mib),
                                          "--attempt-timeout-seconds", str(args.attempt_timeout_seconds)] if args.qwen3_q3_microsd_reference else []) +
                                        (["--start-case", args.start_case, "--resume-capture-sha256", args.resume_capture_sha256]
                                         if args.start_case is not None else []),
                                       stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.DEVNULL, env=env)
            process.communicate(remote)
        if process.returncode:
            print(f"{model}: stopped; private capture retained at {capture}")
            raise SystemExit(process.returncode)
        print(f"{model}: complete; private capture retained at {capture}")
    print(f"Private run directory: {run_dir}")


if __name__ == "__main__":
    main()
