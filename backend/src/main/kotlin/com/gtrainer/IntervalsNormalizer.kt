package com.gtrainer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class Normalized<T>(val record: T, val incomplete: Boolean)

internal object IntervalsNormalizer {
    private const val SOURCE = "intervals.icu"
    private val knownOrigins = setOf("GARMIN", "GARMIN_CONNECT", "STRAVA", "INTERVALS", "INTERVALS_ICU")

    private fun JsonObject.text(name: String): String? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        require(value is JsonPrimitive && value.isString) { "Invalid text field" }
        return value.content
    }

    private fun origins(value: JsonElement?): Set<String> = when (value) {
        null, JsonNull -> emptySet()
        is JsonPrimitive -> if (value.isString) {
            setOf(value.content.uppercase().replace('-', '_').replace(' ', '_')).intersect(knownOrigins)
        } else emptySet()
        is JsonObject -> value.values.flatMap { origins(it) }.toSet()
        else -> (value as? kotlinx.serialization.json.JsonArray)?.flatMap { origins(it) }?.toSet() ?: emptySet()
    }

    private fun JsonObject.metric(field: String, unit: String, maximum: Double, originField: String? = null,
                                  whole: Boolean = false): Measurement? {
        val raw = this[field] ?: return null
        if (raw is JsonNull) return null
        require(raw is JsonPrimitive && !raw.isString) { "Invalid numeric field" }
        val value = requireNotNull(raw.doubleOrNull) { "Invalid numeric field" }
        require(value.isFinite() && value >= 0 && value <= maximum && (!whole || value % 1.0 == 0.0)) {
            "Out-of-range numeric field"
        }
        // Do not infer metric origin from a device, the record's source label,
        // or the user's Garmin -> Intervals connection.
        val upstream = originField?.let { origins(this[it]).singleOrNull() }
        return Measurement(value, unit, upstream)
    }

    fun activity(input: JsonObject): Normalized<ActivityRecord>? = try {
        val id = requireNotNull(input.text("id"))
        require(id.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        val sport = requireNotNull(input.text("type"))
        require(sport.matches(Regex("[A-Za-z0-9 _-]{1,64}")))
        val rawLocal = requireNotNull(input.text("start_date_local"))
        require(rawLocal.length <= 40)
        val offsetLocal = runCatching { OffsetDateTime.parse(rawLocal) }.getOrNull()
        val local = offsetLocal?.toLocalDateTime() ?: LocalDateTime.parse(rawLocal)
        val utc = input.text("start_date")?.let { value ->
            // A timestamp without an explicit offset is not silently called UTC.
            require(value.length <= 40)
            OffsetDateTime.parse(value)
        }
        val label = input.text("timezone")?.also { require(it.length <= 128) }
        val zone = label?.let {
            val candidate = it.substringAfterLast(' ')
            runCatching { ZoneId.of(candidate) }.getOrNull()
        }
        val offsets = zone?.rules?.getValidOffsets(local)
        val offset = offsetLocal?.offset ?: utc?.let { instant ->
            zone?.rules?.getOffset(instant.toInstant())
        } ?: offsets?.singleOrNull()
        if (utc != null && zone != null) {
            require(LocalDateTime.ofInstant(utc.toInstant(), zone) == local) { "Conflicting source timestamps" }
        }
        if (utc != null && offsetLocal != null) {
            require(utc.toInstant() == offsetLocal.toInstant()) { "Conflicting source timestamps" }
        }
        val instant: Instant? = utc?.toInstant() ?: offsetLocal?.toInstant() ?: offset?.let(local::toInstant)
        val context = when {
            utc != null -> "upstream_instant"
            offsetLocal != null -> "explicit_offset"
            offsets?.size == 1 -> "named_zone"
            offsets != null -> "ambiguous_or_nonexistent_local_time"
            else -> "local_time_only"
        }
        val moving = input.metric("moving_time", "seconds", 31.0 * 86400, whole = true)
        val elapsed = input.metric("elapsed_time", "seconds", 31.0 * 86400, whole = true)
        require(moving == null || elapsed == null || moving.value <= elapsed.value) { "Conflicting durations" }
        val record = ActivityRecord(SOURCE, id, origins(input["source"]) + origins(input["sources"]), sport,
            local.toString(), instant?.toString(), zone?.id, label, offset?.id, context, moving, elapsed,
            input.metric("calories", "kcal", 100_000.0), input.metric("distance", "metres", 10_000_000.0),
            input.metric("average_heartrate", "beats/minute", 300.0),
            input.metric("icu_training_load", "Intervals.icu load", 100_000.0))
        Normalized(record, instant == null || moving == null || record.calories == null || record.upstreamSources.isEmpty())
    } catch (_: Exception) {
        null  // No raw value, record ID, exception, or payload leaves validation.
    }

    fun wellness(input: JsonObject): Normalized<WellnessRecord>? = try {
        val id = requireNotNull(input.text("id"))
        val date = LocalDate.parse(id).toString()
        val metrics = linkedMapOf<String, Measurement>()
        fun add(name: String, unit: String, max: Double, origin: String? = null, whole: Boolean = false) {
            input.metric(name, unit, max, origin, whole)?.let { metrics[name] = it }
        }
        add("weight", "kg", 500.0, "weightSource")
        add("bodyFat", "percent", 100.0, "bodyFatSource")
        add("hrv", "ms", 2000.0, "hrvSource")
        add("hrvSDNN", "ms", 2000.0, "hrvSource")
        add("restingHR", "beats/minute", 300.0)
        add("sleepSecs", "seconds", 86400.0, whole = true)
        add("sleepScore", "source sleep score", 100.0)
        add("steps", "count", 250_000.0, whole = true)
        add("vo2max", "mL/kg/min", 200.0)
        add("atl", "Intervals.icu load", 100_000.0)
        add("ctl", "Intervals.icu load", 100_000.0)
        Normalized(WellnessRecord(SOURCE, id, date, origins(input["source"]) + origins(input["sources"]), metrics),
            metrics.isEmpty() || listOf("weight", "vo2max", "hrv", "sleepSecs").any { it !in metrics })
    } catch (_: Exception) {
        null
    }
}
