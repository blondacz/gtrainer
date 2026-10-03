package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class TrendRange(val oldest: LocalDate, val newest: LocalDate) {
    val days: Int = (ChronoUnit.DAYS.between(oldest, newest) + 1).toInt()
    init {
        require(oldest <= newest && ChronoUnit.DAYS.between(oldest, newest) < 366)
        require(oldest.year in 1..9999 && newest.year in 1..9999)
    }
    fun previous(): TrendRange = TrendRange(oldest.minusDays(days.toLong()), oldest.minusDays(1))
}

class TrendSizeLimit : IllegalStateException("Choose a shorter trend range; input exceeds the record budget")

@Serializable
data class ObservedValue(
    val source: String, val sourceRecordId: String, val category: String, val field: String,
    val date: String, val value: Double, val unit: String, val upstreamSource: String?,
    val recordOrigins: List<String>, val timeContext: String, val timeZone: String?, val startInstant: String?,
) { override fun toString(): String = "ObservedValue([REDACTED])" }

@Serializable
data class MetricSeries(
    val key: String, val label: String, val unit: String, val aggregation: String,
    val value: Double?, val sampleCount: Int, val observedDays: Int, val periodDays: Int,
    val missingRecordValues: Int, val rejectedValues: Int, val points: List<ObservedValue>,
) { override fun toString(): String = "MetricSeries([REDACTED])" }

@Serializable
data class SportTrends(val sport: String, val metrics: List<MetricSeries>) {
    override fun toString(): String = "SportTrends([REDACTED])"
}

@Serializable
data class PeriodTrends(
    val oldest: String, val newest: String, val days: Int, val activityRecords: Int, val wellnessRecords: Int,
    val sports: List<SportTrends>, val wellness: List<MetricSeries>, val flags: List<String>,
) { override fun toString(): String = "PeriodTrends([REDACTED])" }

@Serializable
data class MetricComparison(
    val sport: String?, val key: String, val label: String, val unit: String,
    val currentValue: Double?, val previousValue: Double?, val absoluteChange: Double?, val percentChange: Double?,
    val currentSamples: Int, val previousSamples: Int, val flags: List<String>,
) { override fun toString(): String = "MetricComparison([REDACTED])" }

@Serializable
data class UnavailableMetric(val key: String, val label: String, val reason: String)

@Serializable
data class TrendReport(
    val evaluatedOnUtc: String, val selectedSport: String?, val availableSports: List<String>,
    val current: PeriodTrends, val previous: PeriodTrends, val comparisons: List<MetricComparison>,
    val sourceStatus: List<CategoryStatus>, val unavailable: List<UnavailableMetric>,
    val dateBasis: String = "Activities use their preserved source-local start date; wellness uses its source date.",
) { override fun toString(): String = "TrendReport([REDACTED])" }

@Serializable
data class SummaryFact(
    val evidenceId: String, val period: String, val oldest: String, val newest: String, val sport: String?,
    val metric: String, val label: String, val unit: String, val aggregation: String, val value: Double?,
    val sampleCount: Int, val observedDays: Int, val periodDays: Int, val firstObservedDate: String?,
    val lastObservedDate: String?, val sources: List<String>, val metricOrigins: List<String>,
    val recordOrigins: List<String>, val unknownMetricOriginCount: Int, val flags: List<String>,
) { override fun toString(): String = "SummaryFact([REDACTED])" }

@Serializable
data class AnalysisInput(
    val schemaVersion: Int, val evidenceReportSha256: String, val evaluatedOnUtc: String, val selectedSport: String?,
    val facts: List<SummaryFact>, val comparisons: List<MetricComparison>, val sourceStatus: List<CategoryStatus>,
    val unavailable: List<UnavailableMetric>, val limitations: List<String>,
) { override fun toString(): String = "AnalysisInput([REDACTED])" }

/** Pure descriptive arithmetic over normalized records. Never calls a source or model. */
internal object Trends {
    private val reportJson = Json { encodeDefaults = true }
    private data class Definition(val key: String, val label: String, val unit: String, val aggregation: String)
    private val activityDefinitions = listOf(
        Definition("activityCount", "Imported activities", "count", "sum"),
        Definition("movingTime", "Recorded activity time (moving)", "seconds", "sum"),
        Definition("elapsedTime", "Recorded activity time (elapsed)", "seconds", "sum"),
        Definition("calories", "Recorded activity calories", "kcal", "sum"),
        Definition("intervalsTrainingLoad", "Intervals.icu activity load", "Intervals.icu load", "sum"),
    )
    private val wellnessDefinitions = listOf(
        Definition("weight", "Weight", "kg", "mean"),
        Definition("vo2max", "VO2 max", "mL/kg/min", "mean"),
        Definition("hrv", "HRV", "ms", "mean"),
        Definition("sleepSecs", "Sleep duration", "seconds", "mean"),
        Definition("hrvSDNN", "HRV SDNN", "ms", "mean"),
        Definition("bodyFat", "Body fat", "percent", "mean"),
        Definition("restingHR", "Resting heart rate", "beats/minute", "mean"),
        Definition("sleepScore", "Source sleep score", "source sleep score", "mean"),
        Definition("steps", "Steps", "count", "mean"),
        Definition("atl", "Intervals.icu ATL", "Intervals.icu load", "mean"),
        Definition("ctl", "Intervals.icu CTL", "Intervals.icu load", "mean"),
    )
    private val unavailable = listOf(
        UnavailableMetric("fitnessAge", "Garmin fitness age", "Not supplied by the selected source; not estimated."),
        UnavailableMetric("enduranceScore", "Garmin endurance score", "Not supplied by the selected source; not estimated."),
        UnavailableMetric("trainingStatus", "Garmin training status", "Not supplied by the selected source; not estimated."),
    )

