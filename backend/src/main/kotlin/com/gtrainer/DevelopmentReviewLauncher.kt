package com.gtrainer

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.nio.file.Files
import java.nio.file.Path

@Serializable
internal data class DevelopmentReviewLaunchConfiguration(
    val enabled: Boolean, val bindAddress: String, val port: Int, val databasePath: String,
    val passwordVerifierFile: String, val models: List<DevelopmentReviewModelBinding>,
    val policy: DevelopmentReviewPolicyBinding,
    val worker: DevelopmentReviewWorkerConfiguration? = null,
) { override fun toString() = "DevelopmentReviewLaunchConfiguration([REDACTED])" }

/** Only development-namespaced operator settings are read; normal private environment settings are ignored. */
internal class DevelopmentReviewLaunchSettings private constructor(
    val bindAddress: String, val port: Int, val databasePath: Path, val auth: SingleUserAuth,
    models: List<DevelopmentReviewModelBinding>, val policy: DevelopmentReviewPolicyBinding,
    internal val workerConfiguration: DevelopmentReviewWorkerConfiguration? = null,
) {
    private val catalogue = models.associateBy { it.providerId to it.modelId }
    fun models(): List<DevelopmentReviewModelBinding> = catalogue.values.toList()
    fun model(providerId: String, modelId: String) = catalogue[providerId to modelId]
        ?: throw IllegalArgumentException("Unknown development model")

    fun selection(providerId: String, modelId: String): DevelopmentReviewConfiguration {
        val model = model(providerId, modelId)
        return DevelopmentReviewConfiguration(bindAddress, databasePath, model.providerId, model.modelId, model.label,
            model.tag, model.manifestDigest, model.ollamaVersion, model.contextWindow, model.tokenLimit,
            model.threads, model.seed, model.temperature, policy)
    }

    override fun toString() = "DevelopmentReviewLaunchSettings([REDACTED])"

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): DevelopmentReviewLaunchSettings? {
            if (environment["GTRAINER_DEVELOPMENT_REVIEW_ENABLED"] != "true") return null
            try {
                val configPath = Path.of(requireNotNull(environment["GTRAINER_DEVELOPMENT_REVIEW_CONFIG_FILE"]))
                val text = readOperatorFile(configPath, 64 * 1024)
                val configuration = Json.decodeFromJsonElement<DevelopmentReviewLaunchConfiguration>(StrictModelJson.parse(text))
                if (!configuration.enabled) return null
                return enabled(configuration)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid development launcher configuration")
            }
        }

        private fun enabled(configuration: DevelopmentReviewLaunchConfiguration): DevelopmentReviewLaunchSettings {
            require(configuration.bindAddress == "127.0.0.1" && configuration.port in 1..65535)
            val database = Path.of(configuration.databasePath)
            require(database.isAbsolute && database.parent != null)
            require(configuration.models.size in 1..8)
            require(configuration.models.distinctBy { it.providerId to it.modelId }.size == configuration.models.size)
            val verifier = PasswordVerifier.fromEncoded(readOperatorFile(Path.of(configuration.passwordVerifierFile), 256))
            val origin = "http://127.0.0.1:${configuration.port}"
            val auth = SingleUserAuth(verifier, origin, secureCookie = false, insecureLoopbackPort = configuration.port)
            return DevelopmentReviewLaunchSettings(configuration.bindAddress, configuration.port, database, auth,
                configuration.models.toList(), configuration.policy, configuration.worker)
        }

        private fun readOperatorFile(path: Path, limit: Int): String {
            require(path.isAbsolute)
            return Files.newInputStream(path).use { stream ->
                val bytes = stream.readNBytes(limit + 1)
                require(bytes.size in 1..limit)
                bytes.decodeToString(throwOnInvalidSequence = true)
            }
        }
    }
}

@Serializable
internal data class DevelopmentReviewCatalogue(val syntheticOnly: Boolean, val qualified: Boolean,
    val executionEnabled: Boolean, val reason: String, val cases: List<String>,
    val models: List<DevelopmentReviewModelBinding>, val policy: DevelopmentReviewPolicyBinding)

@Serializable
internal data class DevelopmentReviewPreviewRequest(val caseId: String, val providerId: String, val modelId: String)

@Serializable
internal data class DevelopmentReviewPreviewResponse(val id: String, val caseId: String,
    val model: DevelopmentReviewModelBinding, val policy: DevelopmentReviewPolicyBinding,
    val promptDigest: String, val exactPrompt: InterpretationPromptV1, val syntheticOnly: Boolean = true,
    val qualified: Boolean = false) { override fun toString() = "DevelopmentReviewPreviewResponse([REDACTED])" }

