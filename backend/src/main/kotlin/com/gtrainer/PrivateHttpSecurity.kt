package com.gtrainer

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Request failures carry only an HTTP status, never submitted text or parser diagnostics. */
@PublishedApi
internal class PrivateRequestError(val status: HttpStatusCode) : IllegalArgumentException("Invalid private request")

/** Bounds chunked requests too; keep strict duplicate-key and UTF-8 checks at the HTTP boundary. */
internal suspend inline fun <reified T> ApplicationCall.privateJson(maximumBytes: Int = 4096): T {
    require(maximumBytes in 1..32 * 1024)
    try {
        val body = receiveChannel().readBuffer(maximumBytes.toLong() + 1).readByteArray()
        if (body.size > maximumBytes) throw PrivateRequestError(HttpStatusCode.PayloadTooLarge)
        return Json.decodeFromJsonElement<T>(StrictModelJson.parse(body.decodeToString(throwOnInvalidSequence = true)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: PrivateRequestError) {
        throw error
    } catch (_: Exception) {
        throw PrivateRequestError(HttpStatusCode.BadRequest)
    }
}

/** Shared policy, not shared service wiring: development can install headers without personal storage. */
internal fun Application.installPrivateHttpSecurity() {
    install(createApplicationPlugin("PrivacyHeaders") {
        onCall { call ->
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("X-Frame-Options", "DENY")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("Content-Security-Policy", PRIVATE_CONTENT_SECURITY_POLICY)
        }
    })
}

private const val PRIVATE_CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self'; style-src 'self'; " +
        "connect-src 'self'; img-src 'self' data:; object-src 'none'; " +
        "base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
