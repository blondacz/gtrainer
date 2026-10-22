package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.*
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class PlanAmendmentSelectionApiTest {
    @Test fun exactPacketAndSyntheticDraftValidationArePrivateInertAndNotApprovalOrExecution() {
        val dir=Files.createTempDirectory("amendment-selection-api-");val at=Instant.parse("2026-10-15T12:00:00Z")
        val clock=Clock.fixed(at,ZoneOffset.UTC);val store=HistoryStore(dir.resolve("synthetic.db"),clock)
        val origin="http://127.0.0.1:8080";val json=ScopedEvidencePreparation.json;val source=SyntheticSource()
        val author=PlanAuthor("user","synthetic")
        val plan=store.createPlan(AuthoredPlanRequest("synthetic",author,listOf(PlannedSession(UUID.randomUUID().toString(),author,"run","synthetic",
            PlanSchedule("date_only",date="2026-10-18",timeZone="UTC"),permissions=AmendmentPermissions(omit=true))),PlanPolicy("UTC",7,0)),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference));store.updateReviewSchedule(0,ReviewScheduleConfiguration(timeZone="UTC"),at,null,null)
        val snapshot=store.publishAdaptationResult(PreparedAdaptationResult.prepare(store.captureAdaptationResult(null,at)),at)
        val inspect="/api/plans/amendment-selection/${snapshot.snapshotId}/inspection"
        val validate="/api/plans/amendment-selection/validation"
        var providerCalls=0
        val model=object:ReviewFocusModel {
            override val option=syntheticModelOption
            override suspend fun generate(packet:ReviewFocusPacket,feedback:String?):String { providerCalls++;error("No inference") }
        }
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),HistoryService(store,source,clock),
                ReviewInterpretationService(listOf(model)),schedules=null,reviewQueue=null,plans=PlanService(store,clock)) }
            assertEquals(HttpStatusCode.Unauthorized,client.get(inspect).status)
            assertEquals(HttpStatusCode.Unauthorized,client.post(validate).status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrf=json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun get(path:String)=client.get(path) { header(HttpHeaders.Cookie,cookie) }
            suspend fun post(body:String,token:String?=csrf)=client.post(validate) {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);if(token!=null)header("X-CSRF-Token",token)
                contentType(ContentType.Application.Json);setBody(body)
            }
            val changes=store.capturePlanAmendment(at).totalChanges;val before=store.plans();val queue=store.reviewScheduleState()
            val response=get(inspect);assertEquals(HttpStatusCode.OK,response.status);assertEquals("no-store",response.headers[HttpHeaders.CacheControl])
            val prepared=json.decodeFromString<AmendmentSelectionInspection>(response.bodyAsText());val packet=prepared.packet!!
            val candidate=packet.candidates.single()
            val draft=AmendmentSelectionDraftV1(AmendmentSelectionContractV1.PROFILE,packet.candidateSetSha256,"select",listOf(candidate.candidateId),
                listOf(packet.explanationChoices.single { it.text.startsWith("Candidate ${candidate.candidateId}:") }))
            val input=AmendmentValidationRequest(snapshot.snapshotId,prepared.exactPromptBindingSha256!!,AmendmentSelectionContractV1.encodeDraft(draft))
            assertEquals("AmendmentValidationRequest([REDACTED])",input.toString())
            assertEquals(HttpStatusCode.Forbidden,post(json.encodeToString(input),null).status)
            val checked=post(json.encodeToString(input));assertEquals(HttpStatusCode.OK,checked.status);assertEquals("no-store",checked.headers[HttpHeaders.CacheControl])
            val result=json.decodeFromString<CheckedAmendmentResult>(checked.bodyAsText())
            assertEquals("selected",result.outcome);assertTrue(result.approval.startsWith("not_approvable"))
            val rejected=post(json.encodeToString(input.copy(rawDraft=input.rawDraft.replace(candidate.candidateId,"a".repeat(64)))))
            assertEquals("rejected",json.decodeFromString<CheckedAmendmentResult>(rejected.bodyAsText()).outcome)
            assertEquals(HttpStatusCode.Conflict,post(json.encodeToString(input.copy(expectedPromptBindingSha256="a".repeat(64)))).status)
            assertEquals(HttpStatusCode.BadRequest,get("$inspect?extra=1").status)
            assertEquals(HttpStatusCode.BadRequest,post(json.encodeToString(input).dropLast(1)+",\"operations\":[]}").status)
            assertEquals(HttpStatusCode.PayloadTooLarge,post(json.encodeToString(input.copy(rawDraft="x".repeat(32*1024)))).status)
            assertEquals(HttpStatusCode.NotFound,get("/api/plans/amendment-selection/${UUID.randomUUID()}/inspection").status)
            assertEquals(before,store.plans());assertEquals(queue,store.reviewScheduleState());assertEquals(changes,store.capturePlanAmendment(at).totalChanges)
            assertEquals(0,providerCalls);assertEquals(0,source.calls)
            val proposals="/api/plans/amendment-proposals"
            suspend fun proposalPost(path:String,body:String,token:String?=csrf)=client.post(path) {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);if(token!=null)header("X-CSRF-Token",token)
                contentType(ContentType.Application.Json);setBody(body)
            }
            assertEquals(HttpStatusCode.Unauthorized,client.post(proposals).status)
            assertEquals(HttpStatusCode.Forbidden,proposalPost(proposals,json.encodeToString(input),null).status)
            val madeResponse=proposalPost(proposals,json.encodeToString(input))
            assertEquals(HttpStatusCode.OK,madeResponse.status);assertEquals("no-store",madeResponse.headers[HttpHeaders.CacheControl])
            val made=json.decodeFromString<PlanAmendmentProposalInspection>(madeResponse.bodyAsText())
            assertEquals("pending",made.state);assertEquals("explicit_exact_proposal_approval_required",made.approval)
            assertEquals(HttpStatusCode.Unauthorized,client.get(proposals).status)
            val indexed=json.decodeFromString<PlanAmendmentProposalIndex>(get("$proposals?offset=0&limit=1").bodyAsText())
            assertEquals(listOf(made.proposalId),indexed.entries.map { it.proposalId });assertTrue(indexed.freshness.startsWith("uninspected"))
            assertEquals(HttpStatusCode.BadRequest,get("$proposals?offset=0&offset=1").status)
            assertEquals(HttpStatusCode.BadRequest,get("$proposals?limit=51").status)
            val duplicate=json.decodeFromString<PlanAmendmentProposalInspection>(proposalPost(proposals,json.encodeToString(input)).bodyAsText())
            assertEquals(made,duplicate)
            val proposalPath="$proposals/${made.proposalId}"
            assertEquals(HttpStatusCode.Unauthorized,client.get("$proposalPath/inspection").status)
            assertEquals(made,json.decodeFromString<PlanAmendmentProposalInspection>(get("$proposalPath/inspection").bodyAsText()))
            assertEquals(HttpStatusCode.BadRequest,get("$proposalPath/inspection?extra=1").status)
            assertEquals(HttpStatusCode.BadRequest,proposalPost("$proposalPath/approval","{}").status)
            assertEquals(HttpStatusCode.Conflict,proposalPost("$proposalPath/rejection",json.encodeToString(PlanAmendmentProposalRejection("a".repeat(64)))).status)
            val rejectedProposal=proposalPost("$proposalPath/rejection",json.encodeToString(PlanAmendmentProposalRejection(made.proposalSha256)))
            assertEquals("rejected",json.decodeFromString<PlanAmendmentProposalInspection>(rejectedProposal.bodyAsText()).state)
            assertEquals(before,store.plans());assertEquals(queue,store.reviewScheduleState());assertEquals(0,providerCalls);assertEquals(0,source.calls)
            val ready=json.decodeFromString<PlanAmendmentProposalInspection>(proposalPost(proposals,json.encodeToString(input.copy(rawDraft=input.rawDraft+" "))).bodyAsText())
            val approvalPath="$proposals/${ready.proposalId}/approval"
            val approval=PlanAmendmentApprovalRequest(UUID.randomUUID().toString(),ready.proposal!!.basePlan,ready.proposalSha256,ready.proposal.selectionGeneration)
            val approvalJson=json.encodeToString(approval)
            assertEquals(HttpStatusCode.Unauthorized,client.post(approvalPath).status)
            assertEquals(HttpStatusCode.Forbidden,proposalPost(approvalPath,approvalJson,null).status)
            assertEquals(HttpStatusCode.BadRequest,proposalPost("$approvalPath?extra=1",approvalJson).status)
            assertEquals(HttpStatusCode.BadRequest,proposalPost(approvalPath,approvalJson.dropLast(1)+",\"operations\":[]}").status)
            assertEquals(HttpStatusCode.BadRequest,proposalPost(approvalPath,approvalJson.replace("\"expectedSelectionGeneration\":1","\"expectedSelectionGeneration\":1,\"expectedSelectionGeneration\":1")).status)
            assertEquals(HttpStatusCode.BadRequest,proposalPost(approvalPath,approvalJson.replace("\"expectedSelectionGeneration\":1","\"expectedSelectionGeneration\":\"1\"")).status)
            assertEquals(HttpStatusCode.PayloadTooLarge,proposalPost(approvalPath,"x".repeat(2049)).status)
            assertEquals(HttpStatusCode.Conflict,proposalPost(approvalPath,json.encodeToString(approval.copy(expectedProposalSha256="a".repeat(64)))).status)
            assertEquals(before,store.plans())
            val approved=proposalPost(approvalPath,approvalJson)
            assertEquals(HttpStatusCode.OK,approved.status);assertEquals("no-store",approved.headers[HttpHeaders.CacheControl])
            val receipt=json.decodeFromString<PlanAmendmentApprovalReceipt>(approved.bodyAsText())
            assertEquals(PlanRef(plan.reference.planId,2),receipt.appliedPlan)
            assertEquals(receipt,json.decodeFromString<PlanAmendmentApprovalReceipt>(proposalPost(approvalPath,approvalJson).bodyAsText()))
            assertEquals(2L,store.plans().selection.generation)
            assertEquals("applied",json.decodeFromString<PlanAmendmentProposalInspection>(get("$proposals/${ready.proposalId}/inspection").bodyAsText()).state)
            assertEquals(0,providerCalls);assertEquals(0,source.calls)
        } } finally { store.close();Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}