/** No normal module, history/source service, scheduler, personal database or provider is constructed. */
internal fun Application.developmentReviewModule(settings: DevelopmentReviewLaunchSettings, store: DevelopmentReviewStore,
    worker: DevelopmentReviewWorker? = null) {
    installPrivateHttpSecurity()
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    val workerJob = worker?.start(CoroutineScope(Dispatchers.IO + SupervisorJob()))
    monitor.subscribe(io.ktor.server.application.ApplicationStopPreparing) { worker?.beginShutdown(); workerJob?.cancel() }
    monitor.subscribe(ApplicationStopped) {
        try { runBlocking { workerJob?.cancelAndJoin() } } finally { store.close() }
    }
    val access = PrivateApiAccess(settings.auth, DEVELOPMENT_SESSION_COOKIE)
    routing {
        get("/healthz") { call.respond(HealthResponse("ok")) }
        route("/api") {
            // One authorization interceptor also protects unknown API paths from revealing existence.
            intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
                val session = access.authorize(call)
                if (session == null || access.respondToSessionRequest(call, session)) finish()
            }
            get("/development/configuration") {
                call.respond(DevelopmentReviewCatalogue(true, false, worker != null,
                    if (worker == null) "development_worker_not_configured" else "synthetic_development_only",
                    DevelopmentReviewFixtures.load().keys.sorted(), settings.models(), settings.policy))
            }
            post("/development/previews") {
                call.developmentResponse {
                    val request = call.privateJson<DevelopmentReviewPreviewRequest>()
                    val selection = settings.selection(request.providerId, request.modelId)
                    val preview = withContext(Dispatchers.IO) { store.createPreview(request.caseId, selection) }
                    call.respond(HttpStatusCode.Created, previewResponse(preview))
                }
            }
            get("/development/previews/{id}") {
                call.developmentResponse {
                    val id = developmentId(requireNotNull(call.parameters["id"]))
                    val preview = withContext(Dispatchers.IO) { store.preview(id) } ?: throw ConnectedReviewNotFound()
                    call.respond(previewResponse(preview))
                }
            }
            developmentQueueRoutes(store, worker)
            route("/{path...}") {
                handle { call.respond(HttpStatusCode.NotFound, ApiError("development_route_not_found")) }
            }
        }
        staticResources("/", "development-web")
    }
}

private fun previewResponse(preview: DevelopmentReviewPreview) = DevelopmentReviewPreviewResponse(
    preview.id, preview.caseId, Json.decodeFromString<DevelopmentReviewModelBinding>(preview.configurationJson),
    preview.policy, preview.promptDigest, InterpretationContractV1.prompt(preview.packet))

internal suspend fun io.ktor.server.application.ApplicationCall.developmentResponse(action: suspend () -> Unit) {
    try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: PrivateRequestError) { respond(error.status, ApiError("invalid_development_request")) }
    catch (_: ConnectedReviewNotFound) { respond(HttpStatusCode.NotFound, ApiError("development_record_not_found")) }
    catch (_: DevelopmentReviewCapacityReached) { respond(HttpStatusCode.Conflict, ApiError("development_capacity_reached")) }
    catch (_: DevelopmentReviewPreviewExpired) { respond(HttpStatusCode.Gone, ApiError("development_preview_expired")) }
    catch (_: IllegalArgumentException) { respond(HttpStatusCode.BadRequest, ApiError("invalid_development_request")) }
    catch (_: Exception) { respond(HttpStatusCode.ServiceUnavailable, ApiError("development_storage_unavailable")) }
}

fun developmentReviewMain() {
    val settings = DevelopmentReviewLaunchSettings.fromEnvironment() ?: return
    val store = DevelopmentReviewStore(settings.databasePath)
    try {
        val worker = settings.worker(store)
        embeddedServer(CIO, host = settings.bindAddress, port = settings.port) {
            developmentReviewModule(settings, store, worker)
        }.start(wait = true)
    } finally { store.close() }
}

/** Separate JVM entry class: the normal application's main/default service construction is never called. */
object DevelopmentReviewLauncher {
    @JvmStatic fun main(args: Array<String>) {
        try { developmentReviewMain() }
        catch (_: Exception) {
            System.err.println("Development launcher unavailable; check its isolated configuration")
            kotlin.system.exitProcess(2)
        }
    }
}
