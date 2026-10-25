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
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class OwnerUatExecutionTest {
    private val at=Instant.parse("2020-06-10T12:00:00Z")
    private val clock=Clock.fixed(at,ZoneOffset.UTC)
    private val selection=ConnectedReviewProviderSelection("synthetic","synthetic-model","local")
    private val resource=PersonalResourceIdentity("synthetic-domain","synthetic-runtime",selection)
    private val profile=ContextIntakeSuggestionContractV1.PROFILE

    private inner class Fixture(val dir: Path) {
        val path=dir.resolve("history.db")
        val store=HistoryStore(path)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val calls=AtomicInteger()
        val constructions=AtomicInteger()
        val observations=AtomicInteger()
        var confirmStop=true
        var respond: suspend (InterpretationPromptV1)->String={ prompt ->
            entered.complete(Unit);release.await();valid(prompt)
        }
        val control=object: PersonalRuntimeControl {
            override suspend fun observe(expected: PersonalResourceIdentity): PersonalResourceObservation {
                observations.incrementAndGet();return PersonalResourceObservation(resource,at,at.plusSeconds(1800),true)
            }
            override suspend fun stop(claim: PersonalReviewClaim): PersonalStopEvidence? =
                if(confirmStop) PersonalStopEvidence(claim,at,true,true) else null
        }
        val factory=object: PersonalProviderFactory {
            override val identity=resource
            override fun create(claim: PersonalReviewClaim): ConnectedReviewProvider {
                constructions.incrementAndGet()
                return object: ConnectedReviewProvider {
                    override val providerId=selection.providerId
                    override val modelId=selection.modelId
                    override val hosted=false
                    override val enforcedMaximumCostUsd: BigDecimal?=null
                    override suspend fun generate(prompt: InterpretationPromptV1,correctionReason: String?,responseLimitBytes: Int,costLimitUsd: BigDecimal?): ConnectedReviewProviderReply {
                        assertNull(correctionReason);calls.incrementAndGet();return ConnectedReviewProviderReply(respond(prompt),null)
                    }
                }
            }
        }
        val first=prepare()
        val installed=OwnerUatInstallationBinding(AnalysisClaims.hash("synthetic app"),resource,
            AnalysisClaims.hash("synthetic runtime"),AnalysisClaims.hash("synthetic model"),AnalysisClaims.hash("synthetic resources"),
            listOf(OwnerUatProfileBinding(profile,listOf(AnalysisClaims.hash(first.binding.exactPrompt.systemInstructions)),AnalysisClaims.hash("synthetic schema"))))
        val policyFile=dir.resolve("policy.json")
        val gate: OwnerUatAdmission
        val request: OwnerUatJobRequest
        val jobs: OwnerUatExecutionService
        init {
            Files.writeString(policyFile,ScopedEvidencePreparation.json.encodeToString(OwnerUatAcceptedPolicy(installed,true,at.toString())))
            Files.setPosixFilePermissions(policyFile,PosixFilePermissions.fromString("rw-------"))
            gate=OwnerUatAdmission(policyFile,{ installed },clock)
            request=OwnerUatJobRequest(assertNotNull(gate.inspect(profile).ownerUat?.policySha256),first.binding.consentSha256)
            jobs=OwnerUatExecutionService(store,gate,control,factory,scope,clock)
        }
        fun prepare(): ScopedExecutionInspection {
            val note=store.createIntake(ContextIntakeRequest("Synthetic note","user_report","synthetic","2020-06-01"),at)
            return ScopedExecutionService(store,clock).prepare(profile,ScopedExecutionSource(intakeId=note.intakeId,intakeRevision=note.revision),selection)
        }
        fun valid(prompt: InterpretationPromptV1): String {
            val packet=ScopedEvidencePreparation.json.decodeFromString<ContextIntakeSuggestionPacketV1>(prompt.userPacketJson)
            return ContextIntakeSuggestionContractV1.encodeDraft(ContextIntakeSuggestionDraftV1(profile,packet.intake.intakeId,packet.intake.revision,emptyList(),emptyList()))
        }
        suspend fun terminal(id: String=first.binding.executionId): OwnerUatJobStatus=withTimeout(5000) {
            while(true) {
                val state=jobs.status(id)
                if(state.state!="UNVERIFIED" && state.runtimePhase in setOf("STOP_CONFIRMED","UNKNOWN")) return@withTimeout state
                delay(5)
            }
            error("Unreachable")
        }
        suspend fun close() {
            release.complete(Unit)
            jobs.close();scope.coroutineContext[Job]!!.cancelAndJoin();store.close()
        }
    }

    private suspend fun fixture(block: suspend (Fixture)->Unit) {
        val dir=Files.createTempDirectory("synthetic-owner-jobs-")
        val fixture=Fixture(dir)
        try { block(fixture) }
        finally {
            fixture.close()
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun `manual request is asynchronous durable one shot and inspections send nothing`()=runBlocking { fixture { f ->
        val id=f.first.binding.executionId
        repeat(2) { assertEquals("IN_FLIGHT",f.jobs.status(id).state) }
        assertEquals(0,f.observations.get());assertEquals(0,f.calls.get())
        assertFalse(f.jobs.request(id,f.request).accepted)
        assertEquals(0,f.constructions.get())
        assertTrue(f.jobs.rearm(f.request.policySha256))
        val submitted=withTimeout(5000) { f.jobs.request(id,f.request) }
        assertTrue(submitted.accepted)
        assertNotNull(f.store.personalReviewForExecution(id))
        withTimeout(5000) { f.entered.await() }
        repeat(3) { assertTrue(f.jobs.request(id,f.request).accepted);f.jobs.status(id) }
        assertEquals(1,f.calls.get());assertEquals(1,f.constructions.get())
        f.release.complete(Unit)
        assertEquals("PUBLISHED",f.terminal().state)
        assertEquals(1,f.terminal().attempts)
        assertNotNull(ScopedExecutionService(f.store,clock).inspect(id).validatedOutputJson)
        assertTrue(f.jobs.request(id,f.request).accepted)
        assertEquals(1,f.calls.get())
        // Empty accepted fake output tests lifecycle only, not useful AI output or confirmation.
        assertTrue(f.store.athleteContexts().list().isEmpty())
    } }

    @Test fun `single owned domain refuses overlap and uncertain stopping blocks the next job`()=runBlocking { fixture { f ->
        assertTrue(f.jobs.rearm(f.request.policySha256))
        assertTrue(f.jobs.request(f.first.binding.executionId,f.request).accepted)
        withTimeout(5000) { f.entered.await() }
        val second=f.prepare()
        val request=f.request.copy(packetSha256=second.binding.consentSha256)
        assertFalse(f.jobs.request(second.binding.executionId,request).accepted)
        assertEquals(1,f.constructions.get())
        f.confirmStop=false;f.release.complete(Unit)
        assertEquals("UNKNOWN",f.terminal().runtimePhase)
        withTimeout(5000) { f.scope.coroutineContext[Job]!!.children.toList().joinAll() }
        val third=f.prepare()
        assertFalse(f.jobs.request(third.binding.executionId,request.copy(packetSha256=third.binding.consentSha256)).accepted)
        assertEquals(1,f.calls.get())
    } }

    @Test fun `rejected output has one attempt no correction and durable terminal failure`()=runBlocking { fixture { f ->
        f.respond={ "not JSON" }
        assertTrue(f.jobs.rearm(f.request.policySha256))
        assertTrue(f.jobs.request(f.first.binding.executionId,f.request).accepted)
        val final=f.terminal()
        assertEquals("FAILED",final.state)
        assertEquals("invalid_output",final.reason)
        assertEquals(1,final.attempts);assertEquals(1,f.calls.get())
        assertTrue(f.jobs.request(f.first.binding.executionId,f.request).accepted)
        assertEquals(1,f.calls.get())
    } }

    @Test fun `different runtime identity with same selected model cannot observe or construct`()=runBlocking { fixture { f ->
        assertTrue(f.jobs.rearm(f.request.policySha256))
        val wrong=object: PersonalProviderFactory by f.factory { override val identity=resource.copy(runtimeId="different-runtime") }
        val authority=PersonalReviewRuntimeAdmission(f.store,f.control,limits=ConnectedReviewExecutionLimits.ownerLocalUat(),
            clock=clock,ownerUat=f.gate)
        assertEquals("owner_uat_runtime_changed",authority.execute(f.first.binding.executionId,wrong).reason)
        assertEquals(0,f.observations.get());assertEquals(0,f.constructions.get())
        assertNull(f.store.personalReviewForExecution(f.first.binding.executionId))
    } }

    @Test fun `discarding execution revokes owned work and retains stop proof without late output`()=runBlocking { fixture { f ->
        assertTrue(f.jobs.rearm(f.request.policySha256))
        val id=f.first.binding.executionId
        assertTrue(f.jobs.request(id,f.request).accepted)
        withTimeout(5000) { f.entered.await() }
        f.jobs.cancel(id,f.request.packetSha256)
        f.store.discardScopedExecution(id)
        f.release.complete(Unit)
        withTimeout(5000) { f.scope.coroutineContext[Job]!!.children.toList().joinAll() }
        assertFailsWith<ConnectedReviewNotFound> { f.store.captureScopedExecution(id) }
        assertEquals(PersonalRuntimePhase.STOP_CONFIRMED,f.store.personalReviewForExecution(id)?.phase)
        assertEquals(1,f.calls.get())
    } }

    @Test fun `cancellation and policy revocation fence a late provider result before publication`()=runBlocking {
        for(disarm in listOf(false,true)) fixture { f ->
            f.respond={ prompt -> f.entered.complete(Unit);withContext(NonCancellable) { f.release.await() };f.valid(prompt) }
            assertTrue(f.jobs.rearm(f.request.policySha256))
            assertTrue(f.jobs.request(f.first.binding.executionId,f.request).accepted)
            withTimeout(5000) { f.entered.await() }
            if(disarm) f.jobs.disarm() else f.jobs.cancel(f.first.binding.executionId,f.request.packetSha256)
            f.release.complete(Unit)
            withTimeout(5000) { f.scope.coroutineContext[Job]!!.children.toList().joinAll() }
            val final=f.jobs.status(f.first.binding.executionId)
            assertEquals("FAILED",final.state)
            assertEquals("STOP_CONFIRMED",final.runtimePhase)
            assertNull(ScopedExecutionService(f.store,clock).inspect(f.first.binding.executionId).validatedOutputJson)
            assertEquals(1,f.calls.get())
        }
    }

    @Test fun `source correction fences running output and restart never replays durable claimed work`()=runBlocking { fixture { f ->
        assertTrue(f.jobs.rearm(f.request.policySha256))
        assertTrue(f.jobs.request(f.first.binding.executionId,f.request).accepted)
        withTimeout(5000) { f.entered.await() }
        f.store.correctIntake(requireNotNull(f.first.binding.source.intakeId),ContextIntakeCorrection(1,
            ContextIntakeRequest("Corrected synthetic note","user_report","synthetic","2020-06-01")),at)
        f.release.complete(Unit)
        assertEquals("STALE",f.terminal().state)
        f.scope.coroutineContext[Job]!!.children.toList().joinAll()
        val reopened=HistoryStore(f.path)
        try {
            val restartedPolicy=OwnerUatAdmission(f.policyFile,{ f.installed },clock)
            val restarted=OwnerUatExecutionService(reopened,restartedPolicy,f.control,f.factory,f.scope,clock)
            assertTrue(restarted.request(f.first.binding.executionId,f.request).accepted)
            assertEquals(false,restartedPolicy.inspect(profile).ownerUat?.armed)
            assertEquals(1,f.calls.get())
            restarted.close()
        } finally { reopened.close() }
    } }

    @Test fun `normal authenticated routes enforce origin csrf exact packet and explicit rearming`()=runBlocking { fixture { f ->
        val origin="http://127.0.0.1:8080"
        val id=f.first.binding.executionId
        testApplication {
            application { module(SingleUserAuth(syntheticVerifier(),origin,false,true),HistoryService(f.store,SyntheticSource(),clock),
                ReviewInterpretationService(emptyList()),schedules=null,reviewQueue=null,ownerUat=f.gate,ownerUatExecution={ f.jobs }) }
            assertEquals(HttpStatusCode.Unauthorized,client.post("/api/review-executions/$id/request").status)
            val login=client.post("/api/login") { header(HttpHeaders.Origin,origin);setBody("""{"password":"$SYNTHETIC_PASSWORD"}""") }
            val cookie=assertNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
            val csrf=Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
            suspend fun post(path: String,body: String,token: String?=csrf,from: String=origin)=client.post(path) {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,from);token?.let { header("X-CSRF-Token",it) }
                contentType(ContentType.Application.Json);setBody(body)
            }
            val rearm="/api/review-profiles/owner-uat/rearm"
            val rearmBody="""{"policySha256":"${f.request.policySha256}"}"""
            assertEquals(HttpStatusCode.Forbidden,post(rearm,rearmBody,null).status)
            assertEquals(HttpStatusCode.Forbidden,post(rearm,rearmBody,from="https://hostile.invalid").status)
            assertEquals(HttpStatusCode.NoContent,post(rearm,rearmBody).status)
            assertEquals(0,f.calls.get())
            val request="/api/review-executions/$id/request"
            val body=ScopedEvidencePreparation.json.encodeToString(f.request)
            assertEquals(HttpStatusCode.Forbidden,post(request,body,null).status)
            assertEquals(HttpStatusCode.BadRequest,post(request,body.dropLast(1)+",\"endpoint\":\"http://hostile.invalid\"}").status)
            assertEquals(HttpStatusCode.BadRequest,post(request,ScopedEvidencePreparation.json.encodeToString(f.request.copy(packetSha256=AnalysisClaims.hash("wrong")))).status)
            assertEquals(HttpStatusCode.Accepted,post(request,body).status)
            withTimeout(5000) { f.entered.await() }
            assertEquals(HttpStatusCode.Accepted,post(request,body).status)
            repeat(2) {
                assertEquals(HttpStatusCode.OK,client.get("/api/review-executions/$id/status") { header(HttpHeaders.Cookie,cookie) }.status)
            }
            assertEquals(1,f.calls.get())
            val cancel="/api/review-executions/$id/cancel"
            assertEquals(HttpStatusCode.Forbidden,post(cancel,"""{"packetSha256":"${f.request.packetSha256}"}""",null).status)
            assertEquals(HttpStatusCode.OK,post(cancel,"""{"packetSha256":"${f.request.packetSha256}"}""").status)
            f.release.complete(Unit)
            assertEquals("FAILED",f.terminal().state)
            assertEquals(1,f.calls.get())
            assertEquals(HttpStatusCode.Forbidden,client.delete("/api/review-executions/$id") {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin)
            }.status)
            assertEquals(HttpStatusCode.NoContent,client.delete("/api/review-executions/$id") {
                header(HttpHeaders.Cookie,cookie);header(HttpHeaders.Origin,origin);header("X-CSRF-Token",csrf)
            }.status)
            assertEquals(PersonalRuntimePhase.STOP_CONFIRMED,f.store.personalReviewForExecution(id)?.phase)
        }
    } }
}
