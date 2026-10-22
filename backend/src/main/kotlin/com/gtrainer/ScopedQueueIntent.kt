package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Content-free routing metadata. The primary captures the actual resolved scope at enqueue. */
@Serializable
data class ScopedQueueIntent(
    val profile: String,
    val purpose: String,
    val configurationVersion: Long,
    val scope: ResolvedReviewScope,
    val sourceRevision: Long,
    val planReasonRevision: Long,
    val providerSelection: ConnectedReviewProviderSelection? = null,
    val adaptation: AdaptationQueuePayload? = null,
) {
    init {
        require(profile == PROFILE)
        require(purpose in PURPOSES)
        require((purpose == "adaptation") == (adaptation != null))
        require(configurationVersion >= 0 && sourceRevision >= 0 && planReasonRevision >= 0)
        require(scope.status == "available")
        if (adaptation != null) {
            require(providerSelection == null)
            require(scope.request.mode == "period" && scope.request.intent == "review")
            require(scope.timeZone == adaptation.family.timeZone)
        }
        require(scope.request.mode in setOf("period", "activities") && scope.request.intent in setOf("review", "compare"))
        require(scope.wellnessPolicy in setOf("off", "display_only", "assessment"))
        require(scope.activityIds.size <= 1000 && scope.activityIds.distinct().size == scope.activityIds.size)
        require(scope.activityIds.all { it.matches(Regex("[0-9a-f]{64}")) })
        require(scope.request.activityIds == scope.activityIds)
        require(scope.activityDates.size <= 1000 && scope.activityDates.distinct().size <= 366)
        val zone = ZoneId.of(scope.timeZone)
        val cutoff = Instant.parse(scope.asOf)
        require(cutoff.atZone(zone).year in 1..9999)
        scope.evidenceRange?.range()
        scope.fullCalendarRange?.range()
        scope.comparison.comparableSelected?.range()
        scope.comparison.comparablePrevious?.range()
        scope.comparison.priorCalendarFull?.range()
        scope.comparison.comparisonFull?.range()
        (scope.activityDates + scope.comparison.unmatchedSelectedDates + scope.comparison.unmatchedPreviousDates + scope.comparison.priorCalendarRemainderDates)
            .forEach { require(LocalDate.parse(it).year in 1..9999) }
        if (scope.activityDates.isNotEmpty()) {
            val dates = scope.activityDates.map(LocalDate::parse)
            require(java.time.temporal.ChronoUnit.DAYS.between(dates.min(), dates.max()) <= 365)
        }
        require(scope.activityIds.isNotEmpty() || scope.request.mode != "activities")
        require(scope.request.mode != "activities" || scope.activityIds.size == scope.activityDates.size)
        if (purpose in setOf("morning", "evening")) {
            require(scope.request.mode == "period" && scope.request.scope == "day" && scope.request.intent == "review" && scope.wellnessPolicy == "assessment")
        }
        if (scope.request.mode == "period") {
            require(ReviewScopeResolver.resolve(scope.request, cutoff, scope.timeZone) == scope)
        }
    }

    override fun toString() = "ScopedQueueIntent([REDACTED])"

    companion object {
        const val PROFILE = "scoped-descriptive-v1"
        val PURPOSES = setOf("morning", "evening", "activity_reflection", "period_reflection", "adaptation")
    }
}

/** Cutoff is part of the selection identity; source/reason revisions are deliberately coalescible. */
fun scopedQueueKey(intent: ScopedQueueIntent): String {
    intent.adaptation?.let { payload ->
        return AnalysisClaims.hash(ScopedEvidencePreparation.json.encodeToString(
            listOf(intent.profile, "adaptation", payload.family.key())))
    }
    val scope = intent.scope
    val key = ScopedQueueKey(intent.profile, intent.purpose, scope.request, scope.asOf, scope.timeZone,
        scope.anchorDate, scope.wellnessPolicy, scope.status, scope.evidenceRange, scope.fullCalendarRange,
        scope.activityIds, scope.activityDates, scope.comparison, intent.configurationVersion, intent.providerSelection)
    return AnalysisClaims.hash(ScopedEvidencePreparation.json.encodeToString(key))
}

@Serializable
private data class ScopedQueueKey(
    val profile: String, val purpose: String, val request: ScopedReviewRequest, val asOf: String,
    val timeZone: String, val anchorDate: String, val wellnessPolicy: String, val status: String,
    val evidenceRange: ScopedDateRange?, val fullCalendarRange: ScopedDateRange?, val activityIds: List<String>,
    val activityDates: List<String>, val comparison: ScopedComparisonWindows, val configurationVersion: Long,
    val providerSelection: ConnectedReviewProviderSelection?,
)
