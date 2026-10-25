package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Physical model startup/stop is supplied by task 3.3. A transport receipt is not physical evidence. */
internal interface OwnerUatSupervisorBackend {
    fun accepts(job: OwnerUatSupervisedJob): Boolean
    fun launch(job: OwnerUatSupervisedJob): Process?
    /** Must finish within this finite stop budget; inability to verify stopping returns false. */
    fun stop(job: OwnerUatSupervisedJob, budgetMillis: Long = 60_000): Boolean
}

/** Raw connection to the existing journal; NEVER opens HistoryStore or rotates its owner epoch. */
internal class OwnerUatSupervisorDatabase(path: Path) : AutoCloseable {
    private val connection: Connection
    init {
        OwnerUatSocketIO.privateOwner(path, directory = false)
        require(Files.isRegularFile(path, NOFOLLOW_LINKS))
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        try {
            connection.createStatement().use {
                it.execute("PRAGMA busy_timeout=5000"); it.execute("PRAGMA foreign_keys=ON"); it.execute("PRAGMA synchronous=FULL")
            }
            HistorySchema.validate(connection)
        } catch (e: Exception) { connection.close(); throw e }
    }
    @Synchronized fun <T> transaction(block: (Connection) -> T): T {
        connection.autoCommit = false
        try { val value = block(connection); connection.commit(); return value }
        catch (e: Exception) { connection.rollback(); throw e }
        finally { connection.autoCommit = true }
    }
    @Synchronized override fun close() { connection.close() }
}

@Serializable
private data class OwnerUatSupervisorOwner(val process: OwnerUatProcessIdentity, val epoch: String, val socketFileKey: String)

