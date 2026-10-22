import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import campaign_v4 as c
import native_clock_v4 as clock
from test_campaign_v4 import FakeClock

TEMP = "/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode"


def retain(name, value):
    configured = os.environ.get("INTAKE_V4_NATIVE_CLOCK_EVIDENCE")
    if configured:
        directory = c.private.private_directory(Path(configured))
        c.durable(directory / name, value)


class ClockContractTest(unittest.TestCase):
    def test_missing_invalid_or_unsupported_native_clock_never_falls_back(self):
        for value in (-1, True, 1.5, None, 2**63):
            with patch.object(clock.time, "clock_gettime_ns", return_value=value), self.assertRaises(ValueError):
                clock.monotonic()
        with patch.object(clock.time, "clock_gettime_ns", side_effect=OSError("unavailable")), \
             patch.object(clock.time, "monotonic") as fallback:
            with self.assertRaises(ValueError): clock.monotonic()
            fallback.assert_not_called()
        with patch.object(clock.sys, "platform", "unsupported"), patch.object(clock.time, "clock_gettime_ns") as read:
            with self.assertRaises(ValueError): clock.monotonic()
            read.assert_not_called()

    def test_clock_identity_is_explicit_and_legacy_identity_is_not_migrated(self):
        identity = FakeClock().identity()
        self.assertTrue(clock.valid_identity(identity))
        for invalid in ({"clock": "time.monotonic", "bootSessionUuid": "same-boot"},
                        dict(identity, version="old"), dict(identity, clock="time.monotonic"),
                        dict(identity, extra=True)):
            self.assertFalse(clock.valid_identity(invalid))
        with self.assertRaises(ValueError):
            c.definition("11111111-1111-4111-8111-111111111111", "synthetic_fake",
                         {"clock": "time.monotonic", "bootSessionUuid": "same-boot"}, 10.0, "2026-10-20T00:00:00Z")

    def test_runtime_deadline_samples_local_first_and_cannot_extend_or_reuse_expiry(self):
        with tempfile.TemporaryDirectory(prefix="intake-v4-clock-boundary-", dir=TEMP) as temporary:
            directory = Path(temporary); directory.chmod(0o700); fake = FakeClock()
            with c.Campaign.create(directory, "synthetic_fake", "clock boundary test", "No live calls.", **fake.arguments()) as campaign:
                order = []
                def local(): order.append("local"); return 20.0
                def shared(): order.append("shared"); return 1100.0
                campaign.monotonic = shared
                with patch.object(c.time, "monotonic", side_effect=local):
                    self.assertEqual(campaign._runtime_deadline(1150.0), 70.0)
                    self.assertEqual(order, ["local", "shared"])
                    with self.assertRaises(ValueError): campaign._runtime_deadline(1100.0)
                    for invalid in (True, None, float("inf")):
                        with self.assertRaises(ValueError): campaign._runtime_deadline(invalid)


