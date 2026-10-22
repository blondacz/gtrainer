package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant

/** Original attributed wording, not an extracted assertion or an active constraint. */
@Serializable
data class ContextIntake(val intakeId: String, val revision: Int, val content: String, val sourceCategory: String,
    val authorAttribution: String, val enteredBy: String, val observedOn: String, val recordedUtc: String) {
    override fun toString() = "ContextIntake([REDACTED])"
}

@Serializable
data class ContextIntakeRequest(val content: String, val sourceCategory: String, val authorAttribution: String,
    val observedOn: String) {
    init {
        require(sourceCategory in setOf("user_report", "clinician_guidance", "coach_guidance"))
        validateContextText(content, 4000, true)
        validateContextText(authorAttribution, 160, true)
        validateContextDate(observedOn)
    }
    override fun toString() = "ContextIntakeRequest([REDACTED])"
}

@Serializable
data class ContextIntakeCorrection(val expectedRevision: Int, val note: ContextIntakeRequest) {
    init { require(expectedRevision in 1..999) }
    override fun toString() = "ContextIntakeCorrection([REDACTED])"
}

@Serializable
data class ContextIntakeLink(val intakeId: String, val intakeRevision: Int, val contextId: String, val contextRevision: Int,
    val sourceChanged: Boolean = false)

@Serializable
data class ContextIntakeAssociation(val expectedRevision: Int, val contextId: String, val contextRevision: Int) {
    init { require(expectedRevision in 1..1000 && contextRevision in 1..1000); validateContextId(contextId) }
}

interface ContextIntakeRepository {
    fun intakes(): List<ContextIntake>
    fun intakeHistory(id: String): List<ContextIntake>
    fun createIntake(request: ContextIntakeRequest, at: Instant): ContextIntake
    fun correctIntake(id: String, request: ContextIntakeCorrection, at: Instant): ContextIntake
    fun intakeLinks(id: String): List<ContextIntakeLink>
    fun associateIntake(id: String, request: ContextIntakeAssociation): ContextIntakeLink
    fun confirmIntake(id: String, request: ContextConfirmationRequest, at: Instant): ContextConfirmationResult
}

class ContextIntakeService(private val repository: ContextIntakeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun list() = repository.intakes()
    fun history(id: String) = repository.intakeHistory(id)
    fun create(request: ContextIntakeRequest) = repository.createIntake(request, clock.instant())
    fun correct(id: String, request: ContextIntakeCorrection) = repository.correctIntake(id, request, clock.instant())
    fun links(id: String) = repository.intakeLinks(id)
    fun associate(id: String, request: ContextIntakeAssociation) = repository.associateIntake(id, request)
    fun confirm(id: String, request: ContextConfirmationRequest) = repository.confirmIntake(id,request,clock.instant())
}

class ContextIntakeNotFound : IllegalStateException("Context intake not found")
class ContextIntakeConflict : IllegalStateException("Context intake or linked context changed")
