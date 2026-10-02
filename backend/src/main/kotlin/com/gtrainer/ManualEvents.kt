package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/** User-authored records only; never a source of model input or automatic training decisions. */
@Serializable
data class ManualEvent(
    val id: String,
    val startDate: String,
    val endDate: String,
    val sport: String,
    val goal: String,
    val notes: String? = null,
) {
    init {
        validateEventId(id)
        val start = parseEventDate(startDate)
        val end = parseEventDate(endDate)
        require(end >= start && ChronoUnit.DAYS.between(start, end) <= 3660) { "Invalid event date range" }
        validateEventText(sport, 80, required = true)
        validateEventText(goal, 2000, required = true)
        notes?.let { validateEventText(it, 4000, required = false) }
    }

    override fun toString(): String = "ManualEvent([REDACTED])"
}

@Serializable
data class ManualEventRequest(
    val startDate: String,
    val endDate: String,
    val sport: String,
    val goal: String,
    val notes: String? = null,
) {
    init {
        ManualEvent("00000000-0000-0000-0000-000000000000", startDate, endDate, sport, goal, notes)
    }

    override fun toString(): String = "ManualEventRequest([REDACTED])"
}

@Serializable
data class ManualEventList(
    val events: List<ManualEvent>,
    val nextUpcoming: ManualEvent?,
    val ongoing: List<ManualEvent>,
    val evaluatedOn: String,
) {
    override fun toString(): String = "ManualEventList([REDACTED])"
}

/** Separate from imported history, event sources, and model configuration. */
interface ManualEventRepository {
    fun events(): List<ManualEvent>
    fun saveEvent(event: ManualEvent)
    fun deleteEvent(id: String): Boolean
}

class EventNotFound : IllegalStateException("Event not found")

class ManualEventService(
    private val repository: ManualEventRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** UTC date-only ordering. Events starting today are upcoming, not ongoing. */
    @Synchronized fun list(): ManualEventList {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString()
        val events = repository.events().sortedWith(compareBy({ it.startDate }, { it.endDate }, { it.id }))
        return ManualEventList(events, events.firstOrNull { it.startDate >= today },
            events.filter { it.startDate < today && it.endDate >= today }, today)
    }

    @Synchronized fun create(request: ManualEventRequest): ManualEvent = event(UUID.randomUUID().toString(), request).also {
        repository.saveEvent(it)
    }

    /** Never upserts an unknown identifier; the API should map EventNotFound to 404. */
    @Synchronized fun update(id: String, request: ManualEventRequest): ManualEvent {
        validateEventId(id)
        if (repository.events().none { it.id == id }) throw EventNotFound()
        return event(id, request).also { repository.saveEvent(it) }
    }

    /** Invalid identifiers throw IllegalArgumentException; a missing valid identifier returns false. */
    @Synchronized fun remove(id: String): Boolean {
        validateEventId(id)
        return repository.deleteEvent(id)
    }

    private fun event(id: String, request: ManualEventRequest) = ManualEvent(
        id, request.startDate, request.endDate, request.sport, request.goal, request.notes,
    )
}

private val eventIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val eventDatePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

private fun validateEventId(id: String) {
    require(eventIdPattern.matches(id)) { "Invalid event identifier" }
}

private fun parseEventDate(value: String): LocalDate {
    require(eventDatePattern.matches(value) && !value.startsWith("0000-")) { "Invalid event date" }
    return try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("Invalid event date")
    }
}

private fun validateEventText(value: String, maxLength: Int, required: Boolean) {
    require(value.length <= maxLength && (!required || value.trim().isNotEmpty())) { "Invalid event text length" }
    require(value.none { Character.isISOControl(it) && it != '\n' && it != '\t' }) { "Invalid event text characters" }
}
