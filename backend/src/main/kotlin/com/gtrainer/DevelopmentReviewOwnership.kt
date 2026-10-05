package com.gtrainer

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withTimeoutOrNull

internal class DevelopmentReviewOwnershipFailure(val reason: String) : IllegalStateException(reason)

internal data class DevelopmentOwnerEpoch(val number: Long, val nodeName: String) {
    init { require(number > 0 && nodeName.matches(Regex("[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?"))) }
}
internal data class DevelopmentPendingExecution(val runId: String, val epoch: Long, val generation: Long,
    val boundary: DevelopmentNodeBoundary?, val expiresUtc: String)
internal data class DevelopmentOwnedClaim(val runId: String, val owner: DevelopmentOwnerEpoch,
    val generation: Long, val boundary: DevelopmentNodeBoundary, val claimedAtNanos: Long,
    val limits: ConnectedReviewExecutionLimits) {
    init {
        require(UUID.fromString(runId).toString() == runId && generation > 0)
        require(boundary.nodeName == owner.nodeName && limits.policy == ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL)
    }
}

/** All ledger operations run under the parent store monitor and a short JDBC transaction. */
internal class DevelopmentReviewOwnershipLedger(private val database: DevelopmentReviewDatabase) {
    private data class Owner(val epoch: Long, val generation: Long, val node: String?)

    fun epoch(): Long = owner().epoch

    fun advance(node: String): DevelopmentOwnerEpoch {
        require(node.matches(Regex("[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?")))
        val old = owner()
        if (old.node != null && old.node != node) throw DevelopmentReviewOwnershipFailure("development_owner_node_mismatch")
        if (old.epoch == Long.MAX_VALUE) throw DevelopmentReviewOwnershipFailure("development_owner_exhausted")
        database.update(DevelopmentReviewOwnershipSql.CAPTURE_UNBOUND)
        database.update(DevelopmentReviewOwnershipSql.MARK_UNCERTAIN)
        database.update(DevelopmentReviewOwnershipSql.INTERRUPT)
        database.update(DevelopmentReviewOwnershipSql.TASKS_UNKNOWN)
        val next = old.epoch + 1
        if (database.update(DevelopmentReviewOwnershipSql.ADVANCE_EPOCH, next.toString(), node, old.epoch.toString()) != 1)
            throw DevelopmentReviewOwnershipFailure("development_owner_changed")
        return DevelopmentOwnerEpoch(next, node)
    }

    fun canClaim(epoch: DevelopmentOwnerEpoch, boundary: DevelopmentNodeBoundary): Boolean {
        val current = owner()
        return current.epoch == epoch.number && current.node == epoch.nodeName && boundary.nodeName == epoch.nodeName && !blocked()
    }

    fun claimed(id: String, epoch: DevelopmentOwnerEpoch, boundary: DevelopmentNodeBoundary, expires: Instant): Long {
        val current = owner()
        if (current.generation == Long.MAX_VALUE) throw DevelopmentReviewOwnershipFailure("development_owner_exhausted")
        val generation = current.generation + 1
        database.update(DevelopmentReviewOwnershipSql.ADVANCE_GENERATION, generation.toString(), epoch.number.toString())
        database.update(DevelopmentReviewOwnershipSql.INSERT_CLAIM, id, epoch.number.toString(), generation.toString(),
            boundary.nodeName, boundary.bootId, boundary.containerId, expires.toString())
        return generation
    }

    fun started(claim: DevelopmentOwnedClaim, requireRunning: Boolean = true): Boolean {
        if (epoch() != claim.owner.number) return false
        if (requireRunning && !database.query(DevelopmentReviewSql.RUN, claim.runId) { it.next() && it.getString("state") == "RUNNING" }) return false
        if (database.update(DevelopmentReviewOwnershipSql.STARTED, *parameters(claim)) != 1) return false
        database.update(DevelopmentReviewOwnershipSql.TASK_EXECUTING, claim.runId)
        return true
    }

    fun permitsPublication(claim: DevelopmentOwnedClaim): Boolean =
        epoch() == claim.owner.number && database.query(DevelopmentReviewOwnershipSql.MATCHING, *parameters(claim)) {
            it.next() && it.getString(1) == "EXECUTING"
        }

    fun matchesCurrent(claim: DevelopmentOwnedClaim): Boolean =
        epoch() == claim.owner.number && database.query(DevelopmentReviewOwnershipSql.MATCHING, *parameters(claim)) { it.next() }

    fun blocked(): Boolean = database.query(DevelopmentReviewOwnershipSql.BLOCKED) { it.next(); it.getBoolean(1) }

