package com.gtrainer

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class ManualEventPersistenceTest {
    private val clock = Clock.fixed(Instant.parse("2020-06-10T12:00:00Z"), ZoneOffset.UTC)
    private fun fixture(test: (java.nio.file.Path, HistoryStore) -> Unit) {
        val directory = Files.createTempDirectory("gtrainer-synthetic-event-store-")
        val path = directory.resolve("synthetic.sqlite3")
        try { HistoryStore(path).use { test(path, it) } }
        finally {
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
    @Test fun `multiday events survive reopen edits preserve user text and deletion leaves history untouched`() = fixture { path, store ->
        val service = ManualEventService(store, clock)
        val source = SyntheticSource()
        val history = HistoryService(store, source, clock)
        runBlocking { history.sync(ReadRange(LocalDate.parse("2020-06-01"), LocalDate.parse("2020-06-10"))) }
        val before = store.history(LocalDate.parse("2020-06-01"), LocalDate.parse("2020-06-10"))
        val scheduleBefore = store.reviewScheduleState()
        val created = service.create(ManualEventRequest("2020-07-01", "2020-07-09", "SUP", "  synthetic 'goal'\n水  ", "\tSynthetic notes  "))
        val ongoing = service.create(ManualEventRequest("2020-06-09", "2020-06-11", "Ride", "Synthetic ongoing"))
        val next = service.create(ManualEventRequest("2020-06-10", "2020-06-10", "Walk", "Synthetic next"))
        assertEquals(listOf(ongoing.id, next.id, created.id), service.list().events.map { it.id })
        assertEquals(next, service.list().nextUpcoming)
        assertEquals(listOf(ongoing), service.list().ongoing)
        HistoryStore(path).use { reopened ->
            assertEquals(service.list(), ManualEventService(reopened, clock).list())
        }
        val edited = service.update(created.id, ManualEventRequest("2020-06-12", "2020-06-15", "SUP", created.goal, ""))
        assertEquals(created.goal, edited.goal)
        assertEquals("", edited.notes)
        assertFailsWith<EventNotFound> { service.update("00000000-0000-0000-0000-000000000000", ManualEventRequest("2020-01-01", "2020-01-01", "Ride", "Synthetic")) }
        assertEquals(3, store.events().size)
        assertTrue(service.remove(ongoing.id))
        assertFalse(service.remove(ongoing.id))
        assertEquals(before, store.history(LocalDate.parse("2020-06-01"), LocalDate.parse("2020-06-10")))
        assertEquals(scheduleBefore, store.reviewScheduleState())
        assertEquals(2, source.calls)
        runBlocking { history.removeImports() }
        assertEquals(listOf(next.id, edited.id), store.events().map { it.id })
    }

    @Test fun `event capacity rejects new records atomically but permits existing edits`() = fixture { _, store ->
        repeat(1000) { index ->
            store.saveEvent(ManualEvent("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", "2020-01-01", "2020-01-01", "Walk", "Synthetic event"))
        }
        val before = store.events()
        assertFailsWith<IllegalArgumentException> {
            ManualEventService(store, clock).create(ManualEventRequest("2020-01-01", "2020-01-01", "Walk", "Synthetic overflow"))
        }
        assertEquals(before, store.events())
        val updated = ManualEventService(store, clock).update(before.first().id, ManualEventRequest("2020-01-01", "2020-01-01", "Walk", "Synthetic edit at capacity"))
        assertEquals(updated, store.events().first())
        assertEquals(1000, store.events().size)
    }
}
