package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class LiveIntakePilotTest {
    private val json = Json { encodeDefaults = true }
    private fun fixture(): LiveIntakeCapture {
        val witness = SyntheticIntakePilot.witnesses().values.first { it.provenance.caseId == "u02" }
        val packet = json.encodeToString(witness.packet)
        val checked = IntakeExtractionV2.validate(witness.wire, witness.packet)
        val draft = requireNotNull(checked.assembledDraft)
        return LiveIntakeCapture(LiveIntakeCapture.PROFILE, "synthetic_fake", UUID.randomUUID().toString(),
            packet, LiveIntakeCapture.sha(packet), "1".repeat(64), witness.wire, LiveIntakeCapture.sha(witness.wire),
            draft, LiveIntakeCapture.sha(draft), "2".repeat(64))
    }

    @Test fun `strict capture checks preserve source raw and assembled draft without repair`() {
        val capture = fixture()
        val draft = capture.validate(capture.receiptSha256, "synthetic_fake")
        assertEquals(2, draft.suggestions.size)
        assertTrue(draft.suggestions.any { it.unresolvedFields.isNotEmpty() })
        for (bad in listOf(capture.copy(packetSha256 = "0".repeat(64)), capture.copy(rawSha256 = "0".repeat(64)),
            capture.copy(draftSha256 = "0".repeat(64)), capture.copy(profile = "context-intake-suggestion-v1"),
            capture.copy(acquisitionId = "not-an-id"))) {
            assertFailsWith<IllegalArgumentException> { bad.validate(capture.receiptSha256, "synthetic_fake") }
        }
        assertFailsWith<IllegalArgumentException> { capture.validate(capture.receiptSha256, "live") }
        assertFailsWith<IllegalArgumentException> { capture.validate("0".repeat(64), "synthetic_fake") }
        val raw = "{}"
        assertFailsWith<IllegalArgumentException> { capture.copy(raw = raw, rawSha256 = LiveIntakeCapture.sha(raw)).validate(capture.receiptSha256, "synthetic_fake") }
    }

    @Test fun `new capture stores seed one source and no authoritative items and dispose only owned files`() {
        val capture = fixture()
        val first = LiveIntakePilot.State.fresh(capture); val second = LiveIntakePilot.State.fresh(capture)
        val firstPath = first.directory; val secondPath = second.directory
        try {
            assertNotEquals(firstPath, secondPath)
            assertEquals(listOf(capture.packet.intake), first.store.intakes())
            assertTrue(first.store.commitments().isEmpty())
            assertTrue(first.store.contexts(java.time.LocalDate.parse("2026-10-21")).isEmpty())
            val old = first.source
            first.store.correctIntake(old.intakeId, ContextIntakeCorrection(old.revision,
                ContextIntakeRequest(old.content + " Synthetic correction.", old.sourceCategory, old.authorAttribution, old.observedOn)), Instant.now())
            assertEquals("unavailable", first.suggestions(old.intakeId, old.revision).status)
            assertNull(first.suggestions(old.intakeId, old.revision + 1).draft)
            assertEquals("available", second.suggestions(old.intakeId, old.revision).status)
            first.close(); assertFalse(Files.exists(firstPath)); assertTrue(Files.exists(secondPath))
        } finally { first.close(); second.close() }
        assertFalse(Files.exists(secondPath))
    }

    @Test fun `capture API is authenticated inert allowlisted and confirms explicitly with replay safety`() {
        val capture = fixture(); val state = LiveIntakePilot.State.fresh(capture)
        try { testApplication {
            application { LiveIntakePilot.run { module(state, SingleUserAuth(syntheticVerifier(), LiveIntakePilot.ORIGIN, false, false, insecureLoopbackPort = 8082)) } }
            val id = state.source.intakeId; val revision = state.source.revision
            val endpoint = "/api/live-intake-pilot/$id/suggestions?expectedRevision=$revision"
            assertEquals(HttpStatusCode.Unauthorized, client.get(endpoint).status)
            val login = client.post("/api/login") { header(HttpHeaders.Origin, LiveIntakePilot.ORIGIN); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            assertEquals(HttpStatusCode.OK, login.status)
            val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun get(path: String) = client.get(path) { header(HttpHeaders.Cookie, cookie) }
            val profile = Json.parseToJsonElement(get("/api/live-intake-pilot/profile").bodyAsText()).jsonObject
            assertEquals("synthetic_fake", profile.getValue("evidenceMode").jsonPrimitive.content)
            val suggestions = Json.parseToJsonElement(get(endpoint).bodyAsText()).jsonObject
            assertEquals("synthetic_fake_acquisition", suggestions.getValue("provenance").jsonObject.getValue("kind").jsonPrimitive.content)
            assertEquals("unavailable", Json.parseToJsonElement(get(endpoint.substringBefore('?') + "?expectedRevision=${revision + 1}").bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content)
            assertTrue(state.store.commitments().isEmpty())
            val definition = CommitmentDefinition("Synthetic reviewed training", "commitment", "UTC", "2026-11-07T14:00", "2026-11-07T15:00")
            val request = ContextConfirmationRequest(revision, emptyList(), listOf(CommitmentConfirmationItem(definition)), UUID.randomUUID().toString())
            suspend fun send(path: String, value: String, token: String? = csrf, origin: String = LiveIntakePilot.ORIGIN) = client.post(path) {
                header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); if (token != null) header("X-CSRF-Token", token)
                contentType(ContentType.Application.Json); setBody(value)
            }
            val body = json.encodeToString(request); val confirm = "/api/context-intakes/$id/confirm"
            assertEquals(HttpStatusCode.Forbidden, send(confirm, body, token = null).status)
            assertEquals(HttpStatusCode.Forbidden, send(confirm, body, origin = "https://untrusted.invalid").status)
            assertEquals(HttpStatusCode.NotFound, send("/api/context-intakes", "{}").status)
            assertEquals(HttpStatusCode.NotFound, send("/api/scoped-executions", "{}").status)
            assertEquals(HttpStatusCode.OK, send(confirm, body).status)
            assertEquals(1, state.store.commitments().size)
            assertTrue(Json.decodeFromString<ContextConfirmationResult>(send(confirm, body).bodyAsText()).replayed)
            assertEquals(1, state.store.commitments().size)
            assertEquals(HttpStatusCode.Conflict, send(confirm, json.encodeToString(request.copy(expectedRevision = revision + 1))).status)
            assertEquals(HttpStatusCode.Conflict, send(confirm, json.encodeToString(request.copy(commitments = listOf(CommitmentConfirmationItem(definition.copy(label = "Changed")))))).status)
            assertEquals(1, state.store.commitments().size)
            assertFalse(ScopedProfileAdmission.inspectAll().any { it.executionAllowed })
        } } finally { state.close() }
    }

    @Test fun `startup admission rejects missing independent digest symlink and unregistered files`() {
        val directory = Files.createTempDirectory("synthetic-live-import-negative-")
        try {
            assertFailsWith<IllegalArgumentException> { LiveIntakeCapture.load(directory, "", "synthetic_fake") }
            val link = directory.resolve("link"); Files.createSymbolicLink(link, directory)
            assertFailsWith<IllegalArgumentException> { LiveIntakeCapture.load(link, "1".repeat(64), "synthetic_fake") }
            assertFailsWith<IllegalArgumentException> { LiveIntakeCapture.load(directory, "1".repeat(64), "synthetic_fake") }
        } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun `session deadline is monotonic fixed and always invokes cleanup`() {
        var elapsed = 50_000_000_000L; var slept = -1L; var stopped = 0
        LiveIntakePilot.enforceSessionDeadline(0, now = { elapsed }, sleep = { slept = it; elapsed += it * 1_000_000 }) { stopped++ }
        assertEquals(1_150_000L, slept); assertEquals(1, stopped)
        LiveIntakePilot.enforceSessionDeadline(0, now = { 1_201_000_000_000 }, sleep = { fail("Expired sessions cannot wait") }) { stopped++ }
        assertEquals(2, stopped)
        assertFailsWith<IllegalArgumentException> {
            LiveIntakePilot.enforceSessionDeadline(10, now = { 9 }, sleep = { fail("Regressed clocks cannot extend a session") }) { stopped++ }
        }
        assertEquals(3, stopped)
    }

    @Test fun `session claim cannot restart its budget and is private at creation`() {
        val directory = Files.createTempDirectory("synthetic-live-session-")
        try {
            val captureRoot = Files.createDirectory(directory.resolve("capture"))
            val claim = LiveIntakePilot.claimSession(captureRoot, "1".repeat(64), 1000)
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(claim)))
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { LiveIntakePilot.claimSession(captureRoot, "1".repeat(64), 2000) }
            val body = Json.parseToJsonElement(Files.readString(claim)).jsonObject
            assertEquals(1_201_000L, body.getValue("deadlineUnixMillis").jsonPrimitive.long)
        } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}

/** Optional explicit private integration, acknowledged separately so absence is never claimed as a run. */
class LiveIntakeCaptureBridgeTest {
    @Test fun `explicit sealed fake or live artifact passes actual startup admission`() {
        val root = System.getenv("GTRAINER_LIVE_INTAKE_TEST_DIR") ?: return
        val receipt = requireNotNull(System.getenv("GTRAINER_LIVE_INTAKE_TEST_RECEIPT_SHA256"))
        val mode = requireNotNull(System.getenv("GTRAINER_LIVE_INTAKE_TEST_MODE"))
        val capture = LiveIntakeCapture.load(Path.of(root), receipt, mode)
        capture.validate(receipt, mode)
        Files.writeString(Path.of(root).parent.resolve("kotlin-import-ack.json"),
            """{"receiptSha256":"$receipt","evidenceMode":"$mode","actualStartupAdmission":true}""",
            java.nio.file.StandardOpenOption.CREATE_NEW)
    }
}
