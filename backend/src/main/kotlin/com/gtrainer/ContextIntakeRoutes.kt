package com.gtrainer

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dispatch after PrivateApiAccess has authenticated and checked write origin/CSRF. */
internal suspend fun ApplicationCall.respondContextIntake(path: String, service: ContextIntakeService?): Boolean {
    if (service == null) return false
    val method = request.local.method
    if (path == "/api/context-intakes") {
        when (method) {
            HttpMethod.Get -> respond(withContext(Dispatchers.IO) { service.list() })
            HttpMethod.Post -> {
                val input = privateJson<ContextIntakeRequest>(16_384)
                respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { service.create(input) })
            }
            else -> return false
        }
        return true
    }
    if (!path.startsWith("/api/context-intakes/")) return false
    val suffix = path.removePrefix("/api/context-intakes/")
    when {
        suffix.endsWith("/confirm") && method == HttpMethod.Post -> {
            val input=privateJson<ContextConfirmationRequest>(32_768)
            respond(withContext(Dispatchers.IO) { service.confirm(suffix.removeSuffix("/confirm"),input) })
        }
        suffix.endsWith("/history") && method == HttpMethod.Get ->
            respond(withContext(Dispatchers.IO) { service.history(suffix.removeSuffix("/history")) })
        suffix.endsWith("/links") && method == HttpMethod.Get ->
            respond(withContext(Dispatchers.IO) { service.links(suffix.removeSuffix("/links")) })
        suffix.endsWith("/links") && method == HttpMethod.Post -> {
            val input = privateJson<ContextIntakeAssociation>(1024)
            respond(withContext(Dispatchers.IO) { service.associate(suffix.removeSuffix("/links"), input) })
        }
        !suffix.contains('/') && method == HttpMethod.Put -> {
            val input = privateJson<ContextIntakeCorrection>(16_384)
            respond(withContext(Dispatchers.IO) { service.correct(suffix, input) })
        }
        else -> return false
    }
    return true
}
