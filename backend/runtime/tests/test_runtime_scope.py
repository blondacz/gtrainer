import json
import os
from pathlib import Path
import select
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from linux_children import BoundaryFailure, OwnedChildren, become_subreaper
from runtime_scope import RuntimeScope, ScopeIdentity, receive_frame


@unittest.skipUnless(sys.platform == "linux", "requires real Linux kernel process primitives")
class RuntimeScopeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        become_subreaper()

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="synthetic-process-fixture-")
        self.identity = ScopeIdentity("00000000-0000-0000-0000-000000000001", 3, 7, "a" * 64)
        self.scopes = []

    def tearDown(self):
        for scope in self.scopes:
            if scope.process.poll() is None:
                scope.stop(2, 0.1)
        self.directory.cleanup()

    def scope(self, mode="cooperative"):
        fixture = str(Path(__file__).with_name("fixture_process.py"))
        result = RuntimeScope(self.identity, [sys.executable, fixture, mode, self.directory.name], {"PATH": "/usr/bin:/bin"})
        self.scopes.append(result)
        if mode != "root-exit":
            self.marker("ready")
        return result

    def marker(self, name):
        path = Path(self.directory.name, name)
        deadline = time.monotonic() + 5
        while not path.exists() or not path.read_text():
            if time.monotonic() >= deadline:
                self.fail("controlled fixture did not become ready")
            time.sleep(0.01)
        return int(path.read_text())

    def assert_exited(self, pid):
        try:
            descriptor = os.pidfd_open(pid)
        except ProcessLookupError:
            return
        try:
            self.assertTrue(select.select([descriptor], [], [], 0)[0], "owned process still executing")
        finally:
            os.close(descriptor)

    def test_cooperative_stop_reaps_and_binds_receipt(self):
        scope = self.scope()
        pid = self.marker("runtime.pid")
        receipt = scope.stop(2, 0.5)
        self.assertTrue(receipt["confirmed"])
        self.assertEqual("linux_subreaper_waitpid_echild", receipt["mechanism"])
        self.assertEqual(self.identity.__dict__, receipt["identity"])
        self.assertFalse(receipt["escalated"])
        self.assert_exited(pid)

    def test_detached_double_fork_is_killed_and_reaped(self):
        scope = self.scope("detached")
        detached = self.marker("detached.pid")
        receipt = scope.stop(2, 0.1)
        self.assertTrue(receipt["confirmed"])
        self.assertTrue(receipt["escalated"])
        self.assertGreaterEqual(receipt["reaped"], 2)
        self.assert_exited(detached)

    def test_main_exit_is_not_stop_evidence_while_descendant_lives(self):
        scope = self.scope("root-exit")
        detached = self.marker("detached.pid")
        deadline = time.monotonic() + 3
        while not scope.status()["runtime_exited"]:
            if time.monotonic() > deadline:
                self.fail("controlled main process did not exit")
        self.assertFalse(scope.status()["confirmed"])
        self.assertTrue(scope.stop(2, 0.1)["confirmed"])
        self.assert_exited(detached)

    def test_term_resistant_child_escalates_within_total_budget(self):
        scope = self.scope("ignore-term")
        receipt = scope.stop(1, 0.05)
        self.assertTrue(receipt["confirmed"])
        self.assertTrue(receipt["escalated"])
        self.assertLess(receipt["elapsed_millis"], 1000)

    def test_descendant_forked_during_term_is_also_reaped(self):
        scope = self.scope("fork-on-term")
        receipt = scope.stop(2, 0.2)
        self.assertTrue(receipt["confirmed"])
        self.assert_exited(self.marker("late-child.pid"))

    def test_other_process_is_never_signalled(self):
        sentinel = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        try:
            scope = self.scope("detached")
            self.marker("detached.pid")
            self.assertTrue(scope.stop(2, 0.1)["confirmed"])
            self.assertIsNone(sentinel.poll())
        finally:
            sentinel.terminate()
            sentinel.wait(timeout=2)

    def test_abrupt_owner_exit_triggers_eof_cleanup(self):
        fixture = str(Path(__file__).with_name("fixture_process.py"))
        owner = subprocess.Popen([sys.executable, fixture, "abandon-owner", self.directory.name])
        self.assertEqual(17, owner.wait(timeout=6))
        keeper = self.marker("keeper.pid")
        detached = self.marker("detached.pid")
        descriptor = os.pidfd_open(keeper)
        try:
            self.assertTrue(select.select([descriptor], [], [], 8)[0], "keeper failed EOF cleanup")
        finally:
            os.close(descriptor)
        os.waitpid(keeper, 0)
        self.assert_exited(detached)

    def test_bounded_strict_protocol_does_not_echo_rejected_content(self):
        left, right = socket.socketpair()
        try:
            for frame in (b'{"op":"status","op":"private-marker"}\n', b"x" * 4097):
                right.sendall(frame)
                with self.assertRaises((BoundaryFailure, ValueError)) as failure:
                    receive_frame(left)
                self.assertNotIn("private-marker", str(failure.exception))
        finally:
            left.close(); right.close()

    def test_invalid_scope_identity_is_refused(self):
        for args in (("private-marker", 1, 1, "a" * 64), (self.identity.run_id, 0, 1, "a" * 64),
            (self.identity.run_id, 1, 0, "a" * 64), (self.identity.run_id, 1, 1, "wrong")):
            with self.assertRaises((BoundaryFailure, ValueError)):
                ScopeIdentity(*args)

    def test_stop_budgets_reject_boolean_text_and_nonfinite_values_before_signalling(self):
        scope = self.scope()
        for total, term in ((True, 0), (2, False), ("2", 0), (float("nan"), 0), (2, float("inf")), (61, 1)):
            with self.assertRaises(BoundaryFailure):
                scope.stop(total, term)
        self.assertFalse(scope.status()["runtime_exited"])
        self.assertTrue(scope.stop(2, 0.1)["confirmed"])


if __name__ == "__main__":
    unittest.main()
