import datetime as dt
import copy
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import uuid

import recovery_v4 as r
from test_campaign_v4 import FakeRuntime
from test_revision_v4 import fixture_counts
from test_oracle_v4 import assembled, stages


class RecoveryBudgetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="v4-recovery-policy-", dir='/private/var/folders/bk/s3pfblw91vs39ffzns_67jfh0000gn/T/opencode')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        now = dt.datetime.now(dt.timezone.utc)
        self.scope = {"version": "intake-recovery-authority-v1", "mode": "synthetic_fake", "limits": r.c.LIMITS,
            "clockIdentity": r.c.clock_identity(), "startMonotonic": r.c.native_clock.monotonic(),
            "recordedUtc": now.isoformat().replace('+00:00','Z'), "expiresUnix": (now+dt.timedelta(hours=4)).timestamp(),
            "revision":str(self.root/'prepared')}
        r.life.write(self.root/'authority.json',self.scope)

    def mark(self, index, **values):
        r.life.write(self.root/f'attempt-{index:02}.json',dict(cleanupVerified=True,retryEligible=True,repairEligible=True,**values))

    def test_three_full_reservations_never_refund_and_share_original_clock(self):
        raw = r.c.private.private_read(self.root/'authority.json')
        for index in (1,2,3):
            binding = r.reserve(self.root,Path('/unchanged-revision'))
            grant = r.authority(binding,'synthetic_fake')
            self.assertEqual(grant['startMonotonic'],self.scope['startMonotonic'])
            directory=self.root/f'run-{index:02}'/'campaign'
            directory.mkdir(mode=0o700,parents=True)
            with r.c.Campaign.create(directory,'synthetic_fake','offline fixture','synthetic recovery only',recovery=binding) as campaign:
                self.assertEqual(campaign.scope['startMonotonic'],self.scope['startMonotonic'])
                self.assertEqual(campaign.scope['endMonotonic'],self.scope['startMonotonic']+14400)
            self.mark(index)
        self.assertEqual(r.c.private.private_read(self.root/'authority.json'),raw)
        with self.assertRaises(ValueError):r.reserve(self.root,Path('/unchanged-revision'))
        self.assertEqual(len(list(self.root.glob('reservation-*.json')))*8,24)

    def test_uncertain_previous_cleanup_blocks_next_reservation(self):
        r.reserve(self.root,Path('/revision'))
        r.life.write(self.root/'attempt-01.json',{'cleanupVerified':False,'retryEligible':True})
        with self.assertRaises(ValueError):r.reserve(self.root,Path('/revision'))

    def test_quality_failure_cannot_be_relabelled_as_infrastructure(self):
        r.reserve(self.root,Path('/revision'))
        r.life.write(self.root/'attempt-01.json',{'cleanupVerified':True,'retryEligible':False,'repairEligible':False})
        with self.assertRaises(ValueError):r.reserve(self.root,Path('/revision'))

    def test_spent_reservation_cannot_be_refunded_by_coordinator_restart(self):
        binding = r.reserve(self.root,Path('/revision'))
        r.life.write(self.root/'coordinator-start-01.json',{'pid':0})
        self.assertEqual(r.authority(binding,'synthetic_fake')['startMonotonic'],self.scope['startMonotonic'])
        with self.assertRaises(FileNotFoundError):r.reserve(self.root,Path('/revision'))
        self.assertFalse((self.root/'reservation-02.json').exists())

    def test_explicit_cancel_and_source_binding_corruption_refuse_admission(self):
        binding = r.reserve(self.root,Path('/revision'))
        broken=dict(binding,reservationSha256='0'*64)
        with self.assertRaises(ValueError):r.authority(broken,'synthetic_fake')
        r.life.write(self.root/'cancel.json',{'authoritySha256':r.c.e.digest(r.c.private.private_read(self.root/'authority.json'))})
        with self.assertRaises(ValueError):r.authority(binding,'synthetic_fake')

    def test_coordinator_restart_attaches_spent_unknown_spawn_without_double_launch(self):
        r.reserve(self.root,Path('/revision'))
        with patch.object(r.subprocess,'run') as spawn:r.coordinate(self.root)
        spawn.assert_not_called()
        self.assertEqual(r.c.private.read_json(self.root/'outcome.json')['reason'],'uncertain_reserved_spawn')
        self.assertEqual(len(list(self.root.glob('reservation-*.json'))),1)

    def test_completed_assessment_survives_coordinator_loss_before_terminal_write(self):
        r.reserve(self.root,Path('/revision'))
        r.life.write(self.root/'attempt-01.json',{'classification':'passed','cleanupVerified':True,'retryEligible':False,'repairEligible':False})
        with patch.object(r.subprocess,'run') as spawn:r.coordinate(self.root)
        spawn.assert_not_called()
        self.assertEqual(r.c.private.read_json(self.root/'outcome.json')['status'],'passed')

    def test_native_clock_fault_spends_only_three_coordinator_restarts(self):
        with patch.object(r.c.native_clock,'monotonic',side_effect=ValueError('offline clock fault')):
            for _ in range(3):
                with self.assertRaises(ValueError):r.coordinate(self.root)
                self.assertTrue(r.restart_allowed(self.root))
            r.coordinate(self.root)
        self.assertEqual(len(list(self.root.glob('coordinator-start-*.json'))),3)
        self.assertFalse(r.restart_allowed(self.root))

    def test_failed_restart_publication_disables_keepalive_retry(self):
        with patch.object(r.life,'write',side_effect=OSError('disk unavailable')):
            with self.assertRaises(OSError):r.coordinate(self.root)
        self.assertFalse(r.restart_allowed(self.root))

    def test_source_fix_needs_bound_repair_after_verified_cleanup(self):
        with patch.object(r.c,'tooling',return_value={'source':'before'}):r.reserve(self.root,Path('/original'))
        self.mark(1)
        with patch.object(r.c,'tooling',return_value={'source':'after'}):
            with self.assertRaises(FileNotFoundError):r.reserve(self.root,Path('/fixed'))
            previous=r.c.private.read_json(self.root/'attempt-01.json')
            r.life.write(self.root/'repair-01.json',{'sourceSha256':{'source':'after'},'revision':'/fixed',
                'previousAttemptSha256':r.c.e.digest(r.c.e.compact(previous))})
            binding=r.reserve(self.root,Path('/fixed'))
            self.assertEqual(binding['slot'],2)
        self.assertEqual(r.c.private.read_json(self.root/'authority.json')['startMonotonic'],self.scope['startMonotonic'])

    def test_same_reservation_rejects_second_campaign_consumer(self):
        binding=r.reserve(self.root,Path('/revision'))
        directory=self.root/'run-01/campaign';directory.mkdir(mode=0o700,parents=True)
        with r.c.Campaign.create(directory,'synthetic_fake','test','Offline consumer fixture.',recovery=binding):pass
        other=self.root/'second-campaign';other.mkdir(mode=0o700)
        with self.assertRaises(ValueError):r.c.Campaign.create(other,'synthetic_fake','test','Offline duplicate fixture.',recovery=binding)
        self.assertFalse((other/'campaign.json').exists())

    def test_stable_bootstrap_caps_import_syntax_failure_before_project_entrypoint(self):
        entry=self.root/'entry.py';r.c.private.private_write(entry,b'import broken_fixture\n')
        r.c.private.private_write(self.root/'broken_fixture.py',b'def invalid syntax\n')
        codes=[]
        for _ in range(4):
            result=r.subprocess.run([r.sys.executable,'-c',r.BOOTSTRAP,str(self.root),str(entry)],capture_output=True,timeout=10)
            codes.append(result.returncode)
        self.assertEqual(codes,[1,1,0,0])
        self.assertEqual(len(list(self.root.glob('coordinator-start-*.json'))),3)

    def test_stable_bootstrap_missing_root_exits_without_keepalive_loop(self):
        result=r.subprocess.run([r.sys.executable,'-c',r.BOOTSTRAP,str(self.root/'missing'),str(self.root/'entry.py')],capture_output=True,timeout=10)
        self.assertEqual(result.returncode,0)

    def test_native_synthetic_timeout_cleanup_then_new_full_attempt_passes_same_budget(self):
        instructions=r.c.v.revision.INSTRUCTIONS_R1
        evidence=fixture_counts(instructions)
        prepared=r.c.v.revision.definition(str(uuid.uuid4()),instructions,evidence)
        _,inputs,_,fixtures=r.c.e.verify()
        quality_crash=getattr(self,'quality_crash',False)
        if quality_crash:
            fixtures=copy.deepcopy(fixtures);fixtures['i01']['suggestions'][0]['context']['content']='no running'
        observed=[]
        real_run=r.subprocess.run
        def command(argv, **kwargs):
            if '--once' not in argv:
                if argv[0]=='/bin/launchctl':return SimpleNamespace(returncode=0,stdout=b'',stderr=b'')
                return real_run(argv,**kwargs)
            index=int(argv[-1]);row=r.c.private.read_json(self.root/f'reservation-{index:02}.json')
            binding={'root':str(self.root),'slot':index,'authoritySha256':r.c.e.digest(r.c.private.private_read(self.root/'authority.json')),
                     'reservationSha256':r.c.e.digest(r.c.e.compact(row))}
            directory=self.root/f'run-{index:02}';directory.mkdir(mode=0o700)
            campaign_dir=directory/'campaign';campaign_dir.mkdir(mode=0o700)
            with r.c.Campaign.create(campaign_dir,'synthetic_fake','offline test','Synthetic recovery fixture only.',recovery=binding) as campaign:
                def factory(run,guard,request_guard):
                    runtime=FakeRuntime(run,guard,request_guard,prepared,SimpleNamespace(value=0),fixtures,'timeout' if index==1 and not quality_crash else None)
                    observed.append(runtime);return runtime
                def validate(case_id,raw,deadline):return {'caseId':case_id,'rawSha256':r.c.e.digest(raw.encode()),'stages':stages(assembled(case_id,r.c.e.decode(raw.encode()),inputs))}
                report=campaign.run_batch(prepared,evidence,runtime_factory=factory,validator=validate)
            job=directory/'fake-supervisor';job.mkdir(mode=0o700)
            r.life.write(campaign_dir/'supervisor-01.json',{'directory':str(job)})
            if quality_crash:(campaign_dir/'receipt-01.json').unlink()
            r.life.write(job/'outcome.json',{'reason':'supervisor_worker_lost' if quality_crash else 'normal','cleanup':{'verified':True,'syntheticOnly':True}})
            r.life.write(job/'launchd.json',{'domain':'synthetic','label':'no-real-job'})
            return SimpleNamespace(returncode=0,stdout=b'',stderr=b'')
        with patch.object(r.subprocess,'run',side_effect=command):r.coordinate(self.root)
        if quality_crash:
            self.assertEqual(r.c.private.read_json(self.root/'outcome.json')['status'],'failed')
            self.assertFalse(r.c.private.read_json(self.root/'attempt-01.json')['retryEligible'])
            self.assertTrue(r.c.private.read_json(self.root/'attempt-01.json')['knownCompleteQualityFailure'])
            self.assertEqual(len(list(self.root.glob('reservation-*.json'))),1)
            return
        self.assertEqual(r.c.private.read_json(self.root/'outcome.json')['status'],'passed')
        self.assertEqual(r.c.private.read_json(self.root/'attempt-01.json')['classification'],'incomplete')
        self.assertEqual([runtime.cancelled for runtime in observed],[1,1])
        self.assertEqual(len(list(self.root.glob('reservation-*.json'))),2)
        self.assertEqual(r.c.private.read_json(self.root/'run-01/campaign/campaign.json')['endMonotonic'],
                         r.c.private.read_json(self.root/'run-02/campaign/campaign.json')['endMonotonic'])

    def test_report_quality_failure_vetoes_retry_after_receipt_publication_failure(self):
        self.quality_crash=True
        self.test_native_synthetic_timeout_cleanup_then_new_full_attempt_passes_same_budget()


if __name__=='__main__':unittest.main()