    fun report(history: HistoryResponse, range: TrendRange, sport: String?, today: LocalDate,
               statuses: List<CategoryStatus>, latestAllowedDate: LocalDate = today): TrendReport {
        require(range.newest <= latestAllowedDate)
        require(sport == null || sport.matches(Regex("[A-Za-z0-9 _-]{1,64}")))
        val previousRange = range.previous()
        val sports = history.activities.filter { it.startLocal.take(10) in previousRange.oldest.toString()..range.newest.toString() }
            .map { it.sport }.distinct().sorted()
        val selected = sport?.let { listOf(it) } ?: sports
        val current = period(history, range, selected, sport)
        val previous = period(history, previousRange, selected, sport)
        val comparisons = current.sports.zip(previous.sports).flatMap { (a, b) ->
            a.metrics.zip(b.metrics).map { (now, before) -> comparison(now, before, a.sport) }
        } + current.wellness.zip(previous.wellness).map { (now, before) -> comparison(now, before, null) }
        return TrendReport(today.toString(), sport, sports, current, previous, comparisons, statuses, unavailable)
    }

    private fun period(history: HistoryResponse, range: TrendRange, sports: List<String>, sport: String?): PeriodTrends {
        val dates = range.oldest.toString()..range.newest.toString()
        val activities = history.activities.filter { it.startLocal.take(10) in dates && (sport == null || it.sport == sport) }
            .sortedWith(compareBy({ it.startLocal }, { it.source }, { it.sourceRecordId }))
        val wellness = history.wellness.filter { it.date in dates }.sortedWith(compareBy({ it.date }, { it.source }, { it.sourceRecordId }))
        val activitiesBySport = activities.groupBy { it.sport }
        val bySport = sports.map { name ->
            val records = activitiesBySport[name].orEmpty()
            SportTrends(name, activityDefinitions.map { definition ->
                val candidates = records.mapNotNull { record ->
                    val measurement = when (definition.key) {
                        "activityCount" -> Measurement(1.0, "count")
                        "movingTime" -> record.movingTime
                        "elapsedTime" -> record.elapsedTime
                        "calories" -> record.calories
                        else -> record.intervalsTrainingLoad
                    }
                    measurement?.let { ObservedValue(record.source, record.sourceRecordId, "activities", definition.key,
                        record.startLocal.take(10), it.value, it.unit, it.upstreamSource, record.upstreamSources.sorted(),
                        record.timeContext, record.timeZone, record.startInstant) }
                }
                series(definition, candidates, records.size, range.days)
            })
        }
        val wellnessMetrics = wellnessDefinitions.map { definition ->
            val candidates = wellness.mapNotNull { record -> record.measurements[definition.key]?.let {
                ObservedValue(record.source, record.sourceRecordId, "wellness", definition.key, record.date,
                    it.value, it.unit, it.upstreamSource, record.upstreamSources.sorted(), "source_date", null, null)
            } }
            series(definition, candidates, wellness.size, range.days)
        }
        val flags = buildList {
            add("upstream_freshness_unknown")
            if (activities.isEmpty()) add("no_activity_records")
            if (wellness.isEmpty()) add("no_wellness_records")
            if (wellnessMetrics.any { it.observedDays < range.days }) add("incomplete_wellness_date_coverage")
            if (activities.any { it.startInstant == null }) add("uncertain_activity_time_context")
            val latest = (activities.map { it.startLocal.take(10) } + wellness.map { it.date }).maxOrNull()
            if (latest != null && ChronoUnit.DAYS.between(LocalDate.parse(latest), range.newest) > 7) add("selected_period_observed_gap_over_7_days")
        }
        return PeriodTrends(range.oldest.toString(), range.newest.toString(), range.days, activities.size,
            wellness.size, bySport, wellnessMetrics, flags)
    }

