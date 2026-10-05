import json
import os
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from node_termination_verifier import MAX_INSPECT_BYTES, inspect_container, verify


class NodeTerminationVerifierTest(unittest.TestCase):
    container = "a" * 64
    boot = "00000000-0000-0000-0000-000000000001"

    def status(self, **changes):
        status = {"id": self.container, "state": "CONTAINER_EXITED", "finishedAt": 100,
            "labels": {"io.kubernetes.container.name": "dedicated-worker-runtime", "io.kubernetes.pod.namespace": "gtrainer-connected-review-development"}}
        status.update(changes)
        return json.dumps({"status": status, "info": {"privateMarker": "synthetic-private-marker"}}).encode()

    def test_exact_stopped_container_has_content_free_proof(self):
        result = verify(self.container, self.boot, lambda: self.boot, lambda _: self.status())
        self.assertEqual("CONTAINER_EXITED", result["result"])
        self.assertNotIn("synthetic-private-marker", json.dumps(result))

    def test_reboot_is_proof_without_querying_missing_old_container(self):
        new_boot = "00000000-0000-0000-0000-000000000002"
        result = verify(self.container, self.boot, lambda: new_boot, lambda _: self.fail("unneeded CRI query"))
        self.assertEqual("HOST_REBOOTED", result["result"])

    def test_running_wrong_id_missing_finished_time_and_other_workloads_never_prove_stop(self):
        for change in ({"state": "CONTAINER_RUNNING"}, {"id": "b" * 64}, {"finishedAt": 0}, {"labels": {}},
            {"labels": {"io.kubernetes.container.name": "ordinary-app", "io.kubernetes.pod.namespace": "default"}}):
            self.assertEqual("UNCONFIRMED", verify(self.container, self.boot, lambda: self.boot, lambda _: self.status(**change))["result"])

    def test_missing_malformed_oversized_and_unreachable_evidence_are_not_positive_proof(self):
        for raw in (b"{}", b"{", b"\xff", b"x" * (MAX_INSPECT_BYTES + 1), b'{"status":{},"status":{}}'):
            with self.assertRaises((ValueError, KeyError, UnicodeError)):
                verify(self.container, self.boot, lambda: self.boot, lambda _: raw)
        def unavailable(_):
            raise OSError("synthetic-private-marker")
        with self.assertRaises(OSError):
            verify(self.container, self.boot, lambda: self.boot, unavailable)

    def test_arguments_cannot_inject_commands_or_extra_operations(self):
        for value in ("-a", "x;touch marker", "private-marker", "a" * 65):
            with self.assertRaises(ValueError):
                verify(value, self.boot, lambda: self.fail("invalid input accessed node"))

    def test_invalid_old_boot_is_rejected_before_accessing_node(self):
        for boot in ("not-uuid", "00000000000000000000000000000001"):
            with self.assertRaises(ValueError):
                verify(self.container, boot, lambda: self.fail("invalid boot accessed node"))

    def test_read_only_cri_command_is_fixed_bounded_and_does_not_inherit_private_environment(self):
        test = self
        class Process:
            def __init__(self, command, **kwargs):
                test.assertEqual(["/usr/local/bin/k3s", "crictl", "--timeout", "10s", "inspect", test.container], command)
                test.assertEqual({"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8"}, kwargs["env"])
                read, write = os.pipe()
                os.write(write, test.status())
                os.close(write)
                self.stdout = os.fdopen(read, "rb")
            def wait(self, timeout):
                test.assertLessEqual(timeout, 15)
                return 0
            def poll(self):
                return 0
        self.assertEqual(self.status(), inspect_container(self.container, Process))

    def test_finished_time_requires_a_real_positive_integer_not_a_boolean_or_float(self):
        for finished in (True, 1.0, "1.0", "-1", "", None):
            self.assertEqual("UNCONFIRMED", verify(self.container, self.boot, lambda: self.boot,
                lambda _: self.status(finishedAt=finished))["result"])
        self.assertEqual("CONTAINER_EXITED", verify(self.container, self.boot, lambda: self.boot,
            lambda _: self.status(finishedAt="100"))["result"])


if __name__ == "__main__":
    unittest.main()
