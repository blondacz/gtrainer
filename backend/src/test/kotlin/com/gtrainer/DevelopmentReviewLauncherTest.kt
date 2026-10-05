package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.*

class DevelopmentReviewLauncherTest {
    private val origin = "http://127.0.0.1:8081"
    private val binding = DevelopmentReviewModelBinding("127.0.0.1", "ollama", "synthetic-model", "Synthetic model",
        "synthetic:fixture", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1)
    private val policy = DevelopmentReviewPolicyBinding(900_000, 1_840_000)

    @Test fun `disabled launcher does not read private or development paths`() {
        assertNull(DevelopmentReviewLaunchSettings.fromEnvironment(mapOf(
            "GTRAINER_PASSWORD_VERIFIER_FILE" to "/not-a-real-private-verifier",
            "GTRAINER_DATABASE" to "/not-a-real-personal-db",
            "GTRAINER_DEVELOPMENT_REVIEW_CONFIG_FILE" to "/missing-development-config")))
        assertNull(DevelopmentReviewLaunchSettings.fromEnvironment(mapOf("GTRAINER_DEVELOPMENT_REVIEW_ENABLED" to "false")))
        val directory = Files.createTempDirectory("disabled-launcher")
        val configuration = configuration(directory).copy(enabled = false, passwordVerifierFile = "/nonexistent")
        assertNull(load(configuration, directory))
        assertFalse(Files.exists(Path.of(configuration.databasePath)))
    }

    @Test fun `nonloopback invalid config and inherited credentials fail before opening storage`() {
        val directory = Files.createTempDirectory("bad-launcher")
        val base = configuration(directory)
        for (invalid in listOf(base.copy(bindAddress = "0.0.0.0"), base.copy(port = 0),
            base.copy(databasePath = "relative.sqlite"), base.copy(models = emptyList()),
            base.copy(models = listOf(binding, binding)), base.copy(passwordVerifierFile = "/missing-development-verifier"))) {
            val failure = assertFailsWith<IllegalArgumentException> { load(invalid, directory) }
            assertEquals("Invalid development launcher configuration", failure.message)
            assertNull(failure.cause)
            assertFalse(Files.exists(Path.of(base.databasePath)))
        }
        assertFailsWith<IllegalArgumentException> {
            DevelopmentReviewLaunchSettings.fromEnvironment(mapOf("GTRAINER_DEVELOPMENT_REVIEW_ENABLED" to "true",
                "GTRAINER_PASSWORD_VERIFIER_FILE" to base.passwordVerifierFile))
        }
        assertFailsWith<IllegalArgumentException> { SingleUserAuth(null, origin, false) }
    }

    @Test fun `disabled worker validates settings without reading ssh files or constructing runtime clients`() {
        val directory = Files.createTempDirectory("disabled-worker-assembly")
        val worker = DevelopmentReviewWorkerConfiguration(false, "synthetic-node", "00000000-0000-0000-0000-000000000001",
            "a".repeat(64), "/isolated/supervisor.sock", DevelopmentHealthPolicy.PI_APPLIANCE_BYTES, true,
            "synthetic.invalid", "synthetic_user", 22, "/nonexistent-synthetic-identity", "/nonexistent-synthetic-known-hosts")
        val settings = assertNotNull(load(configuration(directory).copy(worker = worker), directory))
        DevelopmentReviewStore(settings.databasePath).use { store ->
            assertNull(settings.worker(store))
            assertTrue(store.pendingExecutions().isEmpty())
            if (System.getProperty("os.name") != "Linux" || ProcessHandle.current().parent().orElseThrow().pid() != 1L) {
                val enabled = assertNotNull(load(configuration(directory).copy(worker = worker.copy(enabled = true)), directory))
                assertFailsWith<IllegalArgumentException> { enabled.worker(store) }
            }
        }
    }

