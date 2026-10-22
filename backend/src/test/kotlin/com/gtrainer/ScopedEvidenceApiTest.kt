package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class ScopedEvidenceApiTest {
    @Test fun `canonical inert snapshot preparation and inspection enforce authenticated byte and query boundaries`() {
        val dir=Files.createTempDirectory("synthetic-scoped-evidence-api-")
        val store=HistoryStore(dir.resolve("history.db"))
        val at=Instant.parse("2020-06-10T12:00:00Z")
        val clock=Clock.fixed(at,ZoneOffset.UTC)
        val source=SyntheticSource()
        val reviews=ReviewInterpretationService(emptyList())
        val origin="http://127.0.0.1:8080"
        val selection=ScopedReviewRequest(scope="custom",oldest="2020-06-02",newest="2020-06-02",wellnessPolicy="display_only")
        val request=ScopedEvidenceRequest(selection=selection,purpose="question",question="What facts were recorded?")
        store.updateReviewSchedule(0,ReviewScheduleConfiguration(timeZone="UTC"),at,null,null)
        store.begin("activities",at)
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity().copy(startLocal="2020-06-02T10:00:00",
            startInstant="2020-06-02T10:00:00Z"))),at)
        store.begin("wellness",at)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(syntheticWellness().copy(date="2020-06-02",
            sourceRecordId="display-only-secret-marker",measurements=mapOf("sleepSecs" to Measurement(987654.321,"seconds","display-origin-marker"))))),at)
        try { testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),HistoryService(store,source,clock),reviews,
                schedules=null,reviewQueue=null) }
            assertEquals(HttpStatusCode.Unauthorized,client.post("/api/reviews/prepare").status)
            assertEquals(HttpStatusCode.Unauthorized,client.get("/api/reviews/11111111-1111-4111-8111-111111111111").status)
            assertEquals(HttpStatusCode.Unauthorized,client.get("/api/review-profiles").status)
            assertEquals(HttpStatusCode.Unauthorized,client.post("/api/review-executions/prepare").status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun get(path: String)=client.get(path) { header(HttpHeaders.Cookie,cookie) }
            suspend fun post(body: String,token: String?=csrf,from: String=origin)=client.post("/api/reviews/prepare") {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,from)
                token?.let { header("X-CSRF-Token",it) };contentType(ContentType.Application.Json);setBody(body)
            }
            val body=ScopedEvidencePreparation.json.encodeToString(request)
            assertEquals(HttpStatusCode.Forbidden,post(body,null).status)
            assertEquals(HttpStatusCode.Forbidden,post(body,from="https://hostile.invalid").status)
            assertEquals(HttpStatusCode.BadRequest,post("{\"schemaVersion\":2,\"schemaVersion\":2}").status)
            assertEquals(HttpStatusCode.BadRequest,post(body.dropLast(1)+",\"unknown\":true}").status)
            assertEquals(HttpStatusCode.BadRequest,post(body.replace("\"schemaVersion\":2","\"schemaVersion\":3")).status)
            assertEquals(HttpStatusCode.PayloadTooLarge,post(" ".repeat(32769)).status)
            val preparedResponse=post(body)
            assertEquals(HttpStatusCode.OK,preparedResponse.status)
            assertTrue(preparedResponse.headers[HttpHeaders.CacheControl].orEmpty().contains("no-store"))
            val prepared=ScopedEvidencePreparation.json.decodeFromString<ScopedEvidencePrepared>(preparedResponse.bodyAsText())
            val snapshot=prepared.snapshot
            assertEquals("PREPARED",snapshot.state)
            assertEquals("closed_not_qualified_not_execution_bound",snapshot.admission)
            assertEquals(prepared.report.scope,snapshot.manifest.scope)
            assertTrue(snapshot.manifest.displayOnly.isNotEmpty())
            val encoded=ScopedEvidencePreparation.json.encodeToString(snapshot.packet)
            assertFalse(encoded.contains("display-only-secret-marker"))
            assertFalse(encoded.contains("display-origin-marker"))
            assertFalse(encoded.contains("987654.321"))
            assertEquals(snapshot.packetSha256,AnalysisClaims.hash(ScopedEvidencePreparation.json.encodeToString(requireNotNull(snapshot.packet))))
            val inspected=get("/api/reviews/${snapshot.snapshotId}")
            assertEquals(HttpStatusCode.OK,inspected.status)
            assertEquals(snapshot,ScopedEvidencePreparation.json.decodeFromString<ScopedEvidenceSnapshot>(inspected.bodyAsText()))
            val records=get("/api/reviews/${snapshot.snapshotId}/records?limit=1")
            assertEquals(HttpStatusCode.OK,records.status)
            val page=ScopedEvidencePreparation.json.decodeFromString<ScopedEvidenceRecords>(records.bodyAsText())
            assertEquals(1,page.items.size);assertEquals(1,page.nextOffset)
            assertEquals("unchanged",page.items.single().status)
            for(query in listOf("limit=51","offset=-1","limit=1&limit=2","x=1"))
                assertEquals(HttpStatusCode.BadRequest,get("/api/reviews/${snapshot.snapshotId}/records?$query").status)
            assertEquals(HttpStatusCode.BadRequest,get("/api/reviews/${snapshot.snapshotId}?x=1").status)
            assertEquals(HttpStatusCode.NotFound,get("/api/reviews/11111111-1111-4111-8111-111111111111").status)
            // The original factual request remains available without storing or requesting inference.
            val factual=post(Json.encodeToString(selection))
            assertEquals(HttpStatusCode.OK,factual.status)
            assertEquals(1,Json.parseToJsonElement(factual.bodyAsText()).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
            assertEquals(0,source.calls)
            val note=store.createIntake(ContextIntakeRequest("Synthetic saved original note","user_report","Synthetic author","2020-06-01"),at)
            val executionRequest=ScopedExecutionPreparationRequest(ContextIntakeSuggestionContractV1.PROFILE,
                ScopedExecutionSource(intakeId=note.intakeId,intakeRevision=note.revision),ConnectedReviewProviderSelection("synthetic","unqualified-model","local"))
            suspend fun postExecution(body: String,token: String?=csrf)=client.post("/api/review-executions/prepare") {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);token?.let { header("X-CSRF-Token",it) }
                contentType(ContentType.Application.Json);setBody(body)
            }
            val executionBody=ScopedEvidencePreparation.json.encodeToString(executionRequest)
            assertEquals(HttpStatusCode.Forbidden,postExecution(executionBody,null).status)
            assertEquals(HttpStatusCode.BadRequest,postExecution(executionBody.replace("\"intakeRevision\":1","\"intakeRevision\":\"1\"")).status)
            val executionResponse=postExecution(executionBody)
            assertEquals(HttpStatusCode.OK,executionResponse.status)
            val execution=ScopedEvidencePreparation.json.decodeFromString<ScopedExecutionInspection>(executionResponse.bodyAsText())
            assertFalse(execution.admission.executionAllowed)
            assertEquals(note.intakeId,execution.binding.source.intakeId)
            val suggestionPath="/api/context-intakes/${note.intakeId}/suggestions?expectedRevision=${note.revision}"
            val unavailable=Json.parseToJsonElement(get(suggestionPath).bodyAsText()).jsonObject
            assertEquals("unavailable",unavailable.getValue("status").jsonPrimitive.content)
            assertEquals(JsonNull,unavailable.getValue("executionId"));assertEquals(JsonNull,unavailable.getValue("draft"))
            assertEquals(HttpStatusCode.BadRequest,get("/api/context-intakes/${note.intakeId}/suggestions").status)
            assertEquals(HttpStatusCode.BadRequest,get("$suggestionPath&expectedRevision=1").status)
            val beforeReads=store.captureScopedExecution(execution.binding.executionId).source.totalChanges
            repeat(2) { assertEquals(execution,ScopedEvidencePreparation.json.decodeFromString<ScopedExecutionInspection>(get("/api/review-executions/${execution.binding.executionId}").bodyAsText())) }
            assertEquals(beforeReads,store.captureScopedExecution(execution.binding.executionId).source.totalChanges)
            assertEquals(HttpStatusCode.BadRequest,get("/api/review-executions/${execution.binding.executionId}?x=1").status)
            assertEquals(HttpStatusCode.NotFound,client.post("/api/review-executions/${execution.binding.executionId}/run") {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);header("X-CSRF-Token",csrf)
            }.status)
            // Synthetic publication exercises the optional read/edit path, not a production inference route.
            val resource=PersonalResourceIdentity("synthetic-domain","synthetic-runtime",execution.binding.providerSelection)
            val observation=PersonalResourceObservation(resource,at,at.plusSeconds(600),true)
            val capture=store.captureScopedExecution(execution.binding.executionId)
            val claim=assertNotNull(store.reservePersonalReview(capture,capture.current(),store.personalOwnerEpoch(),observation,resource,clock,600_000))
            assertTrue(store.beginPersonalStart(claim,observation,clock))
            val repository=ScopedExecutionRepository(store,claim,java.time.Clock.fixed(at,java.time.ZoneOffset.UTC))
            assertNotNull(repository.claimConnectedReview(execution.binding.executionId))
            assertTrue(store.authorizePersonalAttempt(claim,1,observation,clock))
            val draft=ContextIntakeSuggestionDraftV1(ContextIntakeSuggestionContractV1.PROFILE,note.intakeId,note.revision,
                listOf(ContextIntakeSuggestionV1("context",0,note.content.length,note.content,
                    context=ProposedContextSuggestionV1("note",note.observedOn,null,null,"Synthetic saved original note"),
                    unresolvedFields=listOf("applicableFrom","applicableUntil"))),listOf(ContextIntakeSuggestionQuestionV1("What are the applicability bounds?")))
            val output=(restoredScopedInvocation(execution.binding).validate(ContextIntakeSuggestionContractV1.encodeDraft(draft)) as ConnectedReviewValidation.Accepted).output
            assertEquals("PUBLISHED",repository.publishConnectedReview(execution.binding.executionId,output,execution.binding.dependencyBindingSha256,
                execution.binding.context,execution.binding.providerSelection.storageId,execution.binding.providerSelection.modelId,execution.binding.profile).state)
            val beforeSuggestions=store.captureScopedExecution(execution.binding.executionId).source.totalChanges
            val saved=ScopedEvidencePreparation.json.decodeFromString<SavedIntakeSuggestions>(get(suggestionPath).bodyAsText())
            assertEquals("available",saved.status);assertEquals(draft,saved.draft)
            assertEquals(beforeSuggestions,store.captureScopedExecution(execution.binding.executionId).source.totalChanges)
            val deleted=client.delete("/api/review-executions/${execution.binding.executionId}") {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);header("X-CSRF-Token",csrf)
            }
            assertEquals(HttpStatusCode.NoContent,deleted.status)
            assertEquals(HttpStatusCode.NotFound,get("/api/review-executions/${execution.binding.executionId}").status)
            assertEquals(note,store.intakeHistory(note.intakeId).single())
            assertEquals("unavailable",ScopedEvidencePreparation.json.decodeFromString<SavedIntakeSuggestions>(get(suggestionPath).bodyAsText()).status)
            store.correctIntake(note.intakeId,ContextIntakeCorrection(1,ContextIntakeRequest("Revised source","user_report","Synthetic author","2020-06-01")),at)
            assertEquals(HttpStatusCode.Conflict,get(suggestionPath).status)
            assertEquals(0,source.calls)
            val profileState=get("/api/review-profiles")
            assertEquals(HttpStatusCode.OK,profileState.status)
            assertTrue(profileState.headers[HttpHeaders.CacheControl].orEmpty().contains("no-store"))
            val admission=ScopedEvidencePreparation.json.decodeFromString<List<ScopedProfileAdmissionStatus>>(profileState.bodyAsText())
            assertEquals(ScopedProfileAdmission.inspectAll(),admission)
            assertTrue(admission.all { !it.executionAllowed && it.selectedProviderModel==null && it.qualificationState=="unqualified" })
            assertEquals(HttpStatusCode.BadRequest,get("/api/review-profiles?profile=x&profile=y").status)
            assertEquals(HttpStatusCode.BadRequest,get("/api/review-profiles?extra=true").status)
            val unsupported=ScopedEvidencePreparation.json.decodeFromString<List<ScopedProfileAdmissionStatus>>(get("/api/review-profiles?profile=unknown-profile-v1").bodyAsText()).single()
            assertFalse(unsupported.supported);assertFalse(unsupported.executionAllowed)
            assertEquals(0,source.calls)
            val baselineRequest=request.copy(selection=selection.copy(wellnessPolicy="assessment"),baselines=listOf(
                ScopedBaselineSelection("sleepSecs",syntheticWellness().source,"display-origin-marker",ScopedBaselineConfig("2020-06-03",1,1,1.0,1))))
            val baselineBody=ScopedEvidencePreparation.json.encodeToString(baselineRequest)
            val baselineResponse=post(baselineBody)
            assertEquals(HttpStatusCode.OK,baselineResponse.status)
            val baseline=ScopedEvidencePreparation.json.decodeFromString<ScopedEvidencePrepared>(baselineResponse.bodyAsText()).snapshot.manifest.baselines.single()
            assertNull(baseline.value)
            assertTrue("semantics_unverified" in baseline.reasons)
            assertEquals(HttpStatusCode.BadRequest,post(baselineBody.replace("\"field\":\"sleepSecs\"","\"field\":\"sleepSecs\",\"verification\":\"verified\"")).status)
            assertEquals(HttpStatusCode.BadRequest,post(baselineBody.replace("2020-06-03","2020-06-11")).status)
            assertEquals(HttpStatusCode.BadRequest,post(baselineBody.replace("\"trailingDays\":1","\"trailingDays\":367")).status)
            assertEquals(0,source.calls)
        } } finally {
            store.close();reviews.close()
            Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir)
        }
    }
}
