package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ReadRange(val oldest: LocalDate, val newest: LocalDate) {
    init {
        require(oldest <= newest && ChronoUnit.DAYS.between(oldest, newest) <= 366) {
            "One source read requires an ordered range of at most 366 days"
        }
    }
}

@Serializable
data class Measurement(val value: Double, val unit: String, val upstreamSource: String? = null) {
    override fun toString(): String = "Measurement([REDACTED])"
}

@Serializable
data class ActivityRecord(
    val source: String,
    val sourceRecordId: String,
    val upstreamSources: Set<String>,
    val sport: String,
    val startLocal: String,
    val startInstant: String?,
    val timeZone: String?,
    val sourceTimeZoneLabel: String?,
    val utcOffset: String?,
    val timeContext: String,
    val movingTime: Measurement?,
    val elapsedTime: Measurement?,
    val calories: Measurement?,
    val distance: Measurement?,
    val averageHeartRate: Measurement?,
    val intervalsTrainingLoad: Measurement?,
) {
    override fun toString(): String = "ActivityRecord([REDACTED])"
}

@Serializable
data class WellnessRecord(
    val source: String,
    val sourceRecordId: String,
    val date: String,
    val upstreamSources: Set<String>,
    val measurements: Map<String, Measurement>,
) {
    override fun toString(): String = "WellnessRecord([REDACTED])"
}

enum class ReadStatus {
    SUCCESS, NOT_CONFIGURED, KEY_REJECTED, ACCESS_DENIED, RATE_LIMITED,
    UPSTREAM_ERROR, REDIRECT_BLOCKED, TRANSPORT_ERROR, RESPONSE_TOO_LARGE, INVALID_RESPONSE,
}

data class ReadResult<T>(
    val status: ReadStatus,
    val records: List<T> = emptyList(),
    val rejected: Int = 0,
    val incomplete: Int = 0,
    // An HTTP response cannot establish Garmin-to-intermediary freshness.
    val upstreamFreshness: String = "unknown",
) {
    override fun toString(): String = "ReadResult(status=$status, records=[REDACTED])"
}

/** No provider JSON/authentication details escape this replaceable boundary. */
interface HistorySource {
    val sourceId: String
    suspend fun activities(range: ReadRange): ReadResult<ActivityRecord>
    suspend fun wellness(range: ReadRange): ReadResult<WellnessRecord>
}
