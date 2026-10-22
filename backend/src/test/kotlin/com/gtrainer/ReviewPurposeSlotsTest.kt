package com.gtrainer

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.*

class ReviewPurposeSlotsTest {
    private val morning=ReviewPurposeSlot("11111111-1111-4111-8111-111111111111","morning",true,"08:00")
    private val evening=ReviewPurposeSlot("22222222-2222-4222-8222-222222222222","evening",true,"20:00")
    private val armed=Instant.parse("2026-10-15T00:00:00Z")
    private fun evaluate(at: String,marks: Map<String,String> = emptyMap(),arm: Instant?=armed,version: Long=1)=
        ReviewPurposeSlots.evaluate(listOf(morning,evening),"Europe/Prague",version,arm,false,marks,Instant.parse(at))

    @Test fun `two explicit purpose slots use day assessment evidence without granting execution`() {
        val morningDue=evaluate("2026-10-15T07:00:00Z")
        val due=morningDue.due.single()
        assertEquals("morning",due.purpose);assertEquals("2026-10-15T06:00:00Z",due.scheduledUtc)
        assertEquals("2026-10-15T07:00:00Z",due.routedUtc)
        assertEquals("day",due.request.selection.scope);assertEquals("assessment",due.request.selection.wellnessPolicy)
        assertEquals("2026-10-15",due.request.selection.anchorDate)
        assertFalse(ScopedProfileAdmission.inspect(due.profile).executionAllowed)
        val next=evaluate("2026-10-15T19:00:00Z",morningDue.highWater)
        assertEquals("evening",next.due.single().purpose)
        val scope=ReviewScopeResolver.resolve(next.due.single().request.selection,Instant.parse("2026-10-15T19:02:00Z"),"Europe/Prague")
        assertTrue(scope.soFar);assertEquals("2026-10-15T19:02:00Z",scope.asOf)
    }
    @Test fun `configuration edits do not invent new occurrences and restart requires rearm`() {
        val due=evaluate("2026-10-15T07:00:00Z")
        assertEquals(due.due.single().occurrenceId,evaluate("2026-10-15T07:00:00Z",version=2).due.single().occurrenceId)
        assertTrue(evaluate("2026-10-15T07:01:00Z",due.highWater,version=2).due.isEmpty())
        assertTrue(evaluate("2026-10-15T19:00:00Z",due.highWater,arm=null).due.isEmpty())
        assertTrue(evaluate("2026-10-15T19:00:00Z",due.highWater,arm=Instant.parse("2026-10-15T19:00:00Z")).due.isEmpty())
        val paused=ReviewPurposeSlots.evaluate(listOf(morning),"Europe/Prague",1,armed,true,due.highWater,Instant.parse("2026-10-15T07:00:00Z"))
        assertEquals(due.highWater,paused.highWater);assertEquals("schedules_paused",paused.statuses.single().state)
    }
    @Test fun `slots require explicit real times unique identities and distinct purposes`() {
        assertFails { morning.copy(localTime="25:00") };assertFails { morning.copy(localTime="") }
        assertFails { morning.copy(profile=InterpretationContractV1.PROFILE) }
        assertFails { ReviewPurposeSlots.evaluate(listOf(morning,morning),"UTC",1,armed,false,emptyMap(),armed) }
        assertFails { ReviewPurposeSlots.evaluate(listOf(morning,evening.copy(purpose="morning")),"UTC",1,armed,false,emptyMap(),armed) }
    }

    @Test fun `gap moves forward and overlap routes the earlier instant once`() {
        fun slots(now: String, marks: Map<String,String> = emptyMap()) = ReviewPurposeSlots.evaluate(
            listOf(morning.copy(localTime="02:30")),"Europe/Prague",1,
            Instant.parse("2026-01-01T00:00:00Z"),false,marks,Instant.parse(now))
        assertTrue(slots("2026-03-29T00:59:00Z").due.isEmpty())
        val gap=slots("2026-03-29T01:35:00Z")
        assertEquals("2026-03-29T01:30:00Z",gap.due.single().scheduledUtc)
        assertEquals("2026-03-29",gap.due.single().localDate)
        val first=slots("2026-10-25T00:35:00Z")
        assertEquals("2026-10-25T00:30:00Z",first.due.single().scheduledUtc)
        assertTrue(slots("2026-10-25T01:35:00Z",first.highWater).due.isEmpty())
    }

    @Test fun `slot occurrence dates follow local midnight rather than UTC date`() {
        val before = ReviewPurposeSlots.evaluate(listOf(morning.copy(localTime="00:15")), "Europe/Prague", 1,
            Instant.parse("2026-10-15T00:00:00Z"), false, emptyMap(), Instant.parse("2026-10-15T22:20:00Z"))
        assertEquals("2026-10-16", before.due.single().localDate)
        assertEquals("2026-10-15T22:15:00Z", before.due.single().scheduledUtc)
        assertEquals("2026-10-16", before.due.single().request.selection.anchorDate)
    }

    @Test fun `preview preferences are explicit nullable inert and survive routing without a legacy model`() = runBlocking {
        val json=ScopedEvidencePreparation.json
        val old="""{"slotId":"${morning.slotId}","purpose":"morning","enabled":true,"localTime":"08:00"}"""
        assertNull(json.decodeFromString<ReviewPurposeSlot>(old).providerSelection)
        val selection=ConnectedReviewProviderSelection("synthetic-provider","synthetic-model","hosted")
        val configured=morning.copy(providerSelection=selection)
        assertEquals(configured,json.decodeFromString<ReviewPurposeSlot>(json.encodeToString(configured)))
        assertFails { json.decodeFromString<ReviewPurposeSlot>(json.encodeToString(configured).replace("synthetic-provider","invalid provider")) }
        val dir=java.nio.file.Files.createTempDirectory("synthetic-preview-slot-")
        try { HistoryStore(dir.resolve("history.sqlite3")).use { store ->
            val clock=ReviewTestClock(Instant.parse("2026-10-15T05:00:00Z"))
            ReviewInterpretationService(emptyList(),ReviewExecutionPolicy(),null).use { reviews ->
                val scheduler=ReviewScheduler(store,reviews,clock,durableQueue=true)
                scheduler.configure(ReviewScheduleUpdate(0,ReviewScheduleConfiguration(enabled=true,timeZone="Europe/Prague",purposeSlots=listOf(configured))))
                clock.now=Instant.parse("2026-10-15T06:01:00Z")
                val due=scheduler.tick().purposeDue.single()
                assertEquals(selection,due.providerSelection)
                assertEquals(selection,store.reviewScheduleState().queue.pending.single().scopedIntent?.providerSelection)
                assertNull(reviews.status().selectedModelId)
                assertTrue(store.reviewScheduleState().queue.outcomes.isEmpty())
            }
        } } finally { java.nio.file.Files.list(dir).use { it.forEach(java.nio.file.Files::deleteIfExists) };java.nio.file.Files.deleteIfExists(dir) }
    }
}