    @Test fun `isolated routes authenticate and csrf protect exact synthetic preview`() = testApplication {
        val directory = Files.createTempDirectory("development-api")
        val settings = assertNotNull(load(configuration(directory), directory))
        val store = DevelopmentReviewStore(settings.databasePath)
        application { developmentReviewModule(settings, store) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/development/configuration").status)
        val login = client.post("/api/login") {
            header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$SYNTHETIC_PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        assertTrue(cookie.startsWith("$DEVELOPMENT_SESSION_COOKIE="))
        assertFalse(cookie.startsWith("$SESSION_COOKIE="))
        val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject["csrfToken"]!!.jsonPrimitive.content
        val catalog = client.get("/api/development/configuration") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, catalog.status)
        val config = Json.parseToJsonElement(catalog.bodyAsText()).jsonObject
        assertEquals(9, config["cases"]!!.jsonArray.size)
        assertFalse(config["executionEnabled"]!!.jsonPrimitive.boolean)
        assertFalse(config["qualified"]!!.jsonPrimitive.boolean)
        assertFalse(config.containsKey("passwordVerifierFile"))
        val request = Json.encodeToString(DevelopmentReviewPreviewRequest("matched-run-recovery-facts-only", binding.providerId, binding.modelId))
        val noCsrf = client.post("/api/development/previews") {
            header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.Json); setBody(request)
        }
        assertEquals(HttpStatusCode.Forbidden, noCsrf.status)
        val response = client.post("/api/development/previews") {
            header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
            contentType(ContentType.Application.Json); setBody(request)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val preview = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val id = preview["id"]!!.jsonPrimitive.content
        assertEquals(binding.tag, preview["model"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
        assertTrue(preview["exactPrompt"]!!.jsonObject["userPacketJson"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(0, store.queue().size)
        val reconnect = client.get("/api/development/previews/$id") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(response.bodyAsText(), reconnect.bodyAsText())
        val session = client.get("/api/session") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, session.status)
        val logout = client.post("/api/logout") {
            header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, origin); header("X-CSRF-Token", csrf)
        }
        assertEquals(HttpStatusCode.OK, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/development/configuration") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test fun `unknown cases models packets paths personal snapshot ids and source routes are refused`() = testApplication {
        val directory = Files.createTempDirectory("development-rejection")
        val settings = assertNotNull(load(configuration(directory), directory))
        val store = DevelopmentReviewStore(settings.databasePath)
        val session = assertNotNull(settings.auth.login(SYNTHETIC_PASSWORD))
        application { developmentReviewModule(settings, store) }
        val valid = """{"caseId":"matched-run-recovery-facts-only","providerId":"ollama","modelId":"synthetic-model"}"""
        val invalid = listOf(valid.replace("matched-run-recovery-facts-only", "personal-private-marker"),
            valid.replace("synthetic-model", "unknown-model"), valid.dropLast(1) + ",\"packet\":\"private-marker\"}",
            valid.dropLast(1) + ",\"databasePath\":\"/personal-private-marker.sqlite\"}",
            valid.dropLast(1) + ",\"caseId\":\"duplicate\"}")
        for (body in invalid) {
            val response = client.post("/api/development/previews") {
                header(HttpHeaders.Cookie, "$DEVELOPMENT_SESSION_COOKIE=${session.token}")
                header(HttpHeaders.Origin, origin); header("X-CSRF-Token", session.csrfToken)
                contentType(ContentType.Application.Json); setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertFalse(response.bodyAsText().contains("private-marker"))
        }
        for (path in listOf("/api/development/previews/00000000-0000-0000-0000-000000000001",
            "/api/connected-reviews/personal-snapshot/preview", "/api/history", "/api/imports")) {
            assertEquals(HttpStatusCode.NotFound, client.get(path) {
                header(HttpHeaders.Cookie, "$DEVELOPMENT_SESSION_COOKIE=${session.token}")
            }.status)
        }
        assertEquals(0, store.previews().size)
        assertEquals(0, store.queue().size)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/development/configuration") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${session.token}")
        }.status)
    }

    @Test fun `normal application has no development configuration or preview capability`() = testApplication {
        val auth = SingleUserAuth(syntheticVerifier(), "http://127.0.0.1:8080", false)
        val session = assertNotNull(auth.login(SYNTHETIC_PASSWORD))
        application { module(auth, history = null, analysis = AnalysisService(), reviews = ReviewInterpretationService()) }
        val response = client.get("/api/development/configuration") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=${session.token}") }
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        assertFalse(response.bodyAsText().contains(binding.tag))
    }

    @Test fun `durable asynchronous submit duplicate queue reconnect cancel and maintenance do not require execution`() = testApplication {
        val directory = Files.createTempDirectory("development-queue-api")
        val settings = assertNotNull(load(configuration(directory), directory))
        val store = DevelopmentReviewStore(settings.databasePath)
        val session = assertNotNull(settings.auth.login(SYNTHETIC_PASSWORD))
        application { developmentReviewModule(settings, store) }
        val selection = settings.selection(binding.providerId, binding.modelId)
        val preview = store.createPreview("matched-run-recovery-facts-only", selection)
        fun HttpRequestBuilder.authorized(write: Boolean = false) {
            header(HttpHeaders.Cookie, "$DEVELOPMENT_SESSION_COOKIE=${session.token}")
            if (write) { header(HttpHeaders.Origin, origin); header("X-CSRF-Token", session.csrfToken); contentType(ContentType.Application.Json) }
        }
        val first = client.post("/api/development/runs") { authorized(true); setBody("""{"previewId":"${preview.id}"}""") }
        assertEquals(HttpStatusCode.Accepted, first.status)
        val task = Json.decodeFromString<DevelopmentReviewTask>(first.bodyAsText())
        val duplicate = client.post("/api/development/runs") { authorized(true); setBody("""{"previewId":"${preview.id}"}""") }
        assertEquals(first.bodyAsText(), duplicate.bodyAsText())
        val events = store.pendingEvents()
        repeat(2) {
            assertEquals(HttpStatusCode.OK, client.get("/api/development/queue") { authorized() }.status)
            val inspection = client.get("/api/development/runs/${task.id}") { authorized() }
            assertEquals(HttpStatusCode.OK, inspection.status)
            assertTrue(inspection.bodyAsText().contains("userPacketJson"))
            assertEquals(HttpStatusCode.OK, client.get("/api/development/diagnostics") { authorized() }.status)
        }
        assertEquals(events, store.pendingEvents())
        assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(task.id).outcome)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/development/runs/${task.id}/cancel") { authorized(); setBody("{}") }.status)
        val cancellation = client.post("/api/development/runs/${task.id}/cancel") { authorized(true); setBody("{}") }
        assertTrue(Json.decodeFromString<DevelopmentReviewCancellation>(cancellation.bodyAsText()).accepted)
        assertEquals(DevelopmentReviewExecutionState.NOT_STARTED, store.task(task.id).execution)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/development/maintenance") { authorized(true); setBody("""{"command":"private-marker"}""") }.status)
        assertEquals(HttpStatusCode.Accepted, client.post("/api/development/maintenance") { authorized(true); setBody("{}") }.status)
        assertEquals(1, store.maintenanceTasks().size)
        assertEquals(1, store.queue().size)
    }

    @Test fun `expired submission is gone and inspection hides payload without deleting or waking work`() = testApplication {
        val directory = Files.createTempDirectory("expired-development-api")
        val settings = assertNotNull(load(configuration(directory), directory))
        var now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val store = DevelopmentReviewStore(settings.databasePath, clock)
        val preview = store.createPreview("matched-run-recovery-facts-only", settings.selection(binding.providerId, binding.modelId))
        val task = store.submit(preview.id)
        now = now.plusSeconds(86400)
        val session = assertNotNull(settings.auth.login(SYNTHETIC_PASSWORD))
        application { developmentReviewModule(settings, store) }
        val response = client.post("/api/development/runs") {
            header(HttpHeaders.Cookie, "$DEVELOPMENT_SESSION_COOKIE=${session.token}")
            header(HttpHeaders.Origin, origin); header("X-CSRF-Token", session.csrfToken)
            contentType(ContentType.Application.Json); setBody("""{"previewId":"${preview.id}"}""")
        }
        assertEquals(HttpStatusCode.Gone, response.status)
        val inspection = client.get("/api/development/runs/${task.id}") { header(HttpHeaders.Cookie, "$DEVELOPMENT_SESSION_COOKIE=${session.token}") }
        val body = Json.parseToJsonElement(inspection.bodyAsText()).jsonObject
        assertFalse(body["inspection"]!!.jsonObject["packetAvailable"]!!.jsonPrimitive.boolean)
        assertEquals("", body["inspection"]!!.jsonObject["exactPrompt"]!!.jsonObject["userPacketJson"]!!.jsonPrimitive.content)
        assertEquals("DELETION_DUE", body["task"]!!.jsonObject["retention"]!!.jsonPrimitive.content)
        assertEquals(1, store.queue().size)
        assertEquals(1, store.pendingEvents().size)
    }

    @Test fun `api accepts queued work and cancellation while the sole worker awaits an owned stop receipt`() = testApplication {
        val directory = Files.createTempDirectory("busy-development-api")
        val settings = assertNotNull(load(configuration(directory), directory))
        val store = DevelopmentReviewStore(settings.databasePath)
        val session = assertNotNull(settings.auth.login(SYNTHETIC_PASSWORD))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stopping = kotlinx.coroutines.CompletableDeferred<Unit>()
        val confirmStop = kotlinx.coroutines.CompletableDeferred<Unit>()
        val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))
        val execution = DevelopmentRunExecution { db, _, claim, _ ->
            val handle = DevelopmentRuntimeHandle(claim, 8, claim.generation)
            assertTrue(db.registerRuntime(handle))
            try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                db.beginOwnedStop(handle); stopping.complete(Unit); confirmStop.await()
                val receipt = DevelopmentRuntimeStopReceipt.decode(handle, """{"identity":${identity(claim)},"keeper_pid":8,"keeper_start_ticks":${claim.generation},"confirmed":true,"mechanism":"linux_subreaper_waitpid_echild","reaped":1,"escalated":false,"elapsed_millis":1}""".toByteArray())
                assertTrue(db.confirmOwnedStop(assertNotNull(receipt)))
            } }
        }
        val active = store.submit(store.createPreview("matched-run-recovery-facts-only", settings.selection(binding.providerId, binding.modelId)).id)
        val worker = DevelopmentReviewWorker(store, boundary, execution, configuredModel = { true })
        application { developmentReviewModule(settings, store, worker) }
        fun HttpRequestBuilder.authorized() {
            header(HttpHeaders.Cookie, "$DEVELOPMENT_SESSION_COOKIE=${session.token}")
            header(HttpHeaders.Origin, origin); header("X-CSRF-Token", session.csrfToken); contentType(ContentType.Application.Json)
        }
        try {
            assertEquals(HttpStatusCode.OK, client.get("/api/development/configuration") { authorized() }.status)
            kotlinx.coroutines.withTimeout(3_000) { entered.await() }
            val preview = store.createPreview("matched-run-recovery-context-present", settings.selection(binding.providerId, binding.modelId))
            val queued = client.post("/api/development/runs") { authorized(); setBody("""{"previewId":"${preview.id}"}""") }
            assertEquals(HttpStatusCode.Accepted, queued.status)
            assertEquals(DevelopmentReviewOutcome.QUEUED, Json.decodeFromString<DevelopmentReviewTask>(queued.bodyAsText()).outcome)
            assertFalse(stopping.isCompleted)
            val cancel = client.post("/api/development/runs/${active.id}/cancel") { authorized(); setBody("{}") }
            assertEquals(HttpStatusCode.OK, cancel.status)
            val receipt = Json.decodeFromString<DevelopmentReviewCancellation>(cancel.bodyAsText())
            assertTrue(receipt.accepted)
            assertEquals(DevelopmentReviewOutcome.CANCELLED, receipt.task.outcome)
            assertEquals(DevelopmentReviewExecutionState.STOPPING, receipt.task.execution)
            kotlinx.coroutines.withTimeout(3_000) { stopping.await() }
            assertEquals(DevelopmentReviewExecutionState.STOPPING, store.task(active.id).execution)
        } finally { confirmStop.complete(Unit) }
    }

    private fun configuration(directory: Path): DevelopmentReviewLaunchConfiguration {
        val verifier = directory.resolve("development-verifier")
        Files.writeString(verifier, encodedVerifier)
        return DevelopmentReviewLaunchConfiguration(true, "127.0.0.1", 8081, directory.resolve("isolated.sqlite").toString(),
            verifier.toString(), listOf(binding), policy)
    }

    private fun load(configuration: DevelopmentReviewLaunchConfiguration, directory: Path): DevelopmentReviewLaunchSettings? {
        val file = directory.resolve("development.json")
        Files.writeString(file, Json.encodeToString(configuration))
        return DevelopmentReviewLaunchSettings.fromEnvironment(mapOf("GTRAINER_DEVELOPMENT_REVIEW_ENABLED" to "true",
            "GTRAINER_DEVELOPMENT_REVIEW_CONFIG_FILE" to file.toString(), "GTRAINER_PASSWORD_VERIFIER_FILE" to "/private-unreadable"))
    }

    private val encodedVerifier by lazy {
        val salt = ByteArray(16) { it.toByte() }
        val spec = PBEKeySpec(SYNTHETIC_PASSWORD.toCharArray(), salt, 600_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        "pbkdf2-sha256\$600000\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(key)}"
    }
}
