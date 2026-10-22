package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.*
import java.time.temporal.ChronoUnit

internal fun commitmentLocal(value: String): LocalDateTime {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}").matches(value))
    return LocalDateTime.parse(value)
}

internal fun commitmentInterval(start: String, end: String): Duration {
    val duration=Duration.between(commitmentLocal(start),commitmentLocal(end))
    require(!duration.isNegative && !duration.isZero && duration<=Duration.ofDays(366))
    return duration
}

internal fun commitmentZone(value: String): ZoneId {
    require(value.length<=80 && value in ZoneId.getAvailableZoneIds())
    return ZoneId.of(value)
}

@Serializable
data class CommitmentRecurrence(val frequency: String, val interval: Int = 1, val endOn: String? = null) {
    init { require(frequency in setOf("daily","weekly") && interval in 1..366); endOn?.let(::validateContextDate) }
}

@Serializable
data class CommitmentException(val originalDate: String, val action: String, val startsLocal: String? = null, val endsLocal: String? = null) {
    init {
        validateContextDate(originalDate)
        require(action in setOf("cancel","reschedule"))
        if(action=="cancel") require(startsLocal==null && endsLocal==null)
        else commitmentInterval(requireNotNull(startsLocal),requireNotNull(endsLocal))
    }
    override fun toString()="CommitmentException([REDACTED])"
}

@Serializable
data class CommitmentDefinition(val label: String, val kind: String, val timeZone: String, val startsLocal: String,
    val endsLocal: String, val recurrence: CommitmentRecurrence? = null, val exceptions: List<CommitmentException> = emptyList()) {
    init {
        validateContextText(label,160,true)
        require(kind in setOf("commitment","availability"))
        commitmentZone(timeZone)
        commitmentInterval(startsLocal,endsLocal)
        val anchor=commitmentLocal(startsLocal).toLocalDate()
        require(recurrence?.endOn?.let { LocalDate.parse(it)>=anchor } != false)
        require(exceptions.size<=1000 && exceptions.map { it.originalDate }.distinct().size==exceptions.size)
        require(exceptions.all { isCommitmentDate(anchor,recurrence,LocalDate.parse(it.originalDate)) })
    }
    override fun toString()="CommitmentDefinition([REDACTED])"
}

internal fun isCommitmentDate(anchor: LocalDate, recurrence: CommitmentRecurrence?, date: LocalDate): Boolean {
    if(recurrence==null) return date==anchor
    if(date<anchor || recurrence.endOn?.let { date>LocalDate.parse(it) } == true) return false
    val step=recurrence.interval.toLong() * if(recurrence.frequency=="weekly") 7 else 1
    return ChronoUnit.DAYS.between(anchor,date)%step==0L
}

@Serializable
data class CommitmentConfirmation(val intakeId: String, val expectedIntakeRevision: Int, val definition: CommitmentDefinition,
    val contextId: String? = null, val expectedContextRevision: Int? = null, val eventId: String? = null) {
    init {
        validateContextId(intakeId); require(expectedIntakeRevision in 1..1000)
        require((contextId==null)==(expectedContextRevision==null))
        contextId?.let(::validateContextId); expectedContextRevision?.let { require(it in 1..1000) }
        eventId?.let(::validateEventId)
    }
    override fun toString()="CommitmentConfirmation([REDACTED])"
}

@Serializable
data class CommitmentCorrection(val expectedCommitmentRevision: Int, val confirmation: CommitmentConfirmation) {
    init { require(expectedCommitmentRevision in 1..999) }
    override fun toString()="CommitmentCorrection([REDACTED])"
}

@Serializable
data class CommitmentEvidenceRef(val commitmentId: String, val revision: Int, val intakeId: String, val intakeRevision: Int,
    val contextId: String?, val contextRevision: Int?)

@Serializable
data class ConfirmedCommitment(val reference: CommitmentEvidenceRef, val definition: CommitmentDefinition,
    val eventId: String?, val authorAttribution: String, val sourceCategory: String, val enteredBy: String, val confirmedUtc: String,
    val sourceChanged: Boolean, val contextChanged: Boolean, val needsReconfirmation: Boolean) {
    override fun toString()="ConfirmedCommitment([REDACTED])"
}

@Serializable
data class CommitmentOccurrence(val occurrenceId: String, val originalDate: String, val reference: CommitmentEvidenceRef,
    val label: String, val kind: String, val timeZone: String, val startsLocal: String, val endsLocal: String,
    val startsUtc: String, val endsUtc: String, val authoritative: Boolean, val flags: List<String>) {
    override fun toString()="CommitmentOccurrence([REDACTED])"
}

@Serializable
data class CommitmentCalendar(val oldest: String, val newest: String, val timeZone: String, val occurrences: List<CommitmentOccurrence>,
    val needsReconfirmation: List<CommitmentEvidenceRef>, val constraintsBlocked: Boolean,
    val coverage: String = "confirmed_local_only_external_unavailable") {
    override fun toString()="CommitmentCalendar([REDACTED])"
}

class CommitmentNotFound : IllegalStateException("Commitment not found")
class CommitmentConflict : IllegalStateException("Commitment or its source changed")
class CommitmentProjectionLimit : IllegalStateException("Commitment projection exceeds bound")
class CommitmentTimeUncertain : IllegalStateException("Commitment interval needs timezone clarification")

interface CommitmentRepository {
    fun commitments(): List<ConfirmedCommitment>
    fun commitmentHistory(id: String): List<ConfirmedCommitment>
    fun confirmCommitment(request: CommitmentConfirmation, at: Instant): ConfirmedCommitment
    fun correctCommitment(id: String, request: CommitmentCorrection, at: Instant): ConfirmedCommitment
}

class CommitmentService(private val repository: CommitmentRepository, private val clock: Clock = Clock.systemUTC()) {
    fun list()=repository.commitments()
    fun history(id: String)=repository.commitmentHistory(id)
    fun confirm(request: CommitmentConfirmation)=repository.confirmCommitment(request,clock.instant())
    fun correct(id: String, request: CommitmentCorrection)=repository.correctCommitment(id,request,clock.instant())
    fun calendar(oldest: String, newest: String, timeZone: String): CommitmentCalendar =
        projectCommitments(repository.commitments(),oldest,newest,timeZone)
}