    private fun series(definition: Definition, candidates: List<ObservedValue>, recordCount: Int, days: Int): MetricSeries {
        val points = candidates.filter { it.unit == definition.unit && it.value.isFinite() && it.value >= 0 }
        val value = if (points.isEmpty()) { if (definition.key == "activityCount") 0.0 else null } else {
            val sum = points.sumOf { it.value }
            (if (definition.aggregation == "mean") sum / points.size else sum).takeIf { it.isFinite() }
        }
        return MetricSeries(definition.key, definition.label, definition.unit, definition.aggregation, value,
            points.size, points.map { it.date }.distinct().size, days, recordCount - candidates.size,
            candidates.size - points.size, points)
    }

    private fun comparison(current: MetricSeries, previous: MetricSeries, sport: String?): MetricComparison {
        val difference = if (current.value != null && previous.value != null) current.value - previous.value else null
        val percent = if (difference != null && previous.value != 0.0) difference / previous.value!! * 100 else null
        val flags = buildList {
            if (difference == null) add("unavailable_comparison")
            if (current.missingRecordValues + previous.missingRecordValues + current.rejectedValues + previous.rejectedValues > 0) add("partial_metric_coverage")
            if (current.sampleCount < 2 || previous.sampleCount < 2) add("sparse_comparison")
            if (previous.value == 0.0) add("zero_baseline_no_percentage")
            if (current.aggregation == "mean" && (current.observedDays < current.periodDays || previous.observedDays < previous.periodDays)) add("incomplete_date_coverage")
        }
        return MetricComparison(sport, current.key, current.label, current.unit, current.value, previous.value,
            difference?.takeIf { it.isFinite() }, percent?.takeIf { it.isFinite() }, current.sampleCount, previous.sampleCount, flags)
    }

    fun analysisInput(report: TrendReport): AnalysisInput {
        fun facts(period: PeriodTrends, name: String): List<SummaryFact> {
            fun fact(series: MetricSeries, sport: String?): SummaryFact {
                val points = series.points
                val flags = buildList {
                    addAll(period.flags)
                    if (series.value == null) add("metric_unavailable")
                    if (series.missingRecordValues > 0 || series.rejectedValues > 0) add("partial_metric_coverage")
                    if (series.sampleCount < 2) add("sparse_metric")
                    if (points.any { it.upstreamSource == null }) add("metric_origin_unknown")
                    if (points.map { it.source }.distinct().size > 1) add("multiple_record_sources")
                    if (points.mapNotNull { it.upstreamSource }.distinct().size > 1) add("mixed_metric_origins")
                    if (points.maxOfOrNull { it.date }?.let {
                            ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.parse(period.newest)) > 7
                        } == true) add("metric_observed_gap_over_7_days")
                    val category = if (series.key in activityDefinitions.map { it.key }) "activities" else "wellness"
                    if (report.sourceStatus.any { it.category == category && it.readStatus != "SUCCESS" }) add("latest_category_read_not_successful")
                }.distinct()
                return SummaryFact("$name/${sport ?: "wellness"}/${series.key}", name, period.oldest, period.newest,
                    sport, series.key, series.label, series.unit, series.aggregation, series.value, series.sampleCount,
                    series.observedDays, series.periodDays, points.minOfOrNull { it.date }, points.maxOfOrNull { it.date },
                    points.map { it.source }.distinct().sorted(), points.mapNotNull { it.upstreamSource }.distinct().sorted(),
                    points.flatMap { it.recordOrigins }.distinct().sorted(), points.count { it.upstreamSource == null }, flags)
            }
            return period.sports.flatMap { group -> group.metrics.map { fact(it, group.sport) } } + period.wellness.map { fact(it, null) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(reportJson.encodeToString(report).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return AnalysisInput(1, digest, report.evaluatedOnUtc, report.selectedSport, facts(report.current, "current") + facts(report.previous, "previous"),
            report.comparisons, report.sourceStatus, report.unavailable, listOf(
                report.dateBasis,
                "Evidence IDs identify a period/sport/metric series in the matching private trend report, whose points retain source record IDs and time context.",
                "Totals include only populated validated values; missing measurements are not zero. Moving and elapsed time are separate, never Garmin intensity minutes.",
                "Wellness means weight each populated source record equally, not each day; different sources are not deduplicated or assumed interchangeable.",
                "Record counts/date coverage are observed imports, not verified complete activity or wellness history. Missing records do not prove missed activity or illness.",
                "Read success and latest observed dates do not verify Garmin-to-Intervals.icu freshness. Arithmetic comparisons are descriptive, not statistical significance or cause.",
                "No diagnosis, safety clearance, workout prescription, proprietary-score estimate, or automatic plan is permitted.",
                "No model is called by this endpoint. Any later non-local transfer requires explicit provider choice and informed consent; never silently fall back.",
            ))
    }
}
