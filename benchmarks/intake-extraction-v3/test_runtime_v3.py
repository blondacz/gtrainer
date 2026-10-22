import copy
import base64
import hashlib
import contextlib
import io
import tempfile
import subprocess
import datetime as dt
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import runtime_v3 as v
import run_mac_v3


class RuntimeSeamTest(unittest.TestCase):
    def setUp(self):
        _, self.artifacts = v.e.verify()
        self.run = {"profile": v.e.PROFILE, "executionMode": "live", "experiment": v.bindings(),
                     "runtime": v.mac.IDENTITY, "model": v.replay.MODEL, "settings": v.replay.settings(self.artifacts),
                     "runnerPolicy": copy.deepcopy(v.RUNNER_POLICY), "requestVariant": v.request_variant()}

    def test_no_repack_policy_is_v3_only_and_does_not_change_requests(self):
        run = dict(self.run, runId="11111111-1111-4111-8111-111111111111")
        base = v.mac.MacRuntime().manifest(run)
        manifest = v.Runtime().manifest(run)
        self.assertNotIn("LLAMA_ARG_REPACK", {x["name"] for x in base["spec"]["containers"][0]["env"]})
        expected = copy.deepcopy(base)
        expected["spec"]["containers"][0]["env"].append({"name": "LLAMA_ARG_REPACK", "value": "0"})
        self.assertEqual(manifest, expected)
        self.assertEqual(self.run["runnerPolicy"]["environment"], {"LLAMA_ARG_REPACK": "0"})

    def test_mmap_variant_binds_new_wire_bytes_and_identical_frozen_context(self):
        proof_before = (v.e.HERE / "context-proof-v3.json").read_bytes()
        variant = v.request_variant()
        self.assertEqual(variant["frozenContextProofSha256"], v.e.digest(proof_before))
        self.assertEqual(variant["renderedSha256"], v.bindings()["renderedSha256"])
        for case in self.artifacts["cases"]:
            original = v.payload(case["exactPrompt"])
            changed = v.mmap_payload(case["exactPrompt"])
            self.assertIs(changed["options"].pop("use_mmap"), True)
            self.assertEqual(v.e.compact(changed), v.e.compact(original))
            self.assertNotIn("use_mmap", original["options"])
            self.assertEqual(variant["frozenRequestSha256"][case["caseId"]], v.e.digest(v.e.compact(original)))
            self.assertEqual(variant["requestSha256"][case["caseId"]],
                             v.e.digest(v.e.compact(v.mmap_payload(case["exactPrompt"]))))
            self.assertNotEqual(variant["requestSha256"][case["caseId"]], variant["frozenRequestSha256"][case["caseId"]])
        probe = v.with_mmap(v.probe_payload())
        self.assertEqual(variant["probeRequestSha256"], v.e.digest(v.e.compact(probe)))
        self.assertIs(probe["options"].pop("use_mmap"), True)
        self.assertEqual(probe, v.probe_payload())
        self.assertEqual(proof_before, (v.e.HERE / "context-proof-v3.json").read_bytes())
        with self.assertRaises(ValueError): v.with_mmap({"options": {"use_mmap": False}})

    def test_server_environment_drift_refuses_chat_before_launch(self):
        runtime = v.Runtime(); runtime.pod = {"name": "owned", "uid": "uid"}
        document = {"metadata": {"uid": "uid"}, "spec": {"containers": [{"env": [
            {"name": "LLAMA_ARG_REPACK", "value": "0"}]}]}}
        with patch.object(runtime, "pod_status"), patch.object(runtime, "document", return_value=document), \
             patch.object(runtime, "kube", return_value=b"LLAMA_ARG_REPACK=1\n"), \
             patch.object(v.mac.MacRuntime, "launch") as launch:
            with self.assertRaises(RuntimeError): runtime.launch("chat", {})
            launch.assert_not_called()
        document["spec"]["containers"][0]["env"].append({"name": "LLAMA_ARG_REPACK", "value": "0"})
        with patch.object(runtime, "pod_status"), patch.object(runtime, "document", return_value=document), \
             patch.object(runtime, "kube") as kube:
            with self.assertRaises(RuntimeError): runtime.verify_server()
            kube.assert_not_called()

    def test_loaded_probe_requires_actual_runner_and_non_repack_buffer_log(self):
        args = base64.b64encode(b"/usr/lib/ollama/llama-server\0--offline\0")
        good = b"123|LLAMA_ARG_REPACK=0|" + args + b"\n"
        for observed, logs, accepted in ((good, b"load_mode = mmap\nCPU_Mapped model buffer size = 4000 MiB", True),
                (good, b"load_mode = none\nCPU model buffer size = 4000 MiB", False),
                (good, b"CPU_REPACK model buffer size = 4000 MiB", False),
                (good, b"no buffer evidence", False), (b"", b"CPU model buffer size = 4000 MiB", False),
                (good.replace(b"REPACK=0", b"REPACK=1"), b"CPU model buffer size = 4000 MiB", False)):
            runtime = v.Runtime(); runtime.pod = {"name": "owned", "uid": "uid"}; runtime.variant_uid = "uid"
            events = []; runtime.diagnostic_emit = events.append
            with patch.object(runtime, "kube", return_value=observed), patch.object(runtime, "loader_logs", return_value=logs):
                if accepted: runtime.verify_loaded_runner({"models": ["loaded"]})
                else:
                    with self.assertRaises(RuntimeError): runtime.verify_loaded_runner({"models": ["loaded"]})
            self.assertEqual(runtime.runner_probe_verified, accepted)

    def test_runner_cli_override_and_missing_process_refuse(self):
        runtime = v.Runtime(); runtime.pod = {"name": "owned", "uid": "uid"}; runtime.variant_uid = "uid"
        runtime.diagnostic_emit = lambda e: None
        for args in (b"/usr/lib/ollama/llama-server\0--repack\0", b"/usr/lib/ollama/llama-server\0--repack=true\0",
                     b"/usr/lib/ollama/llama-server\0--load-mode\0none\0",
                     b"/usr/lib/ollama/llama-server\0--load-mode=none\0",
                     b"/usr/lib/ollama/llama-server\0--no-mmap\0"):
            raw = b"123|LLAMA_ARG_REPACK=0|" + base64.b64encode(args) + b"\n"
            with patch.object(runtime, "kube", return_value=raw):
                with self.assertRaises(RuntimeError): runtime.runner_configuration(required=True)
        with patch.object(runtime, "kube", return_value=b""):
            self.assertFalse(runtime.runner_configuration())
            with self.assertRaises(RuntimeError): runtime.runner_configuration(required=True)

    def test_loader_logs_are_owned_bounded_hashed_and_stop_survives_log_failure(self):
        runtime = v.Runtime(); runtime.pod = {"name": "owned", "uid": "uid"}; runtime.variant_uid = "uid"
        events = []; runtime.diagnostic_emit = events.append
        document = {"metadata": {"uid": "uid"}}
        with patch.object(runtime, "document", return_value=document), patch.object(runtime, "kube", return_value=b"log"):
            self.assertEqual(runtime.loader_logs(), b"log")
        self.assertEqual(events[-1]["sha256"], v.e.digest(b"log"))
        self.assertEqual(base64.b64decode(events[-1]["base64"]), b"log")
        with patch.object(runtime, "document", return_value={"metadata": {"uid": "replaced"}}), patch.object(runtime, "kube") as kube:
            with self.assertRaises(RuntimeError): runtime.loader_logs()
            kube.assert_not_called()
        runtime.deadline = 900
        with patch.object(runtime, "loader_logs", side_effect=RuntimeError("lost logs")), \
             patch.object(v.mac.MacRuntime, "stop_pod") as stop, patch.object(v.time, "monotonic", return_value=10):
            runtime.stop_pod(60, events.append)
            stop.assert_called_once()
            self.assertEqual(runtime.deadline, 900)
            self.assertEqual(events[-1]["type"], "loader_log_unavailable")
        runtime.log_counts["uid"] = v.RUNNER_POLICY["maximumSnapshotsPerPod"]
        with patch.object(runtime, "kube") as kube:
            with self.assertRaises(RuntimeError): runtime.loader_logs()
            kube.assert_not_called()

    def test_scoring_remains_blocked_before_verified_runner_probe(self):
        runtime = v.Runtime(); runtime.prepared = runtime.probed = True
        with patch.object(runtime, "sample") as sample, patch.object(runtime, "launch") as launch:
            with self.assertRaises(RuntimeError):
                runtime.generate(self.artifacts["cases"][0]["exactPrompt"], self.run, 900, lambda e: None)
            sample.assert_not_called(); launch.assert_not_called()

    def test_v1_bytes_remain_equal_for_all_initial_and_correction_prompts(self):
        _, artifacts = v.e.baseline.verify()
        for case in artifacts["cases"]:
            if case["profile"] != "context-intake-suggestion-v1": continue
            for key in ("exactPrompt", "exactCorrectionPrompt"):
                prompt = case[key]
                old = {"model": "qwen3:8b", "messages": [
                    {"role": "system", "content": prompt["systemInstructions"]},
                    {"role": "user", "content": prompt["userPacketJson"]}],
                    "format": "json", "stream": False, "think": False, "keep_alive": "0s",
                    "options": {"temperature": 0, "seed": 42, "num_thread": 3, "num_ctx": 4096, "num_predict": 1536}}
                self.assertEqual(v.e.compact(v.mac.MacRuntime.payload(prompt)), v.e.compact(old))

    def test_exact_schema_and_request_hashes_all_eight_and_no_projection(self):
        for case in self.artifacts["cases"]:
            request = v.payload(case["exactPrompt"])
            self.assertEqual(request["format"], self.artifacts["schema"])
            self.assertEqual(v.e.digest(v.e.compact(request)), self.run["experiment"]["requestSha256"][case["caseId"]])
            self.assertLessEqual(len(v.e.compact(request)), 65536)
            self.assertNotIn("tools", request)
        with self.assertRaises(ValueError): v.payload({"systemInstructions": "changed", "userPacketJson": "{}"})

    def test_missing_drifted_and_cross_version_proofs_refuse_before_runtime_actions(self):
        for key, value in (("experiment", None), ("profile", "context-intake-extraction-v2"), ("settings", {}), ("model", {}), ("runtime", {}), ("runnerPolicy", {}), ("requestVariant", {})):
            run = dict(self.run, **{key: value})
            runtime = v.mac.MacRuntime(request_builder=v.payload, context_validator=v.context)
            runtime.prepared = runtime.probed = True
            with patch.object(runtime, "baseline") as baseline, patch.object(runtime, "sample") as sample, patch.object(runtime, "launch") as launch, patch.object(runtime, "kube") as kube:
                for action in (lambda: runtime.preflight(run), lambda: runtime.generate({}, run, 900, lambda e: None), lambda: runtime.cancellation_probe(run, lambda e: None, 900)):
                    with self.assertRaises((ValueError, RuntimeError)): action()
                for callback in (baseline, sample, launch, kube): callback.assert_not_called()
        for hooks in ({}, {"request_builder": v.payload}):
            with self.assertRaises(RuntimeError):
                runtime = v.mac.MacRuntime(**hooks)
                runtime.preflight(self.run)

    def test_observed_template_tokenizer_hidden_context_drift_refuses(self):
        show = {"template": "test", "model_info": {"tokenizer.ggml.model": "gpt2"}}
        digest = v.e.digest
        with patch.object(v.e, "digest", side_effect=lambda raw: v.e.TEMPLATE if raw == b"test" else digest(raw)):
            self.assertIsNone(v.context(self.run, show)["providerMeasuredInputTokens"])
            for key, value in (("template", "other"), ("system", "hidden"), ("messages", [{}]), ("model_info", {"tokenizer.ggml.model": "other"})):
                with self.assertRaises(ValueError): v.context(self.run, dict(show, **{key: value}))

    def test_fake_mode_cannot_construct_live_runtime_and_invalid_prompt_is_pre_action(self):
        with self.assertRaises(ValueError): v.make_runtime(dict(self.run, executionMode="synthetic_fake"))
        runtime = v.make_runtime(self.run); runtime.prepared = runtime.probed = True
        runtime.runner_probe_verified = True
        with patch.object(runtime, "sample") as sample, patch.object(runtime, "api") as api:
            with self.assertRaises(ValueError): runtime.generate({}, self.run, 900, lambda e: None)
            sample.assert_not_called(); api.assert_not_called()
        with patch.object(runtime, "sample"), patch.object(runtime, "api", return_value={"models": []}), patch.object(runtime, "launch") as launch, patch.object(v.mac.time, "monotonic", return_value=901):
            with self.assertRaises(RuntimeError): runtime.generate(self.artifacts["cases"][0]["exactPrompt"], self.run, 900, lambda e: None)
            launch.assert_not_called()

    def test_validator_refuses_unknown_identity_and_expired_budget_before_bridge(self):
        with patch.object(v.replay, "kotlin_validate") as bridge:
            with self.assertRaises(ValueError): run_mac_v3.validate_one(None, "../private", "{}", 0)
            with self.assertRaises(ValueError): run_mac_v3.validate_one(None, "i01", "{}", 0)
            bridge.assert_not_called()

    def test_bridge_subprocess_uses_explicit_remaining_budget(self):
        with tempfile.TemporaryDirectory(dir="/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode") as temp:
            directory = Path(temp); directory.chmod(0o700)
            with patch.object(v.replay.subprocess, "run", side_effect=subprocess.TimeoutExpired("offline-gradle", 12.5)) as run:
                with self.assertRaises(subprocess.TimeoutExpired):
                    v.replay.kotlin_validate(directory, [], timeout_seconds=12.5)
                self.assertEqual(run.call_args.kwargs["timeout"], 12.5)
                self.assertIn("--offline", run.call_args.args[0])

    def test_cli_refuses_fake_record_before_lock_or_runtime_and_hides_private_errors(self):
        import acquire_v3 as a
        import uuid
        a_authorize = a.authorize
        run = a.make_run(str(uuid.uuid4()), "synthetic_fake")
        approval = {"version": a.APPROVAL_VERSION, "runSha256": v.e.digest(v.e.compact(run)),
            "purpose": "synthetic_fake_acquisition_only", "recordedBy": "fake test",
            "recordedAt": "2026-10-18T17:00:00Z", "expiresUtc": "2026-10-18T19:00:00Z",
            "allowCancellationProbe": True, "allowCorrection": False, "approvalEvidence": "fake only"}
        with tempfile.TemporaryDirectory(dir="/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode") as temp:
            directory = Path(temp); directory.chmod(0o700)
            for name, value in (("acquisition-run.json", run), ("inference-approval.json", approval)):
                v.replay.private.private_write(directory / name, v.e.compact(value))
            with patch.object(a, "authorize", wraps=lambda run, approval, now: a_authorize(run, approval, dt.datetime(2026, 10, 18, 18, tzinfo=dt.timezone.utc))), patch.object(run_mac_v3.Path, "home") as home, patch.object(v, "make_runtime") as runtime:
                with self.assertRaises(ValueError): run_mac_v3.execute(directory)
                runtime.assert_not_called(); home.assert_not_called()
        output = io.StringIO()
        with patch("sys.argv", ["run_mac_v3.py", "--directory", "/unused"]), patch.object(run_mac_v3, "execute", side_effect=RuntimeError("private-marker")), contextlib.redirect_stdout(output):
            self.assertEqual(run_mac_v3.main(), 2)
        self.assertNotIn("private-marker", output.getvalue())


if __name__ == "__main__": unittest.main()
