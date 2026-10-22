package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class SyntheticIntakePilotTest {
    private val json = Json { encodeDefaults = true }

    @Test fun `all eight handwritten witnesses have strict source-bound provenance`() {
        val witnesses = SyntheticIntakePilot.witnesses()
        assertEquals(8, witnesses.size)
        assertEquals((1..8).map { "u${it.toString().padStart(2, '0')}" }.toSet(), witnesses.values.map { it.provenance.caseId }.toSet())
        witnesses.values.forEach { witness ->
            val validation = IntakeExtractionV2.validate(witness.wire, witness.packet)
            assertEquals("accepted", validation.semantic)
            assertEquals(witness.id, witness.validatedDraft().jsonObject.getValue("intakeId").jsonPrimitive.content)
            assertEquals(64, witness.provenance.packetSha256.length)
            assertEquals(64, witness.provenance.wireSha256.length)
            assertEquals("handwritten_frozen_witness", witness.provenance.kind)
        }
        assertFalse(ScopedProfileAdmission.inspectAll().any { it.executionAllowed })
    }

    @Test fun `fresh store is isolated and only its generated directory is disposed`() {
        val first = SyntheticIntakePilot.State.fresh()
        val second = SyntheticIntakePilot.State.fresh()
        val firstPath = first.directory
        val secondPath = second.directory
        try {
            assertNotEquals(firstPath, secondPath)
            assertEquals(8, first.counts().getValue("context_intakes"))
            assertEquals(0, first.counts().getValue("athlete_context"))
            assertEquals(0, first.counts().getValue("commitment_revisions"))
            assertEquals(0, first.executionRows())
            val source = first.store.intakes().first()
            first.store.correctIntake(source.intakeId, ContextIntakeCorrection(source.revision,
                ContextIntakeRequest(source.content + " Synthetic correction.", source.sourceCategory, source.authorAttribution, source.observedOn)), java.time.Instant.now())
            assertEquals(source, second.store.intakeHistory(source.intakeId).first())
            first.close()
            assertFalse(Files.exists(firstPath))
            assertTrue(Files.isDirectory(secondPath))
        } finally { first.close(); second.close() }
        assertFalse(Files.exists(secondPath))
    }

    @Test fun `wrong identity revision and same revision changed content never return a witness`() {
        SyntheticIntakePilot.State.fresh().use { state ->
            val witness = state.fixtures.values.first { it.provenance.caseId == "u03" }
            assertFailsWith<ContextIntakeNotFound> { state.suggestion(UUID.randomUUID().toString(), 1) }
            assertEquals("unavailable", state.suggestion(witness.id, witness.revision + 1).status)
            DriverManager.getConnection("jdbc:sqlite:${state.directory.resolve("history.db")}").use { connection ->
                connection.prepareStatement("UPDATE context_intakes SET content=? WHERE intake_id=?").use { statement ->
                    statement.setString(1, "A different bundled-test source at the same revision.")
                    statement.setString(2, witness.id); assertEquals(1, statement.executeUpdate())
                }
            }
            val unavailable = state.suggestion(witness.id, witness.revision)
            assertEquals("unavailable", unavailable.status)
            assertNull(unavailable.provenance); assertNull(unavailable.draft)
            assertEquals(0, state.counts().getValue("context_confirmation_receipts"))
            assertEquals(0, state.executionRows())
        }
    }

    @Test fun `authenticated inspection cannot create authority and correction invalidates fixtures`() {
        val state = SyntheticIntakePilot.State.fresh()
        val directory = state.directory
        try { testApplication {
            application { with(SyntheticIntakePilot) { module(state, SingleUserAuth(syntheticVerifier(), ORIGIN, false, false, insecureLoopbackPort = 8082)) } }
            val source = state.store.intakes().first()
            val endpoint = "/api/synthetic-intake-pilot/${source.intakeId}/suggestions?expectedRevision=${source.revision}"
            assertEquals(HttpStatusCode.Unauthorized, client.get(endpoint).status)
            val login = client.post("/api/login") { header(HttpHeaders.Origin, SyntheticIntakePilot.ORIGIN); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("password", SYNTHETIC_PASSWORD) }.toString()) }
            assertEquals(HttpStatusCode.OK, login.status)
            val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            assertTrue(cookie.startsWith("$SYNTHETIC_INTAKE_PILOT_SESSION_COOKIE="))
            val session = Json.parseToJsonElement(login.bodyAsText()).jsonObject
            assertFalse(session.getValue("intervalsConfigured").jsonPrimitive.boolean)
            val token = session.getValue("csrfToken").jsonPrimitive.content
            suspend fun get(path: String) = client.get(path) { header(HttpHeaders.Cookie, cookie) }
            suspend fun put(path: String, body: String) = client.put(path) { header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, SyntheticIntakePilot.ORIGIN); header("X-CSRF-Token", token); contentType(ContentType.Application.Json); setBody(body) }
            val before = state.counts()
            for (witness in state.fixtures.values) {
                val response = get("/api/synthetic-intake-pilot/${witness.id}/suggestions?expectedRevision=${witness.revision}")
                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(setOf("profile", "intakeId", "intakeRevision", "status", "provenance", "draft"), body.keys)
                assertEquals("available", body.getValue("status").jsonPrimitive.content)
                assertEquals("handwritten_frozen_witness", body.getValue("provenance").jsonObject.getValue("kind").jsonPrimitive.content)
                assertEquals(witness.validatedDraft(), body.getValue("draft"))
            }
            for (path in listOf("/api/context-intakes", "/api/context-lifecycle", "/api/commitments", "/api/context-intakes/${source.intakeId}/links")) assertEquals(HttpStatusCode.OK, get(path).status)
            assertEquals(before, state.counts())
            assertEquals(0, state.executionRows())
            for (query in listOf("expectedRevision=0", "expectedRevision=1&expectedRevision=2", "expectedRevision=1&extra=true")) {
                assertEquals(HttpStatusCode.BadRequest, get(endpoint.substringBefore('?') + "?" + query).status)
            }
            for (path in listOf("/api/context-intakes", "/api/commitments", "/api/context-intakes/${source.intakeId}/links", "/api/scoped-executions")) {
                assertEquals(HttpStatusCode.NotFound, client.post(path) { header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, SyntheticIntakePilot.ORIGIN); header("X-CSRF-Token", token); contentType(ContentType.Application.Json); setBody("{}") }.status)
            }
            assertEquals(before, state.counts())
            val correction = ContextIntakeCorrection(source.revision, ContextIntakeRequest(source.content + " Synthetic correction.", source.sourceCategory, source.authorAttribution, source.observedOn))
            assertEquals(HttpStatusCode.OK, put("/api/context-intakes/${source.intakeId}", json.encodeToString(correction)).status)
            val unavailable = Json.parseToJsonElement(get(endpoint).bodyAsText()).jsonObject
            assertEquals("unavailable", unavailable.getValue("status").jsonPrimitive.content)
            assertEquals(JsonNull, unavailable.getValue("draft")); assertEquals(JsonNull, unavailable.getValue("provenance"))
            assertEquals("unavailable", Json.parseToJsonElement(get(endpoint.substringBefore('?') + "?expectedRevision=${source.revision + 1}").bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(0, state.counts().getValue("context_confirmation_receipts"))
            assertFalse(ScopedProfileAdmission.inspectAll().any { it.executionAllowed })
        } } finally { state.close() }
        assertFalse(Files.exists(directory))
    }

    @Test fun `explicit confirmation is csrf protected atomic replay safe and revision bound`() {
        val state = SyntheticIntakePilot.State.fresh()
        try { testApplication {
            application { with(SyntheticIntakePilot) { module(state, SingleUserAuth(syntheticVerifier(), ORIGIN, false, false, insecureLoopbackPort = 8082)) } }
            val source = state.fixtures.values.first { it.provenance.caseId == "u01" }.packet.intake
            val endpoint = "/api/context-intakes/${source.intakeId}/confirm"
            assertEquals(HttpStatusCode.Unauthorized, client.post(endpoint).status)
            val login = client.post("/api/login") { header(HttpHeaders.Origin, SyntheticIntakePilot.ORIGIN); setBody(buildJsonObject { put("password", SYNTHETIC_PASSWORD) }.toString()) }
            val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val token = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun send(body: String, csrf: String? = token, origin: String = SyntheticIntakePilot.ORIGIN) = client.post(endpoint) {
                header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); if (csrf != null) header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json); setBody(body)
            }
            val context = AthleteContextRequest("symptom", source.sourceCategory, source.authorAttribution, source.observedOn, content = "Synthetic reviewed symptom")
            val definition = CommitmentDefinition("Appointment", "commitment", "UTC", "2026-11-03T11:15", "2026-11-03T12:00")
            val request = ContextConfirmationRequest(source.revision, listOf(ContextConfirmationItem(context)), listOf(CommitmentConfirmationItem(definition)), UUID.randomUUID().toString())
            val body = json.encodeToString(request)
            val before = state.counts()
            assertEquals(HttpStatusCode.Forbidden, send(body, csrf = null).status)
            assertEquals(HttpStatusCode.Forbidden, send(body, origin = "https://untrusted.invalid").status)
            val invalid = send(body.replace("2026-11-03T12:00", "2026-11-03T10:00"))
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("{\"error\":\"invalid_request\"}", invalid.bodyAsText())
            assertFalse(invalid.bodyAsText().contains("Synthetic"))
            assertEquals(before, state.counts())
            val initial = send(body)
            assertEquals(HttpStatusCode.OK, initial.status)
            val saved = Json.decodeFromString<ContextConfirmationResult>(initial.bodyAsText())
            assertEquals(1, saved.contexts.size); assertEquals(1, saved.commitments.size); assertFalse(saved.replayed)
            val after = state.counts()
            assertEquals(1, after.getValue("context_confirmation_receipts"))
            assertEquals(1, after.getValue("athlete_context")); assertEquals(1, after.getValue("commitment_revisions"))
            val replay = Json.decodeFromString<ContextConfirmationResult>(send(body).bodyAsText())
            assertTrue(replay.replayed); assertEquals(saved.contexts, replay.contexts); assertEquals(saved.commitments, replay.commitments)
            assertEquals(after, state.counts())
            assertEquals(HttpStatusCode.Conflict, send(json.encodeToString(request.copy(contexts = listOf(ContextConfirmationItem(context.copy(content = "Changed synthetic symptom")))))).status)
            val stale = request.copy(expectedRevision = source.revision + 1, confirmationId = UUID.randomUUID().toString())
            assertEquals(HttpStatusCode.Conflict, send(json.encodeToString(stale)).status)
            assertEquals(HttpStatusCode.BadRequest, send(body.dropLast(1) + ",\"confirmationId\":\"duplicate\"}").status)
            assertEquals(after, state.counts())
            assertEquals(0, state.executionRows())
        } } finally { state.close() }
    }

    @Test fun `strict witness validation rejects wire excerpt unsupported date and mode mutations`() {
        val witnesses = SyntheticIntakePilot.witnesses().values
        val witness = witnesses.first { it.provenance.caseId == "u03" }
        assertEquals("rejected", IntakeExtractionV2.validate("{}", witness.packet).wire)
        val absent = witness.wire.replace("A rehearsal on Tuesday evening is tentative", "Absent synthetic source")
        assertNotEquals(witness.wire, absent)
        assertEquals("rejected", IntakeExtractionV2.validate(absent, witness.packet).binding)
        assertEquals("rejected", IntakeExtractionV2.validate(witness.wire.replace("\"questions\":", "\"questions\":[],\"questions\":"), witness.packet).wire)
        val unsupported = witness.wire.replace("\"startsLocal\":null", "\"startsLocal\":\"2027-01-01T10:00\"").replace("\"startsLocal\",", "")
        val unsupportedResult = IntakeExtractionV2.validate(unsupported, witness.packet)
        assertEquals("accepted", unsupportedResult.wire); assertEquals("accepted", unsupportedResult.binding); assertEquals("rejected", unsupportedResult.semantic)
        val symptom = witnesses.first { it.provenance.caseId == "u07" }
        val wrongMode = symptom.wire.replace("\"applicableUntilMode\":\"unknown\"", "\"applicableUntilMode\":\"open\"")
        assertNotEquals(symptom.wire, wrongMode)
        assertEquals("rejected", IntakeExtractionV2.validate(wrongMode, symptom.packet).semantic)
    }
}
