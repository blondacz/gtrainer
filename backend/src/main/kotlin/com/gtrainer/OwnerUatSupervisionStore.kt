package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.sql.Connection
import java.time.Instant
import java.util.UUID

@Serializable
internal data class OwnerUatProcessIdentity(val pid: Long, val startedUtc: String) {
    init { require(pid > 0); Instant.parse(startedUtc) }
    fun current(): Boolean = ProcessHandle.of(pid).filter { handle ->
        handle.isAlive && handle.info().startInstant().orElse(null)?.toString() == startedUtc
    }.isPresent
    companion object {
        fun capture(handle: ProcessHandle = ProcessHandle.current()) =
            OwnerUatProcessIdentity(handle.pid(), handle.info().startInstant().orElseThrow().toString())
    }
}

@Serializable
internal data class OwnerUatJobRegistration(val origin: OwnerUatClockSample, val policySha256: String,
    val runtimeSha256: String, val application: OwnerUatProcessIdentity) {
    init { for (digest in listOf(policySha256, runtimeSha256)) require(digest.matches(Regex("[a-f0-9]{64}"))) }
}

@Serializable
internal data class OwnerUatSupervisedJob(
    val claim: PersonalReviewClaim,
    val registration: OwnerUatJobRegistration,
    val phase: String = "STARTUP",
    val phaseStartedTicks: Long = registration.origin.ticks,
    val lastObservedTicks: Long = registration.origin.ticks,
    val phaseDeadline: Long = registration.origin.afterMillis(120_000).ticks,
    val workDeadline: Long = registration.origin.afterMillis(1_140_000).ticks,
    val totalDeadline: Long = registration.origin.afterMillis(1_200_000).ticks,
    val supervisorEpoch: String? = null,
    val supervisorProcess: OwnerUatProcessIdentity? = null,
    val launchNonce: String = UUID.randomUUID().toString(),
    val launchConsumed: Boolean = false,
    val worker: OwnerUatProcessIdentity? = null,
    val tombstoned: Boolean = false,
    val outcome: String? = null,
    val stopObservation: OwnerUatClockSample? = null,
) {
    init {
        require(phase in setOf("STARTUP", "GENERATING", "VALIDATING", "STOPPING", "STOP_CONFIRMED", "UNKNOWN"))
        validateContextId(launchNonce); supervisorEpoch?.let(::validateContextId)
        require((supervisorEpoch == null) == (supervisorProcess == null))
        require(workDeadline == registration.origin.afterMillis(1_140_000).ticks)
        require(totalDeadline == registration.origin.afterMillis(1_200_000).ticks)
        require(phaseDeadline >= registration.origin.ticks && phaseDeadline <= totalDeadline)
        require(phaseStartedTicks >= registration.origin.ticks && phaseStartedTicks <= totalDeadline)
        require(lastObservedTicks >= phaseStartedTicks && lastObservedTicks <= totalDeadline)
        val expectedPhaseDeadline = when (phase) {
            "STARTUP" -> registration.origin.afterMillis(120_000).ticks
            "GENERATING" -> minOf(registration.origin.copy(ticks = phaseStartedTicks).afterMillis(900_000).ticks, workDeadline)
            "VALIDATING" -> minOf(registration.origin.copy(ticks = phaseStartedTicks).afterMillis(30_000).ticks, workDeadline)
            else -> totalDeadline
        }
        require(phaseDeadline == expectedPhaseDeadline)
        require(worker == null || launchConsumed)
        require(!tombstoned || phase in setOf("STOPPING", "STOP_CONFIRMED", "UNKNOWN"))
        require(outcome == null || outcome in setOf("published", "cancelled", "expired", "owner_lost", "supervisor_recovered", "clock_unverified", "launch_failed"))
        require(stopObservation == null || phase == "STOP_CONFIRMED")
    }
    fun permits(sample: OwnerUatClockSample): Boolean = !tombstoned && registration.origin.compatible(sample) &&
        sample.ticks >= lastObservedTicks && sample.ticks < minOf(workDeadline, phaseDeadline)
    /** Recovery cleanup after expiry is allowed; this never grants renewed execution/publication time. */
    fun cleanupBudgetMillis(sample: OwnerUatClockSample?): Long {
        if(sample==null || !registration.origin.compatible(sample) || sample.ticks>=totalDeadline) return 60_000
        val remaining=sample.elapsedNanosUntil(registration.origin.copy(ticks=totalDeadline)) ?: return 60_000
        return (remaining/1_000_000).coerceIn(1,60_000)
    }
    override fun toString() = "OwnerUatSupervisedJob([REDACTED])"
}

/** SQL-only companion to the existing ownership ledger. Caller supplies one short transaction.
 * No control-plane operation or worker launch occurs while this store holds a database transaction. */
