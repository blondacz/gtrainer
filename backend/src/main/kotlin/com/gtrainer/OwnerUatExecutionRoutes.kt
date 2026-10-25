package com.gtrainer

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
private data class OwnerUatRearmRequest(val policySha256: String) {
    init { require(policySha256.matches(Regex("[a-f0-9]{64}"))) }
}
@Serializable
private data class OwnerUatCancelRequest(val packetSha256: String) {
    init { require(packetSha256.matches(Regex("[a-f0-9]{64}"))) }
}
@Serializable
private data class OwnerUatDisarmRequest(val confirmation: String) {
    init { require(confirmation=="Disarm local model") }
}

/** Called only after ordinary authentication, Origin and CSRF authorization. No browser runtime settings. */
internal suspend fun ApplicationCall.respondOwnerUatExecution(path: String,jobs: OwnerUatExecutionService?): Boolean {
    if(jobs==null) return false
    if(path in setOf("/api/review-profiles/owner-uat/rearm","/api/review-profiles/owner-uat/disarm")) {
        require(request.queryParameters.names().isEmpty())
        if(request.local.method!=HttpMethod.Post) return false
        val body=privateJson<JsonObject>(1024).toString()
        if(path.endsWith("/rearm")) {
            val input=StrictProfileDraftJson.decode<OwnerUatRearmRequest>(body)
            if(jobs.rearm(input.policySha256)) respond(HttpStatusCode.NoContent)
            else respond(HttpStatusCode.Conflict,ApiError("owner_uat_policy_not_accepted"))
        } else {
            StrictProfileDraftJson.decode<OwnerUatDisarmRequest>(body)
            withContext(Dispatchers.IO) { jobs.disarm() };respond(HttpStatusCode.NoContent)
        }
        return true
    }
    if(!path.startsWith("/api/review-executions/")) return false
    val tail=path.removePrefix("/api/review-executions/")
    val parts=tail.split('/')
    if(parts.size!=2 || parts[1] !in setOf("request","status","cancel")) return false
    val id=parts[0];validateContextId(id);require(request.queryParameters.names().isEmpty())
    when {
        parts[1]=="status" && request.local.method==HttpMethod.Get -> respond(withContext(Dispatchers.IO) { jobs.status(id) })
        parts[1]=="request" && request.local.method==HttpMethod.Post -> {
            val input=StrictProfileDraftJson.decode<OwnerUatJobRequest>(privateJson<JsonObject>(1024).toString())
            val submitted=jobs.request(id,input)
            if(submitted.accepted) respond(HttpStatusCode.Accepted,submitted.status)
            else respond(HttpStatusCode.Conflict,ApiError(submitted.reason ?: "runtime_admission_closed"))
        }
        parts[1]=="cancel" && request.local.method==HttpMethod.Post -> {
            val input=StrictProfileDraftJson.decode<OwnerUatCancelRequest>(privateJson<JsonObject>(1024).toString())
            respond(jobs.cancel(id,input.packetSha256))
        }
        else -> return false
    }
    return true
}
