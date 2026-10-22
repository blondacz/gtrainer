package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Serializable
data class ScopedSignalIdentity(
    val field: String, val unit: String, val intermediary: String,
    val upstreamOrigin: String? = null, val method: String? = null,
    val basis: String? = null, val verification: String = "unknown",
) { override fun toString() = "ScopedSignalIdentity([REDACTED])" }

@Serializable
data class ScopedWellnessObservation(
    val evidenceId: String, val field: String, val value: Double, val unit: String,
    val sourceDate: String, val source: String, val sourceRecordId: String,
    val metric: String, val upstreamSource: String?, val recordOrigins: List<String>,
    val provenance: List<ScopedImportProvenance>, val activityIds: List<String> = emptyList(), val method: String = "unknown",
    val basis: String = "unknown", val verification: String = "unknown",
) { override fun toString() = "ScopedWellnessObservation([REDACTED])" }

@Serializable
data class ScopedSignalSample(
    val identity: ScopedSignalIdentity, val date: String, val value: Double,
    val evidenceId: String, val receivedUtc: String? = null, val revision: Long? = null,
) { override fun toString() = "ScopedSignalSample([REDACTED])" }

@Serializable
data class ScopedBaselineConfig(
    val evaluationDate: String, val trailingDays: Int, val minimumSamples: Int,
    val minimumCoverage: Double, val maximumAgeDays: Int,
) {
    init {
        validateContextDate(evaluationDate)
        require(trailingDays in 1..366 && minimumSamples in 1..trailingDays)
        require(minimumCoverage.isFinite() && minimumCoverage in 0.0..1.0 && maximumAgeDays in 0..366)
        require(LocalDate.parse(evaluationDate).minusDays(trailingDays.toLong()).year in 1..9999)
    }
    override fun toString() = "ScopedBaselineConfig([REDACTED])"
}

@Serializable
data class ScopedBaselineSelection(val field: String,val source: String,val upstreamOrigin: String?,val configuration: ScopedBaselineConfig) {
    init {
        require(field in setOf("sleepSecs","hrv","hrvSDNN") && source.matches(Regex("[a-zA-Z0-9_.-]{1,64}")))
        upstreamOrigin?.let { validateContextText(it,128,true) }
    }
    fun range()=ScopedDateRange(LocalDate.parse(configuration.evaluationDate).minusDays(configuration.trailingDays.toLong()).toString(),
        LocalDate.parse(configuration.evaluationDate).minusDays(1).toString())
    fun matches(record: WellnessRecord): Boolean {
        val measurement=record.measurements[field] ?: return false
        val range=range()
        return record.source==source && record.date>=range.oldest && record.date<=range.newest &&
            measurement.upstreamSource==upstreamOrigin
    }
    override fun toString()="ScopedBaselineSelection([REDACTED])"
}

@Serializable
data class ScopedBaselineDay(
    val date: String, val status: String, val value: Double? = null,
    val evidenceIds: List<String> = emptyList(),
    val samples: List<ScopedSignalSample> = emptyList(),
    val candidateCount: Int = samples.size,
    val candidateDetails: String = "included_samples",
) { override fun toString() = "ScopedBaselineDay([REDACTED])" }

@Serializable
data class ScopedWellnessBaseline(
    val identity: ScopedSignalIdentity, val evaluationDate: String, val value: Double?,
    val status: String, val reasons: List<String>, val days: List<ScopedBaselineDay>,
    val selectedSamples: Int, val expectedDays: Int,
    val configuration: ScopedBaselineConfig,
    val aggregation: String = "median", val selectionPolicy: String = "unique_compatible",
    val temporalBasis: String = "source_date_window_using_records_available_at_preparation_not_point_in_time_replay",
) { override fun toString() = "ScopedWellnessBaseline([REDACTED])" }

internal object ScopedWellnessEvidence {
    private val fields = mapOf(
        "weight" to "kg", "bodyFat" to "percent", "hrv" to "ms", "hrvSDNN" to "ms",
        "restingHR" to "beats/minute", "sleepSecs" to "seconds", "sleepScore" to "source sleep score",
        "steps" to "count", "vo2max" to "mL/kg/min", "atl" to "Intervals.icu load", "ctl" to "Intervals.icu load",
    )

