package com.gtrainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupportedActivityMetricTest {
    private fun record(moving: Measurement? = null, elapsed: Measurement? = null, distance: Measurement? = null,
                       heartRate: Measurement? = null, load: Measurement? = null, source: String = "synthetic") =
        ActivityRecord(source, "record", emptySet(), "Run", "2026-01-01T10:00", null, null, null,
            null, "unknown", moving, elapsed, null, distance, heartRate, load)

    @Test fun movingAndElapsedAreIndependentAndMissingDoesNotFallback() {
        val r = record(elapsed=Measurement(15.0, "seconds"))
        val missing = SupportedActivityMetrics.read("duration", "seconds", "moving", r)
        assertEquals("measurement_missing", missing.reason); assertNull(missing.value)
        assertNull(missing.unit); assertEquals("movingTime", missing.field)
        val elapsed = SupportedActivityMetrics.read("duration", "seconds", "elapsed", r)
        assertEquals("assessed", elapsed.reason); assertEquals(15.0, elapsed.value)
        assertEquals("elapsedTime", elapsed.field); assertEquals("elapsed", elapsed.basis)
    }

    @Test fun dispatchRejectsUnsupportedCombinationsAndDoesNotConvertUnits() {
        val r = record(moving=Measurement(1.0, "minutes"))
        assertEquals("unit_mismatch", SupportedActivityMetrics.read("duration", "seconds", "moving", r).reason)
        assertEquals("unsupported_source_basis", SupportedActivityMetrics.read("distance", "metres", "moving", r).reason)
        assertEquals("unsupported_source_basis", SupportedActivityMetrics.read("other", "seconds", "moving", r).reason)
    }

    @Test fun nonfiniteAndActualBoundsFailClosed() {
        assertEquals("invalid_measurement", SupportedActivityMetrics.read("duration", "seconds", "moving",
            record(moving=Measurement(Double.POSITIVE_INFINITY, "seconds"))).reason)
        assertEquals("invalid_measurement", SupportedActivityMetrics.read("distance", "metres", "session_total",
            record(distance=Measurement(10_000_001.0, "metres"))).reason)
        assertEquals("invalid_measurement", SupportedActivityMetrics.read("average_heart_rate", "beats/minute", "session_average",
            record(heartRate=Measurement(301.0, "beats/minute"))).reason)
    }

    @Test fun intervalsLoadRequiresIntervalsSourceAndEnforcesMaximumActualValue() {
        val r = record(load=Measurement(100_001.0, "Intervals.icu load"), source="intervals.icu")
        assertEquals("invalid_measurement", SupportedActivityMetrics.read("intervals_training_load", "Intervals.icu load", "intervals_session_load", r).reason)
        val wrongSource = SupportedActivityMetrics.read("intervals_training_load", "Intervals.icu load", "intervals_session_load", r.copy(source="synthetic"))
        assertEquals("unsupported_source_basis", wrongSource.reason)
        assertEquals("synthetic", wrongSource.source)
        assertEquals("assessed", SupportedActivityMetrics.read("intervals_training_load", "Intervals.icu load", "intervals_session_load",
            r.copy(intervalsTrainingLoad=Measurement(100_000.0, "Intervals.icu load"))).reason)
    }
}
