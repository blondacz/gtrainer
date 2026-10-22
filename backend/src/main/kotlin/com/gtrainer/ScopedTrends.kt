package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Same exact source tuple encoding used by legacy activity routing. */
internal fun scopedActivityId(record: ActivityRecord): String = scopedActivityId(record.source, record.sourceRecordId)
internal fun scopedActivityId(source: String, id: String): String = AnalysisClaims.hash(Json.encodeToString(listOf(source, id)))

@Serializable
data class ScopedSessionMetric(val key: String, val label: String, val value: Double?, val unit: String,
                               val basis: String, val upstreamSource: String?) {
    override fun toString() = "ScopedSessionMetric([REDACTED])"
}

@Serializable
data class ScopedSessionFacts(val activityId: String, val record: ActivityRecord, val metrics: List<ScopedSessionMetric>) {
    override fun toString() = "ScopedSessionFacts([REDACTED])"
}

@Serializable
data class ScopedComparedValue(val activityId: String, val value: Double?) {
    override fun toString() = "ScopedComparedValue([REDACTED])"
}

/** Side-by-side subjects, not an invented reference workout or all-pairs expansion. */
@Serializable
data class ScopedSessionComparison(val key: String, val label: String, val unit: String, val basis: String,
                                   val sport: String?, val values: List<ScopedComparedValue>, val flags: List<String>) {
    override fun toString() = "ScopedSessionComparison([REDACTED])"
}

@Serializable
data class ScopedWellnessContext(val record: WellnessRecord, val activityIds: List<String>, val usage: String,
                                 val flags: List<String>) {
    override fun toString() = "ScopedWellnessContext([REDACTED])"
}

@Serializable
data class ScopedTrendReport(
    val schemaVersion: Int = 1, val scope: ResolvedReviewScope,
    val selected: PeriodTrends?, val comparisonFull: PeriodTrends?, val priorCalendarFull: PeriodTrends?,
    val comparableSelected: PeriodTrends?, val comparablePrevious: PeriodTrends?,
    val comparisons: List<MetricComparison>, val sessions: List<ScopedSessionFacts>,
    val sessionComparisons: List<ScopedSessionComparison>, val wellnessContext: List<ScopedWellnessContext>,
    val sourceStatus: List<CategoryStatus>, val unavailable: List<UnavailableMetric>,
    val limitations: List<String>,
    val factualReview: FactualReview? = null,
) { override fun toString() = "ScopedTrendReport([REDACTED])" }

