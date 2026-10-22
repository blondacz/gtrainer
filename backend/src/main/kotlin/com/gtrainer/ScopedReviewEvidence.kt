package com.gtrainer

import kotlinx.serialization.Serializable

/** Whitelisted facts only. Charts, raw records and display-dependent report digests do not belong here. */
@Serializable
data class ScopedSessionEvidence(
    val evidenceId: String,
    val activityId: String,
    val source: String,
    val sourceRecordId: String,
    val sport: String,
    val startLocal: String,
    val startInstant: String?,
    val timeZone: String?,
    val utcOffset: String?,
    val sourceTimeZoneLabel: String?,
    val timeContext: String,
    val recordOrigins: List<String>,
    val received: ScopedImportProvenance,
    val metrics: List<ScopedSessionMetric>,
) { override fun toString() = "ScopedSessionEvidence([REDACTED])" }

@Serializable
data class ScopedAggregateEvidence(
    val evidenceId: String,
    val period: String,
    val oldest: String,
    val newest: String,
    val sport: String?,
    val key: String,
    val unit: String,
    val aggregation: String,
    val value: Double?,
    val sampleCount: Int,
    val observedDays: Int,
    val periodDays: Int,
    val missingRecordValues: Int,
    val rejectedValues: Int,
    val sources: List<String>,
    val metricOrigins: List<String>,
    val unknownMetricOriginCount: Int,
    val limitation: String,
) { override fun toString() = "ScopedAggregateEvidence([REDACTED])" }

@Serializable
data class ScopedEvidenceFacts(
    val schemaVersion: Int = 1,
    val scope: ResolvedReviewScope,
    val sessions: List<ScopedSessionEvidence>,
    val aggregates: List<ScopedAggregateEvidence>,
    val wellness: List<ScopedWellnessObservation>,
    val sessionComparisons: List<ScopedSessionComparison>,
    val periodComparisons: List<MetricComparison>,
    val sourceStatus: List<CategoryStatus>,
    val unavailable: List<UnavailableMetric>,
    val limitations: List<String>,
) { override fun toString() = "ScopedEvidenceFacts([REDACTED])" }

/** Pure projection from exactly one existing factual report; never recalculates period arithmetic. */
internal object ScopedReviewEvidence {
    /** Projected inspection membership, not the broader inputs used by the ordinary factual charts. */
    fun membership(captured: CapturedScopedRead, includeDisplayOnly: Boolean): HistoryResponse {
        val scope=captured.scope
        if(scope.status!="available") return HistoryResponse(emptyList(),emptyList())
        val compare=scope.request.intent=="compare"
        val ranges=listOfNotNull(scope.evidenceRange)+if(compare) listOfNotNull(scope.comparison.comparableSelected,
            scope.comparison.comparablePrevious,scope.comparison.comparisonFull,scope.comparison.priorCalendarFull) else emptyList()
        fun dateEligible(date: String)=if(scope.request.mode=="activities") date in scope.activityDates else ranges.any { date>=it.oldest && date<=it.newest }
        val cutoff=java.time.Instant.parse(scope.asOf)
        val today=cutoff.atZone(java.time.ZoneId.of(scope.timeZone)).toLocalDate().toString()
        val activities=captured.history.activities.filter { record ->
            (record.startInstant?.let { java.time.Instant.parse(it)<=cutoff } != false) &&
                if(scope.request.mode=="activities") scopedActivityId(record) in scope.activityIds else
                    dateEligible(record.startLocal.take(10)) && (scope.request.sport==null || scope.request.sport==record.sport)
        }
        val wellness=if(scope.wellnessPolicy=="assessment" || includeDisplayOnly && scope.wellnessPolicy=="display_only")
            captured.history.wellness.filter { it.date<=today && dateEligible(it.date) } else emptyList()
        return HistoryResponse(activities,wellness)
    }

