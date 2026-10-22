"""Owned, synthetic-only Mac pod adapter. Importing this module performs no operations."""
import base64
import hashlib
import http.client
import json
import math
import re
import subprocess
import threading
import time
import uuid

import acquire
import protocol

KUBE = "/usr/local/bin/gtrainer-kubectl"
NS = "gtrainer-models"
IMAGE = "ollama/ollama@sha256:9c1dc45ea758396139ec0adfa52947714c61f8d1e4537a6e2daef8138e7a64a9"
TEMPLATE = "ae370d884f108d16e7cc8fd5259ebc5773a0afa6e078b11f4ed7e39a27e0dfc4"
IDENTITY = {"nodeUid": "c533e6fe-e3fe-40ac-862a-134ee32243f2",
            "deploymentUid": "7c5a722f-f502-4bd2-8498-993f1b95d884",
            "pvcUid": "1c50c36a-05e0-4e33-8277-c4e3fbf30706", "image": IMAGE,
            "vmCpuCount": 6, "vmMemoryBytes": 10 * 1024**3}


def need(condition, category="runtime_refused"):
    if not condition:
        raise acquire.RuntimeFailure(category)


def memory_sample(raw):
    """Read cgroup counters without subtracting file cache from the stop metric."""
    try:
        lines = raw.decode("ascii").splitlines()
        current, peak = int(lines[0]), int(lines[1])
        counters = {}
        for line in lines[2:]:
            key, value = line.split()
            need(key not in counters)
            counters[key] = int(value)
        need(current >= 0 and peak >= 0 and all(value >= 0 for value in counters.values()))
        need("anon" in counters and "file" in counters)
    except (ValueError, IndexError, UnicodeError):
        need(False, "memory_diagnostics_unavailable")
    selected = ("anon", "file", "kernel", "shmem", "file_mapped", "active_file", "inactive_file", "pagetables")
    return current, peak, {key: counters[key] for key in selected if key in counters}


def context_proof(show):
    """Conservative token upper bound, NOT a measured tokenizer count.

    This pinned GPT-2 byte-level BPE template has no tools/history here. Its
    selected system/user/non-thinking assistant branch adds fewer than 256 bytes.
    Byte BPE cannot emit more content tokens than UTF-8 bytes. Reserve the full
    output allowance as well; refuse other templates/tokenizers or long profiles.
    """
    need(hashlib.sha256(show.get("template", "").encode()).hexdigest() == TEMPLATE, "context_template_mismatch")
    need(not show.get("system") and show.get("model_info", {}).get("tokenizer.ggml.model") == "gpt2")
    _, artifacts = protocol.verify()
    bounds = []
    for case in artifacts["cases"]:
        if case["profile"] == "context-intake-suggestion-v1":
            for kind in ("exactPrompt", "exactCorrectionPrompt"):
                prompt = case[kind]
                bounds.append(sum(len(prompt[k].encode()) for k in ("systemInstructions", "userPacketJson")) + 256)
    maximum = max(bounds)
    need(maximum + acquire.SETTINGS["num_predict"] <= acquire.SETTINGS["num_ctx"], "context_budget_exceeded")
    return {"method": "pinned_gpt2_byte_bpe_upper_bound", "templateSha256": TEMPLATE,
            "maximumInputTokenUpperBound": maximum, "reservedOutputTokens": acquire.SETTINGS["num_predict"],
            "contextTokens": acquire.SETTINGS["num_ctx"], "measuredTokenCount": None}


class LimitedPipe:
    def __init__(self, pipe, limit):
        self.pipe, self.remaining = pipe, limit

    def read(self, count=-1):
        count = self.remaining + 1 if count < 0 else min(count, self.remaining + 1)
        data = self.pipe.read(count)
        self.remaining -= len(data)
        need(self.remaining >= 0, "response_transport_limit")
        return data

    def readline(self, count=-1):
        data = self.pipe.readline(min(self.remaining + 1, 8193 if count < 0 else count))
        self.remaining -= len(data)
        need(self.remaining >= 0 and len(data) <= 8192, "response_transport_limit")
        return data

    def readinto(self, buffer):
        data = self.read(len(buffer))
        buffer[:len(data)] = data
        return len(data)

    def close(self):
        self.pipe.close()


class PipeSocket:
    def __init__(self, pipe):
        self.pipe = LimitedPipe(pipe, acquire.LIMITS["maxWrapperBytes"])

    def makefile(self, *args, **kwargs):
        return self.pipe


