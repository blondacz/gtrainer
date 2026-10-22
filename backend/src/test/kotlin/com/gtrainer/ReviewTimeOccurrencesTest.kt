package com.gtrainer

import java.time.*
import kotlin.test.*

class ReviewTimeOccurrencesTest {
    @Test fun `daily gap shifts forward and overlap uses earlier offset once per local date`() {
        val zone=ZoneId.of("Europe/Prague")
        val gap=ReviewTimeOccurrences.resolve(Instant.parse("2026-03-29T01:40:00Z"),zone,LocalTime.parse("02:30"),Instant.parse("2026-03-28T12:00:00Z"),null)
        assertTrue(gap.due);assertEquals(Instant.parse("2026-03-29T01:30:00Z"),gap.scheduledUtc)
        val overlap=ReviewTimeOccurrences.resolve(Instant.parse("2026-10-25T01:40:00Z"),zone,LocalTime.parse("02:30"),Instant.parse("2026-10-24T12:00:00Z"),null)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"),overlap.scheduledUtc);assertTrue(overlap.due)
        val consumed=ReviewTimeOccurrences.resolve(Instant.parse("2026-10-25T01:50:00Z"),zone,LocalTime.parse("02:30"),Instant.parse("2026-10-24T12:00:00Z"),"2026-10-25")
        assertFalse(consumed.due);assertEquals(Instant.parse("2026-10-26T01:30:00Z"),consumed.nextUtc)
    }
    @Test fun `rearm after slot time does not replay earlier dates or reset high water`() {
        val now=Instant.parse("2026-10-15T19:00:00Z")
        val result=ReviewTimeOccurrences.resolve(now,ZoneOffset.UTC,LocalTime.parse("18:00"),now,null)
        assertFalse(result.due);assertEquals(Instant.parse("2026-10-16T18:00:00Z"),result.nextUtc)
        val high=ReviewTimeOccurrences.resolve(now,ZoneOffset.UTC,LocalTime.parse("18:00"),now.minusSeconds(7200),"2026-10-16")
        assertFalse(high.due);assertEquals(Instant.parse("2026-10-17T18:00:00Z"),high.nextUtc)
    }
    @Test fun `weekly slot shares daily time rules and preserves actual occurrence date`() {
        val result=ReviewTimeOccurrences.resolve(Instant.parse("2026-10-15T12:00:00Z"),ZoneOffset.UTC,LocalTime.parse("08:00"),
            Instant.parse("2026-10-11T12:00:00Z"),null,DayOfWeek.MONDAY)
        assertTrue(result.due);assertEquals(LocalDate.parse("2026-10-12"),result.localDate)
        assertEquals(Instant.parse("2026-10-19T08:00:00Z"),result.nextUtc)
    }
}