    fun raw(
        records: List<WellnessRecord>, provenance: List<ScopedImportProvenance>,
        activitiesByDate: Map<String, List<String>> = emptyMap(),
    ): List<ScopedWellnessObservation> {
        require(records.size <= 50_000) { "Too many wellness records" }
        require(records.map { it.source to it.sourceRecordId }.distinct().size == records.size)
        val provenanceByRecord = provenance.groupBy { it.source to it.sourceRecordId }
        val result = records.flatMap { record ->
            val date = date(record.date)
            record.measurements.toSortedMap().mapNotNull { (field, measurement) ->
                val unit = fields[field] ?: return@mapNotNull null
                if (measurement.unit != unit || !measurement.value.isFinite() || measurement.value < 0) return@mapNotNull null
                val id = AnalysisClaims.hash(kotlinx.serialization.json.Json.encodeToString(listOf(record.source, record.sourceRecordId, field)))
                ScopedWellnessObservation(id, field, measurement.value, unit, date.toString(), record.source,
                    record.sourceRecordId, field, measurement.upstreamSource, record.upstreamSources.sorted(),
                    provenanceByRecord[record.source to record.sourceRecordId].orEmpty().filter { it.category == "wellness" }
                        .sortedWith(compareBy({ it.revision ?: Long.MIN_VALUE }, { it.receivedUtc ?: "" })),
                    activitiesByDate[date.toString()].orEmpty().distinct().sorted(),
                )
            }
        }
        return result.sortedWith(compareBy({ it.sourceDate }, { it.source }, { it.sourceRecordId }, { it.field }))
    }

    fun baseline(identity: ScopedSignalIdentity, config: ScopedBaselineConfig, samples: List<ScopedSignalSample>): ScopedWellnessBaseline {
        require(samples.size <= 50_000) { "Too many wellness samples" }
        require(identity.field.isNotBlank() && identity.unit.isNotBlank() && identity.intermediary.isNotBlank())
        require(fields[identity.field] == identity.unit)
        val semanticsVerified = verified(identity)
        require(config.trailingDays in 1..366 && config.minimumSamples in 1..config.trailingDays)
        require(config.minimumCoverage.isFinite() && config.minimumCoverage in 0.0..1.0)
        require(config.maximumAgeDays in 0..366)
        val evaluation = date(config.evaluationDate)
        val indexed = samples.map { it to date(it.date) }.groupBy { it.second }
        require(samples.all { it.value.isFinite() && it.value >= 0 && it.evidenceId.isNotBlank() &&
            (it.revision == null || it.revision > 0) })
        samples.forEach { it.receivedUtc?.let(java.time.Instant::parse) }
        val first = evaluation.minusDays(config.trailingDays.toLong())
        require(first.year in 1..9999)
        val allDays = (0 until config.trailingDays).map { first.plusDays(it.toLong()) }
        val samplesByDay = indexed.mapValues { (_, entries) -> entries.map { it.first } }
        val days = allDays.map { day -> selectDay(identity, day.toString(), samplesByDay[day].orEmpty()) }
        val usable = days.filter { it.status == "unique_compatible" && it.value != null }
        val coverage = usable.size.toDouble() / config.trailingDays
        val age = usable.minOfOrNull { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(it.date), evaluation).toInt() }
        val reasons = buildList {
            if (!semanticsVerified) add("semantics_unverified")
            if (usable.size < config.minimumSamples) add("insufficient_samples")
            if (coverage < config.minimumCoverage) add("insufficient_coverage")
            if (age == null || age > config.maximumAgeDays) add("stale_or_missing")
        }
        val value = if (reasons.isEmpty()) usable.mapNotNull { it.value }.sorted().let { values ->
            if (values.size % 2 == 1) values[values.size / 2] else values[values.size / 2 - 1] / 2.0 + values[values.size / 2] / 2.0
        } else null
        return ScopedWellnessBaseline(identity, evaluation.toString(), value,
            if (value == null) "not_computed" else "computed", reasons, days, usable.size, config.trailingDays,config)
    }

    private fun verified(identity: ScopedSignalIdentity): Boolean = identity.verification == "verified" &&
        identity.upstreamOrigin?.isNotBlank() == true && identity.method?.isNotBlank() == true && identity.basis?.isNotBlank() == true

    private fun related(a: ScopedSignalIdentity, b: ScopedSignalIdentity): Boolean =
        a.field == b.field && a.unit == b.unit && a.intermediary == b.intermediary

    internal fun selectDay(identity: ScopedSignalIdentity, day: String, samples: List<ScopedSignalSample>): ScopedBaselineDay {
        require(samples.all { it.date==day })
        val onDay = samples
        val exact = onDay.filter { it.identity == identity }.distinct()
        val status = when {
            onDay.isEmpty() -> "missing"
            exact.size > 1 -> "ambiguous"
            !verified(identity) -> "unverified"
            exact.isEmpty() -> if (onDay.any { related(it.identity, identity) && !verified(it.identity) }) "unverified" else "incompatible"
            exact.size != 1 -> "ambiguous"
            else -> "unique_compatible"
        }
        val sample = exact.singleOrNull()?.takeIf { status == "unique_compatible" }
        val candidates = onDay.distinct().sortedWith(compareBy({ it.evidenceId }, { it.revision }, { it.receivedUtc }, { it.value }))
        return ScopedBaselineDay(day, status, sample?.value, candidates.map { it.evidenceId }.distinct().sorted(), candidates)
    }

    private fun date(value: String): LocalDate = try {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
        LocalDate.parse(value).also { require(it.year in 1..9999) }
    } catch (_: DateTimeParseException) { throw IllegalArgumentException("Invalid wellness date") }
}
