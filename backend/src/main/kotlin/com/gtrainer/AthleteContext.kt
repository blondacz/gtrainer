package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.math.BigDecimal

@Serializable
data class AthleteContext(val contextId: String, val revision: Int, val category: String, val sourceCategory: String, val authorAttribution: String,
    val enteredBy: String, val observedOn: String, val applicableFrom: String? = null, val applicableUntil: String? = null,
    val sport: String? = null, val activityId: String? = null, val reviewId: String? = null, val content: String,
    val retired: Boolean = false, val restrictionKind: String? = null, val restrictionValue: String? = null, val restrictionUnit: String? = null) {
    override fun toString() = "AthleteContext([REDACTED])"
}

@Serializable
data class AthleteContextRequest(val category: String, val sourceCategory: String, val authorAttribution: String, val observedOn: String,
    val applicableFrom: String? = null, val applicableUntil: String? = null, val sport: String? = null,
    val activityId: String? = null, val reviewId: String? = null, val content: String,
    val restrictionKind: String? = null, val restrictionValue: String? = null, val restrictionUnit: String? = null) {
    init {
        require(category in setOf("restriction", "symptom", "goal", "preference", "feedback", "note"))
        require(sourceCategory in setOf("user_report", "clinician_guidance", "coach_guidance", "review_feedback"))
        validateContextText(authorAttribution, 160, true); validateContextText(content, 4000, true)
        validateContextDate(observedOn); applicableFrom?.let(::validateContextDate); applicableUntil?.let(::validateContextDate)
        require(applicableFrom == null || applicableUntil == null || applicableUntil >= applicableFrom)
        if (category == "restriction") {
            require(restrictionKind in setOf("blocked_activity", "blocked_sport", "allowed_activity", "allowed_sport", "maximum_duration"))
            require(!restrictionValue.isNullOrBlank())
            validateContextText(restrictionValue, 80, true)
            if (restrictionKind == "maximum_duration") {
                require(restrictionValue.toDoubleOrNull()?.let { it > 0 && it.isFinite() } == true && restrictionUnit == "minutes")
            } else require(restrictionUnit == null)
        } else require(restrictionKind == null && restrictionValue == null && restrictionUnit == null)
        sport?.let { validateContextText(it, 80, true) }; activityId?.let { validateContextText(it, 128, true) }
        reviewId?.let { validateContextText(it, 128, true) }
    }
    override fun toString() = "AthleteContextRequest([REDACTED])"
}

data class ContextRetrievalQuery(val asOf: LocalDate, val sport: String? = null, val activityId: String? = null,
    val reviewId: String? = null, val optionalLimit: Int = 20, val hardPacketLimit: Int? = null)
@Serializable
data class ContextRestrictionConflict(val first: ContextRevisionRefV1, val second: ContextRevisionRefV1, val reason: String)
@Serializable
data class ContextRetrievalResult(val mandatoryRestrictions: List<AthleteContext>, val optionalContexts: List<AthleteContext>,
    val mandatoryOverflow: Boolean, val hasUnstructuredRestrictions: Boolean, val conflicts: List<ContextRestrictionConflict>,
    val blockedReasons: List<String>, val reviewBlocked: Boolean) {
    override fun toString() = "ContextRetrievalResult([REDACTED])"
}

private fun restrictionConflict(a: AthleteContext, b: AthleteContext): String? {
    fun overlaps(left: String?, right: String?) = left == null || right == null || left.equals(right, ignoreCase = true)
    val firstStarts = a.applicableFrom?.let(LocalDate::parse)
    val firstEnds = a.applicableUntil?.let(LocalDate::parse)
    val secondStarts = b.applicableFrom?.let(LocalDate::parse)
    val secondEnds = b.applicableUntil?.let(LocalDate::parse)
    if (firstEnds != null && secondStarts != null && firstEnds < secondStarts ||
        secondEnds != null && firstStarts != null && secondEnds < firstStarts ||
        !overlaps(a.sport, b.sport) || !overlaps(a.activityId, b.activityId) || !overlaps(a.reviewId, b.reviewId)) return null
    val valueA = a.restrictionValue?.trim()?.lowercase() ?: return null
    val valueB = b.restrictionValue?.trim()?.lowercase() ?: return null
    val pair = setOf(a.restrictionKind, b.restrictionKind)
    if ((pair == setOf("blocked_activity", "allowed_activity") || pair == setOf("blocked_sport", "allowed_sport")) && valueA == valueB)
        return "blocked_and_allowed"
    if (a.restrictionKind == "maximum_duration" && b.restrictionKind == "maximum_duration" &&
        runCatching { BigDecimal(valueA).compareTo(BigDecimal(valueB)) != 0 }.getOrDefault(false))
        return "different_maximum_duration"
    return null
}

