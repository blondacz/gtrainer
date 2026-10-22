package com.gtrainer

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal data class ReviewTimeOccurrence(val localDate: LocalDate,val scheduledUtc: Instant,val due: Boolean,val nextUtc: Instant)

/** Shared wall-clock occurrence semantics. Gaps move forward; overlaps use the earlier offset. No missed-day replay. */
internal object ReviewTimeOccurrences {
    fun resolve(now: Instant,zone: ZoneId,time: LocalTime,armedAt: Instant,lastOccurrence: String?,dayOfWeek: DayOfWeek? = null): ReviewTimeOccurrence {
        val today=now.atZone(zone).toLocalDate()
        val date=if(dayOfWeek==null) today else today.with(TemporalAdjusters.previousOrSame(dayOfWeek))
        val previous=lastOccurrence?.let(LocalDate::parse)
        fun at(local: LocalDate)=local.atTime(time).atZone(zone).toInstant()
        val occurrence=at(date)
        val due=occurrence>armedAt && occurrence<=now && (previous==null || date>previous)
        val highWater=if(due) date else previous
        val increment=if(dayOfWeek==null) 1L else 7L
        var next=if(occurrence>now && occurrence>armedAt && (highWater==null || date>highWater)) date else date.plusDays(increment)
        highWater?.let { next=next.coerceAtLeast(it.plusDays(increment)) }
        if(at(next)<=armedAt) {
            val armDate=armedAt.atZone(zone).toLocalDate()
            next=if(dayOfWeek==null) armDate else armDate.with(TemporalAdjusters.nextOrSame(dayOfWeek))
            if(at(next)<=armedAt) next=next.plusDays(increment)
            highWater?.let { next=next.coerceAtLeast(it.plusDays(increment)) }
        }
        return ReviewTimeOccurrence(date,occurrence,due,at(next))
    }
}