/** The independent process retains durable responsibility; app callbacks cannot release occupancy. */
internal class OwnerUatSupervisorServer(private val root: Path, databasePath: Path,
    private val clock: OwnerUatContinuousClock, private val backend: OwnerUatSupervisorBackend) : AutoCloseable {
    val epoch: String = UUID.randomUUID().toString()
    private val closed = AtomicBoolean()
    private val lockChannel: FileChannel
    private val lock: java.nio.channels.FileLock
    private val database: OwnerUatSupervisorDatabase
    private val socket: ServerSocketChannel
    private val watcher = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "owner-uat-cleanup").apply { isDaemon = true } }
    private val path = root.resolve("s")
    private var socketFileKey: String? = null
    private val observedTicks = mutableMapOf<String,Long>() // Lost on restart; recovery fences all old jobs before listening.

    init {
        OwnerUatSocketIO.privateOwner(root, directory = true)
        val lockPath = root.resolve("supervisor.lock")
        if (Files.exists(lockPath, NOFOLLOW_LINKS)) {
            OwnerUatSocketIO.privateOwner(lockPath, directory = false)
            require(Files.isRegularFile(lockPath,NOFOLLOW_LINKS))
        }
        lockChannel = FileChannel.open(lockPath, setOf(CREATE, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        lock = try { requireNotNull(lockChannel.tryLock()) } catch (e: Exception) { lockChannel.close(); throw e }
        var openedDatabase: OwnerUatSupervisorDatabase? = null
        var openedSocket: ServerSocketChannel? = null
        try {
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                OwnerUatSocketIO.privateOwner(path, directory = false)
                require((Files.getAttribute(path,"unix:mode",NOFOLLOW_LINKS) as Int) and 0xf000 == 0xc000)
                val ownerPath = root.resolve("owner.json"); OwnerUatSocketIO.privateOwner(ownerPath, directory = false)
                require(Files.size(ownerPath) <= 4096)
                val previous = StrictProfileDraftJson.decode<OwnerUatSupervisorOwner>(Files.readString(ownerPath))
                require(!previous.process.current() && previous.socketFileKey == Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString())
                Files.delete(path)
            }
            database = OwnerUatSupervisorDatabase(databasePath).also { openedDatabase = it }
            // Recovery fences every prior responsibility before accepting any new mutation.
            for (job in database.transaction { OwnerUatSupervisionStore(it).unresolved() }) finish(job.claim, "supervisor_recovered")
            socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { openedSocket = it }
            socket.bind(UnixDomainSocketAddress.of(path))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            socketFileKey = requireNotNull(Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()).toString()
            val owner = OwnerUatSupervisorOwner(OwnerUatProcessIdentity.capture(), epoch, requireNotNull(socketFileKey))
            val temporary = root.resolve("owner-$epoch.json")
            Files.writeString(temporary, ScopedEvidencePreparation.json.encodeToString(owner), CREATE_NEW, WRITE)
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            FileChannel.open(temporary, WRITE).use { it.force(true) }
            Files.move(temporary, root.resolve("owner.json"), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            watcher.scheduleWithFixedDelay({ try { tick() } catch (_: Exception) { /* Uncertainty never releases occupancy. */ } }, 0, 50, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            watcher.shutdownNow(); runCatching { openedSocket?.close() }; runCatching { openedDatabase?.close() }
            lock.release(); lockChannel.close(); throw e
        }
    }

    fun serve() {
        while (!closed.get()) {
            val channel = try { socket.accept() } catch (_: Exception) { if (closed.get()) break else continue }
            channel.use {
                try {
                    it.configureBlocking(false); OwnerUatSocketIO.peer(it)
                    val bytes = OwnerUatSocketIO.read(it, System.nanoTime() + 5_000_000_000L) ?: return@use
                    val request = StrictProfileDraftJson.decode<OwnerUatSupervisorRequest>(bytes.decodeToString(throwOnInvalidSequence = true))
                    val reply = handle(request)
                    OwnerUatSocketIO.write(it, ScopedEvidencePreparation.json.encodeToString(reply).toByteArray(), System.nanoTime() + 5_000_000_000L)
                } catch (_: Exception) { /* Fixed connection failure, never echo private request data. */ }
            }
        }
    }

    private fun handle(request: OwnerUatSupervisorRequest): OwnerUatSupervisorReply {
        val claim = request.claim
        require(claim.supervisionRequired)
        val known=database.transaction { OwnerUatSupervisionStore(it).get(claim.claimId) }
        if(known?.claim!=claim) return OwnerUatSupervisorReply(false,claim.claimId,epoch)
        var ok = false
        when (request.op) {
            "status" -> ok = true // This branch is read-only: no clock repair, launch or reconcile.
            "register" -> ok = database.transaction { connection ->
                val journal = OwnerUatSupervisionStore(connection)
                val job = journal.get(claim.claimId) ?: return@transaction false
                if(!backend.accepts(job)) return@transaction false
                val sample=executionSample(connection,job) ?: return@transaction false
                journal.acknowledge(claim, epoch, sample)
            }
            "launch" -> ok = launch(claim)
            "phase" -> ok = database.transaction { connection ->
                val journal=OwnerUatSupervisionStore(connection)
                val job=journal.get(claim.claimId) ?: return@transaction false
                val sample=executionSample(connection,job) ?: return@transaction false
                journal.transition(claim,requireNotNull(request.phase),sample)
            }
            "stop" -> { finish(claim, "cancelled"); ok = true }
            else -> throw IllegalArgumentException("Unsupported owner supervisor operation")
        }
        val job = database.transaction { OwnerUatSupervisionStore(it).get(claim.claimId) }
        return OwnerUatSupervisorReply(ok && job?.claim == claim, claim.claimId, epoch, job)
    }

    private fun launch(claim: PersonalReviewClaim): Boolean {
        val intent = database.transaction { connection ->
            val journal = OwnerUatSupervisionStore(connection)
            val job = journal.get(claim.claimId) ?: return@transaction null
            val runtime = PersonalReviewRuntimeStore(connection)
            val lease = runtime.get(claim.claimId) ?: return@transaction null
            val sample=executionSample(connection,job) ?: return@transaction null
            if (job.claim != claim || job.supervisorEpoch != epoch || !job.permits(sample) ||
                !job.registration.application.current() || runtime.epoch() != claim.ownerEpoch || !lease.publicationAllowed ||
                lease.claim != claim || lease.phase !in setOf(PersonalRuntimePhase.START_INTENT, PersonalRuntimePhase.EXECUTING) || !backend.accepts(job)) return@transaction null
            if (job.launchConsumed) return@transaction job to false
            val next = job.copy(launchConsumed = true)
            check(journal.save(job, next)); next to true
        } ?: return false
        val jobIntent = intent.first
        if (!intent.second) return jobIntent.worker?.current() == true // Never redispatch a consumed launch.
        var process: Process? = null
        try {
            check(!closed.get())
            process = backend.launch(jobIntent) ?: throw IllegalStateException("Owned launch unavailable")
            val worker = OwnerUatProcessIdentity.capture(process.toHandle())
            val registered = database.transaction { connection ->
                val journal = OwnerUatSupervisionStore(connection)
                val current = journal.get(claim.claimId) ?: return@transaction false
                val lease = PersonalReviewRuntimeStore(connection).get(claim.claimId)
                val sample=executionSample(connection,current) ?: return@transaction false
                if (current.claim != jobIntent.claim || current.launchNonce != jobIntent.launchNonce || current.supervisorEpoch != epoch ||
                    !current.launchConsumed || current.worker != null || !current.permits(sample) || lease?.publicationAllowed != true ||
                    PersonalReviewRuntimeStore(connection).epoch() != claim.ownerEpoch) return@transaction false
                journal.save(current, current.copy(worker = worker))
            }
            if (!registered) throw IllegalStateException("Owned launch fenced")
            // Child must wait for this gate and independently check the durable tombstone before computation.
            check(!closed.get()); process.outputStream.write(1); process.outputStream.flush()
            return true
        } catch (_: Exception) {
            // Before activation, EOF is a no-computation fence. Physical uncertainty is retained in the ledger.
            runCatching { process?.outputStream?.close() }
            finish(claim, "launch_failed")
            return false
        }
    }

    /** Called under the same SQLite transaction as each execution permission; status never calls it. */
    private fun executionSample(connection: Connection, job: OwnerUatSupervisedJob): OwnerUatClockSample? {
        if(job.tombstoned) return null
        val sample=runCatching { clock.sample() }.getOrNull()
        val failure=when {
            sample==null || !job.registration.origin.compatible(sample) ||
                sample.ticks<maxOf(job.lastObservedTicks,observedTicks[job.claim.claimId] ?: job.lastObservedTicks) -> "clock_unverified"
            !job.permits(sample) -> "expired"
            else -> null
        }
        if(failure!=null) {
            OwnerUatSupervisionStore(connection).tombstone(job.claim,failure)
            PersonalReviewRuntimeStore(connection).revoke(job.claim)
            try { ScopedExecutionStore(connection).fail(job.claim.executionId,"runtime_admission_closed") } catch(_: ConnectedReviewNotFound) { }
            return null
        }
        observedTicks[job.claim.claimId]=requireNotNull(sample).ticks
        return sample
    }

    private fun tick() {
        val jobs = database.transaction { OwnerUatSupervisionStore(it).unresolved() }
        for (job in jobs) {
            val reason = database.transaction { connection ->
                val journal = OwnerUatSupervisionStore(connection)
                val current = journal.get(job.claim.claimId) ?: return@transaction null
                if(current.phase == "STOP_CONFIRMED") return@transaction null
                val sample = runCatching { clock.sample() }.getOrNull()
                val runtime = PersonalReviewRuntimeStore(connection)
                val lease = runtime.get(job.claim.claimId)
                when {
                    current.tombstoned -> current.outcome ?: "cancelled"
                    sample == null || !current.registration.origin.compatible(sample) ||
                        sample.ticks < maxOf(current.lastObservedTicks,observedTicks[current.claim.claimId] ?: current.lastObservedTicks) -> "clock_unverified"
                    !current.permits(sample) -> "expired"
                    runtime.epoch() != current.claim.ownerEpoch || !current.registration.application.current() -> "owner_lost"
                    lease?.publicationAllowed != true -> "cancelled"
                    current.worker != null && !current.worker.current() -> "owner_lost"
                    // Health polling is not a stream of durable writes racing application publication.
                    // Phase/launch/publication authority persists samples; recovery never resumes old work.
                    else -> { observedTicks[current.claim.claimId]=sample.ticks; null }
                }
            }
            if (reason != null) finish(job.claim, reason)
        }
    }

    private fun finish(claim: PersonalReviewClaim, reason: String) {
        val job = database.transaction { connection ->
            val runtime = PersonalReviewRuntimeStore(connection)
            val journal = OwnerUatSupervisionStore(connection)
            val current = journal.get(claim.claimId) ?: return@transaction null
            if (current.claim != claim || current.phase == "STOP_CONFIRMED") return@transaction null
            val fenced = requireNotNull(journal.tombstone(claim, reason))
            runtime.revoke(claim)
            if (fenced.outcome != "published") try { ScopedExecutionStore(connection).fail(claim.executionId, "runtime_admission_closed") }
                catch (_: ConnectedReviewNotFound) { }
            fenced
        } ?: return
        val stopped = try { backend.stop(job, job.cleanupBudgetMillis(runCatching { clock.sample() }.getOrNull())) } catch (_: Exception) { false }
        val stopObservation=if(stopped) runCatching { clock.sample() }.getOrNull() else null
        database.transaction { connection ->
            val journal = OwnerUatSupervisionStore(connection)
            val current = journal.get(claim.claimId) ?: return@transaction
            if (current.claim != claim || !current.tombstoned || current.phase == "STOP_CONFIRMED") return@transaction
            val runtime = PersonalReviewRuntimeStore(connection)
            val now = Instant.now()
            val confirmed = stopped && runtime.confirmStop(runtime.epoch(), claim, PersonalStopEvidence(claim, now, true, true), now)
            check(journal.save(current, current.copy(phase = if (confirmed) "STOP_CONFIRMED" else "UNKNOWN",
                stopObservation = if(confirmed) stopObservation else null)))
            if (!confirmed) runtime.unknown(runtime.epoch(), claim)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.close(); watcher.shutdownNow(); watcher.awaitTermination(6, TimeUnit.SECONDS)
            try { for (job in database.transaction { OwnerUatSupervisionStore(it).unresolved() }) finish(job.claim, "supervisor_recovered") }
            finally {
                database.close()
                if(Files.exists(path,NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) &&
                    Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey()?.toString()==socketFileKey) Files.delete(path)
                lock.release(); lockChannel.close()
            }
        }
    }
}

object OwnerUatSupervisorMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..3)
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        val database = Path.of(args[1]).toAbsolutePath().normalize()
        val synthetic = args.size == 3 && args[2] == "--synthetic-test"
        require(args.size == 2 || synthetic)
        val clock: OwnerUatContinuousClock
        val backend: OwnerUatSupervisorBackend
        if (synthetic) {
            require(root.fileName.toString().startsWith("uat-") && database.parent == root)
            OwnerUatSocketIO.privateOwner(root,directory=true)
            OwnerUatSocketIO.privateOwner(root.resolve("synthetic-only"),directory=false)
            require(Files.isRegularFile(root.resolve("synthetic-only"),NOFOLLOW_LINKS))
            require(Files.readString(root.resolve("synthetic-only")) == "synthetic-owner-supervision-v1\n")
            clock = OwnerUatContinuousClock {
                OwnerUatSocketIO.privateOwner(root.resolve("clock.json"),directory=false)
                require(Files.isRegularFile(root.resolve("clock.json"),NOFOLLOW_LINKS) && Files.size(root.resolve("clock.json"))<=4096)
                StrictProfileDraftJson.decode<OwnerUatClockSample>(Files.readString(root.resolve("clock.json"))).also {
                    require(it.implementation == "synthetic-continuous-v1")
                }
            }
            // Test backend/worker classes are absent from the production artifact.
            backend = Class.forName("com.gtrainer.OwnerUatSupervisorTestBackend").getConstructor(Path::class.java, Path::class.java)
                .newInstance(root, database) as OwnerUatSupervisorBackend
        } else {
            clock = MacOwnerUatContinuousClock()
            backend = object : OwnerUatSupervisorBackend {
                override fun accepts(job: OwnerUatSupervisedJob) = false
                override fun launch(job: OwnerUatSupervisedJob): Process? = null
                override fun stop(job: OwnerUatSupervisedJob, budgetMillis: Long) = false
            }
        }
        val server = OwnerUatSupervisorServer(root, database, clock, backend)
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        server.serve()
    }
}