    fun assemble(captured: CapturedScopedRead, report: ScopedTrendReport): ScopedEvidenceFacts {
        require(report.scope == captured.scope)
        val scope = report.scope
        val compare = scope.request.intent == "compare"
        val assessment = scope.wellnessPolicy == "assessment"
        val receipts = captured.provenance.associateBy { Triple(it.category, it.source, it.sourceRecordId) }
        val sessions = report.sessions.map { facts ->
            val record = facts.record
            ScopedSessionEvidence("session/${facts.activityId}", facts.activityId, record.source, record.sourceRecordId,
                record.sport, record.startLocal, record.startInstant, record.timeZone, record.utcOffset,
                record.sourceTimeZoneLabel, record.timeContext, record.upstreamSources.sorted(),
                receipts[Triple("activities", record.source, record.sourceRecordId)] ?:
                    ScopedImportProvenance("activities", record.source, record.sourceRecordId, null, null), facts.metrics)
        }
        fun aggregates(period: PeriodTrends?, role: String): List<ScopedAggregateEvidence> {
            if (period == null) return emptyList()
            fun fact(metric: MetricSeries, sport: String?) = ScopedAggregateEvidence(
                "$role/${sport ?: "wellness"}/${metric.key}", role, period.oldest, period.newest, sport,
                metric.key, metric.unit, metric.aggregation, metric.value, metric.sampleCount, metric.observedDays,
                metric.periodDays, metric.missingRecordValues, metric.rejectedValues,
                metric.points.map { it.source }.distinct().sorted(),
                metric.points.mapNotNull { it.upstreamSource }.distinct().sorted(),
                metric.points.count { it.upstreamSource == null },
                if (sport == null) "Descriptive per-record arithmetic; not a compatible personal baseline or causal finding."
                else "Observed imported values only; missing records are unknown, not zero or skipped training.")
            return period.sports.flatMap { sport -> sport.metrics.map { fact(it, sport.sport) } } +
                if (assessment) period.wellness.map { fact(it, null) } else emptyList()
        }
        val aggregates = aggregates(report.selected, "selected") + if (compare) {
            aggregates(report.comparableSelected, "comparable_selected") +
                aggregates(report.comparablePrevious, "comparable_previous") +
                aggregates(report.priorCalendarFull, "prior_calendar_full") +
                aggregates(report.comparisonFull, "comparison_full")
        } else emptyList()
        return ScopedEvidenceFacts(scope = scope, sessions = sessions, aggregates = aggregates,
            wellness = if (assessment && scope.status == "available") {
                val ranges = listOfNotNull(scope.evidenceRange) + if (compare)
                    listOfNotNull(scope.comparison.comparableSelected, scope.comparison.comparablePrevious,
                        scope.comparison.priorCalendarFull, scope.comparison.comparisonFull) else emptyList()
                val today = java.time.Instant.parse(scope.asOf).atZone(java.time.ZoneId.of(scope.timeZone)).toLocalDate().toString()
                val selected = captured.history.wellness.filter { record -> record.date <= today &&
                    if (scope.request.mode == "activities") record.date in scope.activityDates else
                        ranges.any { record.date >= it.oldest && record.date <= it.newest } }
                ScopedWellnessEvidence.raw(selected, captured.provenance,
                    sessions.groupBy { it.startLocal.take(10) }.mapValues { (_, rows) -> rows.map { it.activityId } })
            } else emptyList(),
            sessionComparisons = if (compare) report.sessionComparisons else emptyList(),
            periodComparisons = if (compare) report.comparisons.filter { assessment || it.sport != null } else emptyList(),
            sourceStatus = report.sourceStatus.filter { it.category == "activities" || assessment && it.category == "wellness" },
            unavailable = report.unavailable,
            limitations = listOf(
                scope.dateBasis,
                "Factual evidence preparation only: no inference, transfer, source sync or plan mutation.",
                "Source receipt time is the latest changed import, not the observation time or proof of pre-session availability.",
                "Read status and observed coverage do not establish upstream completeness or freshness.",
                "Session comparison uses only explicitly selected identities and compatible physical measurements, not equivalent effort.",
                "Source-date wellness does not establish preceding sleep; readiness and streams are not reconstructed.",
                "Sleep, HRV and readiness alone cannot establish cause, recovery, sport clearance or medical safety."))
    }
}
