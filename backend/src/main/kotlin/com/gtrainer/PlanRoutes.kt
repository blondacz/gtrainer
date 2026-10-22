package com.gtrainer

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal suspend fun ApplicationCall.respondPlan(path: String, service: PlanService?): Boolean {
    if(service==null || path!="/api/plans" && !path.startsWith("/api/plans/")) return false
    val method=request.local.method
    when {
        path=="/api/plans/amendment-proposals" && method==HttpMethod.Get -> {
            val parameters=request.queryParameters
            require(parameters.names().all { it in setOf("offset","limit") && parameters.getAll(it)?.size==1 })
            val offset=parameters["offset"]?.toInt() ?: 0;val limit=parameters["limit"]?.toInt() ?: 25
            respond(withContext(Dispatchers.IO) { service.amendmentProposals(offset,limit) })
        }
        path=="/api/plans/amendment-proposals" && method==HttpMethod.Post -> {
            require(request.queryParameters.names().isEmpty())
            val input=privateJson<AmendmentValidationRequest>(32*1024)
            respond(withContext(Dispatchers.IO) { service.generateAmendmentProposal(input) })
        }
        path.startsWith("/api/plans/amendment-proposals/") -> {
            val parts=path.removePrefix("/api/plans/amendment-proposals/").split('/')
            require(parts.size==2 && request.queryParameters.names().isEmpty())
            when {
                method==HttpMethod.Get && parts[1]=="inspection" -> respond(withContext(Dispatchers.IO) { service.amendmentProposal(parts[0]) })
                method==HttpMethod.Post && parts[1]=="approval" -> {
                    val input=StrictProfileDraftJson.decode<PlanAmendmentApprovalRequest>(privateJson<JsonObject>(2048).toString())
                    respond(withContext(Dispatchers.IO) { service.approveAmendmentProposal(parts[0],input) })
                }
                method==HttpMethod.Post && parts[1]=="rejection" -> {
                    val input=privateJson<PlanAmendmentProposalRejection>(1024)
                    respond(withContext(Dispatchers.IO) { service.rejectAmendmentProposal(parts[0],input) })
                }
                else -> respond(HttpStatusCode.NotFound,ApiError("not_found"))
            }
        }
        path=="/api/plans/amendment-selection/validation" && method==HttpMethod.Post -> {
            require(request.queryParameters.names().isEmpty())
            val input=privateJson<AmendmentValidationRequest>(32*1024)
            respond(withContext(Dispatchers.IO) { service.validateAmendmentSelection(input) })
        }
        path.startsWith("/api/plans/amendment-selection/") && method==HttpMethod.Get -> {
            val parts=path.removePrefix("/api/plans/amendment-selection/").split('/')
            require(parts.size==2 && parts[1]=="inspection" && request.queryParameters.names().isEmpty())
            respond(withContext(Dispatchers.IO) { service.amendmentSelection(parts[0]) })
        }
        path=="/api/plans/amendment-candidates/inspection" && method==HttpMethod.Get -> {
            require(request.queryParameters.names().isEmpty())
            respond(withContext(Dispatchers.IO) { service.amendmentCandidates() })
        }
        path=="/api/plans/workload-rules" && method==HttpMethod.Get -> {
            require(request.queryParameters.names().isEmpty())
            respond(withContext(Dispatchers.IO) { service.workloadRules() })
        }
        path=="/api/plans/workload-rules" && method==HttpMethod.Post -> {
            require(request.queryParameters.names().isEmpty())
            val input=privateJson<ExtraWorkloadRuleRequest>(8192)
            respond(withContext(Dispatchers.IO) { service.saveWorkloadRule(input) })
        }
        path.startsWith("/api/plans/workload-rules/") && method==HttpMethod.Get -> {
            val parts=path.removePrefix("/api/plans/workload-rules/").split('/')
            require(parts.size==2 && parts[1]=="inspection" && request.queryParameters.names().isEmpty())
            respond(withContext(Dispatchers.IO) { service.inspectWorkloadRule(parts[0]) })
        }
        path=="/api/plans/signal-rules" && method==HttpMethod.Get -> {
            require(request.queryParameters.names().isEmpty())
            respond(withContext(Dispatchers.IO) { service.signalRules() })
        }
        path=="/api/plans/signal-rules" && method==HttpMethod.Post -> {
            require(request.queryParameters.names().isEmpty())
            val input=privateJson<PersonalSignalRuleRequest>(8192)
            respond(withContext(Dispatchers.IO) { service.saveSignalRule(input) })
        }
        path.startsWith("/api/plans/signal-rules/") && method==HttpMethod.Get -> {
            val parts=path.removePrefix("/api/plans/signal-rules/").split('/')
            require(parts.size==2 && parts[1]=="inspection")
            val p=request.queryParameters
            require(p.names()==setOf("oldest","newest") && p.names().all { p.getAll(it)?.size==1 })
            val query=PersonalSignalQuery(requireNotNull(p["oldest"]),requireNotNull(p["newest"]))
            respond(withContext(Dispatchers.IO) { service.inspectSignalRule(parts[0],query) })
        }
        path=="/api/plans/effort-reports" && method==HttpMethod.Get -> {
            val p=request.queryParameters
            require(p.names()==setOf("activityId","scaleId") && p.names().all { p.getAll(it)?.size==1 })
            respond(withContext(Dispatchers.IO) { service.effortHistory(requireNotNull(p["activityId"]),requireNotNull(p["scaleId"])) })
        }
        path=="/api/plans/effort-reports" && method==HttpMethod.Post -> {
            require(request.queryParameters.names().isEmpty())
            val input=privateJson<ActivityEffortRequest>(8192)
            respond(withContext(Dispatchers.IO) { service.saveEffortReport(input) })
        }
        path=="/api/plans/reconciliation" && method==HttpMethod.Get -> {
            val p=request.queryParameters
            require(p.names()==setOf("oldest","newest","timeZone") && p.names().all { p.getAll(it)?.size==1 })
            val query=PlanReconciliationQuery(requireNotNull(p["oldest"]),requireNotNull(p["newest"]),requireNotNull(p["timeZone"]))
            respond(withContext(Dispatchers.IO) { service.reconciliation(query) })
        }
        path=="/api/plans/reconciliation/confirmations" && method==HttpMethod.Post -> {
            require(request.queryParameters.names().isEmpty())
            val input=privateJson<PlanReconciliationConfirmationRequest>(8192)
            respond(withContext(Dispatchers.IO) { service.confirmReconciliation(input) })
        }
        path=="/api/plans/calendar" && method==HttpMethod.Get -> {
            val p=request.queryParameters
            require(p.names()==setOf("oldest","newest","timeZone") && p.names().all { p.getAll(it)?.size==1 })
            respond(withContext(Dispatchers.IO) { service.calendar(requireNotNull(p["oldest"]),requireNotNull(p["newest"]),requireNotNull(p["timeZone"])) })
        }
        else -> {
            require(request.queryParameters.names().isEmpty())
            when {
                path=="/api/plans" && method==HttpMethod.Get -> respond(withContext(Dispatchers.IO) { service.list() })
                path=="/api/plans" && method==HttpMethod.Post -> {
                    val input=privateJson<AuthoredPlanRequest>(32_768)
                    respond(HttpStatusCode.Created,withContext(Dispatchers.IO) { service.create(input) })
                }
                path=="/api/plans/selection" && method==HttpMethod.Put -> {
                    val input=privateJson<PlanSelectionRequest>(4096)
                    respond(withContext(Dispatchers.IO) { service.select(input) })
                }
                path.startsWith("/api/plans/") && method==HttpMethod.Get -> {
                    val parts=path.removePrefix("/api/plans/").split('/')
                    require(parts.size==1 || parts.size==3 && parts[1]=="revisions")
                    val revision=if(parts.size==3) requireNotNull(parts[2].toIntOrNull()) else null
                    respond(withContext(Dispatchers.IO) { service.get(parts[0],revision) })
                }
                path.startsWith("/api/plans/") && method==HttpMethod.Put -> {
                    val input=privateJson<PlanCorrection>(32_768)
                    respond(withContext(Dispatchers.IO) { service.correct(path.removePrefix("/api/plans/"),input) })
                }
                else -> return false
            }
        }
    }
    return true
}