@unittest.skipUnless(sys.platform == "darwin", "Native Mac admission test")
class NativeClockTest(unittest.TestCase):
    def test_real_native_readings_bracket_fresh_processes_without_injected_clock(self):
        observations = []
        for _ in range(3):
            before = clock.monotonic()
            result = subprocess.run([sys.executable, "-c",
                "import json,time,native_clock_v4 as c;print(json.dumps({'native':c.monotonic(),'identity':c.identity(),'local':time.monotonic()}))"],
                env=dict(os.environ, PYTHONPATH=os.pathsep.join(sys.path)), capture_output=True, timeout=15, check=True)
            child = json.loads(result.stdout); after = clock.monotonic()
            self.assertLessEqual(before, child["native"]); self.assertLessEqual(child["native"], after)
            self.assertEqual(child["identity"], clock.identity())
            observations.append({"before": before, "child": child, "after": after})
        self.assertLess(observations[0]["after"], observations[-1]["after"])
        retain("native-parent-child-clock-proof.json", {"pythonVersion": sys.version,
               "clockInjected": False, "observations": observations, "inferenceCalls": 0})

    def test_actual_inherited_command_timeout_uses_converted_local_deadline(self):
        with tempfile.TemporaryDirectory(prefix="intake-v4-native-boundary-", dir=TEMP) as temporary:
            directory = Path(temporary); directory.chmod(0o700)
            with c.Campaign.create(directory, "synthetic_fake", "native deadline test", "No live operations.") as campaign:
                runtime = c.v.mac.MacRuntime()
                shared_deadline = clock.monotonic() + 2.0
                runtime.deadline = campaign._runtime_deadline(shared_deadline)
                self.assertGreater(runtime.deadline, time.monotonic())
                self.assertLessEqual(runtime.deadline, time.monotonic() + 2.0)
                with patch.object(c.v.mac.subprocess, "run", return_value=Mock(returncode=0, stdout=b"bounded")) as command:
                    self.assertEqual(runtime.command(["never-executed"], timeout=1_000_000), b"bounded")
                self.assertGreater(command.call_args.kwargs["timeout"], 0)
                self.assertLessEqual(command.call_args.kwargs["timeout"], 2.0)

    def test_real_separate_process_campaign_failure_then_success_keeps_one_budget(self):
        code = '''
import copy,sys,time,uuid
from pathlib import Path
from test_campaign_v4 import c,FakeRuntime,fixture_counts,assembled,stages
directory=Path(sys.argv[1]);phase=int(sys.argv[2])
campaign=c.Campaign.create(directory,"synthetic_fake","native child test","Synthetic fixtures; real native clock.") if phase==1 else c.Campaign(directory)
with campaign:
 instructions=c.v.revision.INSTRUCTIONS_R1
 previous=None
 if phase==2:
  old=c.private.read_json(directory/"batch-01/revision.json")
  previous=c.e.digest(c.e.compact(old));instructions+="\\nRetain every supported source fact."
 evidence=fixture_counts(instructions)
 prepared=c.v.revision.definition(str(uuid.uuid4()),instructions,evidence,previous)
 _,inputs,_,fixtures=c.e.verify();fixtures=copy.deepcopy(fixtures)
 if phase==1:fixtures["i01"]["suggestions"][0]["context"]["content"]="no running"
 class Spy(FakeRuntime):
  def check_local(self,deadline,cap):
   now=time.monotonic();assert now<deadline<=now+cap+0.01
  def preflight(self,run):
   self.check_local(self.deadline,14160);return super().preflight(run)
  def cancellation_probe(self,run,emit,deadline):
   self.check_local(deadline,900);return super().cancellation_probe(run,emit,deadline)
  def generate(self,prompt,run,deadline,emit):
   self.check_local(deadline,900);return super().generate(prompt,run,deadline,emit)
 def factory(run,guard,request_guard):return Spy(run,guard,request_guard,prepared,None,fixtures)
 def validate(case_id,raw,deadline):
  assert campaign._time()<deadline<=campaign.scope["workCutoffMonotonic"]
  return {"caseId":case_id,"rawSha256":c.e.digest(raw.encode()),"stages":stages(assembled(case_id,c.e.decode(raw.encode()),inputs))}
 report=campaign.run_batch(prepared,evidence,runtime_factory=factory,validator=validate)
 assert report["classification"]==("quality_failure" if phase==1 else "passed")
'''
        with tempfile.TemporaryDirectory(prefix="intake-v4-native-campaign-", dir=TEMP) as temporary:
            directory = Path(temporary); directory.chmod(0o700)
            environment = dict(os.environ, PYTHONPATH=os.pathsep.join(sys.path))
            first = subprocess.run([sys.executable, "-c", code, temporary, "1"], env=environment,
                                   capture_output=True, timeout=300)
            self.assertEqual(first.returncode, 0, "Native process A failed; no inference was performed")
            original = c.private.private_read(directory / "campaign.json")
            scope = c.e.decode(original); before_revision_work = clock.monotonic()
            second = subprocess.run([sys.executable, "-c", code, temporary, "2"], env=environment,
                                    capture_output=True, timeout=300)
            self.assertEqual(second.returncode, 0, "Native process B failed; no inference was performed")
            self.assertEqual(c.private.private_read(directory / "campaign.json"), original)
            reports = [c.private.read_json(directory / f"batch-{index:02}/report.json") for index in (1, 2)]
            self.assertLessEqual(scope["startMonotonic"], reports[0]["finishedMonotonic"])
            self.assertLessEqual(reports[0]["finishedMonotonic"], before_revision_work)
            self.assertGreater(reports[1]["finishedMonotonic"], before_revision_work)
            self.assertEqual(c.private.read_json(directory / "terminal.json")["status"], "passed")
            self.assertEqual(len(list(directory.glob("claim-*.json"))), 2)
            self.assertEqual(len(list(directory.glob("batch-*/*.launched.json"))), 18)
            self.assertGreater(clock.monotonic() - scope["startMonotonic"], 0)
            self.assertLess(scope["endMonotonic"] - clock.monotonic(), 14400)
            retain("native-separate-process-campaign-proof.json", {"clockInjected": False,
                "scopeSha256": c.e.digest(original), "scopeClock": scope["clockIdentity"],
                "originalStart": scope["startMonotonic"], "unchangedEnd": scope["endMonotonic"],
                "batchFinishes": [report["finishedMonotonic"] for report in reports],
                "classifications": [report["classification"] for report in reports],
                "batchClaims": 2, "probeLaunches": 2, "scoredLaunches": 16,
                "modelOutputs": "synthetic_fake", "nativeClockIsReal": True, "inferenceCalls": 0})


if __name__ == "__main__": unittest.main()
