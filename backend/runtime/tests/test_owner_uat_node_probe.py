import json
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from backend.runtime import owner_uat_node_probe as probe


def boundary():
    uid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    return {"claimId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            "launchNonce": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            "nodeBootId": "dddddddd-dddd-4ddd-8ddd-dddddddddddd", "podUid": uid,
            "containerIds": ["a" * 64],
            "cgroupPath": "/sys/fs/cgroup/kubepods.slice/kubepods-burstable.slice/"
                          "kubepods-burstable-pod" + uid.replace("-", "_") + ".slice"}


class OwnerNodeProbeTests(unittest.TestCase):
    def test_exact_reference_and_canonical_paths(self):
        value = boundary()
        self.assertEqual(value, probe.validate_boundary(value))
        for change in ({"extra": True}, {"containerIds": []}, {"containerIds": ["A" * 64]},
                       {"containerIds": ["a" * 64, "a" * 64]}, {"podUid": "wrong"},
                       {"cgroupPath": value["cgroupPath"] + "/../escape"},
                       {"cgroupPath": value["cgroupPath"] + "/child"},
                       {"cgroupPath": value["cgroupPath"].replace("burstable-pod", "burstable-podunrelated")},
                       {"cgroupPath": value["cgroupPath"].replace("/sys/", "/sys//")}):
            wrong = {**value, **change}
            with self.subTest(change=change), self.assertRaises(ValueError):
                probe.validate_boundary(wrong)

    def test_complete_proof_rechecks_group_and_boot_without_mutation(self):
        value = boundary(); calls = []
        def group(b, deadline):
            calls.append("group"); self.assertEqual(value, b); return True
        def cri(b, deadline):
            calls.append("cri"); return True
        result = probe.verify(value, 5000, clock=lambda: 1,
                              boot_reader=lambda: value["nodeBootId"], group_probe=group, cri_probe=cri)
        self.assertEqual("BOUNDARY_EMPTY", result["result"])
        self.assertEqual(["group", "cri", "group"], calls)
        self.assertEqual(value, result["boundary"])

    def test_reboot_timeout_and_incomplete_probe_never_prove_stop(self):
        value = boundary()
        cases = [dict(boot_reader=lambda: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                 dict(group_probe=lambda *_: False), dict(cri_probe=lambda *_: False),
                 dict(group_probe=lambda *_: (_ for _ in ()).throw(PermissionError("private"))),
                 dict(cri_probe=lambda *_: (_ for _ in ()).throw(ValueError("private")))]
        for changes in cases:
            options = dict(clock=lambda: 0, boot_reader=lambda: value["nodeBootId"],
                           group_probe=lambda *_: True, cri_probe=lambda *_: True)
            options.update(changes)
            with self.subTest(changes=changes):
                reply = probe.verify(value, 5000, **options)
                self.assertEqual("UNCONFIRMED", reply["result"])
                self.assertNotIn("private", json.dumps(reply))
        boots = iter([value["nodeBootId"], "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"])
        self.assertEqual("UNCONFIRMED", probe.verify(value, 5000, clock=lambda: 0,
            boot_reader=lambda: next(boots), group_probe=lambda *_: True, cri_probe=lambda *_: True)["result"])
        ticks = iter([0, 6])
        self.assertEqual("UNCONFIRMED", probe.verify(value, 5000, clock=lambda: next(ticks),
            boot_reader=lambda: value["nodeBootId"], group_probe=lambda *_: True, cri_probe=lambda *_: True)["result"])

    def test_full_owned_tree_and_alternate_uid_tree_must_be_empty(self):
        value = boundary()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            owned = root / Path(value["cgroupPath"]).relative_to(probe.CGROUP_ROOT)
            owned.mkdir(parents=True)
            child = owned / ("cri-containerd-" + "a" * 64 + ".scope")
            child.mkdir()
            for path in (owned, child):
                (path / "cgroup.procs").write_text("")
                (path / "cgroup.events").write_text("populated 0\nfrozen 0\n")
            self.assertTrue(probe.cgroups_empty(value, 10, root=root, clock=lambda: 0))
            (child / "cgroup.procs").write_text("123\n")
            with self.assertRaises(ValueError):
                probe.cgroups_empty(value, 10, root=root, clock=lambda: 0)
            (child / "cgroup.procs").write_text("")
            (owned / "cgroup.events").write_text("populated 1\n")
            with self.assertRaises(ValueError):
                probe.cgroups_empty(value, 10, root=root, clock=lambda: 0)
            (owned / "cgroup.events").write_text("populated 0\n")
            other = root / ("pod" + value["podUid"])
            other.mkdir(); (other / "cgroup.procs").write_text("456\n")
            (other / "cgroup.events").write_text("populated 1\n")
            with self.assertRaises(ValueError):
                probe.cgroups_empty(value, 10, root=root, clock=lambda: 0)

    def test_group_absence_requires_readable_complete_tree_and_cri_is_separate(self):
        value = boundary()
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(probe.cgroups_empty(value, 10, root=Path(directory), clock=lambda: 0))
            def unreadable(*args, **kwargs):
                raise PermissionError("private")
            with self.assertRaises(PermissionError):
                probe.cgroups_empty(value, 10, root=Path(directory), clock=lambda: 0, walker=unreadable)

    def test_missing_malformed_oversized_linked_and_racing_cgroup_files_refuse(self):
        value = boundary()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            owned = root / Path(value["cgroupPath"]).relative_to(probe.CGROUP_ROOT)
            owned.mkdir(parents=True)
            with self.assertRaises(ValueError):
                probe.cgroups_empty(value, 10, root=root, clock=lambda: 0)
            (owned / "cgroup.procs").write_text("")
            for events in ("populated 0\npopulated 0", "frozen 0", "populated NaN", "x" * 17000):
                (owned / "cgroup.events").write_text(events)
                with self.subTest(events=events[:30]), self.assertRaises(ValueError):
                    probe.cgroups_empty(value, 10, root=root, clock=lambda: 0)
            (owned / "cgroup.events").unlink()
            (owned / "cgroup.events").symlink_to(root / "absent")
            with self.assertRaises(OSError):
                probe.cgroups_empty(value, 10, root=root, clock=lambda: 0)

    def test_authoritative_cri_excludes_every_recorded_id_pod_uid_and_sandbox(self):
        value = boundary(); calls = []
        def runner(args, deadline):
            calls.append(args)
            return json.dumps({"containers": []} if "ps" in args else {"items": []}).encode()
        self.assertTrue(probe.cri_empty(value, 10, runner=runner))
        self.assertTrue(all(args[:3] == ["/usr/local/bin/k3s", "crictl", "--timeout"] for args in calls))
        entries = [dict(id="a" * 64, labels={"io.kubernetes.pod.uid": "unrelated"}),
                   dict(id="b" * 64, labels={"io.kubernetes.pod.uid": value["podUid"]}),
                   dict(id="b" * 64), dict(id="malformed", labels={})]
        for entry in entries:
            with self.subTest(entry=entry), self.assertRaises(ValueError):
                probe.cri_empty(value, 10, runner=lambda *_: json.dumps({"containers": [entry]}).encode())
        def sandbox(args, deadline):
            return json.dumps({"containers": []} if "ps" in args else
                              {"items": [{"metadata": {"uid": value["podUid"]}}]}).encode()
        with self.assertRaises(ValueError):
            probe.cri_empty(value, 10, runner=sandbox)

    def test_missing_malformed_or_duplicate_cri_evidence_is_not_absence(self):
        for raw in (b'{}', b'{"containers":null}', b'{"containers":[],"containers":[]}',
                    b'{"containers":NaN}', b'\xff', b'x' * 65537):
            with self.subTest(raw=raw[:40]), self.assertRaises((ValueError, UnicodeError)):
                probe.cri_empty(boundary(), 10, runner=lambda *_: raw)

    def test_total_scan_and_file_bounds_and_expiry(self):
        value = boundary()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(probe, "MAX_DIRECTORIES", 0), self.assertRaises(ValueError):
                probe.cgroups_empty(value, 10, root=root, clock=lambda: 0)
            with self.assertRaises(ValueError):
                probe.cgroups_empty(value, 10, root=root, clock=lambda: 10)
        for budget in (True, 0, 1000, 60001):
            with self.subTest(budget=budget):
                self.assertEqual("UNCONFIRMED", probe.verify(value, budget)["result"])

    def test_mount_and_controllers_must_be_authoritative(self):
        valid = b'28 20 0:25 / /sys/fs/cgroup rw - cgroup2 cgroup rw\n'
        probe.verify_cgroup_mount(lambda path, *args: valid if path.name == "mountinfo" else b'cpu memory io\n')
        for raw in (b'', valid.replace(b'cgroup2', b'tmpfs'), valid + valid, b'bad'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                probe.verify_cgroup_mount(lambda path, *args: raw if path.name == "mountinfo" else b'cpu memory')
        with self.assertRaises(ValueError):
            probe.verify_cgroup_mount(lambda path, *args: valid if path.name == "mountinfo" else b'cpu')

    def test_command_deadline_output_and_group_cleanup_use_no_real_process(self):
        from unittest.mock import MagicMock
        process = MagicMock(pid=12345)
        process.wait.return_value = 0
        process.stdout.fileno.return_value = 1
        selector = MagicMock()
        selector.select.return_value = [(1, 1)]
        manager = MagicMock()
        manager.__enter__.return_value = selector
        factory = MagicMock(return_value=process)
        with patch.object(probe.selectors, "DefaultSelector", return_value=manager), \
             patch.object(probe.os, "read", side_effect=[b'{"ok":1}', b'']), \
             patch.object(probe.os, "killpg") as kill:
            self.assertEqual(b'{"ok":1}', probe.read_command(["fixed"], 10, clock=lambda: 0, process_factory=factory))
            self.assertEqual(probe.ENV, factory.call_args.kwargs["env"])
            self.assertTrue(factory.call_args.kwargs["start_new_session"])
            kill.assert_called_once_with(12345, probe.signal.SIGKILL)
            process.stdout.close.assert_called()
        factory.reset_mock()
        with self.assertRaises(ValueError):
            probe.read_command(["fixed"], 1, clock=lambda: 0, process_factory=factory)
        factory.assert_not_called()
        selector.select.return_value = []
        with patch.object(probe.selectors, "DefaultSelector", return_value=manager), \
             patch.object(probe.os, "killpg"), self.assertRaises(ValueError):
            probe.read_command(["fixed"], 10, clock=lambda: 0, process_factory=factory)
        selector.select.return_value = [(1, 1)]
        with patch.object(probe, "MAX_COMMAND_BYTES", 2), \
             patch.object(probe.selectors, "DefaultSelector", return_value=manager), \
             patch.object(probe.os, "read", return_value=b'xxx'), \
             patch.object(probe.os, "killpg"), self.assertRaises(ValueError):
            probe.read_command(["fixed"], 10, clock=lambda: 0, process_factory=factory)

    def test_cli_is_linux_only_bounded_and_does_not_print_private_errors(self):
        import base64
        encoded = base64.b64encode(json.dumps(boundary()).encode()).decode()
        with patch.object(probe.sys, "platform", "linux"), \
             patch.object(probe.sys, "argv", ["probe", "verify", encoded, "5000"]), \
             patch.object(probe.sys, "stdout", io.StringIO()) as output, \
             patch.object(probe, "verify", return_value={"profile": probe.PROFILE,
                 "boundary": boundary(), "result": "BOUNDARY_EMPTY"}) as verify:
            probe.main()
            verify.assert_called_once_with(boundary(), 5000)
            self.assertEqual("BOUNDARY_EMPTY", json.loads(output.getvalue())["result"])
        for platform, args in (("darwin", ["probe", "verify", encoded, "5000"]),
                               ("linux", ["probe", "verify", "x" * 12000, "5000"]),
                               ("linux", ["probe", "verify", "invalid", "5000"]),
                               ("linux", ["probe", "delete", encoded, "5000"]),
                               ("linux", ["probe", "verify", encoded, "private"]),
                               ("linux", ["probe", "verify", encoded, "-1"])):
            with self.subTest(platform=platform, args=args[:2]), \
                 patch.object(probe.sys, "platform", platform), \
                 patch.object(probe.sys, "argv", args), \
                 patch.object(probe.sys, "stdout", io.StringIO()) as output, \
                 patch.object(probe, "verify") as verify:
                probe.main(); verify.assert_not_called()
                reply = json.loads(output.getvalue())
                self.assertEqual({"profile": probe.PROFILE, "boundary": None, "result": "UNCONFIRMED"}, reply)


if __name__ == "__main__":
    unittest.main()