/** Factual preparation only. This report is not a legacy inference packet or an immutable snapshot. */
internal object ScopedTrends {
    fun report(scope: ResolvedReviewScope, history: HistoryResponse, statuses: List<CategoryStatus>): ScopedTrendReport {
        require(history.activities.size.toLong() + history.wellness.size <= 50_000)
        val available = scope.status == "available"
        val cutoff = java.time.Instant.parse(scope.asOf)
        val pastActivities = history.activities.filter { it.startInstant?.let { instant -> java.time.Instant.parse(instant) <= cutoff } != false }
        val excludedFutureActivities = history.activities.size - pastActivities.size
        val selectedRecords = if (available && scope.request.mode == "activities") {
            val byId = pastActivities.associateBy(::scopedActivityId)
            scope.activityIds.map { byId[it] ?: throw IllegalArgumentException("Selected activity no longer exists") }
        } else emptyList()
        val sessions = selectedRecords.map { ScopedSessionFacts(scopedActivityId(it), it, sessionMetrics(it)) }
        val sourceDates = scope.activityDates.toSet()
        val scheduleDate = java.time.Instant.parse(scope.asOf).atZone(java.time.ZoneId.of(scope.timeZone)).toLocalDate().toString()
        val wellness = if (available && scope.wellnessPolicy != "off") history.wellness.filter {
            it.date <= scheduleDate && (scope.request.mode != "activities" || it.date in sourceDates)
        } else emptyList()
        val context = if (scope.request.mode == "activities") wellness.sortedWith(compareBy({ it.date }, { it.source }, { it.sourceRecordId })).map { record ->
            ScopedWellnessContext(record, sessions.filter { it.record.startLocal.take(10) == record.date }.map { it.activityId },
                scope.wellnessPolicy, listOf("source_date_only", "prior_night_mapping_unverified", "pre_activity_availability_unverified"))
        } else emptyList()
        val data = history.copy(activities = pastActivities, wellness = wellness)
        val sports = (if (scope.request.mode == "activities") selectedRecords else pastActivities)
            .map { it.sport }.distinct().sorted().let { scope.request.sport?.let(::listOf) ?: it }
        fun period(range: ScopedDateRange?): PeriodTrends? {
            if (!available || range == null || scope.request.mode != "period") return null
            val result = Trends.period(data, range.range(), sports, scope.request.sport)
            return if (scope.wellnessPolicy != "off") result else result.copy(wellness = emptyList(),
                flags = result.flags.filterNot { it in setOf("no_wellness_records", "incomplete_wellness_date_coverage") } + "optional_wellness_excluded")
        }
        val selected = period(scope.evidenceRange)
        val before = period(scope.comparison.comparablePrevious)
        val after = period(scope.comparison.comparableSelected)
        val comparisons = if (after == null || before == null) emptyList() else
            after.sports.zip(before.sports).flatMap { (a, b) -> a.metrics.zip(b.metrics).map { (now, prior) ->
                Trends.comparison(now, prior, a.sport)
            } } + if (scope.wellnessPolicy == "assessment") after.wellness.zip(before.wellness).map { (now, prior) ->
                Trends.comparison(now, prior, null).let { it.copy(flags = it.flags + listOf(
                    "descriptive_per_record_arithmetic", "personal_signal_compatibility_not_established")) }
            } else emptyList()
        // Code-owned factual rules operate on the resolved comparable pair, never full selected totals.
        val factual = if (after == null || before == null) null else {
            val input = Trends.analysisInput(TrendReport(scheduleDate, scope.request.sport, sports,
                after, before, comparisons, statuses, emptyList(), scope.dateBasis))
            val keys = comparisons.map { it.sport to it.key }.toSet()
            FactualReviews.prepare(input.copy(facts = input.facts.filter { (it.sport to it.metric) in keys }), scope.request.focus)
        }
        return ScopedTrendReport(scope = scope, selected = selected, comparisonFull = period(scope.comparison.comparisonFull),
            priorCalendarFull = period(scope.comparison.priorCalendarFull), comparableSelected = after, comparablePrevious = before,
            comparisons = comparisons, sessions = sessions,
            sessionComparisons = if (scope.request.intent == "compare") sessionComparisons(sessions) else emptyList(),
            wellnessContext = context, sourceStatus = statuses, factualReview = factual,
            unavailable = listOf(
                UnavailableMetric("fitnessAge", "Garmin fitness age", "Not supplied by the selected source; not estimated."),
                UnavailableMetric("enduranceScore", "Garmin endurance score", "Not supplied by the selected source; not estimated."),
                UnavailableMetric("trainingStatus", "Garmin training status", "Not supplied by the selected source; not estimated."),
                UnavailableMetric("trainingReadiness", "Garmin training readiness", "Not supplied by the current normalized adapter; not reconstructed from sleep or HRV."),
                UnavailableMetric("precedingSleep", "Verified preceding sleep", "Source-date sleep is available where imported, but preceding-night association is unverified."),
                UnavailableMetric("activityStreams", "Laps, power and heart-rate streams", "Not supplied by the current adapter; average heart rate is not interval intensity.")),
            limitations = listOf(
                scope.dateBasis,
                "Factual report only: no model call, source sync, saved review, baseline rule or plan amendment is performed.",
                "Period wellness means preserve legacy per-record arithmetic, not compatible per-day personal baselines. Missing imports are unknown, not zero or skipped training.",
                "Side-by-side session values are not an assessment of equivalent effort or training effect. Mixed-sport duration is elapsed/moving time only.",
                "Displayed wellness is distinct from assessment context and compared subjects; none of these controls authorizes inference or hosted transfer.",
                "Sleep, HRV and readiness alone do not establish cause, recovery, readiness for sport or medical safety.",
                "Excluded $excludedFutureActivities imported activities with verified start instants after the captured cutoff; their source dates were not rewritten."))
    }

    private fun sessionMetrics(record: ActivityRecord): List<ScopedSessionMetric> {
        fun metric(key: String, label: String, unit: String, basis: String, value: Measurement?) = ScopedSessionMetric(
            key, label, value?.takeIf { it.unit == unit && it.value.isFinite() && it.value >= 0 }?.value, unit, basis, value?.upstreamSource)
        return listOf(
            metric("movingTime", "Moving duration", "seconds", "moving", record.movingTime),
            metric("elapsedTime", "Elapsed duration", "seconds", "elapsed", record.elapsedTime),
            metric("distance", "Distance", "metres", "distance", record.distance),
            metric("calories", "Recorded calories", "kcal", "source_calories", record.calories),
            metric("averageHeartRate", "Average heart rate", "beats/minute", "session_average", record.averageHeartRate),
            metric("intervalsTrainingLoad", "Intervals.icu activity load", "Intervals.icu load", "intervals_activity_load", record.intervalsTrainingLoad))
    }

    private fun sessionComparisons(sessions: List<ScopedSessionFacts>): List<ScopedSessionComparison> {
        if (sessions.size < 2) return emptyList()
        // No quadratic pairs and no arbitrary first-session baseline. All explicitly selected subjects stay visible.
        val durations = listOf("movingTime", "elapsedTime").map { key ->
            val first = sessions.first().metrics.single { it.key == key }
            ScopedSessionComparison(key, first.label, first.unit, first.basis, null,
                sessions.map { ScopedComparedValue(it.activityId, it.metrics.single { metric -> metric.key == key }.value) },
                listOf("physical_duration_not_effort", "missing_values_are_unknown"))
        }
        val other = sessions.groupBy { it.record.sport }.flatMap { (sport, group) ->
            if (group.size < 2) emptyList() else listOf("distance").map { key ->
                val first = group.first().metrics.single { it.key == key }
                ScopedSessionComparison(key, first.label, first.unit, first.basis, sport,
                    group.map { ScopedComparedValue(it.activityId, it.metrics.single { metric -> metric.key == key }.value) },
                    listOf("same_sport_measurement_not_training_effect", "missing_values_are_unknown"))
            }
        }
        return durations + other
    }
}
