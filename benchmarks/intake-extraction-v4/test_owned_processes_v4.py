import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace

import owned_processes_v4 as p


class OwnedProcessTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp(prefix="v4-compiler-owner-", dir='/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode'))
        self.addCleanup(self.remove_only_after_cleanup)
        p.life.write(self.directory/'job.json',{'syntheticOnly':True})

    def remove_only_after_cleanup(self):
        if p.cleanup(self.directory,time.monotonic()+10,time.time()+10):shutil.rmtree(self.directory)

    def wait(self, path):
        end=time.monotonic()+10
        while not path.exists():
            self.assertLess(time.monotonic(),end);time.sleep(0.02)

    def test_dead_worker_leaves_bound_compiler_group_for_independent_cleanup(self):
        # Model/Gradle are not invoked: a separate-session guardian owns a real
        # sleeping child, just as it owns a compiler outside the worker group.
        program="""import os,sys,time;from pathlib import Path;import owned_processes_v4 as p
d=Path(sys.argv[1]);proc,req=p.launch([sys.executable,'-c','import time;time.sleep(120)'],dict(os.environ),d)
p.life.write(d/'worker-published.json',{'request':str(req),'guardianPid':proc.pid});time.sleep(120)
"""
        worker=subprocess.Popen([sys.executable,'-c',program,str(self.directory)],cwd=p.life.e.HERE,start_new_session=True)
        try:
            self.wait(self.directory/'worker-published.json')
            row=p.life.private.read_json(self.directory/'worker-published.json');request=Path(row['request'])
            self.wait(p.sibling(request,'.bound.json'))
            os.killpg(worker.pid,signal.SIGKILL);worker.wait(timeout=3)
            p.life.write(self.directory/'admission-closed.json',{'closed':True})
            self.assertTrue(p.cleanup(self.directory,time.monotonic()+10,time.time()+10))
            self.assertTrue(p.quiescent(row['guardianPid']))
        finally:
            if worker.poll() is None:os.killpg(worker.pid,signal.SIGKILL);worker.wait(timeout=3)
            p.cleanup(self.directory,time.monotonic()+10,time.time()+10)

    def test_completed_child_keeps_guardian_identity_until_owned_parent_cleanup(self):
        proc,request=p.launch([sys.executable,'-c','print("synthetic compiler finished")'],dict(os.environ),self.directory)
        try:
            output=proc.stdout.read()
            self.assertIn(b'synthetic compiler finished',output)
            self.assertIsNone(proc.poll())
            self.assertEqual(p.life.private.read_json(p.sibling(request,'.result.json'))['exitCode'],0)
        finally:
            self.assertTrue(p.finish(proc,request,time.monotonic()+10));proc.stdout.close()

    def test_closed_admission_prevents_new_group(self):
        p.life.write(self.directory/'admission-closed.json',{'closed':True})
        with self.assertRaises(ValueError):p.launch([sys.executable,'-c','pass'],dict(os.environ),self.directory)

    def test_eperm_requires_numeric_proof_of_no_live_group_members(self):
        with patch.object(p.os,'killpg',side_effect=PermissionError(1,'Operation not permitted')):
            with patch.object(p.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=b'101 42 Z\n102 42 Z+\n103 99 S\n')):
                self.assertFalse(p.absent(42));self.assertTrue(p.quiescent(42))
            with patch.object(p.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=b'101 42 Z\n102 42 S\n')):
                self.assertFalse(p.quiescent(42))
            with patch.object(p.subprocess,'run',return_value=SimpleNamespace(returncode=1,stdout=b'')):
                with self.assertRaises(ValueError):p.quiescent(42)

    def test_parent_cleanup_stops_registered_launchd_test_child(self):
        import supervisor_v4 as supervisor
        child=Path(tempfile.mkdtemp(prefix='v4w-',dir=supervisor.BASE))
        spec={'mode':'synthetic_fake','scenario':'locked','clockIdentity':supervisor.c.clock_identity(),
              'toolingSha256':supervisor.c.tooling(),'workCutoff':p.life.clock.monotonic()+20,
              'expiresUnix':time.time()+20,'nonce':'synthetic-owned-child'}
        with patch.dict(os.environ,{p.ENV:str(self.directory)}):target=supervisor.prepare(child,spec)
        try:
            self.wait(child/'fixture-locked.json')
            p.life.write(self.directory/'admission-closed.json',{'closed':True})
            self.assertTrue(p.cleanup(self.directory,time.monotonic()+10,time.time()+10))
            self.assertTrue(p.life.private.read_json(child/'outcome.json')['cleanup']['verified'])
            self.assertEqual(len(list(self.directory.glob('owned-job-*.json'))),1)
        finally:
            if (child/'outcome.json').exists():subprocess.run(['/bin/launchctl','bootout',target],capture_output=True,timeout=10)


if __name__=='__main__':unittest.main()