internal fun contextRestrictionConflicts(entries: List<AthleteContext>): List<ContextRestrictionConflict> = buildList {
    for (i in entries.indices) for (j in i + 1 until entries.size) {
        val first = entries[i]
        val second = entries[j]
        val conflicts = restrictionConflict(first, second)
        if (conflicts != null) add(ContextRestrictionConflict(ContextRevisionRefV1(first.contextId, first.revision),
            ContextRevisionRefV1(second.contextId, second.revision), conflicts))
    }
}.sortedWith(compareBy({ it.first.contextId }, { it.second.contextId }, { it.reason }))

fun retrieveAthleteContext(all: List<AthleteContext>, query: ContextRetrievalQuery): ContextRetrievalResult {
    require(query.optionalLimit in 0..1000 && (query.hardPacketLimit == null || query.hardPacketLimit >= 0))
    val applicable = all.groupBy { it.contextId }.values.mapNotNull { revisions -> revisions.maxByOrNull { it.revision } }
        .filter { c -> !c.retired && LocalDate.parse(c.observedOn) <= query.asOf &&
            (c.applicableFrom == null || LocalDate.parse(c.applicableFrom) <= query.asOf) &&
            (c.applicableUntil == null || LocalDate.parse(c.applicableUntil) >= query.asOf) &&
            (query.sport == null || c.sport == null || c.sport == query.sport) &&
            (c.activityId == null || c.activityId == query.activityId) &&
            (c.reviewId == null || c.reviewId == query.reviewId) }
    val order = compareByDescending<AthleteContext> { it.observedOn }.thenBy { it.contextId }.thenBy { it.revision }
    val mandatory = applicable.filter { it.category == "restriction" }.sortedWith(order)
    val optional = applicable.filter { it.category != "restriction" }.sortedWith(order).take(query.optionalLimit)
    val overflow = query.hardPacketLimit?.let { mandatory.size > it } ?: false
    val unstructured = mandatory.any { it.restrictionKind == null || it.restrictionValue.isNullOrBlank() }
    val conflicts = contextRestrictionConflicts(mandatory)
    val blocked = buildList {
        if (query.hardPacketLimit == null) add("packet_budget_unconfigured")
        if (overflow) add("mandatory_context_overflow")
        if (unstructured) add("unstructured_restriction")
        if (conflicts.isNotEmpty()) add("restriction_conflict")
    }
    return ContextRetrievalResult(mandatory, optional, overflow, unstructured, conflicts, blocked, blocked.isNotEmpty())
}

interface AthleteContextRepository {
    fun contexts(today: LocalDate): List<AthleteContext>
    fun contextRevisions(id: String): List<AthleteContext>
    fun retrieveContexts(query: ContextRetrievalQuery): ContextRetrievalResult
    fun createContext(request: AthleteContextRequest): AthleteContext
    fun correctContext(id: String, request: AthleteContextRequest): AthleteContext
    fun retireContext(id: String): Boolean
    fun deleteContext(id: String): Boolean
    fun recordReviewFeedback(snapshotId: String, request: ReviewFeedbackRequest, observedOn: LocalDate): AthleteContext
}

class AthleteContextService(private val repository: AthleteContextRepository, private val today: () -> LocalDate = LocalDate::now) {
    fun list() = repository.contexts(today())
    fun history(id: String) = repository.contextRevisions(id)
    fun retrieve(query: ContextRetrievalQuery) = repository.retrieveContexts(query)
    fun create(request: AthleteContextRequest) = repository.createContext(request)
    fun correct(id: String, request: AthleteContextRequest) = repository.correctContext(id, request)
    fun retire(id: String) = repository.retireContext(id)
    fun delete(id: String) = repository.deleteContext(id)
    fun recordReviewFeedback(snapshotId: String, request: ReviewFeedbackRequest) =
        repository.recordReviewFeedback(snapshotId, request, today())
}

class ContextNotFound : IllegalStateException("Context not found")
fun validateContextId(id: String) { require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)) { "Invalid context identifier" } }
private fun validateContextDate(value: String) {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "Invalid context date" }
    try { LocalDate.parse(value) } catch (_: DateTimeParseException) { throw IllegalArgumentException("Invalid context date") }
}
internal fun validateContextText(value: String, limit: Int, required: Boolean) {
    require(value.length <= limit && (!required || value.trim().isNotEmpty())) { "Invalid context text" }
    require(value.none { Character.isISOControl(it) && it != '\n' && it != '\t' }) { "Invalid context text" }
}
