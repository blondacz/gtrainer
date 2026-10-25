package com.gtrainer

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
internal data class ScopedExecutionPreparationRequest(val profile: String,val source: ScopedExecutionSource,
    val providerSelection: ConnectedReviewProviderSelection,val dateResolution: ContextIntakeDateResolutionV1? = null) {
    override fun toString()="ScopedExecutionPreparationRequest([REDACTED])"
}

/** Separate inert execution preview preparation. No run/consent/provider/runtime endpoint is added. */
internal suspend fun ApplicationCall.respondScopedExecution(path: String,service: ScopedExecutionService?,ownerUatJobs: OwnerUatExecutionService?=null): Boolean {
    if(service==null) return false
    if(path=="/api/review-executions" && request.local.method==HttpMethod.Get) {
        val query=request.queryParameters
        require(query.names().all { it in setOf("profile","snapshotId","offset","limit") && query.getAll(it)?.size==1 })
        val profile=requireNotNull(query["profile"])
        val offset=query["offset"]?.let { requireNotNull(it.toIntOrNull()) } ?: 0
        val limit=query["limit"]?.let { requireNotNull(it.toIntOrNull()) } ?: 25
        respond(withContext(Dispatchers.IO) { service.history(profile,query["snapshotId"],offset,limit) });return true
    }
    if(path.startsWith("/api/review-executions/") && path.endsWith("/feedback") && request.local.method==HttpMethod.Post) {
        val id=path.removePrefix("/api/review-executions/").removeSuffix("/feedback");validateContextId(id)
        require(request.queryParameters.names().isEmpty())
        val input=StrictProfileDraftJson.decode<ScopedExecutionFeedbackRequest>(privateJson<JsonObject>(8192).toString())
        respond(HttpStatusCode.Created,withContext(Dispatchers.IO) { service.feedback(id,input) });return true
    }
    if(path.startsWith("/api/context-intakes/") && path.endsWith("/suggestions") && request.local.method==HttpMethod.Get) {
        val id=path.removePrefix("/api/context-intakes/").removeSuffix("/suggestions")
        validateContextId(id)
        val query=request.queryParameters
        require(query.names()==setOf("expectedRevision") && query.getAll("expectedRevision")?.size==1)
        val revision=query["expectedRevision"]?.toIntOrNull() ?: throw IllegalArgumentException("Expected source revision required")
        val saved=withContext(Dispatchers.IO) { service.savedSuggestions(id,revision) }
        respond(ScopedEvidencePreparation.json.encodeToJsonElement(saved));return true
    }
    if(path=="/api/review-executions/prepare" && request.local.method==HttpMethod.Post) {
        require(request.queryParameters.names().isEmpty())
        val body=privateJson<JsonObject>(32*1024)
        val input=StrictProfileDraftJson.decode<ScopedExecutionPreparationRequest>(body.toString())
        if(input.profile==AmendmentSelectionContractV1.PROFILE) respond(HttpStatusCode.Conflict,
            ApiError("No code-owned amendment candidates are available; active plans remain unchanged"))
        else respond(withContext(Dispatchers.IO) { service.prepare(input.profile,input.source,input.providerSelection,input.dateResolution) })
        return true
    }
    if(!path.startsWith("/api/review-executions/")) return false
    val id=path.removePrefix("/api/review-executions/")
    if(id.contains('/')) { respond(HttpStatusCode.NotFound,ApiError("execution_operation_unavailable"));return true }
    validateContextId(id);require(request.queryParameters.names().isEmpty())
    when(request.local.method) {
        HttpMethod.Get -> respond(withContext(Dispatchers.IO) { service.inspect(id) })
        HttpMethod.Delete -> {
            withContext(Dispatchers.IO) { service.ensureDiscardable(id) }
            ownerUatJobs?.cancel(id,withContext(Dispatchers.IO) { service.inspect(id).binding.consentSha256 })
            withContext(Dispatchers.IO) { service.discard(id) };respond(HttpStatusCode.NoContent)
        }
        else -> return false
    }
    return true
}
