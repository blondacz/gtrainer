"""Shared observed mmap/no-repack loader gates for approved extraction profiles."""
import base64
import re
import time

import mac_runtime as mac
import protocol

RUNNER_POLICY = {"version": "v3-cpu-mmap-no-repack-1", "environment": {"LLAMA_ARG_REPACK": "0"},
                 "verifyServerBeforeChat": True, "verifyRunnerBeforeScoring": True,
                 "requireMappedCpuBuffer": True,
                 "loaderLogSnapshotBytes": 32768, "maximumSnapshotsPerPod": 3}


class ObservedMmapRuntime(mac.MacRuntime):
    def __init__(self, *, request_builder, context_validator, request_profile="context-intake-extraction-v3"):
        super().__init__(request_builder=request_builder, context_validator=context_validator,
                         request_profile=request_profile)
        self.variant_uid = None
        self.diagnostic_emit = None
        self.runner_seen = set()
        self.log_counts = {}
        self.runner_probe_verified = False

    def manifest(self, run):
        self.check_request_mode(run)
        manifest = super().manifest(run)
        manifest["spec"]["containers"][0]["env"].append({"name": "LLAMA_ARG_REPACK", "value": "0"})
        return manifest

    def verify_server(self):
        self.pod_status()
        doc = self.document(["get", "pod", self.pod["name"]])
        mac.need(doc["metadata"]["uid"] == self.pod["uid"])
        values = [x for x in doc["spec"]["containers"][0]["env"] if x["name"] == "LLAMA_ARG_REPACK"]
        mac.need(values == [{"name": "LLAMA_ARG_REPACK", "value": "0"}], "runner_environment_mismatch")
        raw = self.kube(["exec", self.pod["name"], "--", "sh", "-c",
                         "tr '\\000' '\\n' < /proc/1/environ | grep '^LLAMA_ARG_REPACK='"])
        mac.need(raw == b"LLAMA_ARG_REPACK=0\n", "runner_environment_mismatch")
        self.variant_uid = self.pod["uid"]

    def create(self, run, deadline=None):
        super().create(run, deadline)
        self.verify_server()
        self.note({"type": "runner_server_configuration", "podUid": self.variant_uid,
                   "environment": RUNNER_POLICY["environment"], "verified": True})

    def note(self, event):
        mac.need(self.diagnostic_emit is not None, "runner_diagnostics_unavailable")
        self.diagnostic_emit(event)

    def runner_configuration(self, required=False):
        mac.need(self.pod is not None and self.variant_uid == self.pod["uid"])
        script = ("for d in /proc/[0-9]*; do "
                  "n=$(cat \"$d/comm\" 2>/dev/null) || continue; "
                  "[ \"$n\" = llama-server ] || continue; "
                  "a=$(base64 -w0 < \"$d/cmdline\" 2>/dev/null) || continue; "
                  "v=$(tr '\\000' '\\n' < \"$d/environ\" 2>/dev/null) || continue; "
                  "v=$(printf '%s\\n' \"$v\" | grep '^LLAMA_ARG_REPACK=' || true); "
                  "printf '%s|%s|%s\\n' \"${d##*/}\" \"$v\" \"$a\"; done")
        raw = self.kube(["exec", self.pod["name"], "--", "sh", "-c", script])
        observed = []
        for line in raw.decode("ascii").splitlines():
            parts = line.split("|")
            mac.need(len(parts) == 3 and parts[0].isdigit() and parts[1] == "LLAMA_ARG_REPACK=0",
                     "runner_environment_mismatch")
            args = base64.b64decode(parts[2], validate=True).decode("utf-8").rstrip("\0").split("\0")
            mac.need(args and args[0].endswith("/llama-server")
                     and not any(a == "--repack" or a.startswith("--repack=") for a in args),
                     "runner_environment_mismatch")
            modes = []
            for index, arg in enumerate(args):
                if arg == "--load-mode":
                    mac.need(index + 1 < len(args), "runner_load_mode_mismatch")
                    modes.append(args[index + 1])
                elif arg.startswith("--load-mode="):
                    modes.append(arg.split("=", 1)[1])
            mac.need("--no-mmap" not in args and all(mode in ("auto", "mmap") for mode in modes),
                     "runner_load_mode_mismatch")
            observed.append((parts[0], args))
            identity = (self.pod["uid"], parts[0], tuple(args))
            if identity not in self.runner_seen:
                self.note({"type": "runner_configuration", "podUid": self.pod["uid"],
                           "pid": int(parts[0]), "environment": RUNNER_POLICY["environment"],
                           "argv": args, "verified": True})
                self.runner_seen.add(identity)
        mac.need(not required or bool(observed), "runner_diagnostics_unavailable")
        return bool(observed)

    def loader_logs(self, *, timeout=5):
        uid = self.pod["uid"]
        count = self.log_counts.get(uid, 0)
        mac.need(count < RUNNER_POLICY["maximumSnapshotsPerPod"], "runner_diagnostics_limit")
        before = self.document(["get", "pod", self.pod["name"]])
        mac.need(before["metadata"]["uid"] == uid, "owned_pod_changed_or_unhealthy")
        raw = self.kube(["logs", self.pod["name"], "--timestamps=true", "--tail=200",
                         "--limit-bytes=" + str(RUNNER_POLICY["loaderLogSnapshotBytes"])], timeout=timeout)
        after = self.document(["get", "pod", self.pod["name"]])
        mac.need(after["metadata"]["uid"] == uid, "owned_pod_changed_or_unhealthy")
        mac.need(len(raw) <= RUNNER_POLICY["loaderLogSnapshotBytes"], "runner_diagnostics_limit")
        self.log_counts[uid] = count + 1
        self.note({"type": "loader_log_snapshot", "podUid": uid, "snapshot": count + 1,
                   "sha256": protocol.digest(raw), "bytes": len(raw), "tailBounded": True,
                   "base64": base64.b64encode(raw).decode("ascii")})
        return raw

    def sample(self, emit):
        current = super().sample(emit)
        if self.runner_configuration() and self.log_counts.get(self.pod["uid"], 0) == 0:
            self.loader_logs()
        return current

    def api(self, path, body=None):
        response = super().api(path, body)
        if path == "ps":
            self.verify_loaded_runner(response)
        return response

    def verify_loaded_runner(self, response):
        if response.get("models") and self.pod and self.variant_uid == self.pod["uid"]:
            self.runner_configuration(required=True)
            if not self.runner_probe_verified:
                logs = self.loader_logs()
                mac.need(not re.search(rb"\bCPU_REPACK\s+model buffer size\s*=", logs)
                         and re.search(rb"\bCPU_Mapped\s+model buffer size\s*=", logs)
                         and re.search(rb"load_mode\s*=\s*mmap\b", logs), "runner_buffer_unverified")
                self.runner_probe_verified = True
                self.note({"type": "runner_probe_configuration", "podUid": self.pod["uid"],
                           "repackingDisabled": True, "mappedCpuBuffer": True, "bufferLogVerified": True})

    def launch(self, path, body=None):
        if path == "chat":
            self.verify_server()
        return super().launch(path, body)

    def cancellation_probe(self, run, emit, deadline):
        self.diagnostic_emit = emit
        result = super().cancellation_probe(run, emit, deadline)
        mac.need(self.runner_probe_verified, "runner_buffer_unverified")
        return result

    def generate(self, prompt, run, deadline, emit):
        self.check_request_mode(run)
        mac.need(self.runner_probe_verified, "runner_buffer_unverified")
        self.diagnostic_emit = emit
        return super().generate(prompt, run, deadline, emit)

    def stop_pod(self, deadline, emit):
        if self.pod and self.variant_uid == self.pod["uid"] and self.log_counts.get(self.pod["uid"], 0) < 3:
            try:
                remaining = deadline - time.monotonic()
                if remaining > 5:
                    saved = self.deadline
                    try:
                        self.deadline = min(deadline - 5, time.monotonic() + 2)
                        self.loader_logs(timeout=2)
                    finally:
                        self.deadline = saved
            except Exception as error:
                try:
                    emit({"type": "loader_log_unavailable", "podUid": self.pod["uid"],
                          "category": getattr(error, "category", "diagnostic_failed")})
                except Exception:
                    pass
        return super().stop_pod(deadline, emit)