class MacRuntime:
    def __init__(self, *, request_builder=None, context_validator=None, request_profile="context-intake-extraction-v3"):
        need((request_builder is None) == (context_validator is None))
        need(request_builder is None or request_profile in ("context-intake-extraction-v3", "context-intake-extraction-v4", "context-intake-extraction-v5"))
        self.request_builder = request_builder
        self.context_validator = context_validator
        self.request_profile = request_profile
        self.pod = None
        self.pending_pod = None
        self.paused = False
        self.pause_pending = False
        self.restore_pending = False
        self.probed = False
        self.serial = 0
        self.prepared = False
        self.deadline = None
        self.cancellation_attempted = False

    def check_request_mode(self, run):
        # Hooks are trusted, source-bound tooling, never substitutes for approval.
        if self.context_validator is not None:
            need(run.get("profile") == self.request_profile)
            return self.context_validator(run)
        need(run.get("profile", "context-intake-suggestion-v1") == "context-intake-suggestion-v1")

    def command(self, args, body=None, timeout=15):
        if self.deadline is not None:
            remaining = self.deadline - time.monotonic()
            need(remaining > 0)
            timeout = min(timeout, remaining)
        result = subprocess.run(args, input=body, capture_output=True, timeout=timeout)
        need(result.returncode == 0 and len(result.stdout) <= 1024 * 1024, "owned_command_failed")
        return result.stdout

    def kube(self, args, body=None, timeout=15):
        return self.command([KUBE, "-n", NS, *args, "--request-timeout=10s"], body, timeout)

    def document(self, args):
        return json.loads(self.kube([*args, "-o", "json"]))

    def baseline(self):
        vm = json.loads(self.command(["limactl", "list", "gtrainer-models", "--json"]))
        need(vm["status"] == "Running" and vm["cpus"] == IDENTITY["vmCpuCount"]
             and vm["memory"] == IDENTITY["vmMemoryBytes"])
        node = self.document(["get", "node", "gtrainer-mac-vm"])
        need(node["metadata"]["uid"] == IDENTITY["nodeUid"] and node["status"]["capacity"]["cpu"] == "6")
        conditions = {x["type"]: x["status"] for x in node["status"]["conditions"]}
        need(conditions["Ready"] == "True" and all(conditions[k] == "False" for k in ("MemoryPressure", "DiskPressure", "PIDPressure")))
        dep = self.document(["get", "deployment", "ollama"])
        need(dep["metadata"]["uid"] == IDENTITY["deploymentUid"])
        container = dep["spec"]["template"]["spec"]["containers"][0]
        need(container["image"] == IMAGE and container["resources"]["limits"] == {"cpu": "3", "memory": "8Gi"})
        pvc = self.document(["get", "pvc", "models"])
        need(pvc["metadata"]["uid"] == IDENTITY["pvcUid"] and pvc["status"]["phase"] == "Bound")
        policies = self.document(["get", "networkpolicies"])["items"]
        for kind, name in (("Ingress", "no-inbound"), ("Egress", "no-runtime-egress")):
            policy = next(p for p in policies if p["metadata"]["name"] == name)["spec"]
            need(policy.get("podSelector") == {} and kind in policy["policyTypes"] and not policy.get(kind.lower()))
        need(self.command(["sysctl", "-n", "kern.memorystatus_vm_pressure_level"]).strip() == b"1", "host_memory_pressure")
        return dep

    def manifest(self, run):
        self.serial += 1
        name = "scoped-intake-" + run["runId"].replace("-", "")[:20] + "-" + str(self.serial)
        return {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": name, "namespace": NS,
                "labels": {"gtrainer-scoped-run": run["runId"]}}, "spec": {
            "restartPolicy": "Never", "activeDeadlineSeconds": acquire.LIMITS["batchTimeoutSeconds"] + 60,
            "automountServiceAccountToken": False, "nodeSelector": {"kubernetes.io/hostname": "gtrainer-mac-vm"},
            "securityContext": {"fsGroup": 10001}, "containers": [{"name": "ollama", "image": IMAGE,
                "imagePullPolicy": "Never", "command": ["ollama", "serve"],
                "env": [{"name": k, "value": v} for k, v in {
                    "HOME": "/models", "OLLAMA_MODELS": "/models/blobs", "OLLAMA_HOST": "127.0.0.1:11434",
                    "OLLAMA_NO_CLOUD": "1", "OLLAMA_NUM_PARALLEL": "1", "OLLAMA_MAX_LOADED_MODELS": "1",
                    "OLLAMA_MAX_QUEUE": "1", "OLLAMA_CONTEXT_LENGTH": "4096"}.items()],
                "resources": {"requests": {"cpu": "500m", "memory": "1Gi"}, "limits": {"cpu": "3", "memory": "8Gi"}},
                "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "runAsGroup": 10001,
                    "allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                    "capabilities": {"drop": ["ALL"]}, "seccompProfile": {"type": "RuntimeDefault"}},
                "volumeMounts": [{"name": "models", "mountPath": "/models"}, {"name": "tmp", "mountPath": "/tmp"}],
                "readinessProbe": {"exec": {"command": ["ollama", "list"]}, "periodSeconds": 2}}],
            "volumes": [{"name": "models", "persistentVolumeClaim": {"claimName": "models"}},
                        {"name": "tmp", "emptyDir": {"sizeLimit": "128Mi"}}]}}

    def create(self, run, deadline=None):
        need(self.pod is None and self.pending_pod is None)
        manifest = self.manifest(run)
        token = str(uuid.uuid4())
        manifest["metadata"]["annotations"] = {"gtrainer-scoped-create-token": token}
        name = manifest["metadata"]["name"]
        need(not self.kube(["get", "pod", name, "--ignore-not-found", "-o", "json"]).strip())
        # An applied mutation can outlive a lost response. Never discard its intent.
        self.pending_pod = {"name": name, "runId": run["runId"], "token": token}
        created = json.loads(self.kube(["create", "-f", "-", "-o", "json"], protocol.compact(manifest)))
        self.bind_pending_pod(created)
        remaining = 60 if deadline is None else min(60, math.floor(deadline - time.monotonic()))
        need(remaining > 0)
        self.kube(["wait", "--for=condition=Ready", "pod/" + self.pod["name"], f"--timeout={remaining}s"], timeout=remaining + 1)
        self.pod_status()

    def bind_pending_pod(self, document):
        intent = self.pending_pod
        need(intent is not None)
        metadata = document["metadata"]
        need(metadata["name"] == intent["name"]
             and metadata.get("labels", {}).get("gtrainer-scoped-run") == intent["runId"]
             and metadata.get("annotations", {}).get("gtrainer-scoped-create-token") == intent["token"])
        need(type(metadata["uid"]) is str and str(uuid.UUID(metadata["uid"])) == metadata["uid"])
        self.pod = {"name": metadata["name"], "uid": metadata["uid"]}
        self.pending_pod = None

    def scale_deployment(self, deployment, replicas, current):
        metadata = deployment["metadata"]
        version = metadata.get("resourceVersion")
        need(metadata["uid"] == IDENTITY["deploymentUid"] and type(version) is str
             and re.fullmatch("[1-9][0-9]*", version) is not None
             and deployment["spec"]["replicas"] == current)
        self.kube(["scale", "deployment/ollama", f"--replicas={replicas}",
                   f"--current-replicas={current}", f"--resource-version={version}"])

    def safety_state(self):
        return {"pod": self.pod, "pendingPod": self.pending_pod, "paused": self.paused,
                "pausePending": self.pause_pending, "restorePending": self.restore_pending}

    def pod_status(self):
        need(self.pod is not None)
        doc = self.document(["get", "pod", self.pod["name"]])
        need(doc["metadata"]["uid"] == self.pod["uid"] and doc["status"]["phase"] == "Running", "owned_pod_changed_or_unhealthy")
        statuses = doc["status"].get("containerStatuses", [])
        need(len(statuses) == 1 and statuses[0]["ready"] and statuses[0]["restartCount"] == 0)
        container = statuses[0]["containerID"].removeprefix("containerd://")
        need(re.fullmatch("[0-9a-f]{64}", container) is not None)
        self.pod["containerId"] = container
        return doc

    def launch(self, path, body=None):
        need(path in ("version", "tags", "ps", "show", "chat"))
        raw = b"" if body is None else protocol.compact(body)
        need(len(raw) <= 65536)
        need(self.deadline is None or time.monotonic() < self.deadline)
        method = "GET" if body is None else "POST"
        headers = (f"{method} /api/{path} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n"
                   f"Content-Type: application/json\r\nContent-Length: {len(raw)}\r\n\r\n").encode()
        # The pinned image has bash but no curl. stdin is data, never shell source.
        args = [KUBE, "-n", NS, "exec", "-i", self.pod["name"], "--", "bash", "-c",
                "exec 3<>/dev/tcp/127.0.0.1/11434; cat >&3; cat <&3"]
        proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        done, result = threading.Event(), {}

        def deliver():
            try:
                proc.stdin.write(headers + raw)
                proc.stdin.close()
            except Exception:
                result["error"] = True
                done.set()
                try:
                    proc.stdin.close()
                except Exception:
                    pass

        def read():
            try:
                response = http.client.HTTPResponse(PipeSocket(proc.stdout))
                response.begin()
                need(response.status == 200)
                data = response.read(acquire.LIMITS["maxWrapperBytes"] + 1)
                need(len(data) <= acquire.LIMITS["maxWrapperBytes"])
                result["raw"] = data
            except http.client.IncompleteRead as error:
                result["partial"] = error.partial[:acquire.LIMITS["maxWrapperBytes"]]
                result["error"] = True
            except Exception:
                result["error"] = True
            finally:
                done.set()

        try:
            # Pipe backpressure must not hide the client from deadline/cleanup code.
            threading.Thread(target=read, daemon=True).start()
            threading.Thread(target=deliver, daemon=True).start()
        except BaseException:
            self.finish_client(proc)
            raise
        return proc, done, result

    @staticmethod
    def finish_client(proc):
        if proc.poll() is None:
            proc.kill()
        proc.wait(timeout=5)

    def api(self, path, body=None):
        proc, done, holder = self.launch(path, body)
        try:
            remaining = 15 if self.deadline is None else min(15, max(0, self.deadline - time.monotonic()))
            need(done.wait(remaining) and "error" not in holder)
            return json.loads(holder["raw"])
        finally:
            self.finish_client(proc)

    def sample(self, emit):
        self.pod_status()
        node = self.document(["get", "node", "gtrainer-mac-vm"])
        need(node["metadata"]["uid"] == IDENTITY["nodeUid"])
        conditions = {x["type"]: x["status"] for x in node["status"]["conditions"]}
        need(conditions["Ready"] == "True" and all(conditions[k] == "False" for k in ("MemoryPressure", "DiskPressure", "PIDPressure")))
        raw = self.kube(["exec", self.pod["name"], "--", "sh", "-c",
                         "cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.stat"])
        current, peak, counters = memory_sample(raw)
        need(self.command(["sysctl", "-n", "kern.memorystatus_vm_pressure_level"]).strip() == b"1", "host_memory_pressure")
        emit({"type": "resource_sample", "podUid": self.pod["uid"], "memoryCurrentBytes": current,
              "memoryPeakBytesCumulative": peak, "memoryStatBytes": counters, "hostPressure": "normal"})
        need(current <= acquire.LIMITS["memoryAbortBytes"], "memory_abort_threshold")
        return current

    def preflight(self, run):
        self.check_request_mode(run)
        need(run["runtime"] == IDENTITY)
        dep = self.baseline()
        need(dep["spec"]["replicas"] == 1)
        pods = self.document(["get", "pods", "--all-namespaces"])["items"]
        need(all(p["metadata"]["namespace"] in (NS, "kube-system") for p in pods))
        models = [p for p in pods if p["metadata"]["namespace"] == NS]
        need(len(models) == 1 and models[0]["metadata"].get("labels", {}).get("app") == "ollama")
        # Read the existing server's metadata, but never treat that shared pod as owned.
        self.pod = {"name": models[0]["metadata"]["name"], "uid": models[0]["metadata"]["uid"]}
        try:
            need(self.api("version")["version"] == "0.35.0")
            tags = {m["name"]: m["digest"] for m in self.api("tags")["models"]}
            need(tags.get(acquire.MODEL["candidate"]) == acquire.MODEL["modelVersionSha256"])
            need(self.api("ps")["models"] == [])
            show = self.api("show", {"model": acquire.MODEL["candidate"]})
            proof = context_proof(show) if self.context_validator is None else self.context_validator(run, show)
            self.prepared = True
            return {"runtime": run["runtime"], "contextFit": proof, "downloads": False, "serviceCreated": False}
        finally:
            self.pod = None

    def stop_pod(self, deadline, emit):
        if self.pending_pod is not None:
            raw = self.kube(["get", "pod", self.pending_pod["name"], "--ignore-not-found", "-o", "json"])
            # Absence cannot exclude a still-in-flight create. Keep the fence.
            need(bool(raw.strip()))
            self.bind_pending_pod(json.loads(raw))
        if self.pod is None:
            return
        owned = dict(self.pod)
        need(str(uuid.UUID(owned["uid"])) == owned["uid"])
        raw = self.kube(["get", "pod", owned["name"], "--ignore-not-found", "-o", "json"])
        if raw.strip():
            doc = json.loads(raw)
            need(doc["metadata"]["uid"] == owned["uid"])
            statuses = doc["status"].get("containerStatuses", [])
            if statuses:
                owned["containerId"] = statuses[0].get("containerID", "").removeprefix("containerd://")
            options = {"apiVersion": "v1", "kind": "DeleteOptions", "gracePeriodSeconds": 5,
                       "preconditions": {"uid": owned["uid"]}}
            self.kube(["delete", "--raw", f"/api/v1/namespaces/{NS}/pods/{owned['name']}", "-f", "-"], protocol.compact(options))
            self.kube(["wait", "--for=delete", "pod/" + owned["name"], "--timeout=30s"], timeout=35)
        container = owned.get("containerId")
        if container:
            need(re.fullmatch("[0-9a-f]{64}", container) is not None)
        # A pod UID also covers a container that started while deletion was requested.
        # Non-root/capability-free workloads cannot move out of the pod cgroup.
        script = (
            "import os,sys; uid=sys.argv[1]; found=[]\n"
            "def fail(error): raise error\n"
            "for root,dirs,files in os.walk('/sys/fs/cgroup', onerror=fail):\n"
            " if uid not in root and uid.replace('-','_') not in root: continue\n"
            " if 'cgroup.procs' in files:\n"
            "  try:\n"
            "   with open(root+'/cgroup.procs') as stream: found.extend(stream.read().split())\n"
            "  except FileNotFoundError: pass\n"
            "assert not found, 'Owned cgroup still populated'\n"
            "print('empty')\n")
        stopped = self.command(["limactl", "shell", "gtrainer-models", "sudo", "python3", "-c", script, owned["uid"]]).strip()
        need(stopped == b"empty")
        running = json.loads(self.command(["limactl", "shell", "gtrainer-models", "sudo", "/usr/local/bin/k3s",
                                           "crictl", "ps", "-o", "json"]))
        need(all(c["id"] != container and c.get("labels", {}).get("io.kubernetes.pod.uid") != owned["uid"]
                 for c in running.get("containers", [])))
        need(time.monotonic() <= deadline)
        self.pod = None
        emit({"type": "owned_pod_stopped", "identity": owned, "cgroupEmpty": True, "runningCriIdentityAbsent": True})

    def cancellation_probe(self, run, emit, deadline):
        """Bound startup by deadline; start the separate stop timer only once active."""
        self.check_request_mode(run)
        need(self.prepared and not self.probed)
        self.deadline = deadline
        deployment = self.document(["get", "deployment", "ollama"])
        self.pause_pending = True
        self.scale_deployment(deployment, 0, 1)
        self.paused = True
        self.kube(["wait", "--for=delete", "pods", "-l", "app=ollama", "--timeout=30s"], timeout=35)
        self.create(run, deadline)
        need(time.monotonic() < deadline)
        payload = self.payload({"systemInstructions": "Return JSON only.",
            "userPacketJson": "Return an object with an integers array containing every integer from 1 through 100000."})
        if self.request_builder is not None:
            need(time.monotonic() < deadline)
        proc, done, holder = self.launch("chat", payload)
        try:
            emit({"type": "cancellation_probe_started", "requestSha256": protocol.digest(protocol.compact(payload)), "payload": payload,
                  "nonScored": True})
            loaded = False
            while time.monotonic() < deadline:
                self.sample(emit)
                need(not done.is_set(), "probe_not_active")
                if self.api("ps").get("models"):
                    loaded = True
                    break
                done.wait(1)
            need(loaded and not done.is_set() and time.monotonic() < deadline, "probe_not_active")
            stop_deadline = time.monotonic() + acquire.LIMITS["stopTimeoutSeconds"]
            self.deadline = stop_deadline
            emit({"type": "cancellation_probe_active", "stopTimeoutSeconds": acquire.LIMITS["stopTimeoutSeconds"]})
            self.stop_pod(stop_deadline, emit)
            need(done.wait(max(0, stop_deadline - time.monotonic())))
            need(time.monotonic() <= stop_deadline, "probe_deadline")
            emit({"type": "cancellation_probe_transport_ended", "responseBytesAvailable": len(holder.get("raw", holder.get("partial", b""))),
                  "transportEnded": True})
            self.probed = True
        finally:
            self.finish_client(proc)
        # This is the one approved probe restart, not a correction or fallback.
        need(time.monotonic() < deadline)
        self.deadline = min(deadline, time.monotonic() + 60)
        self.create(run, self.deadline)
        need(self.api("ps")["models"] == [])
        return {"verified": True, "ownedPod": dict(self.pod), "probeNonScored": True}

    @staticmethod
    def payload(prompt):
        settings = acquire.SETTINGS
        return {"model": acquire.MODEL["candidate"], "messages": [
            {"role": "system", "content": prompt["systemInstructions"]},
            {"role": "user", "content": prompt["userPacketJson"]}],
            "format": settings["format"], "stream": settings["stream"], "think": settings["think"],
            "keep_alive": settings["keep_alive"],
            "options": {k: settings[k] for k in ("temperature", "seed", "num_thread", "num_ctx", "num_predict")}}

    def generate(self, prompt, run, deadline, emit):
        self.check_request_mode(run)
        need(self.prepared and self.probed)
        payload = None if self.request_builder is None else self.request_builder(prompt)
        self.deadline = deadline
        self.sample(emit)
        need(self.api("ps")["models"] == [])
        if payload is None:
            payload = self.payload(prompt)
        emit({"type": "request", "payload": payload, "sha256": protocol.digest(protocol.compact(payload))})
        start = time.monotonic()
        if self.request_builder is not None:
            need(start < deadline)
        proc, done, holder = self.launch("chat", payload)
        try:
            while not done.is_set():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return {"outcome": "timeout", "raw": None, "resources": self.resources(start)}
                if done.wait(min(2, remaining)):
                    break
                self.sample(emit)
                if time.monotonic() >= deadline:
                    return {"outcome": "timeout", "raw": None, "resources": self.resources(start)}
            if "error" in holder:
                partial = holder.get("partial", b"")
                for index in range(0, len(partial), 3072):
                    emit({"type": "provider_partial_chunk", "offset": index,
                          "base64": base64.b64encode(partial[index:index + 3072]).decode()})
                return {"outcome": "incomplete", "raw": None, "resources": self.resources(start)}
            raw = holder["raw"]
            for index in range(0, len(raw), 3072):
                emit({"type": "provider_response_chunk", "offset": index,
                      "base64": base64.b64encode(raw[index:index + 3072]).decode()})
            response = json.loads(raw)
            message = response.get("message", {})
            content = message.get("content")
            need(type(content) is str)
            complete = response.get("done") is True and response.get("done_reason") == "stop" and not message.get("thinking")
            if len(content.encode()) > acquire.LIMITS["maxResponseBytes"]:
                complete = False
                # V3 retains complete wrapper chunks, never a repaired/truncated draft.
                content = (content.encode()[:acquire.LIMITS["maxResponseBytes"]].decode("utf-8", "ignore")
                           if self.request_builder is None else None)
            while self.api("ps")["models"]:
                need(time.monotonic() < deadline)
                time.sleep(0.2)
            return {"outcome": "response" if complete else "incomplete", "raw": content,
                    "resources": self.resources(start, response)}
        finally:
            self.finish_client(proc)

    @staticmethod
    def resources(start, response=None):
        response = response or {}
        return {"elapsedMs": (time.monotonic() - start) * 1000, "peakRssBytes": None,
                "inputTokens": response.get("prompt_eval_count"), "outputTokens": response.get("eval_count"), "costUsd": None}

    def cancel(self, run, deadline, emit):
        self.cancellation_attempted = True
        self.deadline = deadline
        self.stop_pod(deadline, emit)
        if self.paused or self.pause_pending or self.restore_pending:
            dep = self.baseline()
            if not self.restore_pending:
                need(dep["spec"]["replicas"] == 0)
                self.restore_pending = True
                try:
                    self.scale_deployment(dep, 1, 0)
                except Exception:
                    # Reconcile the outcome; never issue a second scale mutation.
                    pass
            restored = self.baseline()
            need(restored["spec"]["replicas"] == 1)
            self.kube(["rollout", "status", "deployment/ollama", "--timeout=30s"], timeout=35)
            restored = self.baseline()
            need(restored["spec"]["replicas"] == 1 and restored.get("status", {}).get("readyReplicas") == 1)
            self.paused = self.pause_pending = self.restore_pending = False
        need(self.pod is None and self.pending_pod is None and not self.paused
             and not self.pause_pending and not self.restore_pending)
        need(time.monotonic() <= deadline)
        return {"verified": True}
