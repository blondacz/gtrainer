package com.gtrainer

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the private authenticated/CSRF dispatcher calls this; no automatic confirmation caller. */
internal suspend fun ApplicationCall.respondCommitment(path: String, service: CommitmentService?): Boolean {
    if(service==null) return false
    val method=request.local.method
    when {
        path=="/api/commitments" && method==HttpMethod.Get -> respond(withContext(Dispatchers.IO) { service.list() })
        path=="/api/commitments/calendar" && method==HttpMethod.Get -> {
            val p=request.queryParameters
            require(p.names()==setOf("oldest","newest","timeZone") && p.names().all { p.getAll(it)?.size==1 })
            val oldest=requireNotNull(p["oldest"]); val newest=requireNotNull(p["newest"]); val zone=requireNotNull(p["timeZone"])
            respond(withContext(Dispatchers.IO) { service.calendar(oldest,newest,zone) })
        }
        path=="/api/commitments/confirm" && method==HttpMethod.Post -> {
            val input=privateJson<CommitmentConfirmation>(32_768)
            respond(HttpStatusCode.Created,withContext(Dispatchers.IO) { service.confirm(input) })
        }
        path.startsWith("/api/commitments/") && path.endsWith("/history") && method==HttpMethod.Get ->
            respond(withContext(Dispatchers.IO) { service.history(path.removePrefix("/api/commitments/").removeSuffix("/history")) })
        path.startsWith("/api/commitments/") && path.endsWith("/confirm") && method==HttpMethod.Post -> {
            val input=privateJson<CommitmentCorrection>(32_768)
            respond(withContext(Dispatchers.IO) { service.correct(path.removePrefix("/api/commitments/").removeSuffix("/confirm"),input) })
        }
        else -> return false
    }
    return true
}
