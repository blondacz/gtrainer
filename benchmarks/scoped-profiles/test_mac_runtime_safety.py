"""Focused regression tests for ambiguous Kubernetes and client I/O failures.

These tests replace every command boundary with fakes. They never start a real
process, contact a runtime, or access a model.
"""
import json
import threading
import time
import unittest
from unittest.mock import patch

import mac_runtime as mac


RUN_ID = "11111111-1111-4111-8111-111111111111"
POD_UID = "22222222-2222-4222-8222-222222222222"


class FakePipe:
    def __init__(self, write=None):
        self.write_call = write or (lambda data: len(data))
        self.closed = False

    def write(self, data):
        return self.write_call(data)

    def close(self):
        self.closed = True


class FakeProcess:
    def __init__(self, write=None):
        self.stdin = FakePipe(write)
        self.stdout = FakePipe()
        self.killed = False
        self.waited = False

    def poll(self):
        return None if not self.waited else 0

    def kill(self):
        self.killed = True

    def wait(self, timeout=None):
        self.waited = True
        return 0


class MacRuntimeSafetyTest(unittest.TestCase):
    def test_ambiguous_create_keeps_intent_and_nonce_can_prove_ownership(self):
        runtime = mac.MacRuntime()
        run = {"runId": RUN_ID}
        sent = []
        def kube(args, body=None, timeout=15):
            if body is not None:
                sent.append(json.loads(body))
                raise RuntimeError("lost create acknowledgement")
            return b""
        with patch.object(runtime, "kube", side_effect=kube):
            with self.assertRaises(RuntimeError):
                runtime.create(run, time.monotonic() + 30)
        intent = runtime.pending_pod
        self.assertEqual(intent["runId"], RUN_ID)
        self.assertTrue(intent["token"])
        manifest = sent[0]
        self.assertEqual(manifest["metadata"]["annotations"]["gtrainer-scoped-create-token"], intent["token"])
        created = {"metadata": dict(manifest["metadata"], uid=POD_UID), "status": {"containerStatuses": []}}
        with patch.object(runtime, "kube", side_effect=[json.dumps(created).encode(), json.dumps(created).encode(), b"{}", b"deleted"]) as kube, \
             patch.object(runtime, "command", side_effect=[b"empty", b'{"containers":[]}']):
            runtime.stop_pod(time.monotonic() + 30, lambda event: None)
        self.assertIsNone(runtime.pending_pod)
        self.assertIsNone(runtime.pod)
        self.assertEqual(json.loads(kube.call_args_list[2].args[1])["preconditions"], {"uid": POD_UID})

    def test_missing_or_foreign_nonce_never_deletes_ambiguous_pod(self):
        for response in (b"", json.dumps({"metadata": {"name": "owned", "uid": POD_UID,
                    "labels": {"gtrainer-scoped-run": RUN_ID}, "annotations": {"gtrainer-scoped-create-token": "foreign"}}}).encode()):
            runtime = mac.MacRuntime()
            runtime.pending_pod = {"name": "owned", "runId": RUN_ID, "token": "expected"}
            with patch.object(runtime, "kube", return_value=response) as kube:
                with self.assertRaises(RuntimeError):
                    runtime.stop_pod(time.monotonic() + 30, lambda event: None)
            self.assertEqual(kube.call_count, 1)
            self.assertIsNotNone(runtime.pending_pod)

    def test_scale_lost_ack_preserves_pause_intent_and_cancel_restores_baseline(self):
        runtime = mac.MacRuntime()
        runtime.prepared = True
        with patch.object(runtime, "document", return_value={"metadata": {"uid": mac.IDENTITY["deploymentUid"],
                    "resourceVersion": "7"}, "spec": {"replicas": 1}}), \
             patch.object(runtime, "kube", side_effect=RuntimeError("lost scale acknowledgement")):
            with self.assertRaises(RuntimeError):
                runtime.cancellation_probe({"runId": RUN_ID}, lambda event: None, time.monotonic() + 30)
        self.assertTrue(runtime.pause_pending)
        self.assertFalse(runtime.paused)
        paused = {"metadata": {"uid": mac.IDENTITY["deploymentUid"], "resourceVersion": "8"}, "spec": {"replicas": 0}}
        restored = {"metadata": {"uid": mac.IDENTITY["deploymentUid"], "resourceVersion": "9"}, "spec": {"replicas": 1}, "status": {"readyReplicas": 1}}
        with patch.object(runtime, "baseline", side_effect=[paused, restored, restored]), patch.object(runtime, "kube") as kube:
            self.assertTrue(runtime.cancel({}, time.monotonic() + 30, lambda event: None)["verified"])
        self.assertIn("--resource-version=8", kube.call_args_list[0].args[0])
        self.assertFalse(runtime.pause_pending)

    def test_cancel_never_restores_after_scale_not_applied_or_deployment_replaced(self):
        for dep in ({"metadata": {"uid": mac.IDENTITY["deploymentUid"], "resourceVersion": "8"}, "spec": {"replicas": 1}},
                    {"metadata": {"uid": "replaced", "resourceVersion": "8"}, "spec": {"replicas": 0}}):
            runtime = mac.MacRuntime()
            runtime.pause_pending = True
            runtime.paused = True
            with patch.object(runtime, "baseline", return_value=dep), patch.object(runtime, "stop_pod"), \
                 patch.object(runtime, "kube") as kube:
                with self.assertRaises(RuntimeError):
                    runtime.cancel({}, time.monotonic() + 30, lambda event: None)
            kube.assert_not_called()
            self.assertTrue(runtime.pause_pending)

    def test_restore_ack_loss_requires_observed_ready_baseline(self):
        runtime = mac.MacRuntime()
        runtime.pause_pending = runtime.paused = True
        paused = {"metadata": {"uid": mac.IDENTITY["deploymentUid"], "resourceVersion": "8"}, "spec": {"replicas": 0}}
        dep = {"metadata": {"uid": mac.IDENTITY["deploymentUid"], "resourceVersion": "9"},
               "spec": {"replicas": 1}, "status": {"readyReplicas": 1}}
        with patch.object(runtime, "stop_pod"), patch.object(runtime, "baseline", side_effect=[paused, dep, dep]), \
             patch.object(runtime, "kube", side_effect=[RuntimeError("lost ack"), b"rollout ok"]) as kube:
            # A lost scale acknowledgement may be reconciled from observed replicas=1.
            result = runtime.cancel({}, time.monotonic() + 30, lambda event: None)
        self.assertTrue(result["verified"])
        self.assertFalse(runtime.pause_pending)
        self.assertFalse(runtime.restore_pending)
        self.assertEqual(sum(call.args[0][0] == "scale" for call in kube.call_args_list), 1)

    def test_failed_restore_retains_unverified_flags(self):
        runtime = mac.MacRuntime()
        runtime.pause_pending = runtime.paused = runtime.restore_pending = True
        dep = {"metadata": {"uid": mac.IDENTITY["deploymentUid"], "resourceVersion": "9"},
               "spec": {"replicas": 0}}
        with patch.object(runtime, "stop_pod"), patch.object(runtime, "baseline", return_value=dep), \
             patch.object(runtime, "kube", side_effect=RuntimeError("restore failed")):
            with self.assertRaises(RuntimeError):
                runtime.cancel({}, time.monotonic() + 30, lambda event: None)
        self.assertTrue(runtime.pause_pending)
        self.assertTrue(runtime.restore_pending)
        self.assertTrue(runtime.paused)

    def test_launch_returns_without_waiting_for_blocked_stdin(self):
        blocked = threading.Event()
        proc = FakeProcess(lambda data: blocked.wait(2))
        runtime = mac.MacRuntime()
        runtime.pod = {"name": "fake"}
        with patch.object(mac.subprocess, "Popen", return_value=proc):
            started = time.monotonic()
            returned = runtime.launch("version")
            self.assertLess(time.monotonic() - started, 0.2)
        blocked.set()
        runtime.finish_client(returned[0])
        self.assertTrue(proc.killed)
        self.assertTrue(proc.waited)

    def test_api_timeout_kills_and_reaps_client(self):
        runtime = mac.MacRuntime()
        proc = FakeProcess()
        done = threading.Event()
        runtime.deadline = time.monotonic() - 1
        with patch.object(runtime, "launch", return_value=(proc, done, {})):
            with self.assertRaises(RuntimeError):
                runtime.api("version")
        self.assertTrue(proc.killed)
        self.assertTrue(proc.waited)

    def test_thread_start_failure_cleans_up_client(self):
        runtime = mac.MacRuntime()
        proc = FakeProcess()
        runtime.pod = {"name": "fake"}
        with patch.object(mac.subprocess, "Popen", return_value=proc), \
             patch.object(mac.threading.Thread, "start", side_effect=RuntimeError("thread start failed")):
            with self.assertRaises(RuntimeError):
                runtime.launch("version")
        self.assertTrue(proc.killed)
        self.assertTrue(proc.waited)

    def test_writer_failure_is_transport_error_without_waiting_for_reader(self):
        released = threading.Event()
        def fail(data): raise BrokenPipeError("fake delivery failed")
        proc = FakeProcess(fail)
        class BlockedOutput:
            def readline(self, count=-1): released.wait(1); return b""
            def close(self): pass
        proc.stdout = BlockedOutput()
        runtime = mac.MacRuntime(); runtime.pod = {"name": "fake"}
        with patch.object(mac.subprocess, "Popen", return_value=proc):
            process, done, holder = runtime.launch("version")
            self.assertTrue(done.wait(0.5))
            self.assertTrue(holder.get("error"))
            runtime.finish_client(process)
            released.set()
        self.assertTrue(proc.killed and proc.waited)

    def test_blocked_delivery_cannot_hide_client_from_api_deadline(self):
        released = threading.Event()
        proc = FakeProcess(lambda data: released.wait(1))
        class BlockedOutput:
            def readline(self, count=-1): released.wait(1); return b""
            def close(self): pass
        proc.stdout = BlockedOutput()
        original_kill = proc.kill
        def kill(): original_kill(); released.set()
        proc.kill = kill
        runtime = mac.MacRuntime(); runtime.pod = {"name": "fake"}
        runtime.deadline = time.monotonic() + 0.05
        with patch.object(mac.subprocess, "Popen", return_value=proc):
            started = time.monotonic()
            with self.assertRaises(RuntimeError): runtime.api("version")
            self.assertLess(time.monotonic() - started, 0.5)
        self.assertTrue(proc.killed and proc.waited)

    def test_probe_event_failure_still_cleans_up_launched_client(self):
        runtime = mac.MacRuntime(); runtime.prepared = True
        proc = FakeProcess()
        dep = {"metadata": {"uid": mac.IDENTITY["deploymentUid"], "resourceVersion": "10"}, "spec": {"replicas": 1}}
        def fail(event): raise RuntimeError("journal unavailable")
        with patch.object(runtime, "document", return_value=dep), patch.object(runtime, "kube"), \
             patch.object(runtime, "create"), patch.object(runtime, "launch", return_value=(proc, threading.Event(), {})):
            with self.assertRaises(RuntimeError):
                runtime.cancellation_probe({"runId": RUN_ID}, fail, time.monotonic() + 30)
        self.assertTrue(proc.killed and proc.waited)


if __name__ == "__main__":
    unittest.main()