internal class OwnerUatSupervisionStore(private val connection: Connection) {
    private val json get() = ScopedEvidencePreparation.json
    fun get(claimId: String): OwnerUatSupervisedJob? = connection.prepareStatement(
        "SELECT state_json FROM owner_uat_job_supervision WHERE claim_id=?").use { statement ->
        statement.setString(1, claimId)
        statement.executeQuery().use { rows -> if (!rows.next()) null else
            StrictProfileDraftJson.decode<OwnerUatSupervisedJob>(rows.getString(1)).also { check(it.claim.claimId == claimId) } }
    }
    fun unresolved(): List<OwnerUatSupervisedJob> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT claim_id FROM owner_uat_job_supervision LIMIT 1001").use { rows ->
            buildList { while (rows.next()) { check(size < 1000); add(requireNotNull(get(rows.getString(1)))) } }
                .filter { it.phase != "STOP_CONFIRMED" }
        }
    }
    fun register(claim: PersonalReviewClaim, registration: OwnerUatJobRegistration, sample: OwnerUatClockSample) {
        require(claim.supervisionRequired)
        val job = OwnerUatSupervisedJob(claim, registration)
        require(job.permits(sample) && registration.application.current())
        connection.prepareStatement("INSERT INTO owner_uat_job_supervision(claim_id,state_json) VALUES(?,?)").use {
            it.setString(1, claim.claimId); it.setString(2, json.encodeToString(job)); check(it.executeUpdate() == 1)
        }
    }
    fun save(old: OwnerUatSupervisedJob, next: OwnerUatSupervisedJob): Boolean {
        require(old.claim == next.claim && old.registration == next.registration && old.launchNonce == next.launchNonce)
        require(!old.tombstoned || next.tombstoned)
        require(!old.launchConsumed || next.launchConsumed)
        require(old.worker == null || old.worker == next.worker)
        require(old.supervisorEpoch == null || old.supervisorEpoch == next.supervisorEpoch)
        require(old.supervisorProcess == null || old.supervisorProcess == next.supervisorProcess)
        require(next.lastObservedTicks >= old.lastObservedTicks)
        require(old.phase != "STOP_CONFIRMED" || next == old)
        return connection.prepareStatement("UPDATE owner_uat_job_supervision SET state_json=? WHERE claim_id=? AND state_json=?").use {
            it.setString(1, json.encodeToString(next)); it.setString(2, old.claim.claimId); it.setString(3, json.encodeToString(old))
            it.executeUpdate() == 1
        }
    }
    fun acknowledge(claim: PersonalReviewClaim, epoch: String, sample: OwnerUatClockSample,
        process: OwnerUatProcessIdentity = OwnerUatProcessIdentity.capture()): Boolean {
        val job = get(claim.claimId) ?: return false
        val lease = PersonalReviewRuntimeStore(connection).get(claim.claimId) ?: return false
        if (job.claim != claim || lease.claim != claim || !lease.publicationAllowed || !process.current() ||
            !job.registration.application.current() || !job.permits(sample) ||
            PersonalReviewRuntimeStore(connection).epoch() != claim.ownerEpoch) return false
        if(job.supervisorEpoch != null && (job.supervisorEpoch != epoch || job.supervisorProcess != process)) return false
        return save(job, job.copy(supervisorEpoch = epoch, supervisorProcess = process, lastObservedTicks = sample.ticks))
    }
    fun transition(claim: PersonalReviewClaim, phase: String, sample: OwnerUatClockSample): Boolean {
        val job = get(claim.claimId) ?: return false
        val lease = PersonalReviewRuntimeStore(connection).get(claim.claimId) ?: return false
        if (job.claim != claim || lease.claim != claim || !lease.publicationAllowed || job.supervisorEpoch == null ||
            job.supervisorProcess?.current() != true || !job.launchConsumed || job.worker == null || lease.phase != PersonalRuntimePhase.EXECUTING ||
            !job.permits(sample) || PersonalReviewRuntimeStore(connection).epoch() != claim.ownerEpoch) return false
        if (phase == job.phase) return save(job, job.copy(lastObservedTicks = sample.ticks)) // Never renew the deadline.
        val millis = when {
            phase == "GENERATING" && job.phase == "STARTUP" -> 900_000L
            phase == "VALIDATING" && job.phase == "GENERATING" -> 30_000L
            else -> return false
        }
        val deadline = minOf(sample.afterMillis(millis).ticks, job.workDeadline)
        return save(job, job.copy(phase = phase, phaseStartedTicks = sample.ticks, lastObservedTicks = sample.ticks, phaseDeadline = deadline))
    }
    fun authorizePublication(claim: PersonalReviewClaim, sample: OwnerUatClockSample): Boolean {
        val job = get(claim.claimId) ?: return !claim.supervisionRequired
        val permitted = job.claim == claim && job.phase == "VALIDATING" && job.supervisorEpoch != null && job.launchConsumed &&
            job.supervisorProcess?.current() == true && job.worker != null && job.permits(sample)
        return permitted && save(job, job.copy(lastObservedTicks = sample.ticks))
    }
    fun observed(job: OwnerUatSupervisedJob, sample: OwnerUatClockSample): Boolean =
        job.permits(sample) && (job.lastObservedTicks == sample.ticks || save(job, job.copy(lastObservedTicks = sample.ticks)))
    fun tombstone(claim: PersonalReviewClaim, outcome: String): OwnerUatSupervisedJob? {
        val job = get(claim.claimId) ?: return null
        if (job.claim != claim) return null
        if (job.tombstoned) return job
        val next = job.copy(phase = "STOPPING", phaseDeadline = job.totalDeadline, tombstoned = true, outcome = outcome)
        check(save(job, next)); return next
    }
}
