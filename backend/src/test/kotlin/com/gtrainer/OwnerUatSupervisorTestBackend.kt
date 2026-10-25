package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Test-classpath only. Never accepts the production Ollama selection or any command from IPC. */
internal class OwnerUatSupervisorTestBackend(private val root: Path, private val database: Path) : OwnerUatSupervisorBackend {
    override fun accepts(job: OwnerUatSupervisedJob): Boolean = root.fileName.toString().startsWith("uat-") &&
        job.claim.resource.domain.startsWith("synthetic") && job.claim.resource.selection.providerId == "synthetic" &&
        job.claim.profile == ScopedDescriptiveContractV1.PROFILE &&
        Files.readString(root.resolve("synthetic-only")) == "synthetic-owner-supervision-v1\n"

    override fun launch(job: OwnerUatSupervisedJob): Process {
        require(accepts(job))
        return ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms16m", "-Xmx96m", "--enable-native-access=ALL-UNNAMED", "-cp",
            System.getProperty("gtrainer.test.runtimeClasspath") ?: System.getProperty("java.class.path"),
            "com.gtrainer.OwnerUatFakeWorker", database.toString(), root.toString(), job.claim.claimId, job.launchNonce)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }

    override fun stop(job: OwnerUatSupervisedJob, budgetMillis: Long): Boolean {
        require(budgetMillis in 1..60_000)
        if (!accepts(job)) return false
        val worker = job.worker ?: return !job.launchConsumed // Missing launched identity is UNKNOWN, never invented absence.
        val handle = ProcessHandle.of(worker.pid).orElse(null) ?: return true
        val start = handle.info().startInstant().orElse(null) ?: return false
        if (start.toString() != worker.startedUtc) return true // Original process is gone; never signal a reused PID.
        if (!handle.isAlive) return true
        val args = handle.info().arguments().orElse(null) ?: return false
        if (handle.info().user().orElse(null) != System.getProperty("user.name") ||
            "com.gtrainer.OwnerUatFakeWorker" !in args || job.launchNonce !in args || root.toString() !in args) return false
        handle.destroy()
        val deadline=System.nanoTime()+budgetMillis*1_000_000
        try { handle.onExit().get(minOf(budgetMillis,1000), TimeUnit.MILLISECONDS) } catch (_: Exception) {
            if (worker.current()) handle.destroyForcibly()
            val remaining=((deadline-System.nanoTime())/1_000_000).coerceAtLeast(1)
            try { handle.onExit().get(minOf(remaining,2000), TimeUnit.MILLISECONDS) } catch (_: Exception) { return false }
        }
        return !worker.current() && !handle.isAlive
    }
}

/** Waits for registered identity plus a launch gate; intentionally survives supervisor death after activation. */
object OwnerUatFakeWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4)
        val database = Path.of(args[0]); val root = Path.of(args[1]); val claimId = args[2]; val nonce = args[3]
        require(root.fileName.toString().startsWith("uat-") && database.parent == root)
        require(Files.readString(root.resolve("synthetic-only")) == "synthetic-owner-supervision-v1\n")
        if (System.`in`.read() != 1) return
        val current = OwnerUatProcessIdentity.capture()
        val allowed = OwnerUatSupervisorDatabase(database).use { db -> db.transaction { connection ->
            val job = OwnerUatSupervisionStore(connection).get(claimId) ?: return@transaction false
            val lease = PersonalReviewRuntimeStore(connection).get(claimId) ?: return@transaction false
            val sample = StrictProfileDraftJson.decode<OwnerUatClockSample>(Files.readString(root.resolve("clock.json")))
            job.launchNonce == nonce && job.worker == current && job.launchConsumed && job.permits(sample) &&
                job.claim.resource.selection.providerId == "synthetic" && lease.publicationAllowed &&
                lease.phase in setOf(PersonalRuntimePhase.START_INTENT, PersonalRuntimePhase.EXECUTING) &&
                PersonalReviewRuntimeStore(connection).epoch() == job.claim.ownerEpoch
        } }
        if (!allowed) return
        java.nio.channels.FileChannel.open(root.resolve("active-$nonce"), setOf(CREATE_NEW, WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
            val marker=java.nio.ByteBuffer.wrap("synthetic worker only\n".toByteArray())
            while(marker.hasRemaining()) channel.write(marker)
            channel.force(true)
        }
        // Independent test-only rescue leash, not an app expiry or production execution budget.
        val until = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < until) Thread.sleep(50)
    }
}
