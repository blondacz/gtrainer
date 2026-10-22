"""Explicitly gated fake transport through the real adapter, bridge and replay."""
import copy
import datetime as dt
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
import uuid
from unittest.mock import patch

import acquire_v3 as a
import run_mac_v3
import runtime_v3 as v
import replay as r


class FakeTransport(v.Runtime):
    """No command/process/socket can be launched. Only transport is simulated."""
    synthetic_fake = True

    def __init__(self, clock, outputs):
        super().__init__()
        self.clock, self.outputs = clock, list(outputs)
        self.replicas, self.current, self.probe_event = 1, None, None
        self.requests, self.low_calls = [], []

    def command(self, args, body=None, timeout=15):
        self.low_calls.append(args)
        if args[:2] == ["limactl", "list"]:
            return r.e.compact({"status": "Running", "cpus": 6, "memory": 10 * 1024**3})
        if args[0] == "sysctl": return b"1"
        if "crictl" in args: return b'{"containers":[]}'
        if "python3" in args: return b"empty"
        raise AssertionError("Unexpected fake command")

    def document(self, args):
        kind = args[1]
        if kind == "node": return {"metadata": {"uid": v.mac.IDENTITY["nodeUid"]}, "status": {
            "capacity": {"cpu": "6"}, "conditions": [{"type": name, "status": "True" if name == "Ready" else "False"}
                for name in ("Ready", "MemoryPressure", "DiskPressure", "PIDPressure")]}}
        if kind == "deployment": return {"metadata": {"uid": v.mac.IDENTITY["deploymentUid"], "resourceVersion": "42"}, "status": {"readyReplicas": self.replicas}, "spec": {
            "replicas": self.replicas, "template": {"spec": {"containers": [{"image": v.mac.IMAGE,
                "resources": {"limits": {"cpu": "3", "memory": "8Gi"}}}]}}}}
        if kind == "pvc": return {"metadata": {"uid": v.mac.IDENTITY["pvcUid"]}, "status": {"phase": "Bound"}}
        if kind == "networkpolicies": return {"items": [{"metadata": {"name": name}, "spec": {
            "podSelector": {}, "policyTypes": [direction]}} for direction, name in (("Ingress", "no-inbound"), ("Egress", "no-runtime-egress"))]}
        if kind == "pods": return {"items": [{"metadata": {"namespace": v.mac.NS, "name": "shared", "uid": str(uuid.uuid4()), "labels": {"app": "ollama"}}}]}
        if kind == "pod": return copy.deepcopy(self.current)
        raise AssertionError("Unexpected fake document")

    def kube(self, args, body=None, timeout=15):
        self.low_calls.append(args)
        if args[0] == "scale": self.replicas = 0 if "--replicas=0" in args else 1
        elif args[0] == "create":
            doc = r.e.decode(body)
            self.current = {"metadata": dict(doc["metadata"], uid=str(uuid.uuid4())),
                "spec": doc["spec"],
                "status": {"phase": "Running", "containerStatuses": [{"ready": True, "restartCount": 0, "containerID": "containerd://" + "a" * 64}]}}
            return r.e.compact(self.current)
        elif args[0] == "get": return r.e.compact(self.current) if self.current else b""
        elif args[0] == "delete":
            assert r.e.decode(body)["preconditions"]["uid"] == self.current["metadata"]["uid"]
            self.current = None
            if self.probe_event: self.probe_event.set()
        elif args[0] == "logs": return b"load_tensors: loading model tensors (load_mode = mmap)\nload_tensors: CPU_Mapped model buffer size = 4000.00 MiB\n"
        elif args[0] == "exec":
            if "/proc/1/environ" in args[-1]: return b"LLAMA_ARG_REPACK=0\n"
            if "/proc/[0-9]*" in args[-1]:
                import base64
                return b"123|LLAMA_ARG_REPACK=0|" + base64.b64encode(b"/usr/lib/ollama/llama-server\0--offline\0") + b"\n"
            return b"1000000\n2000000\nanon 400000\nfile 600000\n"
        elif args[0] not in ("wait", "rollout"): raise AssertionError("Unexpected fake kube operation")
        return b"{}"

    def api(self, path, body=None):
        if path == "version": return {"version": "0.35.0"}
        if path == "tags": return {"models": [{"name": r.MODEL["candidate"], "digest": r.e.MODEL}]}
        if path == "show": return {"template": "fake-template", "model_info": {"tokenizer.ggml.model": "gpt2"}}
        if path == "ps":
            response = {"models": ["fake-loaded"] if self.probe_event and not self.probe_event.is_set() else []}
            self.verify_loaded_runner(response)
            return response
        raise AssertionError("Unexpected fake API")

    def launch(self, path, body=None):
        assert path == "chat"
        self.verify_server()
        assert body["options"]["use_mmap"] is True
        self.requests.append(copy.deepcopy(body))
        done = threading.Event()
        if body["format"] == "json":
            assert r.e.digest(r.e.compact(body)) == v.request_variant()["probeRequestSha256"]
            self.probe_event = done
            return object(), done, {}
        assert self.probed and self.prepared
        assert body["format"] == r.e.verify()[1]["schema"]
        outcome, raw = self.outputs.pop(0)
        if outcome == "timeout":
            clock = self.clock
            class TimeoutEvent:
                def is_set(self): return False
                def wait(self, seconds): clock[0] += 901; return False
            return object(), TimeoutEvent(), {}
        done.set()
        wrapper = {"done": True, "done_reason": "stop" if outcome != "incomplete" else "length",
                   "message": {"content": raw}}
        return object(), done, {"raw": r.e.compact(wrapper)}

    @staticmethod
    def finish_client(proc): pass

    @staticmethod
    def resources(start, response=None):
        return {key: None for key in r.RESOURCE_FIELDS}


