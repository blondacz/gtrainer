import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from node_health_observer import APP_COMMAND, application, host_available, observe


class NodeHealthObserverTest(unittest.TestCase):
    container = "a" * 64
    boot = "00000000-0000-0000-0000-000000000001"

    def inspect(self, **changes):
        status = {"id": self.container, "state": "CONTAINER_RUNNING", "metadata": {"attempt": 0},
            "labels": {"io.kubernetes.container.name": "dedicated-worker-runtime", "io.kubernetes.pod.namespace": "gtrainer-connected-review-development"}}
        status.update(changes)
        return json.dumps({"status": status, "info": {"runtimeSpec": {"linux": {"resources": {"memory": {"limit": 5 * 1024 ** 3}}}},
            "private": "synthetic-private-marker"}}).encode()

    def pods(self):
        return {"items": [{"metadata": {"namespace": "gtrainer", "uid": "synthetic-pod", "labels": {"app.kubernetes.io/name": "gtrainer"}},
            "spec": {"private": "synthetic-private-marker"}, "status": {"phase": "Running", "conditions": [{"type": "Ready", "status": "True"}],
                "containerStatuses": [{"name": "app", "imageID": "synthetic-image", "ready": True, "restartCount": 3}]}}]}

    def test_resource_sample_preserves_distinct_headroom_and_appliance_limit_without_raw_inspect(self):
        result = observe(self.container, self.boot, lambda: self.boot, lambda: "MemAvailable: 1048576 kB\n", lambda _: self.inspect())
        self.assertEqual(1024 ** 3, result["availableBytes"])
        self.assertEqual(5 * 1024 ** 3, result["applianceMemoryLimitBytes"])
        self.assertEqual(0, result["containerAttempt"])
        self.assertNotIn("synthetic-private-marker", json.dumps(result))

    def test_changed_boot_wrong_container_workload_and_nonrunning_state_are_refused(self):
        for change in ({"id": "b" * 64}, {"state": "CONTAINER_EXITED"}, {"labels": {}}, {"metadata": {"attempt": True}}):
            with self.assertRaises(ValueError):
                observe(self.container, self.boot, lambda: self.boot, lambda: "MemAvailable: 1048576 kB", lambda _: self.inspect(**change))
        with self.assertRaises(ValueError):
            observe(self.container, self.boot, lambda: "00000000-0000-0000-0000-000000000002", lambda: self.fail("changed host queried"))
        boots = iter([self.boot, "00000000-0000-0000-0000-000000000002"])
        with self.assertRaises(ValueError):
            observe(self.container, self.boot, lambda: next(boots), lambda: "MemAvailable: 1048576 kB", lambda _: self.inspect())

    def test_memory_parser_rejects_missing_duplicate_wrong_unit_negative_and_unbounded_values(self):
        for value in ("", "MemAvailable: -1 kB", "MemAvailable: 1 GB", "MemAvailable: 1 kB\nMemAvailable: 2 kB",
            "MemAvailable: 999999999999999999999999 kB"):
            with self.assertRaises(ValueError):
                host_available(value)

    def test_application_read_is_fixed_and_projects_only_safe_readiness_restart_identity_fields(self):
        def runner(command):
            self.assertEqual(APP_COMMAND, command)
            return json.dumps(self.pods()).encode()
        result = application(runner)
        self.assertEqual(3, result["containers"][0]["restartCount"])
        self.assertTrue(result["containers"][0]["ready"])
        self.assertNotIn("synthetic-private-marker", json.dumps(result))

    def test_empty_other_namespace_deleting_and_malformed_app_states_are_refused(self):
        invalid = [{"items": []}]
        for field, value in (("namespace", "default"), ("labels", {}), ("deletionTimestamp", "synthetic-timestamp")):
            data = self.pods()
            data["items"][0]["metadata"][field] = value
            invalid.append(data)
        for field, value in (("restartCount", True), ("ready", "true"), ("name", "x" * 129)):
            data = self.pods()
            data["items"][0]["status"]["containerStatuses"][0][field] = value
            invalid.append(data)
        for data in invalid:
            with self.assertRaises(ValueError):
                application(lambda _: json.dumps(data).encode())


if __name__ == "__main__":
    unittest.main()
