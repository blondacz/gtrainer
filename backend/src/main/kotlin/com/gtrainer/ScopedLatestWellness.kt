package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable
data class ScopedLatestWellnessDay(
    val field: String, val source: String, val upstreamOrigin: String?, val sourceDate: String,
    val ageDays: Int, val status: String, val observations: List<ScopedWellnessObservation>,
) { override fun toString() = "ScopedLatestWellnessDay([REDACTED])" }

@Serializable
data class ScopedLatestWellnessContext(
    val evaluationDate: String, val searchOldest: String, val searchNewest: String,
    val days: List<ScopedLatestWellnessDay>, val missing: List<String>,
    val temporalBasis: String = "fixed_source_date_not_verified_prior_night_not_point_in_time_replay",
) { override fun toString() = "ScopedLatestWellnessContext([REDACTED])" }

internal object ScopedLatestWellness {
    private val fields = listOf("sleepSecs", "hrv", "hrvSDNN", "restingHR", "sleepScore")

    fun assemble(read: CapturedScopedRead): ScopedLatestWellnessContext {
        val evaluation = java.time.Instant.parse(read.scope.asOf).atZone(java.time.ZoneId.of(read.scope.timeZone)).toLocalDate()
        val oldest = evaluation.minusDays(minOf(365L,evaluation.toEpochDay()-LocalDate.of(1,1,1).toEpochDay()))
        val latest = ScopedWellnessEvidence.raw(read.history.wellness, read.provenance)
            .filter { it.field in fields && LocalDate.parse(it.sourceDate).let { date -> date >= oldest && date <= evaluation } }
            .groupBy { listOf(it.field, it.source, it.upstreamSource, it.method, it.basis) }
            .values.mapNotNull { group ->
                val date = group.maxOf { LocalDate.parse(it.sourceDate) }
                val observations = group.filter { LocalDate.parse(it.sourceDate) == date }
                    .distinct().sortedWith(compareBy({ it.evidenceId }, { it.value }, { it.sourceRecordId }))
                val first = group.first()
                ScopedLatestWellnessDay(first.field, first.source, first.upstreamSource, date.toString(),
                    ChronoUnit.DAYS.between(date, evaluation).toInt(),
                    if (observations.size == 1) "unique_observation" else "ambiguous", observations)
            }.sortedWith(compareBy({ it.field }, { it.source }, { it.upstreamOrigin ?: "" }, { it.sourceDate }, { it.observations.firstOrNull()?.method ?: "" }, { it.observations.firstOrNull()?.basis ?: "" }))
        val missing = buildList {
            for (field in listOf("sleepSecs", "hrv", "hrvSDNN")) if (latest.none { it.field == field }) add(field)
            add("training_readiness_unsupported")
        }
        return ScopedLatestWellnessContext(evaluation.toString(), oldest.toString(), evaluation.toString(), latest, missing)
    }
}
