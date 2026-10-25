package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OwnerUatSupervisionProcessTest {
    private class Harness : AutoCloseable {
        private val base = Path.of(System.getProperty("java.io.tmpdir"))
        private val temp = base.resolve("opencode").takeIf { Files.isDirectory(it) } ?: base
        val root: Path = Files.createTempDirectory(temp, "uat-").toAbsolutePath()
        val database = root.resolve("history.db")
        val origin = OwnerUatClockSample(UUID.randomUUID().toString(), "synthetic-continuous-v1", 1, 1, 1_000_000_000)
        private val processes = mutableListOf<Pair<Process, OwnerUatProcessIdentity>>()
        var supervisor: Process? = null
        init {
            require(root.resolve("s").toString().toByteArray().size < 104)
            Files.writeString(root.resolve("synthetic-only"), "synthetic-owner-supervision-v1\n")
            Files.setPosixFilePermissions(root.resolve("synthetic-only"),PosixFilePermissions.fromString("rw-------"))
            setClock(origin)
            runBlocking { DescriptiveUiFixture(root).close() }
        }
        fun setClock(sample: OwnerUatClockSample) {
            val next = root.resolve("clock-${UUID.randomUUID()}.json")
            Files.writeString(next, ScopedEvidencePreparation.json.encodeToString(sample))
            Files.setPosixFilePermissions(next, PosixFilePermissions.fromString("rw-------"))
            Files.move(next, root.resolve("clock.json"), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        fun start(className: String, vararg args: String): Process {
            val log = root.resolve("process-${processes.size}.log")
            Files.createFile(log, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            val p = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms16m", "-Xmx128m", "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("gtrainer.test.runtimeClasspath"), className, *args)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            processes += p to OwnerUatProcessIdentity.capture(p.toHandle())
            return p
        }
        suspend fun startSupervisor() {
            val p = start("com.gtrainer.OwnerUatSupervisorMain", root.toString(), database.toString(), "--synthetic-test")
            supervisor = p
            await {
                if (!p.isAlive) error("Synthetic supervisor exited: ${Files.readString(root.resolve("process-${processes.size-1}.log"))}")
                runCatching { Json.parseToJsonElement(Files.readString(root.resolve("owner.json"))).jsonObject
                    .getValue("process").jsonObject.getValue("pid").jsonPrimitive.long == p.pid() && Files.exists(root.resolve("s")) }.getOrDefault(false)
            }
        }
        fun startApp(mode: String) = start("com.gtrainer.OwnerUatSupervisionAppFixture", root.toString(), mode)
        suspend fun running(): OwnerUatSupervisedJob {
            await { Files.isRegularFile(root.resolve("running.json")) }
            val data = Json.parseToJsonElement(Files.readString(root.resolve("running.json"))).jsonObject
            val id = data.getValue("claimId").jsonPrimitive.content
            await { job(id)?.worker != null && Files.exists(root.resolve("active-${job(id)?.launchNonce}")) }
            return requireNotNull(job(id))
        }
        fun job(id: String): OwnerUatSupervisedJob? = OwnerUatSupervisorDatabase(database).use { db ->
            db.transaction { OwnerUatSupervisionStore(it).get(id) }
        }
        fun lease(id: String): PersonalRuntimeLease? = OwnerUatSupervisorDatabase(database).use { db ->
            db.transaction { PersonalReviewRuntimeStore(it).get(id) }
        }
        fun epoch() = OwnerUatSupervisorDatabase(database).use { db -> db.transaction { PersonalReviewRuntimeStore(it).epoch() } }
        fun kill(p: Process) {
            val identity = processes.single { it.first === p }.second
            if (identity.current()) p.destroyForcibly()
            check(p.waitFor(5, TimeUnit.SECONDS))
        }
        suspend fun stopped(id: String) { await { job(id)?.phase == "STOP_CONFIRMED" && lease(id)?.phase == PersonalRuntimePhase.STOP_CONFIRMED } }
        override fun close() {
            for ((p, identity) in processes.asReversed()) if (identity.current()) { p.destroy(); if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); p.waitFor(3, TimeUnit.SECONDS) } }
            val jobs = runCatching { OwnerUatSupervisorDatabase(database).use { db -> db.transaction { connection ->
                connection.createStatement().use { statement -> statement.executeQuery("SELECT claim_id FROM owner_uat_job_supervision").use { rows ->
                    buildList { while (rows.next()) add(requireNotNull(OwnerUatSupervisionStore(connection).get(rows.getString(1)))) }
                } }
            } } }.getOrDefault(emptyList())
            val backend = OwnerUatSupervisorTestBackend(root, database)
            for (job in jobs) if (job.worker?.current() == true) backend.stop(job)
            check(processes.none { it.second.current() } && jobs.none { it.worker?.current() == true }) { "Owned test process remains; synthetic evidence retained at $root" }
            require(Files.readString(root.resolve("synthetic-only")) == "synthetic-owner-supervision-v1\n")
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    companion object {
        private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(20) }
    }

    @Test fun `separate supervisor completes a fake request once without rotating the application epoch`() = runBlocking {
        Harness().use { h ->
            val epoch = h.epoch(); h.startSupervisor(); assertEquals(epoch, h.epoch())
            val app = h.startApp("complete"); val job = h.running()
            await { Files.exists(h.root.resolve("completion.json")) }; assertTrue(app.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, app.exitValue())
            h.stopped(job.claim.claimId)
            assertEquals("PUBLISHED", Json.parseToJsonElement(Files.readString(h.root.resolve("completion.json"))).jsonObject.getValue("state").jsonPrimitive.content)
            assertEquals(1, Files.list(h.root).use { it.filter { p -> p.fileName.toString().startsWith("active-") }.count() })
            assertFalse(requireNotNull(h.job(job.claim.claimId)?.worker).current())
        }
    }

    @Test fun `app death leaves supervisor alive and stops exact worker without a retry`() = runBlocking {
        Harness().use { h ->
            h.startSupervisor(); val app = h.startApp("hold"); val before = h.running()
            h.kill(app); assertTrue(requireNotNull(h.supervisor).isAlive)
            h.stopped(before.claim.claimId); assertFalse(requireNotNull(before.worker).current())
            val after = requireNotNull(h.job(before.claim.claimId))
            assertTrue(after.tombstoned); assertEquals(before.registration.origin, after.registration.origin)
            assertEquals(before.totalDeadline, after.totalDeadline); assertEquals(1, h.lease(before.claim.claimId)?.attempts)
            if(Files.exists(h.root.resolve("completion.json"))) assertNotEquals(JsonPrimitive("PUBLISHED"),
                Json.parseToJsonElement(Files.readString(h.root.resolve("completion.json"))).jsonObject["state"])
            val restarted = h.startApp("inspect"); assertTrue(restarted.waitFor(8, TimeUnit.SECONDS)); assertEquals(0, restarted.exitValue())
            val status = Json.parseToJsonElement(Files.readString(h.root.resolve("inspection.json"))).jsonObject
            assertNotEquals(JsonPrimitive("PUBLISHED"), status["state"])
            assertEquals(1, Files.list(h.root).use { it.filter { p -> p.fileName.toString().startsWith("active-") }.count() })
        }
    }

    @Test fun `supervisor crash and restart recover the same worker and never renew the original clock`() = runBlocking {
        Harness().use { h ->
            h.startSupervisor(); val app = h.startApp("hold"); val before = h.running(); val epoch = h.epoch()
            h.kill(requireNotNull(h.supervisor)); h.startSupervisor()
            h.stopped(before.claim.claimId); val after = requireNotNull(h.job(before.claim.claimId))
            assertEquals(epoch, h.epoch()); assertEquals(before.registration, after.registration)
            assertEquals(before.workDeadline, after.workDeadline); assertEquals(before.totalDeadline, after.totalDeadline)
            assertEquals(before.worker, after.worker); assertFalse(requireNotNull(before.worker).current())
            if (app.isAlive) h.kill(app)
            if(Files.exists(h.root.resolve("completion.json"))) assertNotEquals(JsonPrimitive("PUBLISHED"),
                Json.parseToJsonElement(Files.readString(h.root.resolve("completion.json"))).jsonObject["state"])
        }
    }

    @Test fun `fake sleep across work or total expiry and boot discontinuity fence publication and stop worker`() = runBlocking {
        for (kind in listOf("work", "total", "boot", "backwards")) Harness().use { h ->
            h.startSupervisor(); val app = h.startApp("hold"); val before = h.running()
            val changed = when (kind) {
                "work" -> h.origin.copy(ticks = before.workDeadline)
                "total" -> h.origin.copy(ticks = before.totalDeadline + 1)
                "boot" -> h.origin.copy(bootId = UUID.randomUUID().toString())
                else -> h.origin.copy(ticks = h.origin.ticks - 1)
            }
            h.setClock(changed); h.stopped(before.claim.claimId)
            assertFalse(requireNotNull(before.worker).current()); assertTrue(requireNotNull(h.job(before.claim.claimId)).tombstoned)
            val observed=requireNotNull(h.job(before.claim.claimId)?.stopObservation)
            if(kind=="total") assertTrue(observed.ticks>before.totalDeadline) // Verified on recovery, never claimed stopped at deadline.
            if(Files.exists(h.root.resolve("completion.json"))) assertNotEquals(JsonPrimitive("PUBLISHED"),
                Json.parseToJsonElement(Files.readString(h.root.resolve("completion.json"))).jsonObject["state"])
            if (app.isAlive) h.kill(app)
        }
    }

    @Test fun `unregistered child receives EOF and a consumed uncertain launch is never redispatched`() = runBlocking {
        Harness().use { h ->
            val f=DescriptiveUiFixture(h.root)
            val clock=OwnerUatContinuousClock { h.origin }
            f.store.configureOwnerUatClock(clock)
            try {
                val preview=f.prepare("activities"); assertTrue(f.jobs.rearm(f.policyDigest()))
                val capture=f.store.captureScopedExecution(preview.binding.executionId)
                val observation=requireNotNull(f.control.observe(f.resource))
                val claim=requireNotNull(f.store.reservePersonalReview(capture,capture.current(),f.store.personalOwnerEpoch(),
                    observation,f.resource,f.clock,1_200_000,ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT,
                    requireNotNull(f.gate.supervisionRegistration(h.origin))))
                assertTrue(f.store.beginPersonalStart(claim,observation,f.clock))
                val intent=OwnerUatSupervisorDatabase(h.database).use { db -> db.transaction { connection ->
                    val journal=OwnerUatSupervisionStore(connection)
                    check(journal.acknowledge(claim,UUID.randomUUID().toString(),h.origin))
                    val job=requireNotNull(journal.get(claim.claimId)); val next=job.copy(launchConsumed=true)
                    check(journal.save(job,next)); next
                } }
                val child=OwnerUatSupervisorTestBackend(h.root,h.database).launch(intent)
                try {
                    child.outputStream.close() // Parent died before identity registration/activation.
                    assertTrue(child.waitFor(8,TimeUnit.SECONDS)); assertEquals(0,child.exitValue())
                    assertFalse(Files.exists(h.root.resolve("active-${intent.launchNonce}")))
                } finally { if(child.isAlive) { child.destroyForcibly();child.waitFor(3,TimeUnit.SECONDS) } }
                h.startSupervisor()
                assertEquals("UNKNOWN",h.job(claim.claimId)?.phase)
                val uncertain=requireNotNull(h.lease(claim.claimId))
                assertTrue(uncertain.phase in setOf(PersonalRuntimePhase.UNKNOWN,PersonalRuntimePhase.STOPPING))
                assertFalse(uncertain.publicationAllowed)
                val client=OwnerUatSupervisorClient(clock,OwnerUatUnixSupervisorTransport(h.root.resolve("s")))
                assertNull(client.launch(claim)); assertTrue(requireNotNull(h.job(claim.claimId)).tombstoned)
                assertFalse(Files.exists(h.root.resolve("active-${intent.launchNonce}")))
            } finally { f.close() }
        }
    }

    @Test fun `singleton ownership and read only status do not replace the supervisor or renew a job`() = runBlocking {
        Harness().use { h ->
            h.startSupervisor();val original=requireNotNull(h.supervisor);val epoch=h.epoch()
            val other=h.start("com.gtrainer.OwnerUatSupervisorMain",h.root.toString(),h.database.toString(),"--synthetic-test")
            assertTrue(other.waitFor(8,TimeUnit.SECONDS));assertNotEquals(0,other.exitValue())
            assertTrue(original.isAlive);assertEquals(epoch,h.epoch())
            val app=h.startApp("hold");val before=h.running()
            val client=OwnerUatSupervisorClient(OwnerUatContinuousClock { h.origin },OwnerUatUnixSupervisorTransport(h.root.resolve("s")))
            repeat(10) { assertEquals(before,client.status(before.claim)) }
            assertEquals(before,h.job(before.claim.claimId))
            assertEquals(1,Files.list(h.root).use { it.filter { p -> p.fileName.toString().startsWith("active-") }.count() })
            h.kill(app);h.stopped(before.claim.claimId)
        }
    }
}
