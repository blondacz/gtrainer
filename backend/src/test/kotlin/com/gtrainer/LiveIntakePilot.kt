package com.gtrainer

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean

/** Separate test-classpath capture inspector. No browser operation can acquire model output. */
object LiveIntakePilot {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
    const val ORIGIN = "http://127.0.0.1:8082"
    @Serializable data class Profile(val profile: String, val evidenceMode: String, val boundedSessionSeconds: Int = 1200)
    @Serializable data class Provenance(val kind: String, val acquisitionId: String, val receiptSha256: String,
        val packetSha256: String, val requestSha256: String, val rawSha256: String, val draftSha256: String)
    @Serializable data class Suggestions(val profile: String, val intakeId: String, val intakeRevision: Int,
        val status: String, val provenance: Provenance?, val draft: JsonElement?)

    internal fun enforceSessionDeadline(startedNanos: Long, now: () -> Long = System::nanoTime,
        sleep: (Long) -> Unit = Thread::sleep, stop: () -> Unit) {
        try {
            val current = now()
            require(current >= startedNanos)
            val remaining = maxOf(0, 1_200_000 - (current - startedNanos) / 1_000_000)
            if (remaining > 0) sleep(remaining)
        } finally { stop() }
    }

    internal fun claimSession(captureRoot: Path, receipt: String, started: Long): Path {
        require(receipt.matches(Regex("[a-f0-9]{64}")))
        val path = captureRoot.parent.resolve("app-session-claim.json")
        val claim = buildJsonObject { put("receiptSha256", receipt); put("pid", ProcessHandle.current().pid()); put("startedUnixMillis", started); put("deadlineUnixMillis", started + 1_200_000) }
        Files.newByteChannel(path, setOf(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(claim.toString().toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            (channel as java.nio.channels.FileChannel).force(true)
        }
        java.nio.channels.FileChannel.open(path.parent, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        return path
    }

    internal class State private constructor(val capture: LiveIntakeCapture, val directory: Path, val store: HistoryStore) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        val source = capture.packet.intake
        private val draft = capture.validate(capture.receiptSha256, capture.evidenceMode)
        fun suggestions(id: String, expected: Int): Suggestions {
            require(expected in 1..1000)
            if (id != source.intakeId) throw ContextIntakeNotFound()
            val current = store.intakeHistory(id).first()
            val available = current == source && current.revision == expected
            val provenance = if (available) Provenance(if (capture.evidenceMode == "live") "live_model_capture" else "synthetic_fake_acquisition",
                capture.acquisitionId, capture.receiptSha256, capture.packetSha256, capture.requestSha256, capture.rawSha256, capture.draftSha256) else null
            return Suggestions(LiveIntakeCapture.PROFILE, id, expected, if (available) "available" else "unavailable",
                provenance, if (available) json.encodeToJsonElement(draft) else null)
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { store.close() } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
        companion object {
            fun fresh(capture: LiveIntakeCapture): State {
                capture.validate(capture.receiptSha256, capture.evidenceMode)
                val directory = Files.createTempDirectory("gtrainer-live-intake-pilot-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath()
                var store: HistoryStore? = null
                try {
                    val database = directory.resolve("history.db")
                    store = HistoryStore(database)
                    Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("rw-------"))
                    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                        connection.prepareStatement("INSERT INTO context_intakes(intake_id,revision,content,source_category,author_attribution,entered_by,observed_on,recorded_utc) VALUES(?,?,?,?,?,?,?,?)").use { statement ->
                            val source = capture.packet.intake
                            statement.setString(1, source.intakeId); statement.setInt(2, source.revision); statement.setString(3, source.content)
                            statement.setString(4, source.sourceCategory); statement.setString(5, source.authorAttribution); statement.setString(6, source.enteredBy)
                            statement.setString(7, source.observedOn); statement.setString(8, source.recordedUtc); statement.executeUpdate()
                        }
                    }
                    return State(capture, directory, store)
                } catch (error: Exception) {
                    store?.close()
                    Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                    throw error
                }
            }
        }
    }

    internal fun Application.module(state: State, auth: SingleUserAuth, frontend: Path? = null) {
        install(ContentNegotiation) { json(LiveIntakePilot.json) }
        installPrivateHttpSecurity()
        val access = PrivateApiAccess(auth, SYNTHETIC_INTAKE_PILOT_SESSION_COOKIE)
        val intakes = ContextIntakeService(state.store)
        val lifecycle = ContextLifecycleService(state.store)
        val commitments = CommitmentService(state.store)
        routing {
            get("/healthz") { call.respond(HealthResponse("synthetic-only")) }
            route("/api/{path...}") {
                handle {
                    try {
                        val session = access.authorize(call) ?: return@handle
                        if (access.respondToSessionRequest(call, session)) return@handle
                        val path = call.request.path(); val method = call.request.local.method
                        if (method == HttpMethod.Get && path == "/api/live-intake-pilot/profile") {
                            require(call.request.queryParameters.isEmpty())
                            call.respond(Profile(LiveIntakeCapture.PROFILE, state.capture.evidenceMode)); return@handle
                        }
                        val suggestion = Regex("^/api/live-intake-pilot/([^/]+)/suggestions$").matchEntire(path)
                        if (method == HttpMethod.Get && suggestion != null) {
                            val query = call.request.queryParameters
                            require(query.names() == setOf("expectedRevision") && query.getAll("expectedRevision")?.size == 1)
                            val revision = query["expectedRevision"]?.toIntOrNull() ?: throw IllegalArgumentException()
                            call.respond(state.suggestions(suggestion.groupValues[1], revision)); return@handle
                        }
                        val source = Regex("^/api/context-intakes/([^/]+)(?:/(links|confirm))?$").matchEntire(path)
                        val known = source != null && source.groupValues[1] == state.source.intakeId
                        val allowedRead = method == HttpMethod.Get && (path in setOf("/api/context-intakes", "/api/context-lifecycle", "/api/commitments") || known && source.groupValues[2] == "links")
                        val allowedWrite = known && (method == HttpMethod.Put && source.groupValues[2].isEmpty() || method == HttpMethod.Post && source.groupValues[2] == "confirm")
                        if (allowedRead || allowedWrite) {
                            if (call.respondContextIntake(path, intakes) || call.respondContextLifecycle(path, lifecycle) || call.respondCommitment(path, commitments)) return@handle
                        }
                        call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                    } catch (error: PrivateRequestError) { call.respond(error.status, ApiError("invalid_request"))
                    } catch (_: ContextIntakeNotFound) { call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                    } catch (_: ContextIntakeConflict) { call.respond(HttpStatusCode.Conflict, ApiError("revision_conflict"))
                    } catch (_: ContextRevisionConflict) { call.respond(HttpStatusCode.Conflict, ApiError("revision_conflict"))
                    } catch (_: CommitmentConflict) { call.respond(HttpStatusCode.Conflict, ApiError("revision_conflict"))
                    } catch (_: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                    } catch (_: Exception) { call.respond(HttpStatusCode.InternalServerError, ApiError("pilot_unavailable")) }
                }
            }
            if (frontend != null) staticFiles("/", frontend.toFile())
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val startedNanos = System.nanoTime()
        val started = System.currentTimeMillis()
        require(args.isEmpty()) { "Launcher arguments are not supported" }
        val captureRoot = Path.of(requireNotNull(System.getenv("GTRAINER_LIVE_INTAKE_CAPTURE_DIR")))
        val receipt = requireNotNull(System.getenv("GTRAINER_LIVE_INTAKE_RECEIPT_SHA256"))
        val mode = requireNotNull(System.getenv("GTRAINER_LIVE_INTAKE_EVIDENCE_MODE"))
        val verifier = PasswordVerifier.fromEncoded(requireNotNull(System.getenv("GTRAINER_SYNTHETIC_PILOT_PASSWORD_HASH")))
        val frontend = LiveIntakeCapture.projectRoot().resolve("frontend/dist-live-intake-pilot")
        require(Files.isRegularFile(frontend.resolve("index.html")) && Files.readString(frontend.resolve("index.html")).contains("live-intake-pilot-root"))
        val capture = LiveIntakeCapture.load(captureRoot, receipt, mode)
        claimSession(captureRoot, receipt, started)
        val state = State.fresh(capture)
        try {
            val auth = SingleUserAuth(verifier, ORIGIN, false, false, insecureLoopbackPort = 8082)
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 8082) { module(state, auth, frontend) }
            Runtime.getRuntime().addShutdownHook(Thread { try { server.stop(100, 1000) } finally { state.close() } })
            Thread {
                enforceSessionDeadline(startedNanos) { try { server.stop(100, 1000) } finally { state.close() } }
            }.apply { isDaemon = true; start() }
            server.start(wait = true)
        } finally { state.close() }
    }
}
