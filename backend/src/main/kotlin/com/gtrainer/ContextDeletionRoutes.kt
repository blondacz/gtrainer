package com.gtrainer

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PrivateApiAccess authenticates and checks mutation origin/CSRF before dispatch. */
internal suspend fun ApplicationCall.respondContextDeletion(path: String, service: ContextDeletionService?): Boolean {
    if(service==null || !path.startsWith("/api/contexts/")) return false
    val suffix=path.removePrefix("/api/contexts/")
    return when {
        suffix.endsWith("/deletion-preview") && request.local.method==HttpMethod.Get -> {
            require(request.queryParameters.names().isEmpty())
            respond(withContext(Dispatchers.IO) { service.preview(suffix.removeSuffix("/deletion-preview")) }); true
        }
        suffix.endsWith("/redact-and-delete") && request.local.method==HttpMethod.Post -> {
            val input=privateJson<ContextDeletionRequest>(32_768)
            respond(withContext(Dispatchers.IO) { service.apply(suffix.removeSuffix("/redact-and-delete"),input) }); true
        }
        else -> false
    }
}
