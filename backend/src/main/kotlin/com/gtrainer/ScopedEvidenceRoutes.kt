package com.gtrainer

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Authenticated dispatcher only. No provider, upstream-source, scheduler or maintenance access. */
internal suspend fun ApplicationCall.respondScopedEvidence(path: String, history: HistoryService?): Boolean {
    if(history==null) return false
    if(path=="/api/reviews/prepare" && request.local.method==HttpMethod.Post) {
        require(request.queryParameters.names().isEmpty())
        val body=privateJson<JsonObject>(32_768)
        val version=body["schemaVersion"]?.jsonPrimitive
        require(version==null || !version.isString)
        when(version?.content ?: "1") {
            "1" -> respond(ReviewFocusProtocol.json.encodeToJsonElement(history.scopedTrends(Json.decodeFromJsonElement<ScopedReviewRequest>(body))))
            "2" -> respond(ReviewFocusProtocol.json.encodeToJsonElement(history.prepareEvidence(Json.decodeFromJsonElement<ScopedEvidenceRequest>(body))))
            else -> throw IllegalArgumentException("Unsupported preparation schema")
        }
        return true
    }
    if(!path.startsWith("/api/reviews/") || request.local.method!=HttpMethod.Get) return false
    val parts=path.removePrefix("/api/reviews/").split('/')
    // Leave options and unknown subresources to their owning dispatcher.
    if(parts.size==1 && parts[0]=="options") return false
    if(parts.size==1) {
        validateContextId(parts[0]);require(request.queryParameters.names().isEmpty())
        respond(ReviewFocusProtocol.json.encodeToJsonElement(history.inspectEvidence(parts[0])))
    } else if(parts.size==2 && parts[1]=="records") {
        validateContextId(parts[0])
        val p=request.queryParameters
        require(p.names().all { it in setOf("offset","limit") && p.getAll(it)?.size==1 })
        val offset=p["offset"]?.let { requireNotNull(it.toIntOrNull()) } ?: 0
        val limit=p["limit"]?.let { requireNotNull(it.toIntOrNull()) } ?: 25
        respond(ReviewFocusProtocol.json.encodeToJsonElement(history.evidenceRecords(parts[0],offset,limit)))
    } else return false
    return true
}
