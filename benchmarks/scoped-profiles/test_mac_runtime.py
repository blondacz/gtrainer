import copy
import http.client
import hashlib
import io
import json
import time
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

import acquire
import mac_runtime as mac
import protocol
import run_mac_intake


class MacRuntimeTest(unittest.TestCase):
    def test_payload_is_exact_frozen_prompt_with_no_feedback_or_tools(self):
        _, artifacts = protocol.verify()
        for case in artifacts["cases"]:
            if case["profile"] != "context-intake-suggestion-v1":
                continue
            for kind in ("exactPrompt", "exactCorrectionPrompt"):
                prompt = case[kind]
                payload = mac.MacRuntime.payload(prompt)
                self.assertEqual(payload["messages"], [{"role": "system", "content": prompt["systemInstructions"]},
                                                      {"role": "user", "content": prompt["userPacketJson"]}])
                self.assertFalse(payload["think"])
                self.assertEqual(payload["keep_alive"], "0s")
                self.assertNotIn("tools", payload)

    def test_owned_pod_uses_only_cached_image_and_no_public_service(self):
        runtime = mac.MacRuntime()
        manifest = runtime.manifest({"runId": "11111111-1111-4111-8111-111111111111"})
        spec = manifest["spec"]; c = spec["containers"][0]
        self.assertEqual(c["imagePullPolicy"], "Never")
        self.assertEqual(c["image"], mac.IMAGE)
        self.assertEqual(c["resources"]["limits"], {"cpu": "3", "memory": "8Gi"})
        self.assertEqual(spec["restartPolicy"], "Never")
        self.assertFalse(spec["automountServiceAccountToken"])
        self.assertNotIn("app", manifest["metadata"]["labels"])
        self.assertNotIn("hostNetwork", spec)
        self.assertEqual(dict((e["name"], e["value"]) for e in c["env"])["OLLAMA_HOST"], "127.0.0.1:11434")

    def test_unknown_template_or_tokenizer_refuses_context_claim(self):
        with self.assertRaises(RuntimeError):
            mac.context_proof({"template": "other", "model_info": {"tokenizer.ggml.model": "gpt2"}})
        with patch.object(mac, "TEMPLATE", hashlib.sha256(b"test").hexdigest()):
            with self.assertRaises(RuntimeError):
                mac.context_proof({"template": "test", "model_info": {"tokenizer.ggml.model": "unknown"}})
            proof = mac.context_proof({"template": "test", "model_info": {"tokenizer.ggml.model": "gpt2"}})
        self.assertLessEqual(proof["maximumInputTokenUpperBound"] + proof["reservedOutputTokens"], 4096)
        self.assertIsNone(proof["measuredTokenCount"])

    def test_http_pipe_decodes_chunked_response_and_bounds_transport(self):
        wire = b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n'
        response = http.client.HTTPResponse(mac.PipeSocket(io.BytesIO(wire)))
        response.begin()
        self.assertEqual(response.read(), b'{}')
        with self.assertRaises(RuntimeError):
            mac.LimitedPipe(io.BytesIO(b'x' * 20), 5).read()
        with self.assertRaises(RuntimeError):
            mac.LimitedPipe(io.BytesIO(b'x' * 9000), 10000).readline()

    def test_http_pipe_decodes_content_length_like_actual_metadata_endpoint(self):
        wire = (b'HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n'
                b'Content-Length: 20\r\nConnection: close\r\n\r\n{"version":"0.35.0"}')
        response = http.client.HTTPResponse(mac.PipeSocket(io.BytesIO(wire)))
        response.begin()
        self.assertEqual(response.read(65537), b'{"version":"0.35.0"}')
        target = bytearray(3)
        self.assertEqual(mac.LimitedPipe(io.BytesIO(b'ab'), 2).readinto(target), 2)
        self.assertEqual(target[:2], b'ab')

    def test_unknown_runtime_identity_has_zero_commands(self):
        runtime = mac.MacRuntime()
        with patch.object(runtime, "command") as command:
            with self.assertRaises(RuntimeError):
                runtime.preflight({"runtime": {}})
            command.assert_not_called()

    def test_larger_model_envelope_requires_approved_vm_allocation(self):
        runtime = mac.MacRuntime()
        vm = {"status": "Running", "cpus": 6, "memory": 8 * 1024**3}
        with patch.object(runtime, "command", return_value=json.dumps(vm).encode()), \
             patch.object(runtime, "document") as document:
            with self.assertRaises(acquire.RuntimeFailure):
                runtime.baseline()
            document.assert_not_called()

    def test_replaced_pod_is_never_deleted(self):
        runtime = mac.MacRuntime()
        runtime.pod = {"name": "owned", "uid": "11111111-1111-4111-8111-111111111111"}
        with patch.object(runtime, "kube", return_value=json.dumps({"metadata": {"uid": "other"}}).encode()) as kube:
            with self.assertRaises(RuntimeError):
                runtime.stop_pod(time.monotonic() + 60, lambda event: None)
            self.assertEqual(kube.call_count, 1)

    def test_scale_restore_is_forbidden_after_unverified_stop(self):
        runtime = mac.MacRuntime(); runtime.paused = True
        with patch.object(runtime, "stop_pod", side_effect=RuntimeError("unverified")), patch.object(runtime, "kube") as kube:
            with self.assertRaises(RuntimeError):
                runtime.cancel({}, time.monotonic() + 60, lambda event: None)
            kube.assert_not_called()

    def test_monitor_failure_or_memory_threshold_blocks_generation(self):
        runtime = mac.MacRuntime(); runtime.prepared = True; runtime.probed = True
        with patch.object(runtime, "sample", side_effect=RuntimeError("memory")), patch.object(runtime, "launch") as launch:
            with self.assertRaises(RuntimeError):
                runtime.generate({}, {}, time.monotonic() + 900, lambda event: None)
            launch.assert_not_called()

    def test_no_scored_generation_before_verified_probe(self):
        runtime = mac.MacRuntime(); runtime.prepared = True
        with patch.object(runtime, "launch") as launch:
            with self.assertRaises(RuntimeError):
                runtime.generate({}, {}, time.monotonic() + 900, lambda event: None)
            launch.assert_not_called()

    def test_approved_memory_stop_is_seven_and_a_half_gib(self):
        self.assertEqual(acquire.LIMITS["memoryLimitBytes"], 8 * 1024**3)
        self.assertEqual(acquire.LIMITS["memoryAbortBytes"], 15 * 1024**3 // 2)
        runtime = mac.MacRuntime()
        runtime.pod = {"uid": "fake", "name": "fake"}
        node = {"metadata": {"uid": mac.IDENTITY["nodeUid"]}, "status": {"conditions": [
            {"type": name, "status": "True" if name == "Ready" else "False"}
            for name in ("Ready", "MemoryPressure", "DiskPressure", "PIDPressure")]}}
        with patch.object(runtime, "pod_status"), patch.object(runtime, "document", return_value=node), \
             patch.object(runtime, "command", return_value=b"1"), patch.object(runtime, "kube") as kube:
            limit = acquire.LIMITS["memoryAbortBytes"]
            kube.return_value = f"{limit}\n{limit}\nanon 1024\nfile 8192\n".encode()
            events = []
            self.assertEqual(runtime.sample(events.append), limit)
            self.assertEqual(events[-1]["memoryStatBytes"], {"anon": 1024, "file": 8192})
            kube.return_value = f"{limit+1}\n{limit+1}\nanon 1024\nfile {limit}\n".encode()
            with self.assertRaises(acquire.RuntimeFailure) as refused:
                runtime.sample(events.append)
            self.assertEqual(refused.exception.category, "memory_abort_threshold")
            self.assertEqual(events[-1]["memoryCurrentBytes"], limit + 1)
            self.assertEqual(events[-1]["memoryStatBytes"]["file"], limit)

    def test_memory_diagnostics_preserve_counters_without_relabeling_as_rss(self):
        raw = b"900\n950\nanon 300\nfile 500\nkernel 100\nshmem 20\nfile_mapped 400\ninactive_file 480\npgfault 7\n"
        current, peak, counters = mac.memory_sample(raw)
        self.assertEqual((current, peak), (900, 950))
        self.assertEqual(counters, {"anon": 300, "file": 500, "kernel": 100, "shmem": 20,
                                    "file_mapped": 400, "inactive_file": 480})

    def test_unavailable_or_malformed_memory_diagnostics_fail_closed(self):
        for raw in (b"1\n2\n", b"1\n2\nanon 1\n", b"1\n2\nanon 1\nfile -1\n",
                    b"1\n2\nanon 1\nfile 2\nanon 3\n", b"x\n2\nanon 1\nfile 2\n"):
            with self.subTest(raw=raw), self.assertRaises(acquire.RuntimeFailure):
                mac.memory_sample(raw)

    def test_validator_input_cannot_choose_arbitrary_path(self):
        with self.assertRaises(ValueError):
            run_mac_intake.validate_one(None, "../../other", 1, "{}")

    def test_owned_stop_requires_uid_precondition_and_actual_process_absence(self):
        runtime = mac.MacRuntime()
        uid = "11111111-1111-4111-8111-111111111111"
        runtime.pod = {"name": "owned", "uid": uid, "containerId": "a" * 64}
        pod = {"metadata": {"uid": uid}, "status": {"containerStatuses": []}}
        events = []
        with patch.object(runtime, "kube", side_effect=[json.dumps(pod).encode(), b"{}", b"deleted"]) as kube, \
             patch.object(runtime, "command", side_effect=[b"empty\n", b'{"containers":[]}']):
            runtime.stop_pod(time.monotonic() + 60, events.append)
            deletion = json.loads(kube.call_args_list[1].args[1])
            self.assertEqual(deletion["preconditions"], {"uid": uid})
        self.assertIsNone(runtime.pod)
        self.assertTrue(events[0]["cgroupEmpty"])
        self.assertTrue(events[0]["runningCriIdentityAbsent"])

    def test_remaining_cgroup_process_blocks_verified_stop(self):
        runtime = mac.MacRuntime()
        runtime.pod = {"name": "owned", "uid": "11111111-1111-4111-8111-111111111111"}
        with patch.object(runtime, "kube", return_value=b""), patch.object(runtime, "command", return_value=b"123\n"):
            with self.assertRaises(RuntimeError):
                runtime.stop_pod(time.monotonic() + 60, lambda event: None)
        self.assertIsNotNone(runtime.pod)

    def test_expired_command_budget_cannot_launch_another_process(self):
        runtime = mac.MacRuntime(); runtime.deadline = time.monotonic() - 1
        with patch.object(mac.subprocess, "run") as run:
            with self.assertRaises(RuntimeError):
                runtime.command(["untrusted"])
            run.assert_not_called()

    def test_cli_errors_do_not_print_private_exception_details(self):
        output = io.StringIO()
        with patch("sys.argv", ["runner", "--directory", "/not-read"]), \
             patch.object(run_mac_intake, "execute", side_effect=RuntimeError("private-output-marker")), redirect_stdout(output):
            self.assertEqual(run_mac_intake.main(), 2)
        self.assertNotIn("private-output-marker", output.getvalue())

    def test_cold_probe_startup_does_not_consume_cancellation_budget(self):
        runtime = mac.MacRuntime(); runtime.prepared = True
        clock = [0.0]
        stopped = [False]
        class ProbeEvent:
            def is_set(self):
                return stopped[0]
            def wait(self, timeout):
                return stopped[0]
        def sample(emit):
            clock[0] += 10
        def create_pod(run, deadline):
            runtime.pod = {"name": "fake-owned-pod", "uid": "11111111-1111-4111-8111-111111111111"}
        def api(path):
            return {"models": ["loaded"] if clock[0] >= 120 and not stopped[0] else []}
        def stop(deadline, emit):
            self.assertEqual(deadline - clock[0], 60)
            self.assertEqual(runtime.deadline, deadline)
            clock[0] += 5
            stopped[0] = True
        events = []
        with patch.object(mac.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(runtime, "document", return_value={"metadata": {"uid": mac.IDENTITY["deploymentUid"], "resourceVersion": "42"}, "spec": {"replicas": 1}}), \
             patch.object(runtime, "kube"), patch.object(runtime, "create", side_effect=create_pod) as create, \
             patch.object(runtime, "launch", return_value=(object(), ProbeEvent(), {})), \
             patch.object(runtime, "sample", side_effect=sample), patch.object(runtime, "api", side_effect=api), \
             patch.object(runtime, "stop_pod", side_effect=stop), patch.object(runtime, "finish_client"):
            result = runtime.cancellation_probe({}, events.append, 900)
        self.assertTrue(result["verified"])
        self.assertTrue(runtime.probed)
        self.assertEqual(create.call_args_list[0].args[1], 900)
        self.assertEqual(create.call_args_list[1].args[1], 185)
        self.assertIn("cancellation_probe_active", [e["type"] for e in events])

    def test_probe_startup_timeout_cannot_start_scored_pod(self):
        runtime = mac.MacRuntime(); runtime.prepared = True
        clock = [0.0]
        class ProbeEvent:
            def is_set(self): return False
            def wait(self, timeout): return False
        def sample(emit): clock[0] += 31
        with patch.object(mac.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(runtime, "document", return_value={"metadata": {"uid": mac.IDENTITY["deploymentUid"], "resourceVersion": "42"}, "spec": {"replicas": 1}}), \
             patch.object(runtime, "kube"), patch.object(runtime, "create") as create, \
             patch.object(runtime, "launch", return_value=(object(), ProbeEvent(), {})), \
             patch.object(runtime, "sample", side_effect=sample), patch.object(runtime, "api", return_value={"models": []}), \
             patch.object(runtime, "stop_pod") as stop, patch.object(runtime, "finish_client"):
            with self.assertRaises(acquire.RuntimeFailure):
                runtime.cancellation_probe({}, lambda event: None, 90)
        self.assertEqual(create.call_count, 1)
        stop.assert_not_called()
        self.assertFalse(runtime.probed)


if __name__ == "__main__":
    unittest.main()
