package com.gtrainer

internal data class SupportedActivityMetricReading(
    val reason: String,
    val value: Double? = null,
    val unit: String? = null,
    val field: String? = null,
    val basis: String? = null,
    val source: String,
    val upstreamOrigin: String? = null
) { override fun toString()="SupportedActivityMetricReading([REDACTED])" }

internal object SupportedActivityMetrics {
    fun read(metric: String, unit: String, basis: String, record: ActivityRecord): SupportedActivityMetricReading {
        val field: String
        val supportedBasis: String
        val expectedUnit: String
        val bounds: ClosedFloatingPointRange<Double>
        val measurement: Measurement?
        when (metric) {
            "duration" -> {
                field = if (basis == "moving") "movingTime" else if (basis == "elapsed") "elapsedTime" else
                    return unsupported(record)
                supportedBasis = basis; expectedUnit = "seconds"; bounds = 0.0..2678400.0
                measurement = if (basis == "moving") record.movingTime else record.elapsedTime
            }
            "distance" -> {
                if (basis != "session_total") return unsupported(record)
                field = "distance"; supportedBasis = basis; expectedUnit = "metres"; bounds = 0.0..10000000.0; measurement = record.distance
            }
            "average_heart_rate" -> {
                if (basis != "session_average") return unsupported(record)
                field = "averageHeartRate"; supportedBasis = basis; expectedUnit = "beats/minute"; bounds = 0.0..300.0; measurement = record.averageHeartRate
            }
            "intervals_training_load" -> {
                if (basis != "intervals_session_load") return unsupported(record)
                field = "intervalsTrainingLoad"; supportedBasis = basis; expectedUnit = "Intervals.icu load"; bounds = 0.0..100000.0; measurement = record.intervalsTrainingLoad
            }
            else -> return unsupported(record)
        }
        if (measurement == null) return SupportedActivityMetricReading("measurement_missing", field=field, basis=supportedBasis, source=record.source)
        if (metric == "intervals_training_load" && record.source != "intervals.icu")
            return SupportedActivityMetricReading("unsupported_source_basis", field=field, basis=supportedBasis, source=record.source)
        if (unit != expectedUnit || measurement.unit != unit) return SupportedActivityMetricReading("unit_mismatch", unit=measurement.unit, field=field, basis=supportedBasis, source=record.source, upstreamOrigin=measurement.upstreamSource)
        val value = measurement.value
        if (!value.isFinite() || value !in bounds) return SupportedActivityMetricReading("invalid_measurement", unit=measurement.unit, field=field, basis=supportedBasis, source=record.source, upstreamOrigin=measurement.upstreamSource)
        return SupportedActivityMetricReading("assessed", value, measurement.unit, field, supportedBasis, record.source, measurement.upstreamSource)
    }

    private fun unsupported(record: ActivityRecord) = SupportedActivityMetricReading("unsupported_source_basis", source=record.source)
}
