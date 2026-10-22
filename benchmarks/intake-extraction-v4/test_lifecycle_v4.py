import copy
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch
import uuid

import lifecycle_v4 as life
import supervisor_v4 as supervisor
from test_runtime_v4 import RuntimeFixture


class JournalTest(RuntimeFixture):
    def setUp(self):
        super().setUp()
        self.temp = tempfile.TemporaryDirectory(prefix="v4-journal-", dir=supervisor.BASE)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.worker = Mock(directory=self.directory, run=self.run)
        self.runtime = life.JournalRuntime(self.run, self.approval, self.prepared, self.evidence,
            campaign_guard=lambda run: True, request_guard=lambda run, sha: True,
            now=lambda: self.now, lifecycle=self.worker)
        self.deployment = {"metadata": {"uid": life.v.mac.IDENTITY["deploymentUid"], "resourceVersion": "1"},
                           "spec": {"replicas": 1, "unchanged": "full original spec"}}

    def pause(self):
        self.runtime.pause_pending = True
        with patch.object(life.v.mac.MacRuntime, "scale_deployment") as scale:
            self.runtime.scale_deployment(self.deployment, 0, 1)
        self.assertEqual(scale.call_count, 1)

    def test_pause_intent_precedes_mutation_and_lost_response_retains_ownership(self):
        self.runtime.pause_pending = True
        def fail(*args):
            rows = life.records(self.directory, "ownership")
            self.assertEqual(rows[-1]["operation"], "pause_pending")
            self.assertEqual(rows[-1]["state"]["originalDeployment"], self.deployment)
            raise RuntimeError("lost scale response")
        with patch.object(life.v.mac.MacRuntime, "scale_deployment", side_effect=fail):
            with self.assertRaises(RuntimeError): self.runtime.scale_deployment(self.deployment, 0, 1)
        self.assertEqual(len(life.records(self.directory, "ownership")), 1)

    def test_journal_failure_or_fence_prevents_mutation(self):
        with patch.object(life, "write", side_effect=OSError("disk unavailable")), \
             patch.object(life.v.mac.MacRuntime, "scale_deployment") as mutate:
            with self.assertRaises(OSError): self.runtime.scale_deployment(self.deployment, 0, 1)
            mutate.assert_not_called()
        self.worker.guard.side_effect = ValueError("fenced")
        with patch.object(life.v.mac.MacRuntime, "create") as create:
            with self.assertRaises(ValueError): self.runtime.create(self.run)
            create.assert_not_called()

    def test_both_creates_and_probe_stop_are_journaled_without_shared_preflight_identity(self):
        self.runtime.pod = {"name": "shared-not-owned", "uid": str(uuid.uuid4())}
        with patch.object(life.v.mac.MacRuntime, "pod_status", return_value={}): self.runtime.pod_status()
        self.assertEqual(life.records(self.directory, "ownership"), [])
        self.runtime.pod = None
        self.pause()
        for serial in (1, 2):
            pending = {"name": f"owned-{serial}", "runId": self.run["runId"], "token": str(uuid.uuid4())}
            self.runtime.pending_pod = pending
            def response(*args):
                self.assertEqual(life.records(self.directory, "ownership")[-1]["state"]["pendingPod"], pending)
                return b"{}"
            with patch.object(life.v.mac.MacRuntime, "kube", side_effect=response):
                self.runtime.kube(["create", "-f", "-"])
            doc = {"metadata": {"name": pending["name"], "uid": str(uuid.uuid4()),
                "labels": {"gtrainer-scoped-run": self.run["runId"]},
                "annotations": {"gtrainer-scoped-create-token": pending["token"]}}}
            self.runtime.bind_pending_pod(doc)
            if serial == 1:
                def stopped(*args): self.runtime.pod = None
                with patch.object(life.v.mac.MacRuntime, "stop_pod", side_effect=stopped):
                    self.runtime.stop_pod(time.monotonic() + 60, lambda event: None)
        rows = life.records(self.directory, "ownership")
        self.assertEqual(sum(r["operation"] == "create_pending" for r in rows), 2)
        self.assertEqual(sum(r["operation"] == "create_bound" for r in rows), 2)
        self.assertEqual(sum(r["operation"] == "probe_stopped" for r in rows), 1)

    def test_final_cleanup_is_delegated_once_and_does_not_use_authorization(self):
        self.worker.cancel.return_value = {"verified": True}
        self.runtime.campaign_guard = lambda run: False
        self.runtime.pod = {"name": "owned", "uid": str(uuid.uuid4())}
        with patch.object(life.v.mac.MacRuntime, "cancel") as local:
            self.assertTrue(self.runtime.cancel(self.run, 60, lambda event: None)["verified"])
            local.assert_not_called()
        self.worker.cancel.assert_called_once()
        self.assertIsNone(self.runtime.pod)

    def test_unresolved_pause_or_absent_pending_create_never_restores(self):
        self.pause()
        for replicas in (1, 0):
            runtime = Mock()
            runtime.baseline.return_value = dict(self.deployment, spec=dict(self.deployment["spec"], replicas=replicas))
            runtime.kube.return_value = b""
            runtime.cancel.side_effect = ValueError("pending pause did not resolve to zero replicas")
            runtime.safety_state.return_value = {}
            if replicas == 0:
                self.runtime.pending_pod = {"name": "pending", "runId": self.run["runId"], "token": str(uuid.uuid4())}
                self.runtime.journal.state(self.runtime, "create_pending")
            with self.assertRaises(ValueError):
                life.cleanup(self.directory, {"run": self.run}, life.clock.monotonic() + 60, time.time() + 60,
                             factory=lambda *args: runtime)
            if replicas == 0: runtime.cancel.assert_not_called()
            else: runtime.cancel.assert_called_once()

    def test_bound_wrong_run_refuses_before_owned_cancel(self):
        self.pause()
        self.runtime.pod = {"name": "owned", "uid": str(uuid.uuid4())}
        self.runtime.journal.state(self.runtime, "create_bound")
        runtime = Mock()
        runtime.baseline.return_value = dict(self.deployment, spec=dict(self.deployment["spec"], replicas=0))
        runtime.kube.return_value = life.e.compact({"metadata": dict(self.runtime.pod, labels={"gtrainer-scoped-run": "other"})})
        with self.assertRaises(ValueError):
            life.cleanup(self.directory, {"run": self.run}, life.clock.monotonic() + 60, time.time() + 60,
                         factory=lambda *args: runtime)
        runtime.cancel.assert_not_called()

    def test_cleanup_runtime_checks_full_spec_and_wall_budget(self):
        runtime = life.CleanupRuntime(self.deployment, time.time() + 60)
        changed = copy.deepcopy(self.deployment); changed["spec"]["unchanged"] = "drift"
        with patch.object(life.v.mac.MacRuntime, "baseline", return_value=changed):
            with self.assertRaises(ValueError): runtime.baseline()
        runtime.wall_end = time.time() - 1
        with patch.object(life.v.mac.MacRuntime, "command") as command:
            with self.assertRaises(ValueError): runtime.command(["never-executed"])
            command.assert_not_called()

    def test_owned_stop_precedes_deployment_drift_refusal_and_preserves_evidence(self):
        self.pause()
        self.runtime.pod = {"name": "owned", "uid": str(uuid.uuid4())}
        self.runtime.journal.state(self.runtime, "create_bound")
        runtime = life.CleanupRuntime(self.deployment, time.time() + 60)
        runtime.kube = Mock(return_value=life.e.compact({"metadata": dict(self.runtime.pod,
            labels={"gtrainer-scoped-run": self.run["runId"]})}))
        runtime.baseline = Mock(side_effect=ValueError("deployment spec drift"))
        runtime.scale_deployment = Mock()
        def stop(deadline, emit):
            runtime.pod = None
            emit({"type": "synthetic_owned_stop"})
        runtime.stop_pod = Mock(side_effect=stop)
        with self.assertRaises(ValueError):
            life.cleanup(self.directory, {"run": self.run}, life.clock.monotonic() + 60, time.time() + 60,
                         factory=lambda *args: runtime)
        runtime.stop_pod.assert_called_once()
        runtime.scale_deployment.assert_not_called()
        self.assertEqual(life.private.read_json(self.directory / "cleanup-stop-evidence.json")["events"],
                         [{"type": "synthetic_owned_stop"}])

    def test_complete_path_uses_authoritative_cancel_once_and_checks_restoration(self):
        self.pause()
        self.runtime.pod = {"name": "owned", "uid": str(uuid.uuid4())}
        self.runtime.journal.state(self.runtime, "create_bound")
        runtime = life.CleanupRuntime(self.deployment, time.time() + 60)
        runtime.kube = Mock(return_value=life.e.compact({"metadata": dict(self.runtime.pod,
            labels={"gtrainer-scoped-run": self.run["runId"]})}))
        paused = copy.deepcopy(self.deployment); paused["spec"]["replicas"] = 0
        restored = dict(self.deployment, status={"readyReplicas": 1})
        runtime.baseline = Mock(side_effect=[paused, restored, restored, restored])
        runtime.scale_deployment = Mock()
        def stop(deadline, emit): runtime.pod = None
        runtime.stop_pod = Mock(side_effect=stop)
        runtime.document = Mock(return_value={"items": [{"metadata": {"name": "shared", "uid": str(uuid.uuid4()),
            "labels": {"app": "ollama"}}, "status": {"containerStatuses": [{"ready": True}]}}]})
        runtime.api = Mock(return_value={"models": []})
        with patch.object(runtime, "cancel", wraps=runtime.cancel) as cancel:
            result = life.cleanup(self.directory, {"run": self.run}, life.clock.monotonic() + 60, time.time() + 60,
                                  factory=lambda *args: runtime)
            cancel.assert_called_once()
        self.assertTrue(result["verified"])
        runtime.scale_deployment.assert_called_once_with(paused, 1, 0)
        runtime.api.assert_called_once_with("ps")

    def test_clock_gap_source_drift_duplicate_lock_and_missing_readiness_refuse(self):
        self.assertTrue(supervisor.gap(10, 100, 11, 120))
        self.assertFalse(supervisor.gap(10, 100, 17, 107))
        self.assertTrue(supervisor.gap(10, 100, 9, 101))
        self.assertFalse(supervisor.gap(10, 100, 11, 101))
        spec = {"mode": "synthetic_fake", "clockIdentity": supervisor.c.clock_identity(),
                "toolingSha256": {}, "workCutoff": life.clock.monotonic() + 60, "expiresUnix": time.time() + 60}
        with self.assertRaises(ValueError): supervisor.validate(spec)
        with life.locked(self.directory / "exclusive.lock"):
            with self.assertRaises(BlockingIOError):
                with life.locked(self.directory / "exclusive.lock", nonblocking=True): self.fail("duplicate ownership")
        with patch.object(life.v.mac.MacRuntime, "scale_deployment") as mutation:
            with self.assertRaises(FileNotFoundError): life.Worker(self.directory, self.directory)
            mutation.assert_not_called()

    def test_phase_deadlines_cannot_slide_and_cleanup_deadline_never_resets(self):
        now = life.clock.monotonic()
        life.write(self.directory / "phase-0001.json", {"name": "preflight", "started": now, "deadline": now + 1})
        life.write(self.directory / "phase-0002.json", {"name": "preflight", "started": now + 0.5, "deadline": now + 5})
        with self.assertRaises(ValueError): supervisor.phase_rows(self.directory, {"workCutoff": now + 30})
        life.write(self.directory / "cleanup-request.json", {"nativeEnd": now + 10, "wallEnd": time.time() + 10})
        native_end, wall_end = supervisor.cleanup_ends(self.directory, now + 5, time.time() + 5)
        self.assertEqual(native_end, now + 10)
        self.assertLess(wall_end - time.time(), 11)

    def test_atomic_publication_is_invisible_until_complete_and_never_overwrites(self):
        life.write(self.directory / "ownership-0001.json", {"state": "pending"})
        entered, release = threading.Event(), threading.Event()
        real_publish = life.publish
        errors = []
        def paused(source, destination):
            entered.set()
            self.assertTrue(release.wait(5))
            real_publish(source, destination)
        def writer():
            try: life.write(self.directory / "ownership-0002.json", {"state": "applied"})
            except BaseException as error: errors.append(error)
        with patch.object(life, "publish", side_effect=paused):
            thread = threading.Thread(target=writer); thread.start()
            try:
                self.assertTrue(entered.wait(5))
                self.assertFalse((self.directory / "ownership-0002.json").exists())
                self.assertEqual(life.records(self.directory, "ownership"), [{"state": "pending"}])
            finally: release.set(); thread.join(5)
        self.assertFalse(errors)
        self.assertEqual(life.records(self.directory, "ownership")[-1], {"state": "applied"})
        with self.assertRaises(FileExistsError): life.write(self.directory / "ownership-0001.json", {"state": "overwrite"})
        self.assertEqual(life.private.read_json(self.directory / "ownership-0001.json"), {"state": "pending"})
        self.assertEqual((self.directory / "ownership-0001.json").stat().st_nlink, 1)

    def test_killed_post_mutation_writer_leaves_previous_complete_ownership_record(self):
        life.write(self.directory / "ownership-0001.json", {"state": "pending-before-synthetic-mutation"})
        program = """import sys,time;from pathlib import Path;import lifecycle_v4 as l
d=Path(sys.argv[1]);l.write(d/'synthetic-mutation-applied.json',{'syntheticOnly':True})
def pause(source,destination):
 l.private.private_write(d/'writer-paused',b'complete bytes not yet published');time.sleep(120)
l.publish=pause;l.write(d/'ownership-0002.json',{'state':'post-mutation-update'})
"""
        child = subprocess.Popen([sys.executable, "-c", program, str(self.directory)], cwd=life.e.HERE,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            end = time.monotonic() + 10
            while not (self.directory / "writer-paused").exists():
                self.assertLess(time.monotonic(), end); time.sleep(0.02)
            child.kill(); child.wait(timeout=3)
            self.assertTrue((self.directory / "synthetic-mutation-applied.json").exists())
            self.assertEqual(life.records(self.directory, "ownership"), [{"state": "pending-before-synthetic-mutation"}])
            self.assertFalse((self.directory / "ownership-0002.json").exists())
        finally:
            if child.poll() is None: child.kill(); child.wait(timeout=3)

    def test_fence_write_and_corrupt_cleanup_request_cannot_skip_quiescence(self):
        life.private.private_write(self.directory / "cleanup-request.json", b"{")
        finalizer = supervisor.Finalizer(self.directory, {"mode": "live"})
        real_write = life.write
        def fail_fence(path, value):
            if path.name == "fenced.json": raise OSError("disk full")
            return real_write(path, value)
        with patch.object(life, "write", side_effect=fail_fence), \
             patch.object(supervisor, "quiesce") as quiesce, \
             patch.object(supervisor, "perform_cleanup", return_value={"verified": False}) as cleanup:
            finalizer.start()
            captured = finalizer.ends
            self.assertFalse(finalizer.record("fenced.json", {"reason": "caller lost"}))
            finalizer.stop(Mock())
            quiesce.assert_called_once()
            cleanup.assert_called_once()
            self.assertFalse(cleanup.call_args.kwargs["allow_restore"])
            finalizer.stop(Mock())
            self.assertEqual(cleanup.call_count, 1)
            self.assertEqual(finalizer.ends, captured)
        self.assertFalse(finalizer.result["verified"])

    def test_start_evidence_failure_still_attempts_owned_cleanup_without_restoration(self):
        real_write = life.write
        def fail_started(path, value):
            if path.name == "cleanup-started.json": raise OSError("disk full")
            return real_write(path, value)
        with patch.object(life, "write", side_effect=fail_started), \
             patch.object(supervisor, "bind", return_value={"run": self.run}), \
             patch.object(life, "cleanup", return_value={"verified": True}) as cleanup:
            result = supervisor.perform_cleanup(self.directory, {"mode": "live"},
                                                life.clock.monotonic() + 60, time.time() + 60)
            cleanup.assert_called_once()
            self.assertFalse(cleanup.call_args.kwargs["allow_restore"])
            self.assertFalse(result["verified"])

    def test_stop_proof_write_failure_happens_after_stop_and_before_restoration(self):
        self.pause()
        self.runtime.pod = {"name": "owned", "uid": str(uuid.uuid4())}
        self.runtime.journal.state(self.runtime, "create_bound")
        runtime = life.CleanupRuntime(self.deployment, time.time() + 60)
        runtime.kube = Mock(return_value=life.e.compact({"metadata": dict(self.runtime.pod,
            labels={"gtrainer-scoped-run": self.run["runId"]})}))
        runtime.baseline, runtime.scale_deployment = Mock(), Mock()
        def stop(deadline, emit):
            runtime.pod = None
            emit({"type": "synthetic_owned_stop"})
        runtime.stop_pod = Mock(side_effect=stop)
        real_write = life.write
        def fail_stop_proof(path, value):
            if path.name.startswith("cleanup-event-"): raise OSError("disk full")
            return real_write(path, value)
        with patch.object(life, "write", side_effect=fail_stop_proof):
            with self.assertRaises(OSError):
                life.cleanup(self.directory, {"run": self.run}, life.clock.monotonic() + 60,
                             time.time() + 60, factory=lambda *args: runtime)
        runtime.stop_pod.assert_called_once()
        runtime.baseline.assert_not_called()
        runtime.scale_deployment.assert_not_called()

    def test_late_phase_and_unseen_expired_intermediate_lease_are_rejected(self):
        spec = {"workCutoff": 5000}
        life.write(self.directory / "phase-0001.json", {"name": "preflight", "started": 100, "deadline": 101})
        life.write(self.directory / "phase-0002.json", {"name": "probe", "started": 102, "deadline": 1000})
        life.write(self.directory / "phase-0003.json", {"name": "i01", "started": 103, "deadline": 1001})
        with self.assertRaises(ValueError): supervisor.phase_rows(self.directory, spec)
        startup = {"name": "startup", "deadline": 99}
        with self.assertRaises(ValueError): supervisor.phase_rows(self.directory, spec, startup)

    def test_native_campaign_clock_failure_still_kills_and_reaps_real_worker_once(self):
        child = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(120)"],
                                 start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        finalizer = supervisor.Finalizer(self.directory, {"mode": "synthetic_fake"})
        try:
            with patch.object(life.clock, "monotonic", side_effect=ValueError("native API unavailable")), \
                 patch.object(supervisor, "perform_cleanup", return_value={"verified": True}) as cleanup:
                finalizer.stop(child)
                self.assertEqual(child.returncode, -signal.SIGKILL)
                cleanup.assert_called_once()
                self.assertFalse(cleanup.call_args.kwargs["allow_restore"])
                self.assertIsNotNone(cleanup.call_args.kwargs["local_end"])
                captured = finalizer.local_end
                finalizer.stop(child)
                self.assertEqual(finalizer.local_end, captured)
                self.assertEqual(cleanup.call_count, 1)
                self.assertFalse(finalizer.result["verified"])
        finally:
            if child.poll() is None: os.killpg(child.pid, signal.SIGKILL); child.wait(timeout=3)

    def test_native_fault_cannot_skip_shared_owned_stop_or_permit_restoration(self):
        self.pause()
        self.runtime.pod = {"name": "owned", "uid": str(uuid.uuid4())}
        self.runtime.journal.state(self.runtime, "create_bound")
        runtime = life.CleanupRuntime(self.deployment, time.time() + 60)
        runtime.kube = Mock(return_value=life.e.compact({"metadata": dict(self.runtime.pod,
            labels={"gtrainer-scoped-run": self.run["runId"]})}))
        runtime.command, runtime.scale_deployment = Mock(), Mock()
        def stop(deadline, emit):
            runtime.pod = None
            emit({"type": "synthetic_owned_stop"})
        runtime.stop_pod = Mock(side_effect=stop)
        native_end = life.clock.monotonic() + 60
        with patch.object(life.clock, "monotonic", side_effect=ValueError("native API unavailable")):
            with self.assertRaises(ValueError):
                life.cleanup(self.directory, {"run": self.run}, native_end, time.time() + 60,
                             factory=lambda *args: runtime, local_end=time.monotonic() + 60)
        runtime.stop_pod.assert_called_once()
        runtime.scale_deployment.assert_not_called()
        runtime.command.assert_not_called()


@unittest.skipUnless(sys.platform == "darwin", "actual temporary macOS launchd tests")
class LaunchdTest(unittest.TestCase):
    def job(self, scenario, seconds=15):
        # Retain private forensic evidence; never delete a live job's directory.
        directory = Path(tempfile.mkdtemp(prefix="v4w-", dir=supervisor.BASE))
        spec = {"mode": "synthetic_fake", "scenario": scenario,
            "clockIdentity": supervisor.c.clock_identity(), "toolingSha256": supervisor.c.tooling(),
            "workCutoff": life.clock.monotonic() + seconds, "expiresUnix": time.time() + seconds,
            "nonce": uuid.uuid4().hex}
        target = supervisor.prepare(directory, spec)
        self.addCleanup(self.remove_job, target, directory)
        return directory

    @staticmethod
    def remove_job(target, directory):
        # Bootout only completed jobs. Test failure never kills the cleanup owner.
        if (directory / "outcome.json").exists():
            subprocess.run(["/bin/launchctl", "bootout", target], capture_output=True, timeout=10)

    def wait_file(self, directory, name, seconds=20):
        end = time.monotonic() + seconds
        while not (directory / name).exists():
            if time.monotonic() >= end:
                self.fail(f"Missing {name}; private evidence {directory}")
            time.sleep(0.05)
        return life.private.read_json(directory / name)

    def outcome(self, directory):
        result = self.wait_file(directory, "outcome.json")
        self.assertTrue(result["cleanup"]["verified"])
        self.assertEqual(result["cleanupInvocations"], 1)
        self.assertEqual(len(list(directory.glob("synthetic-cleanup.json"))), 1)
        return result

    def test_normal_cleanup_one_owner(self):
        directory = self.job("normal")
        client = supervisor.connect(directory)
        try: self.assertEqual(self.outcome(directory)["reason"], "normal")
        finally: client.close()

    def test_real_caller_process_tree_loss_does_not_cancel_healthy_worker(self):
        directory = self.job("delayed")
        program = "import sys,time;from pathlib import Path;import supervisor_v4 as s;c=s.connect(Path(sys.argv[1]));time.sleep(120)"
        caller = subprocess.Popen([sys.executable, "-c", program, str(directory)], cwd=life.e.HERE,
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        try:
            self.wait_file(directory, "binding.json")
            ready = life.private.read_json(directory / "ready.json")
            self.assertEqual(ready["launchdParent"], 1)
            os.killpg(caller.pid, signal.SIGKILL); caller.wait(timeout=3)
            result = self.outcome(directory)
            self.assertEqual(result["reason"], "normal")
            self.assertEqual(result["workerExitCode"], 0)
        finally:
            if caller.poll() is None: os.killpg(caller.pid, signal.SIGKILL); caller.wait(timeout=3)

    def test_no_observer_locked_worker_still_stops_at_deadline(self):
        directory = self.job("locked", seconds=4)
        self.wait_file(directory, "fixture-locked.json")
        self.assertEqual(self.outcome(directory)["reason"], "supervisor_deadline")

    def test_reattach_and_idempotent_explicit_cancel_do_not_launch_another_worker(self):
        directory = self.job("locked")
        self.wait_file(directory, "fixture-locked.json")
        original = life.private.read_json(directory / "worker.json")
        for _ in range(3):
            observer = supervisor.connect(directory); observer.close()
            self.assertEqual(supervisor.status(directory)["worker"], original)
        self.assertEqual(supervisor.cancel(directory), "cancellation_requested")
        self.assertEqual(supervisor.cancel(directory), "cancellation_requested")
        self.assertEqual(self.outcome(directory)["reason"], "explicit_cancel")
        self.assertEqual(life.private.read_json(directory / "worker.json"), original)

    def test_fixed_deadline_kills_suspended_worker_without_new_grant(self):
        directory = self.job("hang", seconds=4)
        client = supervisor.connect(directory)
        try:
            self.assertEqual(self.outcome(directory)["reason"], "supervisor_deadline")
            request = life.private.read_json(directory / "cleanup-started.json")
            self.assertLessEqual(request["nativeEnd"] - life.clock.monotonic(), 60)
        finally: client.close()

    def test_worker_exit_fences_without_replacement(self):
        directory = self.job("exit")
        client = supervisor.connect(directory)
        try:
            self.assertEqual(self.outcome(directory)["reason"], "supervisor_worker_lost")
            self.assertEqual(len(list(directory.glob("worker.json"))), 1)
        finally: client.close()

    def test_late_worker_after_cleanup_does_not_repeat_cleanup(self):
        directory = self.job("cleanup_hang", seconds=4)
        client = supervisor.connect(directory)
        try:
            self.assertEqual(self.outcome(directory)["reason"], "supervisor_deadline")
            self.assertEqual(len(list(directory.glob("cleanup-started.json"))), 1)
        finally: client.close()

    def test_real_elapsed_generation_lease_is_identified_separately_from_outer_expiry(self):
        import recovery_v4 as recovery
        directory=self.job('generation_expiry',seconds=15)
        result=self.outcome(directory)
        self.assertEqual(result['reason'],'supervisor_deadline')
        self.assertEqual(result['expiredPhase']['name'],'i01')
        self.assertEqual(result['expiredPhase']['trigger'],'phase')
        campaign=directory/'synthetic-campaign';batch=campaign/'batch-01';batch.mkdir(mode=0o700,parents=True)
        life.write(campaign/'campaign.json',{'workCutoffMonotonic':result['expiredPhase']['deadline']+10})
        intent={'actionId':'i01','deadlineMonotonic':result['expiredPhase']['deadline']}
        life.write(batch/'i01.intent.json',intent)
        life.write(batch/'i01.launched.json',{'intentSha256':life.e.digest(life.e.compact(intent))})
        self.assertTrue(recovery.generation_expired(campaign,result))
        for name in ('startup','validation','probe'):
            changed=dict(result,expiredPhase=dict(result['expiredPhase'],name=name))
            self.assertFalse(recovery.generation_expired(campaign,changed))
        changed=dict(result,expiredPhase=dict(result['expiredPhase'],trigger='approval_expiry'))
        self.assertFalse(recovery.generation_expired(campaign,changed))

    def test_supervisor_scheduling_delay_does_not_cancel_valid_unchanged_lease(self):
        directory = self.job("delayed", seconds=20)
        client = supervisor.connect(directory)
        try:
            self.wait_file(directory, "binding.json")
            pid = life.private.read_json(directory / "ready.json")["supervisorPid"]
            os.kill(pid, signal.SIGSTOP)
            try: time.sleep(5.5)
            finally: os.kill(pid, signal.SIGCONT)
            self.assertEqual(self.outcome(directory)["reason"], "normal")
        finally: client.close()


if __name__ == "__main__": unittest.main()