@unittest.skipUnless(os.environ.get("INTAKE_V3_FAKE_VECTORS"), "Explicit actual Kotlin fake integration required")
class AcquisitionIntegrationTest(unittest.TestCase):
    def exercise(self, outputs, expected):
        with tempfile.TemporaryDirectory(prefix="intake-v3-full-fake-", dir=os.environ.get("INTAKE_V3_TEST_TEMP_ROOT", "/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode")) as temp:
            directory = Path(temp).resolve(); directory.chmod(0o700)
            now = dt.datetime(2026, 10, 18, 18, tzinfo=dt.timezone.utc)
            clock = [10.0]
            run = a.make_run(str(uuid.uuid4()), "synthetic_fake")
            approval = {"version": a.APPROVAL_VERSION, "runSha256": r.e.digest(r.e.compact(run)),
                "purpose": "synthetic_fake_acquisition_only", "recordedBy": "fake integration only",
                "recordedAt": "2026-10-18T17:00:00Z", "expiresUtc": "2026-10-18T19:00:00Z",
                "allowCancellationProbe": True, "allowCorrection": False, "approvalEvidence": "fake transport; no runtime authority"}
            runtime = FakeTransport(clock, outputs)
            original_digest = r.e.digest
            with patch.object(r.e, "digest", side_effect=lambda raw: r.e.TEMPLATE if raw == b"fake-template" else original_digest(raw)), \
                 patch.object(v.mac.time, "monotonic", side_effect=lambda: clock[0]):
                result = a.run_acquisition(directory, run, approval, runtime,
                    lambda case, raw, deadline: run_mac_v3.validate_one(directory, case, raw, deadline),
                    monotonic=lambda: clock[0], now=lambda: now)
            self.assertTrue(result["stopVerified"])
            self.assertIsNone(runtime.pod)
            self.assertEqual(runtime.replicas, 1)
            self.assertEqual([c["attempts"][0]["validation"] or c["attempts"][0]["outcome"] for c in result["cases"]], expected)
            self.assertEqual(len(runtime.requests), 1 + sum(c["attempts"][0]["outcome"] != "unrun" for c in result["cases"]))
            replay_run = {"version": "intake-extraction-private-run-v3", "runId": str(uuid.uuid4()),
                "executionMode": "offline_replay", "inputOrigin": "synthetic_fake", "profile": r.e.PROFILE,
                "caseIds": r.e.IDS, "manifestSha256": run["manifestSha256"], "toolingSha256": r.tooling(),
                "model": r.FAKE_MODEL, "settings": run["settings"], "resourceLimits": r.LIMITS}
            replay_approval = {"version": "intake-extraction-replay-approval-v3", "runSha256": r.e.digest(r.e.compact(replay_run)),
                "purpose": "synthetic_offline_replay_only", "recordedBy": "fake integration only",
                "recordedAt": "2026-10-18T17:00:00Z", "expiresUtc": "2026-10-18T19:00:00Z"}
            for name, value in (("run.json", replay_run), ("approval.json", replay_approval)):
                r.private.private_write(directory / name, r.e.compact(value))
            report = r.replay(directory, now)
            self.assertEqual([c["outcome"] for c in report["cases"]], expected)
            self.assertFalse(report["experimentalSuccess"])
            human = r.private.read_json(directory / "human-review.json")
            self.assertTrue(all(c["usefulFinal"] is None and all(v is None for v in c["scores"].values()) for c in human["cases"]))
            self.assertTrue(all(all(v is None for v in c["resources"].values()) for c in report["cases"]))
            marker = b"private-v3-acquisition-marker"
            for log in directory.rglob("validation.log"):
                self.assertNotIn(marker, log.read_bytes())
            for folder in (*directory.rglob("test-reports"), r.e.ROOT / "backend/build/reports/tests", r.e.ROOT / "backend/build/test-results"):
                for file in folder.rglob("*"):
                    if file.is_file(): self.assertNotIn(marker, file.read_bytes())
            retain = os.environ.get("INTAKE_V3_RETAIN_INTEGRATION")
            if retain:
                import shutil
                shutil.copytree(directory, Path(retain) / self._testMethodName)

    def test_complete_eight_case_acquisition(self):
        vectors = r.private.read_json(Path(os.environ["INTAKE_V3_FAKE_VECTORS"]))
        self.exercise([("response", vectors[cid]) for cid in r.e.IDS], ["accepted"] * 8)

    def test_mixed_stages_empty_incomplete_and_unrun(self):
        vectors = r.private.read_json(Path(os.environ["INTAKE_V3_FAKE_VECTORS"]))
        wire = json.loads(vectors["i01"]); wire["suggestions"][0]["unresolvedFields"] = ["observedOn", "observedOn"]
        binding = json.loads(vectors["i02"]); binding["suggestions"][0]["sourceExcerpt"] = "private-v3-acquisition-marker"
        semantic = json.loads(vectors["i03"]); semantic["questions"] = []
        self.exercise([("response", json.dumps(wire)), ("response", json.dumps(binding)), ("response", json.dumps(semantic)),
                       ("empty", " "), ("incomplete", "private-v3-acquisition-marker")],
                      ["rejected"] * 3 + ["empty", "incomplete"] + ["unrun"] * 3)

    def test_timeout_owned_stop_fences_all_later_cases(self):
        self.exercise([("timeout", None)], ["timeout"] + ["unrun"] * 7)


if __name__ == "__main__": unittest.main()
