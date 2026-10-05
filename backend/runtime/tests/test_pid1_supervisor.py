import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from linux_children import BoundaryFailure
from pid1_supervisor import ControlDirectory, configuration


@unittest.skipUnless(sys.platform == "linux" and os.geteuid() == 0, "requires isolated Linux PID namespace fixture runner")
class Pid1SupervisorTest(unittest.TestCase):
    def appliance(self, directory, scenario):
        config = {"enabled": True, "controlDirectory": str(directory / "control"), "modelsDirectory": str(directory / "models"),
            "workerCommand": [sys.executable, str(Path(__file__).with_name("fixture_process.py")), "appliance-worker", str(directory), scenario],
            "workerEnvironment": {}}
        (directory / "models").mkdir()
        (directory / "models" / "synthetic-public-model-marker").write_text("not a model")
        path = directory / "config.json"
        path.write_text(json.dumps(config))
        entry = str(Path(__file__).with_name("appliance_entry.py"))
        result = subprocess.run(["unshare", "--pid", "--fork", "--mount-proc", "--kill-child", sys.executable, entry, str(path), str(directory)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20,
            env={**os.environ, "GTRAINER_PASSWORD_VERIFIER_FILE": "synthetic-private-marker", "OLLAMA_HOST": "http://foreign.invalid"})
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual(b"", result.stdout)
        self.assertNotIn(b"synthetic-private-marker", result.stderr)
        self.assertEqual("not a model", (directory / "models" / "synthetic-public-model-marker").read_text())
        runtime_env = json.loads((directory / "runtime-env.json").read_text())
        self.assertEqual("127.0.0.1:11434", runtime_env["OLLAMA_HOST"])
        self.assertEqual(str(directory / "models"), runtime_env["OLLAMA_MODELS"])
        self.assertNotIn("GTRAINER_PASSWORD_VERIFIER_FILE", runtime_env)
        return json.loads((directory / "worker-result.json").read_text()) if scenario != "worker-crash" else None

    def test_per_run_recycling_keeps_same_runtime_for_correction_and_preserves_models(self):
        with tempfile.TemporaryDirectory(prefix="pid1-cycle-") as name:
            directory = Path(name)
            result = self.appliance(directory, "cycle")
            self.assertEqual(result["first"]["keeper_pid"], result["during_correction"]["keeper_pid"])
            self.assertNotEqual(result["first"]["keeper_pid"], result["second"]["keeper_pid"])
            self.assertFalse(result["overlap"]["ok"])
            self.assertFalse(result["wrong_identity"]["ok"])
            self.assertTrue(result["stop"]["confirmed"])
            self.assertEqual(result["stop"], result["repeated_stop"])
            self.assertLess(result["stop"]["elapsed_millis"], 3000)
            self.assertTrue(result["second_stop"]["confirmed"])
            receipt = json.loads((directory / "control" / "last-stop-receipt.json").read_text())
            self.assertEqual(8, receipt["identity"]["generation"])
            self.assertEqual(0, (directory / "control" / "last-stop-receipt.json").stat().st_mode & 0o077)

    def test_worker_failure_stops_runtime_before_appliance_exit(self):
        with tempfile.TemporaryDirectory(prefix="pid1-worker-crash-") as name:
            directory = Path(name)
            self.appliance(directory, "worker-crash")
            receipt = json.loads((directory / "control" / "last-stop-receipt.json").read_text())
            self.assertTrue(receipt["confirmed"])
            self.assertEqual("linux_subreaper_waitpid_echild", receipt["mechanism"])
            self.assertGreaterEqual(receipt["reaped"], 2)

    def test_keeper_failure_quarantines_new_starts_without_killing_healthy_worker(self):
        with tempfile.TemporaryDirectory(prefix="pid1-keeper-crash-") as name:
            result = self.appliance(Path(name), "keeper-crash")
            self.assertEqual("runtime_stop_unconfirmed", result["blocked"]["reason"])
            self.assertFalse(result["blocked"]["ok"])

    def test_same_uid_foreign_process_cannot_stop_runtime(self):
        with tempfile.TemporaryDirectory(prefix="pid1-foreign-peer-") as name:
            result = self.appliance(Path(name), "foreign-peer")
            self.assertTrue(result["foreign_denied"])
            self.assertFalse(result["still_running"]["runtime_exited"])

    def test_control_directory_lifetime_lock_rejects_competing_owner(self):
        with tempfile.TemporaryDirectory(prefix="pid1-lifetime-lock-") as name:
            first = ControlDirectory(name)
            try:
                with self.assertRaises(BoundaryFailure):
                    ControlDirectory(name)
            finally:
                first.close()

    def test_disabled_configuration_does_not_require_pid1_or_create_state(self):
        with tempfile.TemporaryDirectory(prefix="disabled-pid1-") as name:
            path = Path(name, "disabled.json")
            path.write_text('{"enabled":false}')
            self.assertIsNone(configuration(path))
            self.assertEqual([path], list(Path(name).iterdir()))


if __name__ == "__main__":
    unittest.main()
