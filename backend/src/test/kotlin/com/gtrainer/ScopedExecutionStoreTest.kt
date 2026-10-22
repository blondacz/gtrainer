package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import java.util.UUID

class ScopedExecutionStoreTest {
    private val at = Instant.parse("2020-06-10T12:00:00Z")
    private val selection = ConnectedReviewProviderSelection("synthetic", "synthetic-model", "local")

    private fun fixture(block: (Path, HistoryStore) -> Unit) {
        val dir = Files.createTempDirectory("scoped-execution-")
        val path = dir.resolve("history.sqlite3")
        try { HistoryStore(path).use { block(path, it) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    private fun intake(text: String = "Synthetic original note with exact wording.") =
        ContextIntakeRequest(text, "user_report", "synthetic author", "2020-06-01")

    private fun prepare(store: HistoryStore, intake: ContextIntake, profile: String = ContextIntakeSuggestionContractV1.PROFILE) =
        ScopedExecutionService(store, Clock.fixed(at, ZoneOffset.UTC)).prepare(profile,
            ScopedExecutionSource(snapshotId = null, intakeId = intake.intakeId, intakeRevision = intake.revision), selection)

    private fun count(path: Path, table: String): Int = java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        db.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { r -> r.next(); r.getInt(1) } }
    }

    @Test fun `preparation stores immutable source binding and inspection is read only`() = fixture { path, store ->
        val source = store.createIntake(intake(), at)
        val result = prepare(store, source)
        assertEquals(source.intakeId, result.binding.source.intakeId)
        assertEquals(source.revision, result.binding.source.intakeRevision)
        assertEquals(source.content, Json.decodeFromString<ContextIntakeSuggestionPacketV1>(result.binding.exactPrompt.userPacketJson).intake.content)
        assertEquals(source.observedOn, result.binding.coverageFrom)
        assertEquals(source.observedOn, result.binding.coverageUntil)
        assertEquals(0, count(path, "athlete_context"))
        assertEquals(0, count(path,"commitment_revisions"))
        assertEquals(0, count(path,"plan_revisions"))
        assertEquals(0, count(path,"connected_review_snapshots"))
        assertNull(result.validatedOutputJson)
        assertTrue(result.admission.blockingReasons.isNotEmpty())
        assertFalse(result.admission.executionAllowed)
        val beforeChanges = store.captureScopedExecutionSource(ContextIntakeSuggestionContractV1.PROFILE,
            ScopedExecutionSource(snapshotId = null, intakeId = source.intakeId, intakeRevision = source.revision)).totalChanges
        repeat(2) { assertEquals(result,ScopedExecutionService(store).inspect(result.binding.executionId)) }
        val changes = store.captureScopedExecutionSource(ContextIntakeSuggestionContractV1.PROFILE,
            ScopedExecutionSource(snapshotId = null, intakeId = source.intakeId, intakeRevision = source.revision)).totalChanges
        assertEquals(beforeChanges, changes)
        val reopened = HistoryStore(path)
        try { assertEquals(result.binding, ScopedExecutionService(reopened).inspect(result.binding.executionId).binding) }
        finally { reopened.close() }
    }

    @Test fun `correction stales prior revision and claims are refused`() = fixture { _, store ->
        val source = store.createIntake(intake(), at)
        val first = prepare(store, source)
        val corrected = store.correctIntake(source.intakeId, ContextIntakeCorrection(1, intake("Corrected synthetic wording.")), at)
        assertEquals(2, corrected.revision)
        val inspected = ScopedExecutionService(store).inspect(first.binding.executionId)
        assertEquals("STALE", inspected.state)
        assertNull(ScopedExecutionRepository(store).claimConnectedReview(first.binding.executionId))
        assertEquals("Corrected synthetic wording.", corrected.content)
    }

    @Test fun `new preparation advances generation and stales previous execution`() = fixture { path, store ->
        val source = store.createIntake(intake(), at)
        val first = prepare(store, source)
        val second = prepare(store, source)
        assertEquals(first.binding.generation + 1, second.binding.generation)
        assertEquals("STALE", ScopedExecutionService(store).inspect(first.binding.executionId).state)
        assertEquals("IN_FLIGHT", second.state)
        assertTrue(count(path, "scoped_execution_bindings") >= 2)
    }

    private fun validFinal(source: ContextIntake)=ContextIntakeSuggestionContractV1.encodeDraft(ContextIntakeSuggestionDraftV1(
        ContextIntakeSuggestionContractV1.PROFILE,source.intakeId,source.revision,emptyList(),emptyList()))

    private fun owned(store: HistoryStore,id: String): Pair<PersonalReviewClaim,ScopedExecutionRepository> {
        val capture=store.captureScopedExecution(id)
        val resource=PersonalResourceIdentity("synthetic-domain","synthetic-runtime",selection)
        val observation=PersonalResourceObservation(resource,at,at.plusSeconds(600),true)
        val claim=requireNotNull(store.reservePersonalReview(capture,capture.current(),store.personalOwnerEpoch(),observation,
            resource,Clock.fixed(at,ZoneOffset.UTC),600_000))
        assertTrue(store.beginPersonalStart(claim,observation,Clock.fixed(at,ZoneOffset.UTC)))
        val repository=ScopedExecutionRepository(store,claim,Clock.fixed(at,ZoneOffset.UTC))
        return claim to repository
    }

    private suspend fun authorize(store: HistoryStore,claim: PersonalReviewClaim,number: Int): Boolean {
        val resource=claim.resource
        return store.authorizePersonalAttempt(claim,number,
            PersonalResourceObservation(resource,at,at.plusSeconds(600),true),Clock.fixed(at,ZoneOffset.UTC))
    }

    @Test fun `closed default gate never calls provider and synthetic admitted draft cannot confirm active items`() = fixture { path,store ->
        val source=store.createIntake(intake(),at)
        val prepared=prepare(store,source)
        var calls=0
        val provider=object : ConnectedReviewProvider {
            override val providerId=selection.providerId
            override val modelId=selection.modelId
            override val hosted=false
            override val enforcedMaximumCostUsd: java.math.BigDecimal?=null
            override suspend fun generate(prompt: InterpretationPromptV1,correctionReason: String?,responseLimitBytes: Int,costLimitUsd: java.math.BigDecimal?): ConnectedReviewProviderReply {
                calls++;return ConnectedReviewProviderReply(validFinal(source),null)
            }
        }
        val unbound=ScopedExecutionRepository(store)
        val closed=runBlocking { ConnectedReviewExecutor(unbound,limits=ConnectedReviewExecutionLimits(policy=ConnectedReviewExecutionPolicy.PERSONAL),
            monotonicNanos={ 1L },durableClaimOriginNanos=1L,personalAttemptAuthority={ true }).executeManually(prepared.binding.executionId,selection,provider) }
        assertEquals("qualification_gate_disabled",closed.reason);assertEquals(0,calls)
        val (claim,repo)=owned(store,prepared.binding.executionId)
        val accepted=runBlocking { ConnectedReviewExecutor(repo,ConnectedReviewExecutionGate { _,_->null },
            ConnectedReviewExecutionLimits(policy=ConnectedReviewExecutionPolicy.PERSONAL),{ 1L },1L,
            personalAttemptAuthority={ number -> authorize(store,claim,number) }).executeManually(prepared.binding.executionId,selection,provider) }
        assertEquals("PUBLISHED",accepted.state);assertEquals(1,calls)
        assertEquals(validFinal(source),ScopedExecutionService(store).inspect(prepared.binding.executionId).validatedOutputJson)
        assertEquals(0,count(path,"athlete_context"));assertEquals(0,count(path,"commitment_revisions"));assertEquals(0,count(path,"plan_revisions"))
        assertFailsWith<UnsupportedOperationException> { repo.connectedReviewPacket(prepared.binding.executionId) }
    }

    @Test fun `discard purges prompts and output but preserves original note and opaque generation`() = fixture { path,store ->
        val source=store.createIntake(intake(),at)
        val prepared=prepare(store,source)
        assertTrue(ScopedExecutionService(store).discard(prepared.binding.executionId))
        assertFalse(ScopedExecutionService(store).discard(prepared.binding.executionId))
        assertEquals(0,count(path,"scoped_execution_bindings"));assertEquals(0,count(path,"scoped_execution_state"))
        assertEquals(source,store.intakeHistory(source.intakeId).single())
        assertFailsWith<ConnectedReviewNotFound> { ScopedExecutionService(store).inspect(prepared.binding.executionId) }
        assertEquals(2L,prepare(store,source).binding.generation)
    }

    @Test fun `intervening owner write refuses prepared publication atomically`() = fixture { path,store ->
        val source=store.createIntake(intake(),at)
        val first=prepare(store,source)
        val capture=store.captureScopedExecutionSource(first.binding.profile,first.binding.source)
        val prepared=PreparedScopedExecution.prepare(first.binding.copy(executionId=UUID.randomUUID().toString(),generation=2),capture)
        store.createIntake(intake("Independent new note"),at)
        assertFailsWith<ScopedEvidenceConflict> { store.saveScopedExecution(prepared) }
        assertEquals(1,count(path,"scoped_execution_bindings"))
        assertEquals("IN_FLIGHT",ScopedExecutionService(store).inspect(first.binding.executionId).state)
    }

    @Test fun `running execution is interrupted on reopen without inference replay`() {
        val dir=Files.createTempDirectory("scoped-execution-reopen-")
        val path=dir.resolve("history.sqlite3")
        try {
            val binding=HistoryStore(path).use { store ->
                val source=store.createIntake(intake(),at)
                prepare(store,source).binding.also { assertNull(ScopedExecutionRepository(store).claimConnectedReview(it.executionId))
                    assertNotNull(owned(store,it.executionId).second.claimConnectedReview(it.executionId)) }
            }
            HistoryStore(path).use { reopened ->
                val inspection=ScopedExecutionService(reopened).inspect(binding.executionId)
                assertEquals(binding,inspection.binding)
                assertEquals("INTERRUPTED",inspection.state)
                assertEquals("restart_requires_rearming",inspection.staleReason)
                assertNull(inspection.validatedOutputJson)
                assertNull(ScopedExecutionRepository(reopened).claimConnectedReview(binding.executionId))
            }
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    @Test fun `new derived execution changes deletion preview and atomic purge preserves independent inputs`() = fixture { path,store ->
        val source=store.createIntake(intake("PRIVATE_EXECUTION_MARKER"),at)
        val independent=store.createIntake(intake("Independent unrelated note"),at)
        val context=store.createContext(AthleteContextRequest(category="note",sourceCategory=source.sourceCategory,
            authorAttribution=source.authorAttribution,observedOn=source.observedOn,content=source.content))
        store.associateIntake(source.intakeId,ContextIntakeAssociation(source.revision,context.contextId,context.revision))
        val first=prepare(store,source)
        val preview=store.previewContextDeletion(context.contextId,at)
        assertEquals(listOf(first.binding.executionId),preview.executionIds)
        val second=prepare(store,source)
        val staleRequest=ContextDeletionRequest(context.revision,preview.digest,emptyList(),emptyList(),emptyList())
        assertFailsWith<ContextDeletionConflict> { store.redactAndDeleteContext(context.contextId,staleRequest,at) }
        val fresh=store.previewContextDeletion(context.contextId,at)
        assertEquals(setOf(first.binding.executionId,second.binding.executionId),fresh.executionIds.toSet())
        store.redactAndDeleteContext(context.contextId,staleRequest.copy(previewDigest=fresh.digest),at)
        assertEquals(0,count(path,"scoped_execution_bindings"));assertEquals(0,count(path,"scoped_execution_state"))
        assertEquals(independent,store.intakeHistory(independent.intakeId).single())
        assertFailsWith<ConnectedReviewNotFound> { ScopedExecutionService(store).inspect(first.binding.executionId) }
        assertFailsWith<ConnectedReviewNotFound> { ScopedExecutionService(store).inspect(second.binding.executionId) }
    }

    @Test fun `source correction after output validation fences transactional publication`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        val prepared=prepare(store,source)
        val (claim,repo)=owned(store,prepared.binding.executionId)
        assertNotNull(repo.claimConnectedReview(prepared.binding.executionId))
        assertTrue(runBlocking { authorize(store,claim,1) })
        val capture=store.captureScopedExecution(prepared.binding.executionId)
        assertTrue(capture.current())
        val invocation=restoredScopedInvocation(prepared.binding)
        val validated=invocation.validate(validFinal(source)) as ConnectedReviewValidation.Accepted
        store.correctIntake(source.intakeId,ContextIntakeCorrection(1,intake("Changed after validation")),at)
        assertFailsWith<ScopedEvidenceConflict> { store.publishPersonalReview(claim,capture,true,validated.output,Clock.fixed(at,ZoneOffset.UTC)) }
        assertEquals("STALE",ScopedExecutionService(store).inspect(prepared.binding.executionId).state)
        assertNull(ScopedExecutionService(store).inspect(prepared.binding.executionId).validatedOutputJson)
    }

    @Test fun `prepared execution rejects prompts and consent substituted across sources`() = fixture { _,store ->
        val sourceA=store.createIntake(intake("Source A"),at)
        val sourceB=store.createIntake(intake("Source B"),at)
        val bindingA=prepare(store,sourceA).binding
        val bindingB=prepare(store,sourceB).binding
        val captureA=store.captureScopedExecutionSource(bindingA.profile,bindingA.source)
        val substituted=bindingA.copy(generation=bindingA.generation+1,exactPrompt=bindingB.exactPrompt,
            exactCorrectionPrompt=bindingB.exactCorrectionPrompt,consentSha256=bindingB.consentSha256)
        assertFailsWith<IllegalArgumentException> { PreparedScopedExecution.prepare(substituted,captureA) }

        store.correctIntake(sourceB.intakeId,ContextIntakeCorrection(sourceB.revision,intake("Altered source B")),at)
        val captureB=store.captureScopedExecutionSource(bindingB.profile,bindingB.source)
        assertFailsWith<IllegalArgumentException> {
            PreparedScopedExecution.prepare(bindingB.copy(generation=bindingB.generation+1),captureB)
        }
    }

    @Test fun `publication preparation race becomes stale binding conflict without output`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        val prepared=prepare(store,source)
        var calls=0
        val provider=object : ConnectedReviewProvider {
            override val providerId=selection.providerId
            override val modelId=selection.modelId
            override val hosted=false
            override val enforcedMaximumCostUsd: java.math.BigDecimal?=null
            override suspend fun generate(prompt: InterpretationPromptV1,correctionReason: String?,responseLimitBytes: Int,costLimitUsd: java.math.BigDecimal?): ConnectedReviewProviderReply {
                calls++;return ConnectedReviewProviderReply(validFinal(source),null)
            }
        }
        val (claim,delegate)=owned(store,prepared.binding.executionId)
        val racing=object : ConnectedReviewExecutionRepository by delegate {
            override fun prepareConnectedReviewPublication(snapshotId: String,output: ValidatedConnectedReviewOutput,
                currentEvidenceDigest: String,currentContext: List<ContextRevisionRefV1>,provider: String,model: String,
                contractVersion: String): ()->ConnectedReviewStatus {
                val publish=delegate.prepareConnectedReviewPublication(snapshotId,output,currentEvidenceDigest,currentContext,provider,model,contractVersion)
                store.createIntake(intake("Unrelated concurrent write"),at)
                return publish
            }
        }
        val result=runBlocking { ConnectedReviewExecutor(racing,ConnectedReviewExecutionGate { _,_->null },
            ConnectedReviewExecutionLimits(policy=ConnectedReviewExecutionPolicy.PERSONAL),{ 1L },1L,
            personalAttemptAuthority={ number -> authorize(store,claim,number) })
            .executeManually(prepared.binding.executionId,selection,provider) }
        assertEquals("STALE",result.state);assertEquals("binding_conflict",result.reason)
        assertEquals(1,calls);assertFalse(result.attempts.any { it.status=="rejected" })
        val inspection=ScopedExecutionService(store).inspect(prepared.binding.executionId)
        assertEquals("FAILED",inspection.state);assertEquals("binding_conflict",inspection.staleReason)
        assertNull(inspection.validatedOutputJson)
    }

    @Test fun `claim binding conflict stops before provider invocation`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        val prepared=prepare(store,source)
        var calls=0
        val provider=object : ConnectedReviewProvider {
            override val providerId=selection.providerId
            override val modelId=selection.modelId
            override val hosted=false
            override val enforcedMaximumCostUsd: java.math.BigDecimal?=null
            override suspend fun generate(prompt: InterpretationPromptV1,correctionReason: String?,responseLimitBytes: Int,costLimitUsd: java.math.BigDecimal?): ConnectedReviewProviderReply {
                calls++;return ConnectedReviewProviderReply(validFinal(source),null)
            }
        }
        val delegate=ScopedExecutionRepository(store)
        val conflicting=object : ConnectedReviewExecutionRepository by delegate {
            override fun claimConnectedReview(snapshotId: String): ConnectedReviewStatus? =
                throw ConnectedReviewBindingConflict(prepared.binding.consentSha256)
        }
        val result=runBlocking { ConnectedReviewExecutor(conflicting,ConnectedReviewExecutionGate { _,_->null },
            ConnectedReviewExecutionLimits(policy=ConnectedReviewExecutionPolicy.PERSONAL),{ 1L },1L,personalAttemptAuthority={ true })
            .executeManually(prepared.binding.executionId,selection,provider) }
        assertEquals("STALE",result.state);assertEquals("binding_conflict",result.reason)
        assertEquals(0,calls)
        assertNull(ScopedExecutionService(store).inspect(prepared.binding.executionId).validatedOutputJson)
    }
}
