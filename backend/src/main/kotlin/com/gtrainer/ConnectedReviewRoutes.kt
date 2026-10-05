package com.gtrainer

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Already authenticated/CSRF-checked by normal application dispatch. No development capability. */
internal suspend fun ApplicationCall.respondConnectedReview(path: String, service: ConnectedReviewService?): Boolean {
    if (service == null) return false
    val method = request.local.method
    if (path == "/api/connected-review/providers" && method == HttpMethod.Get) {
        respond(service.providerOptions()); return true
    }
    if (!path.startsWith("/api/connected-reviews/")) return false
    val suffix = path.removePrefix("/api/connected-reviews/")
    when {
        suffix.endsWith("/preview") && method == HttpMethod.Get ->
            respond(withContext(Dispatchers.IO) { service.preview(suffix.removeSuffix("/preview")) })
        !suffix.contains('/') && method == HttpMethod.Get ->
            respond(withContext(Dispatchers.IO) { service.inspect(suffix) })
        suffix.endsWith("/consent") && method == HttpMethod.Post -> {
            val id = suffix.removeSuffix("/consent")
            val input = privateJson<ConnectedReviewConsentRequest>(8192)
            val approved = withContext(Dispatchers.IO) { service.consent(id, input) }
            respond(ConnectedReviewConsentReceipt(id, approved, input.providerSelection, input.packetDigest))
        }
        suffix.endsWith("/consent") && method == HttpMethod.Delete -> {
            withContext(Dispatchers.IO) { service.revokeConsent(suffix.removeSuffix("/consent")) }
            respond(HttpStatusCode.NoContent)
        }
        suffix.endsWith("/run") && method == HttpMethod.Post -> {
            val input = privateJson<ConnectedReviewRunRequest>(8192)
            respond(withContext(Dispatchers.IO) { service.executeManually(suffix.removeSuffix("/run"), input.providerSelection) })
        }
        else -> return false
    }
    return true
}
