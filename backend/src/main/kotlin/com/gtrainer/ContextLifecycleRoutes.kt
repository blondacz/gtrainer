package com.gtrainer

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Authentication and write CSRF checks precede dispatch. GET performs no writes. */
internal suspend fun ApplicationCall.respondContextLifecycle(path: String, service: ContextLifecycleService?): Boolean {
    if(service==null) return false
    val method=request.local.method
    if(path=="/api/context-lifecycle" && method==HttpMethod.Get) {
        val query=request.queryParameters
        require(query.names().all { it=="asOf" && query.getAll(it)?.size==1 })
        respond(withContext(Dispatchers.IO) { service.list(query["asOf"]) })
        return true
    }
    if(path.startsWith("/api/contexts/") && path.endsWith("/lifecycle") && method==HttpMethod.Put) {
        val id=path.removePrefix("/api/contexts/").removeSuffix("/lifecycle")
        val input=privateJson<ContextLifecycleUpdate>(1024)
        respond(withContext(Dispatchers.IO) { service.update(id,input) })
        return true
    }
    return false
}
