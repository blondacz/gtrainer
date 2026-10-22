package com.gtrainer

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Pure, bounded projection of confirmed commitments into a local-date horizon. */
fun projectCommitments(
    all: List<ConfirmedCommitment>, oldest: String, newest: String, timeZone: String,
    onWork: () -> Unit = {},
): CommitmentCalendar {
    fun date(value: String): LocalDate {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
        return LocalDate.parse(value)
    }
    val first = date(oldest)
    val last = date(newest)
    val days = ChronoUnit.DAYS.between(first, last) + 1
    require(days in 1..366)
    val horizonZone = commitmentZone(timeZone)
    val from = first.atStartOfDay(horizonZone).toInstant()
    val until = last.plusDays(1).atStartOfDay(horizonZone).toInstant()

    val canonical = all.groupBy { it.reference.commitmentId }.values.map { revisions ->
        revisions.maxWith(compareBy<ConfirmedCommitment> { it.reference.revision }
            .thenBy { it.reference.intakeRevision })
    }
    if (canonical.size > 1000) throw CommitmentProjectionLimit()
    val stale = canonical.filter { it.needsReconfirmation }.map { it.reference }
        .sortedWith(compareBy<CommitmentEvidenceRef> { it.commitmentId }.thenBy { it.revision })
    // A skipped civil date (e.g. Apia 2011-12-30) has no instants to overlap.
    if(from==until) return CommitmentCalendar(oldest,newest,timeZone,emptyList(),stale,stale.isNotEmpty())
    val out = ArrayList<CommitmentOccurrence>()
    var work = 0
    fun charge() { onWork(); if (++work > 50_000) throw CommitmentProjectionLimit() }
    val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")

    fun occurrence(item: ConfirmedCommitment, original: LocalDate, startLocal: LocalDateTime, endLocal: LocalDateTime): CommitmentOccurrence? {
        val def = item.definition
        val zone = commitmentZone(def.timeZone)
        if (Duration.between(startLocal, endLocal) <= Duration.ZERO || Duration.between(startLocal, endLocal) > Duration.ofDays(366))
            throw CommitmentTimeUncertain()
        val startZ = startLocal.atZone(zone)
        val endZ = endLocal.atZone(zone)
        if (!startZ.toInstant().isBefore(endZ.toInstant())) {
            if(maxOf(startZ.toInstant(),endZ.toInstant())<=from || minOf(startZ.toInstant(),endZ.toInstant())>=until) return null
            throw CommitmentTimeUncertain()
        }
        if (!startZ.toInstant().isBefore(until) || !endZ.toInstant().isAfter(from)) return null
        val flags = mutableListOf<String>()
        fun flag(local: LocalDateTime, zoned: ZonedDateTime) {
            val valid = zone.rules.getValidOffsets(local)
            if (valid.isEmpty()) flags += "dst_gap_shifted"
            else if (valid.size > 1 && zoned.offset == valid.first()) flags += "dst_overlap_earlier_offset"
        }
        flag(startLocal, startZ); flag(endLocal, endZ)
        if (item.needsReconfirmation) flags += "needs_reconfirmation"
        return CommitmentOccurrence(
            "${item.reference.commitmentId}:$original", original.toString(), item.reference,
            def.label, def.kind, def.timeZone, startLocal.format(formatter), endLocal.format(formatter),
            startZ.toInstant().toString(), endZ.toInstant().toString(), !item.needsReconfirmation, flags.distinct()
        )
    }

    for (item in canonical) {
        val def = item.definition
        val anchorStart = commitmentLocal(def.startsLocal)
        val anchorDate = anchorStart.toLocalDate()
        val endAnchor = commitmentLocal(def.endsLocal)
        val dayOffset = ChronoUnit.DAYS.between(anchorDate, endAnchor.toLocalDate())
        val endTime = endAnchor.toLocalTime()
        val exceptions = def.exceptions.associateBy { LocalDate.parse(it.originalDate) }
        val zone=commitmentZone(def.timeZone)
        // Compute candidate dates in the definition's zone, not the horizon's zone.
        val searchStart = from.atZone(zone).toLocalDate().minusDays(dayOffset+1)
        val searchEnd = until.atZone(zone).toLocalDate()
        val recurrence = def.recurrence
        if (recurrence == null) {
            if (anchorDate in searchStart..searchEnd) {
                charge()
                val ex = exceptions[anchorDate]
                if (ex == null) occurrence(item, anchorDate, anchorStart, endAnchor)?.let(out::add)
                else if (ex.action == "reschedule") occurrence(item, anchorDate, commitmentLocal(ex.startsLocal!!), commitmentLocal(ex.endsLocal!!))?.let(out::add)
            }
        } else {
            val step = recurrence.interval.toLong() * if (recurrence.frequency == "weekly") 7 else 1
            val earliest = maxOf(anchorDate, searchStart)
            var n = maxOf(0L, (ChronoUnit.DAYS.between(anchorDate, earliest) + step - 1) / step)
            var d = anchorDate.plusDays(n * step)
            while (d <= searchEnd && recurrence.endOn?.let { d <= LocalDate.parse(it) } != false) {
                charge()
                val ex = exceptions[d]
                if (ex == null) {
                    val start = d.atTime(anchorStart.toLocalTime())
                    val end = d.plusDays(dayOffset).atTime(endTime)
                    occurrence(item, d, start, end)?.let(out::add)
                } else if (ex.action == "reschedule") {
                    occurrence(item, d, commitmentLocal(ex.startsLocal!!), commitmentLocal(ex.endsLocal!!))?.let(out::add)
                }
                n++
                d = anchorDate.plusDays(n * step)
            }
        }
        // This also applies to one-off items moved in from far outside the horizon.
        for (ex in def.exceptions) if (ex.action == "reschedule") {
            val original = LocalDate.parse(ex.originalDate)
            if (original < searchStart || original > searchEnd) {
                charge()
                occurrence(item,original,commitmentLocal(ex.startsLocal!!),commitmentLocal(ex.endsLocal!!))?.let(out::add)
            }
        }
        if (out.size > 50_000) throw CommitmentProjectionLimit()
    }
    return CommitmentCalendar(oldest, newest, timeZone,
        out.sortedWith(compareBy<CommitmentOccurrence> { it.startsUtc }.thenBy { it.occurrenceId }),
        stale, stale.isNotEmpty())
}
