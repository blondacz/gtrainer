import copy
import os
from pathlib import Path
import tempfile
import unittest
import uuid

import pilot_profile as p
import revision_pilot as revision
import run_mac_pilot as launch
import campaign_v4 as c


def candidate():
    path = Path(os.environ["INTAKE_PILOT_TEST_PROMPT"])
    text = c.private.private_read(path).decode()
    p.need(p.e.digest(text.encode()) == p.parent.PROMPT_SHA256)
    return text


class ProfileTest(unittest.TestCase):
    def test_counter_reuses_only_pinned_running_image_and_cached_model(self):
        command = launch.counter_command("a" * 64, "reviewed helper")
        self.assertEqual(["limactl", "shell", "--workdir=/", "gtrainer-models", "sudo", "-n", "env"], command[:7])
        self.assertEqual(p.COUNTER["modelPath"], command[-1])
        self.assertIn("libllama.so.0.4.1", command[-3])
        for container in ("../escape", "a" * 63, "a" * 64 + "; command"):
            with self.assertRaises(ValueError): launch.counter_command(container, "reviewed helper")

    def test_exact_known_packet_and_frozen_policy(self):
        _, inputs, rules, _ = p.verify()
        self.assertEqual(["u02"], [row["caseId"] for row in inputs["cases"]])
        self.assertEqual(["u02"], [row["caseId"] for row in rules["cases"]])
        text = candidate()
        self.assertEqual(["u02", "probe"], list(revision.expected_requests(text)))
        self.assertEqual(p.parent.PROMPT_SHA256, p.e.digest(text.encode()))

    def test_counts_reject_wrong_identity_pin_missing_probe_and_overflow(self):
        text = candidate()
        for change in (lambda d: d["cases"].pop(), lambda d: d["cases"][0].update(caseId="u01"),
                       lambda d: d["cases"][0].update(tokens=2305),
                       lambda d: d["tokenizer"].update(librarySha256="0" * 64),
                       lambda d: d.update(generationCalled=True)):
            evidence = copy.deepcopy(launch.fake_counts(text)); change(evidence)
            with self.assertRaises(ValueError): revision.check_counts(text, evidence)

    def test_fake_evidence_cannot_admit_live_runtime(self):
        text = candidate(); evidence = launch.fake_counts(text)
        prepared = revision.definition(str(uuid.uuid4()), text, evidence)
        with self.assertRaises(ValueError):
            c.v.make_run(str(uuid.uuid4()), "live", prepared, evidence, "1" * 64,
                         grant_expires_utc="2026-10-22T15:00:00Z")

    def test_single_root_grant_and_one_hour_budget(self):
        with tempfile.TemporaryDirectory(prefix="pilot-unit-", dir=str(launch.supervisor.BASE)) as temporary:
            root = Path(temporary); os.chmod(root, 0o700)
            campaign = root / "campaign"; campaign.mkdir(mode=0o700)
            authority = {"version": p.PROFILE, "executionMode": "synthetic_fake", "recordedBy": "unit_test",
                "userInstruction": "Synthetic fake test only; no runtime or model operations.",
                "applyInstruction": "apply guarded-live-intake-pilot", "campaignDirectory": str(campaign),
                "limits": p.LIMITS, "toolingSha256": c.tooling(p.PROFILE), "counter": None}
            grant = root / "authority.json"; c.durable(grant, authority)
            launch.create(campaign, grant, Path(os.environ["INTAKE_PILOT_TEST_PROMPT"]))
            with c.Campaign(campaign) as checked:
                self.assertEqual(3600, checked.scope["endMonotonic"] - checked.scope["startMonotonic"])
                self.assertEqual(3360, checked.scope["workCutoffMonotonic"] - checked.scope["startMonotonic"])
                self.assertEqual(1, checked.scope["limits"]["maxScoredRequests"])
            with self.assertRaises(ValueError):
                launch.create(campaign, grant, Path(os.environ["INTAKE_PILOT_TEST_PROMPT"]))

    def test_old_authority_and_expanded_limits_refused(self):
        with tempfile.TemporaryDirectory(prefix="pilot-unit-", dir=str(launch.supervisor.BASE)) as temporary:
            root = Path(temporary); os.chmod(root, 0o700)
            for index, limits in enumerate((dict(p.LIMITS, scoredLaunches=8), dict(p.LIMITS, campaignSeconds=14400))):
                destination = root / str(index); destination.mkdir(mode=0o700)
                grant = root / (str(index) + ".json")
                c.durable(grant, {"version": p.PROFILE, "executionMode": "synthetic_fake", "recordedBy": "unit_test",
                    "userInstruction": "Fake only", "applyInstruction": "apply guarded-live-intake-pilot",
                    "campaignDirectory": str(destination), "limits": limits, "toolingSha256": c.tooling(p.PROFILE), "counter": None})
                with self.assertRaises(ValueError): launch.create(destination, grant, Path(os.environ["INTAKE_PILOT_TEST_PROMPT"]))
                self.assertFalse((destination / "campaign.json").exists())


if __name__ == "__main__":
    unittest.main()
