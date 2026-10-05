package com.gtrainer

import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.request.path
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.server.application.ApplicationStopping
import java.time.LocalDate

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ApiError(val error: String)

@Serializable
data class LoginRequest(val password: String) {
    override fun toString(): String = "LoginRequest(password=[REDACTED])"
}

@Serializable
data class SessionResponse(val authenticated: Boolean, val csrfToken: String, val intervalsConfigured: Boolean) {
    override fun toString(): String = "SessionResponse([REDACTED])"
}

@Serializable
data class SyncRequest(val oldest: String, val newest: String) {
    override fun toString(): String = "SyncRequest([REDACTED])"
}

@Serializable
data class RemovalRequest(val confirmation: String)

fun Application.module(auth: SingleUserAuth = SingleUserAuth.fromEnvironment(),
                        history: HistoryService? = HistoryService.fromEnvironment(),
                        analysis: AnalysisService = AnalysisService.fromEnvironment(),
                        reviews: ReviewInterpretationService = ReviewInterpretationService.fromEnvironment(analysis.inferenceGate()),
                        schedules: ReviewScheduler? = history?.reviewScheduler(reviews),
                        reviewQueue: ReviewQueueService? = history?.reviewQueue(reviews),
                        events: ManualEventService? = history?.manualEvents(),
                         contexts: AthleteContextService? = history?.athleteContexts(),
                         connectedReviews: ConnectedReviewService? = null) {
    val access = PrivateApiAccess(auth)
    val schedulerJob = schedules?.let { scheduler -> launch {
        while (isActive) {
            delay(30_000)
            try { scheduler.tick() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Fail closed; private records and diagnostics never reach logs. */ }
            // Routing backpressure must not starve the consumer that frees queue capacity.
            try { reviewQueue?.pump(this@module) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Private failure, no payload logging or retrying a model attempt. */ }
        }
    } }
    monitor.subscribe(ApplicationStopping) { schedulerJob?.cancel(); reviewQueue?.close() }
    monitor.subscribe(ApplicationStopped) { history?.close(); analysis.close(); reviews.close() }
    installPrivateHttpSecurity()
    install(ContentNegotiation) {
        json()
    }
    routing {
        // Contains process availability only, never records, secrets, or model inputs.
        get("/healthz") {
            call.respond(HealthResponse("ok"))
        }
        route("/api/{path...}") {
            handle {
                val path = call.request.path()
                val token = call.request.cookies[SESSION_COOKIE]
                val session = access.authorize(call) ?: return@handle
                if (!access.respondToSessionRequest(call, session)) {
                    try {
                        when {
                            call.respondConnectedReview(path, connectedReviews) -> Unit
                            contexts != null && path == "/api/context-retrieval" && call.request.local.method == HttpMethod.Get -> {
                                val parameters = call.request.queryParameters
                                val asOf = LocalDate.parse(requireNotNull(parameters["asOf"]))
                                val optionalLimit = parameters["optionalLimit"]?.toIntOrNull() ?: 20
                                require(optionalLimit in 0..1000)
                                val query = ContextRetrievalQuery(asOf, parameters["sport"], parameters["activityId"],
                                    parameters["reviewId"], optionalLimit)
                                call.respond(withContext(Dispatchers.IO) { contexts.retrieve(query) })
                            }
                            contexts != null && path == "/api/contexts" && call.request.local.method == HttpMethod.Get -> call.respond(withContext(Dispatchers.IO) { contexts.list() })
                            contexts != null && path == "/api/contexts" && call.request.local.method == HttpMethod.Post -> call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { contexts.create(call.privateJson<AthleteContextRequest>(16_384)) })
                            contexts != null && path.startsWith("/api/connected-reviews/") && path.endsWith("/feedback") && call.request.local.method == HttpMethod.Post -> {
                                val snapshotId = path.removePrefix("/api/connected-reviews/").removeSuffix("/feedback")
                                    .removeSuffix("/")
                                call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) {
                                    contexts.recordReviewFeedback(snapshotId, call.privateJson<ReviewFeedbackRequest>(8192))
                                })
                            }
                            contexts != null && path.startsWith("/api/contexts/") && path.endsWith("/retire") && call.request.local.method == HttpMethod.Post -> {
                                val id = path.removePrefix("/api/contexts/").removeSuffix("/retire")
                                if (withContext(Dispatchers.IO) { contexts.retire(id) }) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ApiError("context_not_found"))
                            }
                            contexts != null && path.startsWith("/api/contexts/") && path.endsWith("/history") && call.request.local.method == HttpMethod.Get -> {
                                val id = path.removePrefix("/api/contexts/").removeSuffix("/history")
                                call.respond(withContext(Dispatchers.IO) { contexts.history(id) })
                            }
                            contexts != null && path.startsWith("/api/contexts/") && call.request.local.method == HttpMethod.Put -> call.respond(withContext(Dispatchers.IO) { contexts.correct(path.removePrefix("/api/contexts/"), call.privateJson<AthleteContextRequest>(16_384)) })
                            contexts != null && path.startsWith("/api/contexts/") && call.request.local.method == HttpMethod.Delete -> {
                                if (withContext(Dispatchers.IO) { contexts.delete(path.removePrefix("/api/contexts/")) }) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ApiError("context_not_found"))
                            }
                            events != null && path == "/api/events" && call.request.local.method == HttpMethod.Get ->
                                call.respond(withContext(Dispatchers.IO) { events.list() })
                            events != null && path == "/api/events" && call.request.local.method == HttpMethod.Post -> {
                                val request = call.privateJson<ManualEventRequest>(32_768)
                                call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { events.create(request) })
                            }
                            events != null && path.startsWith("/api/events/") && call.request.local.method == HttpMethod.Put -> {
                                val request = call.privateJson<ManualEventRequest>(32_768)
                                call.respond(withContext(Dispatchers.IO) { events.update(path.removePrefix("/api/events/"), request) })
                            }
                            events != null && path.startsWith("/api/events/") && call.request.local.method == HttpMethod.Delete -> {
                                if (withContext(Dispatchers.IO) { events.remove(path.removePrefix("/api/events/")) }) call.respond(HttpStatusCode.NoContent)
                                else call.respond(HttpStatusCode.NotFound, ApiError("event_not_found"))
                            }
                            schedules != null && path == "/api/review-schedules" && call.request.local.method == HttpMethod.Get ->
                                call.respond(ReviewFocusProtocol.json.encodeToJsonElement(schedules.status()))
                            schedules != null && path == "/api/review-schedules" && call.request.local.method == HttpMethod.Put ->
                                call.respond(ReviewFocusProtocol.json.encodeToJsonElement(schedules.configure(call.privateJson<ReviewScheduleUpdate>(32_768)).also { reviewQueue?.cancelObsolete() }))
                            reviewQueue != null && path == "/api/review-queue" && call.request.local.method == HttpMethod.Get -> call.respond(ReviewFocusProtocol.json.encodeToJsonElement(reviewQueue.status()))
                            reviewQueue != null && path == "/api/review-now" && call.request.local.method == HttpMethod.Post -> {
                                call.respond(ReviewFocusProtocol.json.encodeToJsonElement(reviewQueue.requestNow(call.privateJson<ReviewNowRequest>())))
                                reviewQueue.pump(this@module)
                            }
                            path == "/api/review-models" && call.request.local.method == HttpMethod.Get -> call.respond(ReviewFocusProtocol.json.encodeToJsonElement(reviews.status()))
                            path == "/api/review-models" && call.request.local.method == HttpMethod.Put -> {
                                val prior = reviews.status().selectionVersion
                                val selected = reviews.select(call.privateJson<ReviewSelectionRequest>())
                                if (selected.selectionVersion != prior) reviewQueue?.invalidate("model_selection_changed")
                                call.respond(ReviewFocusProtocol.json.encodeToJsonElement(selected))
                            }
                            history != null && path == "/api/review-interpretation" && call.request.local.method == HttpMethod.Post -> {
                                val request = call.privateJson<ReviewInterpretationRequest>()
                                val range = TrendRange(LocalDate.parse(request.oldest), LocalDate.parse(request.newest))
                                val result = reviews.interpret(request, history.trends(range, request.sport)) { history.trends(range, request.sport) }
                                if (auth.session(token) == null) call.respond(HttpStatusCode.Unauthorized, ApiError("authentication_required"))
                                else if (result.status == "available" && !reviews.current(result))
                                    call.respond(result.copy(status = "unavailable", reason = "model_selection_changed", interpretations = emptyList()))
                                else call.respond(result)
                            }
                            path == "/api/models" && call.request.local.method == HttpMethod.Get -> call.respond(analysis.status())
                            path == "/api/models" && call.request.local.method == HttpMethod.Put ->
                                call.respond(analysis.select(call.privateJson<ModelSelectionRequest>().modelId))
                            history != null && path == "/api/analysis" && call.request.local.method == HttpMethod.Post -> {
                                val request = call.privateJson<AnalysisRequest>()
                                val range = TrendRange(LocalDate.parse(request.oldest), LocalDate.parse(request.newest))
                                val result = analysis.analyze(request, history.trends(range, request.sport)) { history.trends(range, request.sport) }
                                if (auth.session(token) == null) call.respond(HttpStatusCode.Unauthorized, ApiError("authentication_required"))
                                else call.respond(result)
                            }
                            history != null && path == "/api/imports" && call.request.local.method == HttpMethod.Get ->
                                call.respond(history.statuses())
                            history != null && path in setOf("/api/trends", "/api/analysis-input", "/api/review-facts") && call.request.local.method == HttpMethod.Get -> {
                                val range = TrendRange(LocalDate.parse(requireNotNull(call.request.queryParameters["oldest"])),
                                    LocalDate.parse(requireNotNull(call.request.queryParameters["newest"])))
                                val report = history.trends(range, call.request.queryParameters["sport"])
                                val input = Trends.analysisInput(report)
                                call.response.headers.append("X-Evidence-Report-Sha256", input.evidenceReportSha256)
                                if (path == "/api/review-facts") {
                                    val binding = requireNotNull(call.request.queryParameters["evidenceReportSha256"])
                                    require(binding.matches(Regex("[a-f0-9]{64}")))
                                    val focus = call.request.queryParameters["focus"] ?: "daily_combined"
                                    require(focus in FactualReviews.supportedFocuses)
                                    if (auth.session(token) == null) call.respond(HttpStatusCode.Unauthorized, ApiError("authentication_required"))
                                    else if (binding != input.evidenceReportSha256) call.respond(HttpStatusCode.Conflict, ApiError("evidence_changed"))
                                    else call.respond(FactualReviews.prepare(input, focus))
                                } else if (path == "/api/analysis-input") call.respond(input) else call.respond(report)
                            }
                            history != null && path == "/api/history" && call.request.local.method == HttpMethod.Get -> {
                                val oldest = LocalDate.parse(requireNotNull(call.request.queryParameters["oldest"]))
                                val newest = LocalDate.parse(requireNotNull(call.request.queryParameters["newest"]))
                                require(oldest <= newest)
                                call.respond(history.history(oldest, newest))
                            }
                            history != null && path == "/api/sync" && call.request.local.method == HttpMethod.Post -> {
                                val request = call.privateJson<SyncRequest>()
                                val range = ReadRange(LocalDate.parse(request.oldest), LocalDate.parse(request.newest))
                                require(range.newest <= LocalDate.now(java.time.Clock.systemUTC()).plusDays(1))
                                call.respond(history.sync(range))
                            }
                            history != null && path == "/api/imports" && call.request.local.method == HttpMethod.Delete -> {
                                require(call.privateJson<RemovalRequest>().confirmation == "remove-local-imports")
                                history.removeImports()
                                reviewQueue?.cancelObsolete()
                                call.respond(ApiError("local_imports_removed"))
                            }
                            else -> call.respond(HttpStatusCode.NotImplemented, ApiError("feature_not_implemented"))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: SyncBusy) {
                        call.respond(HttpStatusCode.Conflict, ApiError("private_operation_running"))
                    } catch (_: ReviewScheduleConflict) {
                        call.respond(HttpStatusCode.Conflict, ApiError("review_configuration_changed"))
                    } catch (_: ReviewQueueCapacity) {
                        call.respond(HttpStatusCode.Conflict, ApiError("review_queue_capacity"))
                          } catch (_: EventNotFound) {
                            call.respond(HttpStatusCode.NotFound, ApiError("event_not_found"))
                        } catch (_: ContextNotFound) {
                            call.respond(HttpStatusCode.NotFound, ApiError("context_not_found"))
                        } catch (_: ConnectedReviewNotFound) {
                            call.respond(HttpStatusCode.NotFound, ApiError("connected_review_not_found"))
                    } catch (_: TrendSizeLimit) {
                        call.respond(HttpStatusCode.PayloadTooLarge, ApiError("choose_shorter_trend_range"))
                    } catch (error: PrivateRequestError) {
                        call.respond(error.status, ApiError("invalid_request"))
                    } catch (_: java.time.DateTimeException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_date_range"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("data_operation_failed"))
                    }
                }
            }
        }
        // Docker packages the built UI in the jar; local development uses Vite.
        // These static resources contain no personal records or credentials.
        staticResources("/", "web")
    }
}

fun main() {
    val host = System.getenv("GTRAINER_HOST") ?: "127.0.0.1"
    val port = System.getenv("GTRAINER_PORT")?.toIntOrNull() ?: 8080
    require(port in 1..65535) { "GTRAINER_PORT must be a valid port" }
    embeddedServer(CIO, host = host, port = port, module = Application::module)
        .start(wait = true)
}
