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
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean

/** Test-classpath-only harness. No normal application assembly or provider exists here. */
object SyntheticIntakePilot {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
    private const val manifestSha = "64ebc651b21f3d091a84386453b8741e264f32c323dcbe0df6c7e014c4a3a276"
    const val ORIGIN = "http://127.0.0.1:8082"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("backend/build.gradle.kts")) }.toRealPath()

    @Serializable data class Provenance(val kind: String, val caseId: String, val suiteManifestSha256: String, val packetSha256: String, val wireSha256: String)
    @Serializable data class Result(val profile: String, val intakeId: String, val intakeRevision: Int, val status: String, val provenance: Provenance?, val draft: JsonElement?)
    internal data class Witness(val packet: ContextIntakeSuggestionPacketV1, val wire: String, val provenance: Provenance) {
        val id: String get() = packet.intake.intakeId
        val revision: Int get() = packet.intake.revision
        fun validatedDraft(): JsonElement {
            val checked = IntakeExtractionV2.validate(wire, packet)
            require(checked.wire == "accepted" && checked.binding == "accepted" && checked.semantic == "accepted")
            val draft = ContextIntakeSuggestionContractV1.decodeDraft(requireNotNull(checked.assembledDraft), packet)
            return json.encodeToJsonElement(draft)
        }
    }

    internal fun witnesses(): Map<String, Witness> {
        val root = root()
        val base = root.resolve("benchmarks/intake-extraction-v5")
        val manifest = Files.readAllBytes(base.resolve("manifest-v5.json"))
        require(sha(manifest) == manifestSha)
        val document = json.parseToJsonElement(manifest.toString(Charsets.UTF_8)).jsonObject
        require(document["version"]?.jsonPrimitive?.content == "intake-conformance-manifest-v5")
        require(document["profile"]?.jsonPrimitive?.content == "context-intake-extraction-v5")
        document.getValue("files").jsonObject.forEach { (name, digest) ->
            val relative = Path.of(name)
            require(!relative.isAbsolute && !relative.normalize().startsWith(".."))
            require(sha(Files.readAllBytes(root.resolve(relative))) == digest.jsonPrimitive.content)
        }
        val inputs = json.parseToJsonElement(Files.readString(base.resolve("inputs-v5.json"))).jsonObject
        val fixtures = json.parseToJsonElement(Files.readString(base.resolve("fixtures-v5.json"))).jsonObject
        require(inputs["profile"]?.jsonPrimitive?.content == "context-intake-extraction-v5")
        val rows = inputs.getValue("cases").jsonArray
        require(rows.map { it.jsonObject.getValue("caseId").jsonPrimitive.content } == (1..8).map { "u${it.toString().padStart(2, '0')}" })
        return rows.map { row ->
            val item = row.jsonObject
            val caseId = item.getValue("caseId").jsonPrimitive.content
            val packetText = item.getValue("userPacketJson").jsonPrimitive.content
            val packet = StrictProfileDraftJson.decode<ContextIntakeSuggestionPacketV1>(packetText)
            val wire = fixtures.getValue(caseId).toString()
            Witness(packet, wire, Provenance("handwritten_frozen_witness", caseId, manifestSha,
                sha(packetText.toByteArray()), sha(wire.toByteArray()))).also { it.validatedDraft() }
        }.associateBy { it.id }.also { require(it.size == 8) }
    }

    /** The only constructor creates a new private directory; no application path can be supplied. */
    internal class State private constructor(val directory: Path, val store: HistoryStore, val fixtures: Map<String, Witness>) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val database get() = directory.resolve("history.db")
        fun counts(): Map<String, Int> = DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            val names = listOf("context_intakes", "athlete_context", "commitment_revisions", "context_confirmation_receipts")
            names.associateWith { name -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $name").use { rows -> rows.next(); rows.getInt(1) }
            } }
        }
        fun executionRows(): Int = DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            val tables = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                    buildList { while (rows.next()) { val name = rows.getString(1); if (name.contains("execution")) add(name) } }
                }
            }
            tables.sumOf { name ->
                require(name.matches(Regex("[a-zA-Z0-9_]+")))
                connection.createStatement().use { it.executeQuery("SELECT count(*) FROM $name").use { rows -> rows.next(); rows.getInt(1) } }
            }
        }
        fun suggestion(id: String, expected: Int): Result {
            require(expected in 1..1000)
            val witness = fixtures[id] ?: throw ContextIntakeNotFound()
            val current = store.intakeHistory(id).first()
            val matches = current.revision == expected && current == witness.packet.intake
            return Result("synthetic-intake-pilot-v1", id, expected, if (matches) "available" else "unavailable",
                if (matches) witness.provenance else null, if (matches) witness.validatedDraft() else null)
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { store.close() } finally {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
        companion object {
            fun fresh(): State {
                val fixtures = witnesses()
                val directory = Files.createTempDirectory("gtrainer-synthetic-intake-pilot-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath()
                val database = directory.resolve("history.db")
                require(!Files.exists(database))
                val store = HistoryStore(database)
                try {
                    Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("rw-------"))
                    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                        connection.prepareStatement("INSERT INTO context_intakes(intake_id,revision,content,source_category,author_attribution,entered_by,observed_on,recorded_utc) VALUES(?,?,?,?,?,?,?,?)").use { statement ->
                            fixtures.values.forEach { witness ->
                                val source = witness.packet.intake
                                statement.setString(1, source.intakeId); statement.setInt(2, source.revision); statement.setString(3, source.content)
                                statement.setString(4, source.sourceCategory); statement.setString(5, source.authorAttribution); statement.setString(6, source.enteredBy)
                                statement.setString(7, source.observedOn); statement.setString(8, source.recordedUtc); statement.addBatch()
                            }
                            statement.executeBatch()
                        }
                    }
                    return State(directory, store, fixtures)
                } catch (error: Exception) {
                    store.close()
                    Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                    throw error
                }
            }
        }
    }

    internal fun Application.module(state: State, auth: SingleUserAuth, frontend: Path? = null) {
        install(ContentNegotiation) { json(SyntheticIntakePilot.json) }
        installPrivateHttpSecurity()
        val intakes = ContextIntakeService(state.store)
        val lifecycle = ContextLifecycleService(state.store)
        val commitments = CommitmentService(state.store)
        val access = PrivateApiAccess(auth, SYNTHETIC_INTAKE_PILOT_SESSION_COOKIE)
        routing {
            get("/healthz") { call.respond(HealthResponse("synthetic-only")) }
            route("/api/{path...}") {
                handle {
                    try {
                        val session = access.authorize(call) ?: return@handle
                        if (access.respondToSessionRequest(call, session)) return@handle
                        val path = call.request.path()
                        val method = call.request.local.method
                        val fixture = Regex("^/api/synthetic-intake-pilot/([^/]+)/suggestions$").matchEntire(path)
                        if (fixture != null && method == HttpMethod.Get) {
                            val query = call.request.queryParameters
                            require(query.names() == setOf("expectedRevision") && query.getAll("expectedRevision")?.size == 1)
                            val revision = query["expectedRevision"]?.toIntOrNull()?.takeIf { it in 1..1000 } ?: throw IllegalArgumentException()
                            call.respond(state.suggestion(fixture.groupValues[1], revision))
                            return@handle
                        }
                        val source = Regex("^/api/context-intakes/([^/]+)(?:/(links|confirm))?$").matchEntire(path)
                        val knownSource = source != null && source.groupValues[1] in state.fixtures
                        val allowedRead = method == HttpMethod.Get && (path in setOf("/api/context-intakes", "/api/context-lifecycle", "/api/commitments") || knownSource && source.groupValues[2] == "links")
                        val allowedWrite = knownSource && (method == HttpMethod.Put && source.groupValues[2].isEmpty() || method == HttpMethod.Post && source.groupValues[2] == "confirm")
                        if (allowedRead || allowedWrite) {
                            if (call.respondContextIntake(path, intakes) || call.respondContextLifecycle(path, lifecycle) || call.respondCommitment(path, commitments)) return@handle
                        }
                        call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                    } catch (error: PrivateRequestError) {
                        call.respond(error.status, ApiError("invalid_request"))
                    } catch (_: ContextIntakeNotFound) {
                        call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                    } catch (_: ContextIntakeConflict) {
                        call.respond(HttpStatusCode.Conflict, ApiError("revision_conflict"))
                    } catch (_: ContextRevisionConflict) {
                        call.respond(HttpStatusCode.Conflict, ApiError("revision_conflict"))
                    } catch (_: CommitmentConflict) {
                        call.respond(HttpStatusCode.Conflict, ApiError("revision_conflict"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, ApiError("pilot_unavailable"))
                    }
                }
            }
            if (frontend != null) staticFiles("/", frontend.toFile())
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty()) { "Pilot arguments are not supported" }
        val verifier = PasswordVerifier.fromEncoded(requireNotNull(System.getenv("GTRAINER_SYNTHETIC_PILOT_PASSWORD_HASH")) { "Dedicated pilot verifier required" })
        val frontend = root().resolve("frontend/dist-synthetic-intake-pilot")
        require(Files.isRegularFile(frontend.resolve("index.html")) && Files.readString(frontend.resolve("index.html")).contains("synthetic-intake-pilot-root")) { "Synthetic pilot UI must be built separately" }
        val state = State.fresh()
        try {
            val auth = SingleUserAuth(verifier, ORIGIN, false, false, insecureLoopbackPort = 8082)
            val server = embeddedServer(CIO, host = "127.0.0.1", port = 8082) { module(state, auth, frontend) }
            Runtime.getRuntime().addShutdownHook(Thread { try { server.stop(100, 1000) } finally { state.close() } })
            server.start(wait = true)
        } finally { state.close() }
    }
}
