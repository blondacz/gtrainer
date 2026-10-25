package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Invented records and an in-process fake provider, never a model/runtime acceptance witness. */
internal class DescriptiveUiFixture(val dir: Path, private val compactModelInput: Boolean = false,
    providerSelection: ConnectedReviewProviderSelection? = null,
    installationOverride: ((PersonalResourceIdentity) -> OwnerUatInstallationBinding)? = null,
    private val providerOverride: ((PersonalReviewClaim) -> ConnectedReviewProvider)? = null,
    runtimeControlOverride: PersonalRuntimeControl? = null,
    supervision: OwnerUatSupervisorClient? = null) {
    val at=Instant.parse("2020-06-10T12:00:00Z")
    val clock: Clock=Clock.fixed(at,ZoneOffset.UTC)
    private val fresh=!Files.exists(dir.resolve("history.db"))
    val store=HistoryStore(dir.resolve("history.db"),clock)
    val source=SyntheticSource()
    val history=HistoryService(store,source,clock)
    val selection=providerSelection ?: ConnectedReviewProviderSelection("synthetic","fake-descriptive-model","local")
    val resource=PersonalResourceIdentity("synthetic-ui-domain","synthetic-ui-runtime",selection)
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val calls=AtomicInteger()
    val compactInputs=AtomicInteger()
    val observations=AtomicInteger()
    val stopCalls=AtomicInteger()
    var confirmStop=true
    val records=(2..3).map { day -> syntheticActivity().copy(sourceRecordId="synthetic-$day",
        startLocal="2020-06-0${day}T09:00:00",startInstant="2020-06-0${day}T09:00:00Z",
        movingTime=Measurement(600.0,"seconds"),elapsedTime=null,calories=null,distance=null,averageHeartRate=null,intervalsTrainingLoad=null) }
    val control=runtimeControlOverride ?: object: PersonalRuntimeControl {
        override suspend fun observe(expected: PersonalResourceIdentity): PersonalResourceObservation {
            observations.incrementAndGet();return PersonalResourceObservation(resource,at,at.plusSeconds(1800),true)
        }
        override suspend fun stop(claim: PersonalReviewClaim): PersonalStopEvidence? {
            stopCalls.incrementAndGet();return if(confirmStop) PersonalStopEvidence(claim,at,true,true) else null
        }
    }
    val factory=object: PersonalProviderFactory {
        override val identity=resource
        override fun create(claim: PersonalReviewClaim)=providerOverride?.invoke(claim) ?: object: ConnectedReviewProvider {
            override val providerId=selection.providerId
            override val modelId=selection.modelId
            override val hosted=false
            override val enforcedMaximumCostUsd: BigDecimal?=null
            override suspend fun generate(prompt: InterpretationPromptV1,correctionReason: String?,responseLimitBytes: Int,costLimitUsd: BigDecimal?): ConnectedReviewProviderReply {
                check(correctionReason==null);calls.incrementAndGet()
                val packet=if(compactModelInput) {
                    val wire=OwnerUatDescriptiveWireV1.prepare(prompt)
                    OwnerUatDescriptiveWireV1.inspect(wire).also { compactInputs.incrementAndGet() }
                } else ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptivePacketV1>(prompt.userPacketJson)
                val source=packet.references.first { it.kind in setOf("aggregate","session","wellness") }
                val request=packet.evidence.request.selection
                return ConnectedReviewProviderReply(ScopedDescriptiveContractV1.encodeDraft(ScopedDescriptiveDraftV1(
                    ScopedDescriptiveContractV1.PROFILE,request.mode,request.intent,listOf(ScopedDescriptiveItem("observation",
                        "Recorded activity is shown for the selected scope.",listOf(source),"high")),emptyList())),null)
            }
        }
    }
    val installation=installationOverride?.invoke(resource) ?: OwnerUatInstallationBinding(AnalysisClaims.hash("synthetic-ui-app"),resource,
        AnalysisClaims.hash(if(compactModelInput) "synthetic-compact-runtime" else "synthetic-runtime"),AnalysisClaims.hash("synthetic-model"),AnalysisClaims.hash("synthetic-resource-policy"),
        listOf(OwnerUatProfileBinding(ScopedDescriptiveContractV1.PROFILE,ScopedDescriptiveContractV1.promptVariantSha256s(),AnalysisClaims.hash("synthetic-schema"))))
    val gate: OwnerUatAdmission
    val jobs: OwnerUatExecutionService
    val executions: ScopedExecutionService
    init {
        if(fresh) {
            store.updateReviewSchedule(0,ReviewScheduleConfiguration(timeZone="UTC"),at,null,null)
            store.begin("activities",at);store.completeActivities(ReadResult(ReadStatus.SUCCESS,records),at)
        }
        val file=dir.resolve("policy.json")
        if(fresh) {
            Files.writeString(file,ScopedEvidencePreparation.json.encodeToString(OwnerUatAcceptedPolicy(installation,true,at.toString())))
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))
        }
        gate=OwnerUatAdmission(file,{ installation },clock)
        jobs=OwnerUatExecutionService(store,gate,control,factory,scope,clock,supervision=supervision)
        executions=ScopedExecutionService(store,clock,gate,jobs)
    }
    suspend fun prepare(mode: String="period",intent: String="review"): ScopedExecutionInspection {
        val request=if(mode=="activities") ScopedReviewRequest(mode=mode,intent=intent,scope="activities",
            activityIds=records.take(if(intent=="compare") 2 else 1).map(::scopedActivityId),wellnessPolicy="off")
        else ScopedReviewRequest(mode=mode,intent=intent,scope="custom",oldest="2020-06-02",newest="2020-06-02",
            comparison=if(intent=="compare") ScopedDateRange("2020-06-03","2020-06-03") else null,wellnessPolicy="off")
        val evidence=history.prepareEvidence(ScopedEvidenceRequest(selection=request,purpose=if(mode=="activities") "activity_reflection" else "period_reflection"))
        check(evidence.snapshot.state=="PREPARED") { "Synthetic $mode/$intent evidence ${evidence.snapshot.state}: ${evidence.snapshot.manifest.blockedReasons}" }
        return executions.prepare(ScopedDescriptiveContractV1.PROFILE,ScopedExecutionSource(snapshotId=evidence.snapshot.snapshotId),selection)
    }
    fun policyDigest()=requireNotNull(gate.inspect(ScopedDescriptiveContractV1.PROFILE).ownerUat?.policySha256)
    suspend fun run(preview: ScopedExecutionInspection): ScopedExecutionInspection {
        check(jobs.rearm(policyDigest()))
        check(jobs.request(preview.binding.executionId,OwnerUatJobRequest(policyDigest(),preview.binding.consentSha256)).accepted)
        withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
        return executions.inspect(preview.binding.executionId)
    }
    suspend fun close() { jobs.close();scope.coroutineContext[Job]!!.cancelAndJoin();history.close() }
}

