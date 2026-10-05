package com.gtrainer

import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respond

/** Authentication plumbing without constructing a source, database, scheduler or model service. */
internal class PrivateApiAccess(private val auth: SingleUserAuth, private val cookieName: String = SESSION_COOKIE) {
    init { require(cookieName in setOf(SESSION_COOKIE, DEVELOPMENT_SESSION_COOKIE)) }
    suspend fun authorize(call: ApplicationCall): UserSession? {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (call.request.path() == LOGIN_PATH && call.request.local.method == HttpMethod.Post) {
            login(call)
            return null
        }
        val session = auth.session(call.request.cookies[cookieName])
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("authentication_required"))
            return null
        }
        if (!validWrite(call, session)) {
            call.respond(HttpStatusCode.Forbidden, ApiError("csrf_rejected"))
            return null
        }
        return session
    }

    suspend fun respondToSessionRequest(call: ApplicationCall, session: UserSession): Boolean = when {
        call.request.path() == SESSION_PATH && call.request.local.method == HttpMethod.Get -> {
            call.respond(sessionResponse(session))
            true
        }
        call.request.path() == LOGOUT_PATH && call.request.local.method == HttpMethod.Post -> {
            auth.logout(session.token)
            setCookie(call, "", 0)
            call.respond(ApiError("logged_out"))
            true
        }
        else -> false
    }

    private fun validWrite(call: ApplicationCall, session: UserSession): Boolean =
        call.request.local.method in READ_METHODS ||
            (call.request.headers[HttpHeaders.Origin] == auth.allowedOrigin &&
                auth.validCsrf(session, call.request.headers[CSRF_HEADER]))

    private suspend fun login(call: ApplicationCall) {
        if (call.request.headers[HttpHeaders.Origin] != auth.allowedOrigin) {
            call.respond(HttpStatusCode.Forbidden, ApiError("origin_rejected"))
            return
        }
        if (!auth.configured) {
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError("authentication_not_configured"))
            return
        }
        val request = loginRequest(call) ?: return
        val session = auth.login(request.password)
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, ApiError("login_failed"))
            return
        }
        setCookie(call, session.token, SESSION_LIFETIME_SECONDS)
        call.respond(sessionResponse(session))
    }

    private suspend fun loginRequest(call: ApplicationCall): LoginRequest? = try {
        call.privateJson<LoginRequest>(LOGIN_LIMIT_BYTES)
    } catch (error: PrivateRequestError) {
        val reason = if (error.status == HttpStatusCode.PayloadTooLarge) "request_too_large" else "invalid_request"
        call.respond(error.status, ApiError(reason))
        null
    }

    private fun sessionResponse(session: UserSession) =
        SessionResponse(true, session.csrfToken, auth.intervalsConfigured)

    private fun setCookie(call: ApplicationCall, token: String, lifetimeSeconds: Int) {
        call.response.cookies.append(Cookie(cookieName, token, path = "/", maxAge = lifetimeSeconds,
            httpOnly = true, secure = auth.secureCookie, extensions = mapOf("SameSite" to "Strict")))
    }

    private companion object {
        const val LOGIN_PATH = "/api/login"
        const val SESSION_PATH = "/api/session"
        const val LOGOUT_PATH = "/api/logout"
        const val CSRF_HEADER = "X-CSRF-Token"
        const val LOGIN_LIMIT_BYTES = 4096
        const val SESSION_LIFETIME_SECONDS = 8 * 60 * 60
        val READ_METHODS = setOf(HttpMethod.Get, HttpMethod.Head)
    }
}

internal const val SESSION_COOKIE = "gtrainer_session"
internal const val DEVELOPMENT_SESSION_COOKIE = "gtrainer_development_session"
