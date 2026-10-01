package com.gtrainer

import io.ktor.http.HttpStatusCode
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
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

private class PrivateRequestError(val status: HttpStatusCode) : IllegalArgumentException("Invalid private request")

private suspend inline fun <reified T> ApplicationCall.privateJson(): T {
    try {
        val body = receiveChannel().readBuffer(4097L).readByteArray()
        if (body.size > 4096) throw PrivateRequestError(HttpStatusCode.PayloadTooLarge)
        return Json.decodeFromJsonElement<T>(StrictModelJson.parse(body.decodeToString(throwOnInvalidSequence = true)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: PrivateRequestError) {
        throw error
    } catch (_: Exception) {
        throw PrivateRequestError(HttpStatusCode.BadRequest)
    }
}

fun Application.module(auth: SingleUserAuth = SingleUserAuth.fromEnvironment(),
                        history: HistoryService? = HistoryService.fromEnvironment(),
                        analysis: AnalysisService = AnalysisService.fromEnvironment()) {
    monitor.subscribe(ApplicationStopped) { history?.close(); analysis.close() }
    install(createApplicationPlugin("PrivacyHeaders") {
        onCall { call ->
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("X-Frame-Options", "DENY")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; " +
                    "connect-src 'self'; img-src 'self' data:; object-src 'none'; " +
                    "base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        }
    })
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
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                val path = call.request.path()
                val isWrite = call.request.local.method !in listOf(HttpMethod.Get, HttpMethod.Head)
                val token = call.request.cookies["gtrainer_session"]
                val session = auth.session(token)
                if (path == "/api/login" && call.request.local.method == HttpMethod.Post) {
                    if (call.request.headers[HttpHeaders.Origin] != auth.allowedOrigin) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("origin_rejected"))
                        return@handle
                    }
                    if (!auth.configured) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("authentication_not_configured"))
                        return@handle
                    }
                    val request = try {
                        // Bounded even for chunked/no-Content-Length requests.
                        val body = call.receiveChannel().readBuffer(4097L).readByteArray()
                        if (body.size > 4096) {
                            call.respond(HttpStatusCode.PayloadTooLarge, ApiError("request_too_large"))
                            return@handle
                        }
                        Json.decodeFromString<LoginRequest>(body.decodeToString(throwOnInvalidSequence = true))
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@handle
                    }
                    val created = auth.login(request.password)
                    if (created == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("login_failed"))
                        return@handle
                    }
                    call.response.cookies.append(Cookie("gtrainer_session", created.token,
                        path = "/", maxAge = 8 * 60 * 60, httpOnly = true, secure = auth.secureCookie,
                        extensions = mapOf("SameSite" to "Strict")))
                    call.respond(SessionResponse(true, created.csrfToken, auth.intervalsConfigured))
                    return@handle
                }
                if (session == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("authentication_required"))
                    return@handle
                }
                if (isWrite && (call.request.headers[HttpHeaders.Origin] != auth.allowedOrigin ||
                            !auth.validCsrf(session, call.request.headers["X-CSRF-Token"]))) {
                    call.respond(HttpStatusCode.Forbidden, ApiError("csrf_rejected"))
                    return@handle
                }
                if (path == "/api/session" && call.request.local.method == HttpMethod.Get) {
                    call.respond(SessionResponse(true, session.csrfToken, auth.intervalsConfigured))
                } else if (path == "/api/logout" && call.request.local.method == HttpMethod.Post) {
                    auth.logout(session.token)
                    call.response.cookies.append(Cookie("gtrainer_session", "", path = "/", maxAge = 0,
                        httpOnly = true, secure = auth.secureCookie, extensions = mapOf("SameSite" to "Strict")))
                    call.respond(ApiError("logged_out"))
                } else {
                    try {
                        when {
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
                            history != null && path in setOf("/api/trends", "/api/analysis-input") && call.request.local.method == HttpMethod.Get -> {
                                val range = TrendRange(LocalDate.parse(requireNotNull(call.request.queryParameters["oldest"])),
                                    LocalDate.parse(requireNotNull(call.request.queryParameters["newest"])))
                                val report = history.trends(range, call.request.queryParameters["sport"])
                                call.response.headers.append("X-Evidence-Report-Sha256", Trends.analysisInput(report).evidenceReportSha256)
                                if (path == "/api/analysis-input") call.respond(Trends.analysisInput(report)) else call.respond(report)
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
                                call.respond(ApiError("local_imports_removed"))
                            }
                            else -> call.respond(HttpStatusCode.NotImplemented, ApiError("feature_not_implemented"))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: SyncBusy) {
                        call.respond(HttpStatusCode.Conflict, ApiError("private_operation_running"))
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
