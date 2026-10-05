package com.gtrainer

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
internal data class DevelopmentReviewSubmitRequest(val previewId: String)
@Serializable
internal class DevelopmentReviewRunResponse(val task: DevelopmentReviewTask, val inspection: ConnectedReviewInspection,
    val attempts: List<DevelopmentAttemptMetadata>) {
    override fun toString() = "DevelopmentReviewRunResponse([REDACTED])"
}
@Serializable
internal data class DevelopmentReviewQueueResponse(val tasks: List<DevelopmentReviewTask>, val maintenance: List<DevelopmentMaintenanceTask>)
@Serializable
internal data class DevelopmentReviewDiagnosticsResponse(val worker: DevelopmentWorkerDiagnostics, val summaries: List<DevelopmentWorkerSummary>)
@Serializable
internal class DevelopmentReviewEmptyRequest

/** Short durable writes only. Committed notifications cannot turn a read/preview into work. */
internal fun Route.developmentQueueRoutes(store: DevelopmentReviewStore, worker: DevelopmentReviewWorker?) {
    post("/development/runs") {
        call.developmentResponse {
            val request = call.privateJson<DevelopmentReviewSubmitRequest>()
            val task = withContext(Dispatchers.IO) { store.submit(developmentId(request.previewId)) }
            worker?.notifyCommitted()
            call.respond(HttpStatusCode.Accepted, task)
        }
    }
    get("/development/queue") {
        call.developmentResponse {
            call.respond(withContext(Dispatchers.IO) { DevelopmentReviewQueueResponse(store.queue(), store.maintenanceTasks()) })
        }
    }
    get("/development/runs/{id}") {
        call.developmentResponse {
            val id = developmentId(requireNotNull(call.parameters["id"]))
            call.respond(withContext(Dispatchers.IO) { store.runResponse(id) })
        }
    }
    post("/development/runs/{id}/cancel") {
        call.developmentResponse {
            call.privateJson<DevelopmentReviewEmptyRequest>()
            val id = developmentId(requireNotNull(call.parameters["id"]))
            val result = withContext(Dispatchers.IO) { store.cancel(id) }
            if (result.accepted) worker?.notifyCommitted()
            call.respond(result)
        }
    }
    post("/development/maintenance") {
        call.developmentResponse {
            call.privateJson<DevelopmentReviewEmptyRequest>()
            val task = withContext(Dispatchers.IO) { store.submitMaintenance() }
            worker?.notifyCommitted()
            call.respond(HttpStatusCode.Accepted, task)
        }
    }
    get("/development/diagnostics") {
        call.developmentResponse {
            call.respond(withContext(Dispatchers.IO) {
                val diagnostics = worker?.diagnostics() ?: DevelopmentWorkerDiagnostics(false, null, store.pendingExecutions().isNotEmpty(),
                    store.pendingExecutions().size, "development_worker_not_configured", null)
                DevelopmentReviewDiagnosticsResponse(diagnostics, store.workerSummaries())
            })
        }
    }
}

internal fun developmentId(value: String): String {
    require(value.length == 36 && UUID.fromString(value).toString() == value)
    return value
}
