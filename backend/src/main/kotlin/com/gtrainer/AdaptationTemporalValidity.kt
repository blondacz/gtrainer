package com.gtrainer

import java.time.Instant
import java.time.ZoneId

/** Earliest exclusive future boundary at which captured adaptation facts may change. */
internal object AdaptationTemporalValidity {
    fun until(capture: CapturedAdaptationEvidence, timeZone: String): Instant {
        require(timeZone=="UTC" || timeZone in ZoneId.getAvailableZoneIds())
        val at = capture.at
        val zones = buildSet {
            add(ZoneId.of(timeZone))
            capture.reconciliation.plan?.definition?.policy?.let { add(ZoneId.of(it.adaptationTimeZone)) }
            capture.signals.filter { it.rule.enabled }.forEach { add(ZoneId.of(requireNotNull(it.rule.configuration).timeZone)) }
            capture.workloadRules.filter { it.enabled }.forEach { it.configuration?.let { config -> add(ZoneId.of(config.timeZone)) } }
        }
        val candidates = mutableListOf<Instant>()
        zones.forEach { zone ->
            val midnight = at.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            if (midnight > at) candidates += midnight
        }
        val policy = capture.reconciliation.plan?.definition?.policy
        capture.reconciliation.plan?.definition?.sessions.orEmpty().forEach { session ->
            listOf(session.schedule.startInstant(), session.schedule.endInstant()).filter { it > at }.forEach(candidates::add)
            if (policy != null) unmatchedAfter(session, policy).takeIf { it > at }?.let(candidates::add)
        }
        capture.reconciliation.activities.mapNotNull { it.verifiedStart()?.takeIf { start -> start > at } }.forEach(candidates::add)
        return candidates.minOrNull() ?: error("A future local midnight must exist")
    }
}
