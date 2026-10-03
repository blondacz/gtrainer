package com.gtrainer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class ManualEventsTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val clock = Clock.fixed(Instant.parse("2020-06-01T12:00:00Z"), ZoneOffset.UTC)

    private class SyntheticRepository(initial: List<ManualEvent> = emptyList()) : ManualEventRepository {
        private val records = initial.associateBy { it.id }.toMutableMap()
        var writes = 0
        override fun events(): List<ManualEvent> = records.values.toList()
        override fun saveEvent(event: ManualEvent) { writes++; records[event.id] = event }
        override fun deleteEvent(id: String): Boolean = records.remove(id) != null
    }

    private fun request(start: String = "2020-06-01", end: String = start, sport: String = "Run",
                        goal: String = "Synthetic goal", notes: String? = null) =
        ManualEventRequest(start, end, sport, goal, notes)

    private fun event(number: Int, start: String, end: String = start) =
        ManualEvent("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}", start, end, "Run", "Synthetic goal")

    @Test fun `CRUD creates unique canonical identifiers and updates preserve identity`() {
        val repository = SyntheticRepository()
        val service = ManualEventService(repository, clock)
        val created = (1..20).map { service.create(request()) }
        assertEquals(20, created.map { it.id }.distinct().size)
        created.forEach { assertEquals(it.id, UUID.fromString(it.id).toString()) }
        val updated = service.update(created.first().id, request("2020-06-03", notes = "Synthetic replacement"))
        assertEquals(created.first().id, updated.id)
        assertEquals("2020-06-03", updated.startDate)
        assertEquals("Synthetic replacement", updated.notes)
        assertEquals(20, service.list().events.size)
        assertEquals(updated, repository.events().single { it.id == updated.id })
        assertTrue(service.remove(updated.id))
        assertFalse(service.remove(updated.id))
        assertEquals(19, service.list().events.size)
    }

    @Test fun `unknown update never upserts and invalid identifiers never mutate repository`() {
        val repository = SyntheticRepository()
        val service = ManualEventService(repository, clock)
        assertFailsWith<EventNotFound> { service.update(id, request()) }
        assertFalse(service.remove(id))
        for (invalid in listOf("", "1-1-1-1-1", id.uppercase().replace("00000001", "ABCDEF01"), "$id\u0000", " $id")) {
            assertFailsWith<IllegalArgumentException> { service.update(invalid, request()) }
            assertFailsWith<IllegalArgumentException> { service.remove(invalid) }
            assertFailsWith<IllegalArgumentException> { ManualEvent(invalid, "2020-06-01", "2020-06-01", "Run", "Goal") }
        }
        assertEquals(0, repository.writes)
        assertTrue(repository.events().isEmpty())
    }

    @Test fun `list sorts by start end identifier and separates ongoing from upcoming`() {
        val past = event(1, "2020-05-01")
        val ongoing = event(2, "2020-05-30", "2020-06-01")
        val multiDay = event(3, "2020-05-31", "2020-06-03")
        val todayLaterEnd = event(4, "2020-06-01", "2020-06-04")
        val todayHighId = event(6, "2020-06-01")
        val todayLowId = event(5, "2020-06-01")
        val future = event(7, "2020-06-02")
        val repository = SyntheticRepository(listOf(future, todayHighId, todayLaterEnd, multiDay, past, todayLowId, ongoing))
        val list = ManualEventService(repository, clock).list()
        assertEquals(listOf(past, ongoing, multiDay, todayLowId, todayHighId, todayLaterEnd, future), list.events)
        assertEquals(todayLowId, list.nextUpcoming)
        assertEquals(listOf(ongoing, multiDay), list.ongoing)
        assertEquals("2020-06-01", list.evaluatedOn)
        assertEquals(0, repository.writes)
    }

    @Test fun `empty past only and ongoing only lists have no invented upcoming event`() {
        for (events in listOf(emptyList(), listOf(event(1, "2020-05-31")), listOf(event(2, "2020-05-31", "2020-06-02")))) {
            val list = ManualEventService(SyntheticRepository(events), clock).list()
            assertEquals(events, list.events)
            assertNull(list.nextUpcoming)
            assertEquals(events.filter { it.endDate >= "2020-06-01" }, list.ongoing)
        }
    }

    @Test fun `evaluation uses one UTC date and advances at midnight`() {
        val records = listOf(event(1, "2020-06-01", "2020-06-02"), event(2, "2020-06-02"))
        val before = Clock.fixed(Instant.parse("2020-06-01T23:59:59Z"), ZoneId.of("Pacific/Auckland"))
        val after = Clock.fixed(Instant.parse("2020-06-02T00:00:00Z"), ZoneId.of("America/Los_Angeles"))
        val repository = SyntheticRepository(records)
        assertEquals(records.first(), ManualEventService(repository, before).list().nextUpcoming)
        val next = ManualEventService(repository, after).list()
        assertEquals("2020-06-02", next.evaluatedOn)
        assertEquals(records.last(), next.nextUpcoming)
        assertEquals(listOf(records.first()), next.ongoing)
    }

    @Test fun `dates require strict ISO real dates and bounded ordered spans`() {
        for (invalid in listOf("2020-6-01", "2020-06-1", "2020-02-30", "2019-02-29", "2020-13-01",
            "2020-00-01", "0000-01-01", "10000-01-01", "+2020-06-01", "2020-06-01T00:00:00Z", "2020-06-01\u0000")) {
            assertFailsWith<IllegalArgumentException> { request(start = invalid) }
            assertFailsWith<IllegalArgumentException> { request(end = invalid) }
        }
        assertFailsWith<IllegalArgumentException> { request("2020-06-02", "2020-06-01") }
        val start = LocalDate.of(2020, 1, 1)
        request(start.toString(), start.plusDays(3660).toString())
        assertFailsWith<IllegalArgumentException> { request(start.toString(), start.plusDays(3661).toString()) }
        request("2020-02-29")
        request("0001-01-01")
        request("9999-12-31")
    }

    @Test fun `required text and optional notes enforce bounds without normalization`() {
        request(sport = "s".repeat(80), goal = "g".repeat(2000), notes = "n".repeat(4000))
        for (empty in listOf("", " \n\t ", "\u2003")) {
            assertFailsWith<IllegalArgumentException> { request(sport = empty) }
            assertFailsWith<IllegalArgumentException> { request(goal = empty) }
        }
        assertFailsWith<IllegalArgumentException> { request(sport = "s".repeat(81)) }
        assertFailsWith<IllegalArgumentException> { request(goal = "g".repeat(2001)) }
        assertFailsWith<IllegalArgumentException> { request(notes = "n".repeat(4001)) }
        val notes = "  Příliš žluťoučký 🏃\n\t目標 e\u0301  "
        val created = ManualEventService(SyntheticRepository(), clock).create(request(sport = " Run ", goal = " Goal ", notes = notes))
        assertEquals(" Run ", created.sport)
        assertEquals(" Goal ", created.goal)
        assertEquals(notes, created.notes)
        assertEquals("", request(notes = "").notes)
        assertEquals("  ", request(notes = "  ").notes)
        assertNull(request().notes)
    }

    @Test fun `control characters including NUL are rejected from personal text`() {
        for (control in listOf('\u0000', '\u0001', '\r', '\u001f', '\u007f', '\u0085', '\u009f')) {
            assertFailsWith<IllegalArgumentException> { request(sport = "Run$control") }
            assertFailsWith<IllegalArgumentException> { request(goal = "Goal$control") }
            assertFailsWith<IllegalArgumentException> { request(notes = "Notes$control") }
        }
        request(goal = "Line one\n\tLine two", notes = "\n\t")
    }

    @Test fun `serialization supports omitted or null notes and validates decoded records`() {
        val json = """{"startDate":"2020-06-01","endDate":"2020-06-01","sport":"Run","goal":"Synthetic goal"}"""
        assertNull(Json.decodeFromString<ManualEventRequest>(json).notes)
        assertNull(Json.decodeFromString<ManualEventRequest>(json.dropLast(1) + ",\"notes\":null}").notes)
        val record = ManualEvent(id, "2020-06-01", "2020-06-01", "Run", "Synthetic goal", "  e\u0301\n🏃  ")
        assertEquals(record, Json.decodeFromString<ManualEvent>(Json.encodeToString(record)))
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<ManualEventRequest>(json.replace("2020-06-01", "2020-02-30"))
        }
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<ManualEvent>(Json.encodeToString(record).replace(id, "bad-id"))
        }
    }

    @Test fun `records requests lists and validation errors redact personal content`() {
        val secret = "Synthetic private marker"
        val request = request(sport = secret, goal = secret, notes = secret)
        val event = ManualEventService(SyntheticRepository(), clock).create(request)
        val list = ManualEventList(listOf(event), event, listOf(event), "2020-06-01")
        assertEquals("ManualEvent([REDACTED])", event.toString())
        assertEquals("ManualEventRequest([REDACTED])", request.toString())
        assertEquals("ManualEventList([REDACTED])", list.toString())
        val error = assertFailsWith<IllegalArgumentException> { request(notes = "$secret\u0000") }
        assertFalse(error.toString().contains(secret))
        assertFalse(EventNotFound().toString().contains(event.id))
    }
}