class OwnerUatDescriptiveJourneyTest {
    private suspend fun fixture(block: suspend (DescriptiveUiFixture)->Unit) {
        val dir=Files.createTempDirectory("synthetic-descriptive-ui-")
        val f=DescriptiveUiFixture(dir)
        try { block(f) } finally { f.close();Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun `all compiled mode intent variants support guarded fake requests without claiming runtime acceptance`()=runBlocking { fixture { f ->
        val hashes=ScopedDescriptiveContractV1.promptVariantSha256s()
        assertEquals(4,hashes.size);assertEquals(hashes.sorted().distinct(),hashes)
        for(mode in listOf("activities","period")) for(intent in listOf("review","compare")) {
            f.jobs.disarm()
            val preview=f.prepare(mode,intent)
            assertFalse(preview.manualRequestAdmission.manualRequestAllowed)
            assertEquals("unqualified",preview.admission.qualificationState)
            assertFalse(preview.admission.executionAllowed)
            assertTrue(f.jobs.rearm(f.policyDigest()))
            val current=f.executions.inspect(preview.binding.executionId)
            assertTrue(current.manualRequestAdmission.manualRequestAllowed)
            assertEquals("guarded_request_available",current.manualRequestAdmission.requestAdmissionState)
            assertFalse(current.admission.executionAllowed)
            assertEquals("closed_not_accepted",current.admission.runtimeAdmissionState)
            val published=f.run(current)
            assertEquals("PUBLISHED",published.state)
            assertNotNull(published.validatedOutputJson)
            assertFalse(published.manualRequestAdmission.manualRequestAllowed)
        }
        assertEquals(4,f.calls.get());assertEquals(0,f.source.calls)
    } }

    @Test fun `feedback binds immutable output survives restart and blocks direct result deletion before mutation`()=runBlocking { fixture { f ->
        val preview=f.prepare()
        assertFailsWith<IllegalArgumentException> { f.executions.feedback(preview.binding.executionId,
            ScopedExecutionFeedbackRequest("a".repeat(64),ReviewFeedbackRequest(rating="useful"))) }
        val result=f.run(preview);val id=result.binding.executionId
        val output=assertNotNull(result.validatedOutputJson)
        val sha=assertNotNull(result.validatedOutputSha256)
        assertFailsWith<IllegalArgumentException> { f.executions.feedback(id,ScopedExecutionFeedbackRequest("a".repeat(64),ReviewFeedbackRequest(rating="useful"))) }
        val feedback=f.executions.feedback(id,ScopedExecutionFeedbackRequest(sha,ReviewFeedbackRequest(correction="Synthetic owner feedback")))
        assertEquals("review_feedback",feedback.sourceCategory);assertEquals(id,feedback.reviewId)
        assertEquals(output,f.store.captureScopedExecution(id).stored.output)
        assertFailsWith<ScopedExecutionFeedbackConflict> { f.executions.ensureDiscardable(id) }
        assertFailsWith<ScopedExecutionFeedbackConflict> { f.executions.discard(id) }
        assertEquals(output,f.store.captureScopedExecution(id).stored.output)
        HistoryStore(f.dir.resolve("history.db"),f.clock).use { reopened ->
            assertEquals(feedback,reopened.contextRevisions(feedback.contextId).single())
            assertEquals(sha,reopened.scopedExecutionHistory(ScopedDescriptiveContractV1.PROFILE,null,0,25).items.single().outputSha256)
        }
        val p=f.store.previewContextDeletion(feedback.contextId,f.at)
        f.store.redactAndDeleteContext(feedback.contextId,ContextDeletionRequest(p.expectedRevision,p.digest,emptyList(),emptyList(),emptyList()),f.at)
        assertTrue(f.executions.discard(id));assertEquals(1,f.calls.get())
    } }

    @Test fun `oversized default comparison refuses before execution rather than dropping chosen evidence`()=runBlocking { fixture { f ->
        val evidence=f.history.prepareEvidence(ScopedEvidenceRequest(selection=ScopedReviewRequest(intent="compare",scope="custom",
            oldest="2020-06-02",newest="2020-06-02",comparison=ScopedDateRange("2020-06-03","2020-06-03")),purpose="period_reflection"))
        assertEquals("BLOCKED",evidence.snapshot.state)
        assertContains(evidence.snapshot.manifest.blockedReasons,"mandatory_or_explicit_subject_packet_overflow")
        assertFailsWith<ScopedEvidenceConflict> { f.executions.prepare(ScopedDescriptiveContractV1.PROFILE,
            ScopedExecutionSource(snapshotId=evidence.snapshot.snapshotId),f.selection) }
        assertEquals(0,f.calls.get());assertEquals(0,f.observations.get());assertEquals(0,f.source.calls)
    } }

    @Test fun `saved result pages are bounded stable source filtered and read only`()=runBlocking { fixture { f ->
        val made=List(3) { f.prepare() }
        val first=f.executions.history(ScopedDescriptiveContractV1.PROFILE,null,0,2)
        assertEquals(2,first.items.size);assertEquals(2,first.nextOffset)
        assertEquals(first,f.executions.history(ScopedDescriptiveContractV1.PROFILE,null,0,2))
        val last=f.executions.history(ScopedDescriptiveContractV1.PROFILE,null,2,2)
        assertEquals(1,last.items.size);assertNull(last.nextOffset)
        assertEquals(3,(first.items+last.items).map { it.executionId }.distinct().size)
        val selected=made.first().binding
        assertEquals(selected.executionId,f.executions.history(ScopedDescriptiveContractV1.PROFILE,selected.source.snapshotId,0,25).items.single().executionId)
        assertEquals(0,f.calls.get());assertEquals(0,f.observations.get());assertEquals(0,f.source.calls)
    } }

    @Test fun `previously published stale result accepts exact attributed feedback without a new model job`()=runBlocking { fixture { f ->
        val result=f.run(f.prepare());val id=result.binding.executionId
        f.executions.prepare(ScopedDescriptiveContractV1.PROFILE,result.binding.source,f.selection)
        assertEquals("STALE",f.store.captureScopedExecution(id).stored.state)
        val input=ScopedExecutionFeedbackRequest(assertNotNull(result.validatedOutputSha256),ReviewFeedbackRequest(rating="not_useful"))
        val saved=f.executions.feedback(id,input)
        assertEquals(id,saved.reviewId)
        assertEquals(result.validatedOutputJson,f.store.captureScopedExecution(id).stored.output)
        assertEquals(1,f.calls.get())
    } }

    @Test fun `feedback save racing result deletion cannot leave an orphaned attributed report`()=runBlocking { fixture { f ->
        val result=f.run(f.prepare());val id=result.binding.executionId
        val start=CompletableDeferred<Unit>()
        val saved=async(Dispatchers.Default) { start.await();runCatching { f.executions.feedback(id,
            ScopedExecutionFeedbackRequest(assertNotNull(result.validatedOutputSha256),ReviewFeedbackRequest(rating="useful"))) } }
        val deleted=async(Dispatchers.Default) { start.await();runCatching { f.executions.discard(id) } }
        start.complete(Unit)
        val feedback=saved.await();val removal=deleted.await()
        if(feedback.isSuccess) {
            assertIs<ScopedExecutionFeedbackConflict>(removal.exceptionOrNull())
            assertEquals(result.validatedOutputJson,f.store.captureScopedExecution(id).stored.output)
            assertEquals(id,f.store.contextRevisions(feedback.getOrThrow().contextId).single().reviewId)
        } else {
            assertIs<ConnectedReviewNotFound>(feedback.exceptionOrNull());assertTrue(removal.getOrThrow())
            assertTrue(f.store.athleteContexts().list().isEmpty())
        }
        assertEquals(1,f.calls.get())
    } }

    @Test fun `late scoped purge failure rolls feedback closure back and preserves unrelated context`()=runBlocking { fixture { f ->
        val target=f.store.createContext(AthleteContextRequest("note","user_report","synthetic","2020-06-01",content="Synthetic source"))
        val result=f.run(f.prepare());val id=result.binding.executionId
        val feedback=f.executions.feedback(id,ScopedExecutionFeedbackRequest(assertNotNull(result.validatedOutputSha256),ReviewFeedbackRequest(rating="useful")))
        val unrelated=f.store.createContext(AthleteContextRequest("note","user_report","synthetic","2020-06-01",content="Unrelated later note"))
        val preview=f.store.previewContextDeletion(target.contextId,f.at)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${f.dir.resolve("history.db")}").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_scoped_purge_failure BEFORE DELETE ON scoped_execution_bindings BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
            try {
                assertFailsWith<java.sql.SQLException> { f.store.redactAndDeleteContext(target.contextId,
                    ContextDeletionRequest(preview.expectedRevision,preview.digest,emptyList(),emptyList(),emptyList()),f.at) }
                assertEquals(target,f.store.contextRevisions(target.contextId).single())
                assertEquals(feedback,f.store.contextRevisions(feedback.contextId).single())
                assertEquals(unrelated,f.store.contextRevisions(unrelated.contextId).single())
                assertEquals(result.validatedOutputJson,f.store.captureScopedExecution(id).stored.output)
            } finally { db.createStatement().use { it.execute("DROP TRIGGER synthetic_scoped_purge_failure") } }
        }
    } }

    @Test fun `source deletion reaches corrected retired feedback and downstream snapshots with preview fencing`()=runBlocking { fixture { f ->
        val target=f.store.createContext(AthleteContextRequest("note","user_report","synthetic","2020-06-01",content="Synthetic source note"))
        val result=f.run(f.prepare());val id=result.binding.executionId
        val sha=assertNotNull(result.validatedOutputSha256)
        val before=f.store.previewContextDeletion(target.contextId,f.at)
        val feedback=f.executions.feedback(id,ScopedExecutionFeedbackRequest(sha,ReviewFeedbackRequest(rating="not_useful")))
        assertFailsWith<ContextDeletionConflict> { f.store.redactAndDeleteContext(target.contextId,
            ContextDeletionRequest(before.expectedRevision,before.digest,emptyList(),emptyList(),emptyList()),f.at) }
        val changed=f.store.correctContext(feedback.contextId,AthleteContextRequest("note","user_report","athlete","2020-06-01",content="Corrected owner note"))
        val input=AnalysisInput(1,"a".repeat(64),f.at.toString(),null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        val downstream=f.store.createConnectedReviewSnapshot(ConnectedReviewBinding("synthetic-downstream",input,
            retrieveAthleteContext(listOf(changed),ContextRetrievalQuery(LocalDate.parse("2020-06-10"),hardPacketLimit=10)),
            "2020-06-01","2020-06-10",null,f.selection,InterpretationContractV1.PROFILE),f.at)
        f.store.retireContext(feedback.contextId)
        val p=f.store.previewContextDeletion(target.contextId,f.at)
        assertContains(p.deletedContextIds,feedback.contextId)
        assertContains(p.executionIds,id)
        assertTrue(f.store.deleteContext(target.contextId))
        assertFailsWith<ContextNotFound> { f.store.contextRevisions(feedback.contextId) }
        assertFailsWith<ConnectedReviewNotFound> { f.store.captureScopedExecution(id) }
        assertFailsWith<ConnectedReviewNotFound> { f.store.connectedReviewStatus(downstream.snapshotId) }
        assertEquals(1,f.calls.get())
    } }

    @Test fun `normal history and feedback routes enforce source binding auth csrf and bounded query`()=runBlocking { fixture { f ->
        f.confirmStop=false
        val result=f.run(f.prepare());val id=result.binding.executionId
        val origin="http://127.0.0.1:8080"
        testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),f.history,ReviewInterpretationService(emptyList()),
                schedules=null,reviewQueue=null,ownerUat=f.gate,ownerUatExecution={ f.jobs }) }
            val list="/api/review-executions?profile=${ScopedDescriptiveContractV1.PROFILE}"
            assertEquals(HttpStatusCode.Unauthorized,client.get(list).status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=assertNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun get(path: String)=client.get(path) { header(HttpHeaders.Cookie,cookie) }
            suspend fun post(body: String,token: String?=csrf,from: String=origin)=client.post("/api/review-executions/$id/feedback") {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,from);token?.let { header("X-CSRF-Token",it) }
                contentType(ContentType.Application.Json);setBody(body)
            }
            val history=ScopedEvidencePreparation.json.decodeFromString<ScopedExecutionHistory>(get(list).bodyAsText())
            assertEquals(id,history.items.single().executionId)
            for(query in listOf("&limit=26","&offset=-1","&extra=1","&limit=1&limit=1")) assertEquals(HttpStatusCode.BadRequest,get(list+query).status)
            val input=ScopedEvidencePreparation.json.encodeToString(ScopedExecutionFeedbackRequest(assertNotNull(result.validatedOutputSha256),ReviewFeedbackRequest(rating="useful")))
            assertEquals(HttpStatusCode.Forbidden,post(input,null).status)
            assertEquals(HttpStatusCode.Forbidden,post(input,from="https://hostile.invalid").status)
            assertEquals(HttpStatusCode.Created,post(input).status)
            val outputBefore=f.store.captureScopedExecution(id).stored.output
            val stopsBefore=f.stopCalls.get()
            assertEquals(HttpStatusCode.Conflict,client.delete("/api/review-executions/$id") {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);header("X-CSRF-Token",csrf)
            }.status)
            assertEquals(outputBefore,f.store.captureScopedExecution(id).stored.output)
            assertEquals(stopsBefore,f.stopCalls.get())
            assertEquals(1,f.calls.get());assertEquals(0,f.source.calls)
        }
    } }

    @Test fun `all four explicit activity only scopes complete through the normal guarded request API`()=runBlocking { fixture { f ->
        val origin="http://127.0.0.1:8080"
        testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),f.history,ReviewInterpretationService(emptyList()),
                schedules=null,reviewQueue=null,ownerUat=f.gate,ownerUatExecution={ f.jobs }) }
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=assertNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun post(path: String,body: String)=client.post(path) {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);header("X-CSRF-Token",csrf)
                contentType(ContentType.Application.Json);setBody(body)
            }
            for(mode in listOf("period","activities")) for(intent in listOf("review","compare")) {
                val preview=f.prepare(mode,intent);val id=preview.binding.executionId
                val observations=f.observations.get()
                assertEquals(HttpStatusCode.OK,client.get("/api/review-executions/$id") { header(HttpHeaders.Cookie,cookie) }.status)
                assertEquals(observations,f.observations.get())
                assertEquals(HttpStatusCode.NoContent,post("/api/review-profiles/owner-uat/rearm","""{"policySha256":"${f.policyDigest()}"}""").status)
                val request=ScopedEvidencePreparation.json.encodeToString(OwnerUatJobRequest(f.policyDigest(),preview.binding.consentSha256))
                assertEquals(HttpStatusCode.Accepted,post("/api/review-executions/$id/request",request).status)
                withTimeout(5000) { f.scope.coroutineContext[Job]!!.children.toList().joinAll() }
                val response=client.get("/api/review-executions/$id") { header(HttpHeaders.Cookie,cookie) }
                val result=ScopedEvidencePreparation.json.decodeFromString<ScopedExecutionInspection>(response.bodyAsText())
                assertEquals("PUBLISHED",result.state)
                val output=ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptiveDraftV1>(assertNotNull(result.validatedOutputJson))
                assertEquals(mode,output.mode);assertEquals(intent,output.intent)
                assertEquals(HttpStatusCode.Accepted,post("/api/review-executions/$id/request",request).status)
            }
            assertEquals(4,f.calls.get());assertEquals(0,f.source.calls)
        }
    } }
}
