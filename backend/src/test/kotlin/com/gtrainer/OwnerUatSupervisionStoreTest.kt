package com.gtrainer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

/** Metadata/clock tests only. The current test process is a fake identity, never a worker to signal. */
class OwnerUatSupervisionStoreTest {
    private class Case : AutoCloseable {
        val dir = Files.createTempDirectory("supervision-metadata-")
        val f = DescriptiveUiFixture(dir)
        val origin = OwnerUatClockSample(UUID.randomUUID().toString(), "synthetic-continuous-v1", 1, 1, 100)
        var now = origin
        var sampler: () -> OwnerUatClockSample = { now }
        val clock = OwnerUatContinuousClock { sampler() }
        val preview = runBlocking { f.prepare("activities") }
        val observation = runBlocking { requireNotNull(f.control.observe(f.resource)) }
        val claim: PersonalReviewClaim
        init {
            f.store.configureOwnerUatClock(clock)
            check(f.jobs.rearm(f.policyDigest()))
            val capture = f.store.captureScopedExecution(preview.binding.executionId)
            claim = requireNotNull(f.store.reservePersonalReview(capture, capture.current(), f.store.personalOwnerEpoch(),
                observation, f.resource, f.clock, 1_200_000, ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT,
                requireNotNull(f.gate.supervisionRegistration(origin))))
            check(f.store.beginPersonalStart(claim, observation, f.clock))
            tx { connection ->
                val journal = OwnerUatSupervisionStore(connection)
                check(journal.acknowledge(claim, UUID.randomUUID().toString(), now))
                val job = requireNotNull(journal.get(claim.claimId))
                check(journal.save(job, job.copy(launchConsumed = true, worker = OwnerUatProcessIdentity.capture())))
            }
            val fresh = f.store.captureScopedExecution(claim.executionId)
            check(f.store.claimPersonalReview(claim, fresh, fresh.current(), f.at) != null)
            check(f.store.authorizePersonalAttempt(claim, 1, observation, f.clock))
        }
        fun <T> tx(block: (java.sql.Connection) -> T): T = OwnerUatSupervisorDatabase(dir.resolve("history.db")).use { it.transaction(block) }
        fun job() = tx { requireNotNull(OwnerUatSupervisionStore(it).get(claim.claimId)) }
        fun phase(phase: String) = tx { OwnerUatSupervisionStore(it).transition(claim, phase, now) }
        fun publish() {
            val capture = f.store.captureScopedExecution(claim.executionId)
            val packet = ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptivePacketV1>(preview.binding.exactPrompt.userPacketJson)
            val output = ScopedDescriptiveContractV1.encodeDraft(ScopedDescriptiveDraftV1(ScopedDescriptiveContractV1.PROFILE,
                "activities", "review", listOf(ScopedDescriptiveItem("observation", "Synthetic record is available.",
                    listOf(packet.references.first { it.kind == "session" }), "high")), emptyList()))
            f.store.publishPersonalReview(claim, capture, capture.current(), ValidatedConnectedReviewOutput(output), f.clock)
        }
        override fun close() {
            runBlocking { f.close() }
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `startup generation validation and total deadlines are immutable and inclusive at refusal`() {
        Case().use { c ->
            c.now = c.origin.afterMillis(120_000)
            assertFalse(c.phase("GENERATING"))
            c.now = c.origin.afterMillis(119_999)
            assertTrue(c.phase("GENERATING")); val generated = c.job()
            assertEquals(c.now.afterMillis(900_000).ticks, generated.phaseDeadline)
            c.now = c.origin.afterMillis(200_000)
            assertTrue(c.phase("GENERATING")); assertEquals(generated.phaseDeadline, c.job().phaseDeadline)
            c.now = c.origin.copy(ticks = generated.phaseDeadline - 1)
            assertTrue(c.phase("VALIDATING")); val validation = c.job()
            assertEquals(c.now.afterMillis(30_000).ticks, validation.phaseDeadline)
            assertTrue(c.phase("VALIDATING")); assertEquals(validation.phaseDeadline, c.job().phaseDeadline)
            c.now = c.origin.copy(ticks = validation.phaseDeadline)
            assertFailsWith<PersonalRuntimeConflict> { c.publish() }
            assertEquals(c.origin.afterMillis(1_140_000).ticks, c.job().workDeadline)
            assertEquals(c.origin.afterMillis(1_200_000).ticks, c.job().totalDeadline)
            assertEquals(60_000,c.job().cleanupBudgetMillis(c.origin.afterMillis(1_140_000)))
            assertEquals(10_000,c.job().cleanupBudgetMillis(c.origin.afterMillis(1_190_000)))
            assertEquals(60_000,c.job().cleanupBudgetMillis(c.origin.afterMillis(1_200_001)))
        }
    }

    @Test fun `backwards ticks within a phase and changed boot or timebase never regain permission`() {
        Case().use { c ->
            c.now = c.origin.afterMillis(1000); assertTrue(c.phase("GENERATING"))
            c.now = c.origin.afterMillis(3000)
            c.tx { connection -> val journal=OwnerUatSupervisionStore(connection)
                check(journal.observed(requireNotNull(journal.get(c.claim.claimId)), c.now)) }
            c.now = c.origin.afterMillis(2000); assertFalse(c.phase("VALIDATING"))
            c.now = c.origin.afterMillis(4000).copy(bootId = UUID.randomUUID().toString()); assertFalse(c.phase("VALIDATING"))
            c.now = c.origin.afterMillis(4000).copy(numerator = 2); assertFalse(c.phase("VALIDATING"))
            assertEquals(c.origin.afterMillis(3000).ticks, c.job().lastObservedTicks)
        }
    }

    @Test fun `expiry at final publication check rolls back result permission and journal writes atomically`() {
        Case().use { c ->
            assertTrue(c.phase("GENERATING")); assertTrue(c.phase("VALIDATING"))
            val before = c.job(); var reads = 0
            c.sampler = { if (++reads < 3) c.now else c.origin.copy(ticks = before.phaseDeadline) }
            assertFailsWith<PersonalRuntimeConflict> { c.publish() }
            c.sampler = { c.now }
            assertEquals(before, c.job())
            val stored = c.f.store.captureScopedExecution(c.claim.executionId).stored
            assertNull(stored.output); assertEquals("RUNNING", stored.state)
            assertTrue(requireNotNull(c.f.store.personalRuntimeLease(c.claim.claimId)).publicationAllowed)
        }
    }

    @Test fun `cancellation and lost supervision record cannot publish or resurrect a launch`() {
        Case().use { c ->
            assertTrue(c.phase("GENERATING")); assertTrue(c.phase("VALIDATING"))
            c.f.store.revokePersonalReview(c.claim)
            assertFailsWith<PersonalRuntimeConflict> { c.publish() }
            assertTrue(c.job().tombstoned); assertFalse(c.phase("GENERATING"))
            c.tx { connection ->
                val journal = OwnerUatSupervisionStore(connection); val job = requireNotNull(journal.get(c.claim.claimId))
                assertFailsWith<IllegalArgumentException> { journal.save(job, job.copy(tombstoned = false, phase = "STARTUP",
                    phaseDeadline = c.origin.afterMillis(120_000).ticks, launchConsumed = false)) }
            }
        }
        Case().use { c ->
            assertTrue(c.phase("GENERATING")); assertTrue(c.phase("VALIDATING"))
            c.tx { it.createStatement().use { statement -> statement.executeUpdate("DELETE FROM owner_uat_job_supervision") } }
            assertTrue(c.claim.supervisionRequired)
            assertFailsWith<PersonalRuntimeConflict> { c.publish() }
            assertNull(c.f.store.captureScopedExecution(c.claim.executionId).stored.output)
        }
    }

    @Test fun `dead or mismatched supervisor identity fences publication without signalling unrelated processes`() {
        Case().use { c ->
            assertTrue(c.phase("GENERATING")); assertTrue(c.phase("VALIDATING"))
            val job = c.job()
            val wrong = job.copy(supervisorProcess = OwnerUatProcessIdentity(ProcessHandle.current().pid(), "2000-01-01T00:00:00Z"))
            c.tx { connection -> connection.prepareStatement("UPDATE owner_uat_job_supervision SET state_json=? WHERE claim_id=?").use {
                it.setString(1, ScopedEvidencePreparation.json.encodeToString(wrong)); it.setString(2, c.claim.claimId); it.executeUpdate()
            } }
            assertFailsWith<PersonalRuntimeConflict> { c.publish() }
            assertTrue(ProcessHandle.current().isAlive)
        }
    }

    @Test fun `original continuous origin includes external preflight and missing clock refuses without reservation`() = runBlocking {
        val dir = Files.createTempDirectory("supervision-preflight-")
        val f = DescriptiveUiFixture(dir)
        val origin = OwnerUatClockSample(UUID.randomUUID().toString(), "synthetic-continuous-v1", 1, 1, 0)
        var now = origin; var observed = 0; var ipc = 0; var clockAvailable=true
        val client = OwnerUatSupervisorClient(OwnerUatContinuousClock { check(clockAvailable) { "private clock marker" };now }, OwnerUatSupervisorTransport { ipc++; null })
        val control = object : PersonalRuntimeControl {
            override suspend fun observe(expected: PersonalResourceIdentity): PersonalResourceObservation {
                observed++; now = origin.afterMillis(120_001)
                return PersonalResourceObservation(expected, f.at, f.at.plusSeconds(1800), true)
            }
            override suspend fun stop(claim: PersonalReviewClaim): PersonalStopEvidence? = error("Nothing should be reserved")
        }
        try {
            val preview = f.prepare("activities"); assertTrue(f.jobs.rearm(f.policyDigest()))
            val admission = PersonalReviewRuntimeAdmission(f.store, control, clock = f.clock, ownerUat = f.gate,
                supervision = client, limits = ConnectedReviewExecutionLimits.ownerLocalUat())
            assertEquals("REFUSED", admission.execute(preview.binding.executionId, f.factory).state)
            assertEquals(1, observed); assertEquals(0, ipc); assertTrue(f.store.unresolvedPersonalReviews().isEmpty())
            clockAvailable=false
            val unavailable=admission.execute(preview.binding.executionId,f.factory)
            assertEquals("REFUSED",unavailable.state); assertEquals("runtime_admission_closed",unavailable.reason)
            assertEquals(1,observed);assertEquals(0,ipc);assertTrue(f.store.unresolvedPersonalReviews().isEmpty())
        } finally { f.close(); Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun `incompatible old schema refuses without migration reset or owner epoch rotation`() {
        val dir = Files.createTempDirectory("supervision-schema-"); val path = dir.resolve("history.db")
        runBlocking { DescriptiveUiFixture(dir).close() }
        try {
            val epoch = DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { it.executeUpdate("DROP TABLE owner_uat_job_supervision") }
                PersonalReviewRuntimeStore(connection).epoch()
            }
            assertFailsWith<HistorySchemaMismatch> { HistoryStore(path).close() }
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                assertEquals(epoch, PersonalReviewRuntimeStore(connection).epoch())
                connection.createStatement().use { statement -> statement.executeQuery("SELECT COUNT(*) FROM activities").use { assertTrue(it.next()); assertEquals(2, it.getInt(1)) } }
            }
        } finally { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
