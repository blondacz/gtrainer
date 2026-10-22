package com.gtrainer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContextIntakeSuggestionContractV1Test {
    private val intake = ContextIntake("11111111-1111-4111-8111-111111111111", 2, "Wednesday unavailable", "user_report", "athlete", "user", "2026-03-01", "2026-03-02T10:00:00Z")
    private val packet = ContextIntakeSuggestionContractV1.packet(intake)
    private fun draft(id: String = intake.intakeId, revision: Int = intake.revision, excerpt: String = "Wednesday") = ContextIntakeSuggestionDraftV1(
        ContextIntakeSuggestionContractV1.PROFILE, id, revision,
        listOf(ContextIntakeSuggestionV1("context", 0, excerpt.length, excerpt,
            context = ProposedContextSuggestionV1("preference", null, null, null, "Unavailable on Wednesday"),
            unresolvedFields = listOf("observedOn", "applicableFrom", "applicableUntil"))), listOf(ContextIntakeSuggestionQuestionV1("Which date do you mean?")))
    @Test fun `validates source offsets and preserves intake attribution dates`() {
        val accepted = ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(draft()), packet)
        assertEquals("athlete", packet.intake.authorAttribution)
        assertEquals("2026-03-01", packet.intake.observedOn)
        assertEquals("2026-03-02T10:00:00Z", packet.intake.recordedUtc)
        assertEquals("Wednesday", accepted.suggestions.single().sourceExcerpt)
        assertThrows(IllegalArgumentException::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(draft("other")), packet) }
        assertThrows(IllegalArgumentException::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(draft(revision = 1)), packet) }
        assertThrows(IllegalArgumentException::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(draft(excerpt = "Invented")), packet) }
    }
    @Test fun `strict json rejects unknown and duplicate keys`() {
        val raw = ContextIntakeSuggestionContractV1.encodeDraft(draft())
        assertThrows(Exception::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(raw.dropLast(1) + ",\"confirmed\":true}", packet) }
        assertThrows(Exception::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(raw.replace("\"profile\":", "\"profile\":\"wrong\",\"profile\":"), packet) }
        assertThrows(Exception::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(raw.replace("\"intakeRevision\":2", "\"intakeRevision\":2,\"authorAttribution\":\"override\""), packet) }
    }
    @Test fun `date resolution context remains explicit and missing fields unresolved`() {
        val without = ContextIntakeSuggestionContractV1.prompt(packet)
        assertFalse(without.userPacketJson.contains("referenceDate"))
        val with = ContextIntakeSuggestionContractV1.prompt(ContextIntakeSuggestionContractV1.packet(intake, ContextIntakeDateResolutionV1("2026-03-01", "UTC")))
        assertTrue(with.userPacketJson.contains("referenceDate"))
        assertEquals(listOf("observedOn", "applicableFrom", "applicableUntil"), ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(draft()), packet).suggestions.single().unresolvedFields)
    }
    @Test fun `rejects missing unresolved disclosures bad commitment values and coercion`() {
        val complete = ProposedCommitmentSuggestionV1("Appointment", "commitment", null, null, null, null)
        fun commitment(unresolved: List<String>, questions: List<ContextIntakeSuggestionQuestionV1> = listOf(ContextIntakeSuggestionQuestionV1("Please clarify dates and recurrence."))) =
            ContextIntakeSuggestionDraftV1(ContextIntakeSuggestionContractV1.PROFILE, intake.intakeId, intake.revision,
                listOf(ContextIntakeSuggestionV1("commitment", 0, 9, "Wednesday", commitment = complete, unresolvedFields = unresolved)), questions)
        fun rejected(value: ContextIntakeSuggestionDraftV1, source: ContextIntakeSuggestionPacketV1 = packet) =
            assertThrows(Exception::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(value), source) }
        val absent = listOf("startsLocal", "endsLocal", "timeZone", "recurrence")
        rejected(commitment(emptyList()))
        rejected(commitment(absent, emptyList()))
        rejected(commitment(absent).copy(suggestions = listOf(ContextIntakeSuggestionV1("commitment", 0, 9, "Wednesday", commitment = complete.copy(kind = null), unresolvedFields = absent))))
        rejected(commitment(absent + "kind").copy(suggestions = listOf(ContextIntakeSuggestionV1("commitment", 0, 9, "Wednesday", commitment = complete.copy(kind = "other"), unresolvedFields = absent + "kind"))))
        rejected(commitment(absent).copy(suggestions = listOf(ContextIntakeSuggestionV1("commitment", 0, 9, "Wednesday", commitment = complete.copy(recurrence = ProposedRecurrenceSuggestionV1("weekly", 2, "bounded", "2099-01-01")), unresolvedFields = absent))))
        val malformed = ContextIntakeSuggestionContractV1.encodeDraft(draft()).replace("\"intakeRevision\":2", "\"intakeRevision\":\"2\"")
        assertThrows(Exception::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(malformed, packet) }
        assertThrows(Exception::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(draft()).replace("\"sourceStart\":0", "\"sourceStart\":\"0\""), packet) }
    }
    @Test fun `supports only explicitly resolved next weekday and strict recurrence detail`() {
        val resolvedPacket = ContextIntakeSuggestionContractV1.packet(intake.copy(content = "Next Wednesday meeting"), ContextIntakeDateResolutionV1("2026-03-01", "UTC"))
        val nextDate = "2026-03-04"
        val rec = ProposedRecurrenceSuggestionV1("one_off", null, "open", null)
        val proposal = ContextIntakeSuggestionDraftV1(ContextIntakeSuggestionContractV1.PROFILE, intake.intakeId, intake.revision,
            listOf(ContextIntakeSuggestionV1("commitment", 0, 22, "Next Wednesday meeting", commitment = ProposedCommitmentSuggestionV1("Meeting", "commitment", "$nextDate" + "T09:00", "$nextDate" + "T10:00", "UTC", rec), unresolvedFields = emptyList())), emptyList())
        assertDoesNotThrow { ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(proposal), resolvedPacket) }
        val fabricated = proposal.copy(suggestions = listOf(proposal.suggestions.single().copy(commitment = proposal.suggestions.single().commitment!!.copy(startsLocal = "2099-01-01T09:00"))))
        assertThrows(Exception::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(fabricated), resolvedPacket) }
        val unsupported = proposal.copy(suggestions = listOf(proposal.suggestions.single().copy(commitment = proposal.suggestions.single().commitment!!.copy(recurrence = ProposedRecurrenceSuggestionV1("daily", null, "unknown", null)), unresolvedFields = listOf("recurrence.interval", "recurrence.end"))), questions = listOf(ContextIntakeSuggestionQuestionV1("What interval and end date?")))
        assertDoesNotThrow { ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(unsupported), resolvedPacket) }
        val otherWeek = ContextIntakeSuggestionContractV1.packet(intake.copy(content = "Every other Wednesday"), ContextIntakeDateResolutionV1("2026-03-01", "UTC"))
        val everyOther = ContextIntakeSuggestionDraftV1(ContextIntakeSuggestionContractV1.PROFILE, intake.intakeId, intake.revision,
            listOf(ContextIntakeSuggestionV1("commitment", 0, 21, "Every other Wednesday", commitment = ProposedCommitmentSuggestionV1("Availability", "availability", null, null, null, ProposedRecurrenceSuggestionV1("weekly", 2, "unknown", null)), unresolvedFields = listOf("startsLocal", "endsLocal", "timeZone", "recurrence.end"))),
            listOf(ContextIntakeSuggestionQuestionV1("Which time zone and times apply?")))
        assertDoesNotThrow { ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(everyOther), otherWeek) }
        val fabricatedContext = draft().copy(suggestions = listOf(ContextIntakeSuggestionV1("context", 0, 9, "Wednesday",
            context = ProposedContextSuggestionV1("preference", null, "2099-01-01", null, "Unavailable", "specified", "unknown"),
            unresolvedFields = listOf("observedOn", "applicableUntil"))))
        assertThrows(Exception::class.java) { ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(fabricatedContext), packet) }
    }
}