    fun pending(): List<DevelopmentPendingExecution> = database.query(DevelopmentReviewOwnershipSql.PENDING) { rows ->
        buildList {
            while (rows.next()) {
                val node = rows.getString("node_name")
                val boot = rows.getString("boot_id")
                val container = rows.getString("container_id")
                add(DevelopmentPendingExecution(rows.getString("run_id"), rows.getLong("owner_epoch"), rows.getLong("generation"),
                    if (node != null && boot != null && container != null) DevelopmentNodeBoundary(node, boot, container) else null,
                    rows.getString("expires_utc")))
            }
        }
    }

    fun reconcile(current: DevelopmentOwnerEpoch, pending: DevelopmentPendingExecution, proof: DevelopmentNodeStopProof): Boolean {
        if (epoch() != current.number || pending.boundary == null || pending.boundary != proof.expected || proof.expected.nodeName != current.nodeName)
            return false
        val boundary = proof.expected
        val changed = database.update(DevelopmentReviewOwnershipSql.RECONCILE, pending.runId, pending.epoch.toString(),
            pending.generation.toString(), boundary.nodeName, boundary.bootId, boundary.containerId) == 1
        if (changed) database.update(DevelopmentReviewOwnershipSql.TASK_STOPPED, pending.runId)
        return changed
    }

    private fun parameters(claim: DevelopmentOwnedClaim): Array<String> = arrayOf(claim.runId, claim.owner.number.toString(),
        claim.generation.toString(), claim.boundary.nodeName, claim.boundary.bootId, claim.boundary.containerId)

    private fun owner(): Owner = database.query(DevelopmentReviewOwnershipSql.OWNER) {
        if (!it.next()) throw DevelopmentReviewOwnershipFailure("development_owner_unavailable")
        val owner = Owner(it.getLong(1), it.getLong(2), it.getString(3))
        if (owner.epoch < 0 || owner.generation < 0) throw DevelopmentReviewStorageFailure()
        owner
    }
}

/** Closing releases a lock, never certifies old execution stopped. No lease/timeout can replace it. */
internal class DevelopmentReviewOwnershipSession private constructor(private val store: DevelopmentReviewStore,
    val epoch: DevelopmentOwnerEpoch, private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun pending(): List<DevelopmentPendingExecution> { checkOpen(); return store.pendingExecutions() }
    fun claim(id: String, boundary: DevelopmentNodeBoundary): DevelopmentOwnedClaim? { checkOpen(); return store.claimOwned(id, epoch, boundary) }
    fun started(claim: DevelopmentOwnedClaim): Boolean { checkClaim(claim); return store.ownedStarted(claim) }
    fun publish(claim: DevelopmentOwnedClaim, output: ValidatedConnectedReviewOutput): ConnectedReviewStatus {
        checkClaim(claim)
        val status = store.connectedReviewStatus(claim.runId)
        return store.publishOwned(claim, output, status.evidenceDigest, status.context, status.provider, status.model, status.contractVersion)
    }

    private fun checkClaim(claim: DevelopmentOwnedClaim) {
        checkOpen()
        if (claim.owner != epoch) throw DevelopmentReviewOwnershipFailure("development_owner_changed")
    }

    suspend fun reconcile(verifier: DevelopmentNodeTerminationVerifier): Int {
        checkOpen()
        var confirmed = 0
        withTimeoutOrNull(60_000) {
            for (pending in pending()) {
                val boundary = pending.boundary ?: continue
                val proof = verifier.verify(boundary) ?: continue // No database monitor/transaction spans the observation.
                checkOpen()
                if (store.reconcileOwned(epoch, pending, proof)) confirmed++
            }
        }
        return confirmed
    }

    private fun checkOpen() {
        if (closed.get()) throw DevelopmentReviewOwnershipFailure("development_owner_closed")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { lock.release() } finally { channel.close() }
        }
    }

    companion object {
        fun acquire(store: DevelopmentReviewStore, nodeName: String): DevelopmentReviewOwnershipSession {
            var channel: FileChannel? = null
            try {
                val directory = store.ownershipDirectory
                require(Files.isDirectory(directory, NOFOLLOW_LINKS))
                require(Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS).all { it.name.startsWith("OWNER_") })
                val path = directory.resolve("development-worker.lock")
                if (Files.exists(path, NOFOLLOW_LINKS)) require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                channel = FileChannel.open(path, setOf(CREATE, WRITE, NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).all { it.name.startsWith("OWNER_") })
                require(Files.getOwner(path, NOFOLLOW_LINKS) == Files.getOwner(directory, NOFOLLOW_LINKS))
                val lock = channel.tryLock() ?: throw DevelopmentReviewOwnershipFailure("development_owner_busy")
                return DevelopmentReviewOwnershipSession(store, store.advanceOwnership(nodeName), channel, lock)
            } catch (error: Exception) {
                runCatching { channel?.close() }
                if (error is DevelopmentReviewOwnershipFailure) throw error
                if (error is OverlappingFileLockException) throw DevelopmentReviewOwnershipFailure("development_owner_busy")
                throw DevelopmentReviewOwnershipFailure("development_owner_unavailable")
            }
        }
    }
}
