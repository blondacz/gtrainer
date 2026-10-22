package com.gtrainer

import kotlinx.coroutines.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class PersonalReviewRuntimeTest {
    private val at=Instant.parse("2020-06-10T12:00:00Z")
    private val selection=ConnectedReviewProviderSelection("synthetic","synthetic-model","local")
    private val resource=PersonalResourceIdentity("synthetic-domain","synthetic-runtime",selection)
    private val personal=ConnectedReviewExecutionLimits(policy=ConnectedReviewExecutionPolicy.PERSONAL)
    private fun fixture(block: (Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("synthetic-personal-runtime-");val path=dir.resolve("history.sqlite3")
        try { HistoryStore(path).use { block(path,it) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun prepared(store: HistoryStore,selected: ConnectedReviewProviderSelection=selection): ScopedExecutionBinding {
        val intake=store.createIntake(ContextIntakeRequest("Synthetic original note","user_report","synthetic","2020-06-01"),at)
        return ScopedExecutionService(store,ReviewTestClock(at)).prepare(ContextIntakeSuggestionContractV1.PROFILE,
            ScopedExecutionSource(intakeId=intake.intakeId,intakeRevision=intake.revision),selected).binding
    }
    private fun final(binding: ScopedExecutionBinding)=ContextIntakeSuggestionContractV1.encodeDraft(
        ContextIntakeSuggestionDraftV1(binding.profile,requireNotNull(binding.source.intakeId),requireNotNull(binding.source.intakeRevision),emptyList(),emptyList()))
    private inner class Control: PersonalRuntimeControl {
        var observation: PersonalResourceObservation?=PersonalResourceObservation(resource,at,at.plusSeconds(600),true)
        var stopConfirmed=true
        var lateStartsFenced=true
        var stopped=0
        var wrongIdentity=false
        var observations=0
        var onObserve: (Int)->Unit={}
        override suspend fun observe(expected: PersonalResourceIdentity): PersonalResourceObservation? {
            onObserve(++observations);return observation
        }
        override suspend fun stop(claim: PersonalReviewClaim): PersonalStopEvidence? {
            stopped++
            return if(!stopConfirmed) null else PersonalStopEvidence(if(wrongIdentity) claim.copy(generation=claim.generation+1) else claim,
                at,true,lateStartsFenced)
        }
    }
    private inner class Factory(val binding: ScopedExecutionBinding,
        private val reply: suspend (PersonalReviewClaim)->String = { final(binding) }): PersonalProviderFactory {
        override val identity=resource.copy(selection=binding.providerSelection)
        var constructions=0
        var calls=0
        lateinit var claim: PersonalReviewClaim
        var onCreate: (PersonalReviewClaim)->Unit={}
        override fun create(claim: PersonalReviewClaim): ConnectedReviewProvider {
            constructions++;this.claim=claim;onCreate(claim)
            return object: ConnectedReviewProvider {
                override val providerId=binding.providerSelection.providerId
                override val modelId=binding.providerSelection.modelId
                override val hosted=binding.providerSelection.providerKind=="hosted"
                override val enforcedMaximumCostUsd=if(hosted) BigDecimal("0.20") else null
                override suspend fun generate(prompt: InterpretationPromptV1,correctionReason: String?,responseLimitBytes: Int,costLimitUsd: BigDecimal?): ConnectedReviewProviderReply {
                    calls++;return ConnectedReviewProviderReply(reply(claim),if(hosted) BigDecimal("0.10") else null)
                }
            }
        }
    }
    private fun admission(store: HistoryStore,control: Control,consent: ConnectedReviewConsentLedger=ConnectedReviewConsentLedger(),
        clock: ReviewTestClock=ReviewTestClock(at))=PersonalReviewRuntimeAdmission(store,control,PersonalProfileQualification { null },consent,personal,clock)
    private fun reserve(store: HistoryStore,binding: ScopedExecutionBinding): PersonalReviewClaim {
        val capture=store.captureScopedExecution(binding.executionId)
        return assertNotNull(store.reservePersonalReview(capture,capture.current(),store.personalOwnerEpoch(),
            PersonalResourceObservation(resource,at,at.plusSeconds(600),true),resource,ReviewTestClock(at),600_000))
    }
    private fun start(store: HistoryStore,claim: PersonalReviewClaim)=store.beginPersonalStart(claim,
        PersonalResourceObservation(resource,at,at.plusSeconds(600),true),ReviewTestClock(at))

    @Test fun `closed qualification and missing stale mismatched observations construct no providers`()=fixture { _,store -> runBlocking {
        val binding=prepared(store);val factory=Factory(binding);val control=Control()
        assertEquals("profile_not_qualified",PersonalReviewRuntimeAdmission(store,control,clock=ReviewTestClock(at)).execute(binding.executionId,factory).reason)
        for(observation in listOf(null,
            PersonalResourceObservation(resource,at.minusSeconds(600),at,true),
            PersonalResourceObservation(resource.copy(runtimeId="wrong-runtime"),at,at.plusSeconds(30),true),
            PersonalResourceObservation(resource,at.plusSeconds(1),at.plusSeconds(30),true),
            PersonalResourceObservation(resource,at,at.plusSeconds(30),false))) {
            control.observation=observation
            assertEquals("REFUSED",admission(store,control).execute(binding.executionId,factory).state)
        }
        assertEquals(0,factory.constructions);assertEquals(0,factory.calls)
    } }

    @Test fun `concurrent executions share one durable domain claim and loser never constructs`()=fixture { _,store -> runBlocking {
        val first=prepared(store);val second=prepared(store)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val control=Control();val authority=admission(store,control)
        val winner=Factory(first) { entered.complete(Unit);release.await();final(first) }
        val loser=Factory(second)
        val running=async { authority.execute(first.executionId,winner) }
        entered.await()
        assertEquals("REFUSED",authority.execute(second.executionId,loser).state)
        assertEquals(0,loser.constructions);assertEquals(0,loser.calls)
        release.complete(Unit)
        assertEquals("PUBLISHED",running.await().state)
        assertEquals(PersonalRuntimePhase.STOP_CONFIRMED,store.personalRuntimeLease(winner.claim.claimId)?.phase)
        assertEquals("PUBLISHED",authority.execute(second.executionId,loser).state)
    } }

    @Test fun `successful output keeps occupancy until exact physical stop and late start fence`()=fixture { _,store -> runBlocking {
        val first=prepared(store);val second=prepared(store)
        val control=Control().also { it.stopConfirmed=false };val authority=admission(store,control)
        val factory=Factory(first)
        assertEquals("PUBLISHED",authority.execute(first.executionId,factory).state)
        assertEquals(PersonalRuntimePhase.UNKNOWN,store.personalRuntimeLease(factory.claim.claimId)?.phase)
        assertFalse(assertNotNull(store.personalRuntimeLease(factory.claim.claimId)).publicationAllowed)
        val next=Factory(second)
        assertEquals("REFUSED",authority.execute(second.executionId,next).state);assertEquals(0,next.constructions)
        control.stopConfirmed=true;control.wrongIdentity=true;authority.cleanup(factory.claim)
        assertEquals(PersonalRuntimePhase.UNKNOWN,store.personalRuntimeLease(factory.claim.claimId)?.phase)
        control.wrongIdentity=false;control.lateStartsFenced=false;authority.cleanup(factory.claim)
        assertEquals(PersonalRuntimePhase.UNKNOWN,store.personalRuntimeLease(factory.claim.claimId)?.phase)
        control.lateStartsFenced=true;authority.cleanup(factory.claim)
        assertEquals(PersonalRuntimePhase.STOP_CONFIRMED,store.personalRuntimeLease(factory.claim.claimId)?.phase)
        assertEquals("PUBLISHED",ScopedExecutionService(store).inspect(first.executionId).state)
        assertEquals("PUBLISHED",authority.execute(second.executionId,next).state)
    } }

    @Test fun `independent cancellation revokes stuck generation and late reply cannot publish`()=fixture { _,store -> runBlocking {
        val binding=prepared(store);val next=prepared(store)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val control=Control().also { it.stopConfirmed=false };val authority=admission(store,control)
        val factory=Factory(binding) { entered.complete(Unit);withContext(NonCancellable) { release.await() };final(binding) }
        val running=async { authority.execute(binding.executionId,factory) }
        entered.await()
        authority.cancel(factory.claim)
        assertFalse(assertNotNull(store.personalRuntimeLease(factory.claim.claimId)).publicationAllowed)
        authority.cleanup(factory.claim)
        val loser=Factory(next)
        assertEquals("REFUSED",authority.execute(next.executionId,loser).state);assertEquals(0,loser.constructions)
        release.complete(Unit)
        assertNotEquals("PUBLISHED",running.await().state)
        assertNull(ScopedExecutionService(store).inspect(binding.executionId).validatedOutputJson)
    } }

    @Test fun `cancellation during construction cannot authorize a late send`()=fixture { _,store -> runBlocking {
        val binding=prepared(store);val control=Control();val authority=admission(store,control)
        val factory=Factory(binding).also { factory -> factory.onCreate={ claim -> authority.cancel(claim) } }
        assertNotEquals("PUBLISHED",authority.execute(binding.executionId,factory).state)
        assertEquals(1,factory.constructions);assertEquals(0,factory.calls)
        assertEquals(PersonalRuntimePhase.STOP_CONFIRMED,store.personalRuntimeLease(factory.claim.claimId)?.phase)
    } }

    @Test fun `validator correction stays within one claim factory and two attempt permits`()=fixture { _,store -> runBlocking {
        val binding=prepared(store);val control=Control();var replies=0
        val factory=Factory(binding) { if(++replies==1) "{}" else final(binding) }
        val result=admission(store,control).execute(binding.executionId,factory)
        assertEquals("PUBLISHED",result.state);assertEquals(1,factory.constructions);assertEquals(2,factory.calls)
        assertEquals(2,store.personalRuntimeLease(factory.claim.claimId)?.attempts)
        assertEquals(listOf("rejected","accepted"),result.attempts.map { it.status })
    } }

    @Test fun `restart fences old epoch without replay and current authority can reconcile old occupancy`()=fixture { path,store -> runBlocking {
        val binding=prepared(store);val next=prepared(store);val claim=reserve(store,binding)
        assertTrue(start(store,claim))
        val repository=ScopedExecutionRepository(store,claim,ReviewTestClock(at))
        assertNotNull(repository.claimConnectedReview(binding.executionId))
        HistoryStore(path).use { reopened ->
            assertEquals(PersonalRuntimePhase.UNKNOWN,reopened.personalRuntimeLease(claim.claimId)?.phase)
            assertNotEquals(claim.ownerEpoch,reopened.personalOwnerEpoch())
            assertEquals("INTERRUPTED",ScopedExecutionService(reopened).inspect(binding.executionId).state)
            assertFalse(reopened.authorizePersonalAttempt(claim,1,PersonalResourceObservation(resource,at,at.plusSeconds(60),true),ReviewTestClock(at)))
            val factory=Factory(next);val control=Control();val authority=admission(reopened,control)
            assertEquals("REFUSED",authority.execute(next.executionId,factory).state);assertEquals(0,factory.constructions)
            authority.cleanup(claim)
            assertEquals(PersonalRuntimePhase.STOP_CONFIRMED,reopened.personalRuntimeLease(claim.claimId)?.phase)
            assertEquals("PUBLISHED",authority.execute(next.executionId,factory).state)
        }
    } }

    @Test fun `discard retains unresolved ownership with no source text`()=fixture { _,store -> runBlocking {
        val binding=prepared(store);val next=prepared(store);val claim=reserve(store,binding)
        assertTrue(start(store,claim))
        store.discardScopedExecution(binding.executionId)
        val lease=assertNotNull(store.personalRuntimeLease(claim.claimId))
        assertFalse(lease.publicationAllowed);assertEquals(PersonalRuntimePhase.STOPPING,lease.phase)
        val control=Control().also { it.stopConfirmed=false };val factory=Factory(next)
        assertEquals("REFUSED",admission(store,control).execute(next.executionId,factory).state)
        assertEquals(0,factory.constructions)
        assertFalse(ScopedEvidencePreparation.json.encodeToString(PersonalReviewClaim.serializer(),claim).contains("Synthetic original note"))
    } }

    @Test fun `restart discovers deleted execution occupancy without retained claim identities`()=fixture { path,store -> runBlocking {
        fun beforeRestart(): ScopedExecutionBinding {
            val deleted=prepared(store);val next=prepared(store)
            val claim=reserve(store,deleted);assertTrue(start(store,claim))
            store.discardScopedExecution(deleted.executionId)
            return next
        }
        val next=beforeRestart()
        store.close()
        HistoryStore(path).use { reopened ->
            val before=reopened.captureScopedExecution(next.executionId).source.totalChanges
            val discovered=reopened.unresolvedPersonalReviews().single()
            assertEquals(before,reopened.captureScopedExecution(next.executionId).source.totalChanges)
            assertEquals(PersonalRuntimePhase.UNKNOWN,discovered.phase)
            assertFailsWith<ConnectedReviewNotFound> { reopened.captureScopedExecution(discovered.claim.executionId) }
            val authority=admission(reopened,Control());val factory=Factory(next)
            assertEquals("REFUSED",authority.execute(next.executionId,factory).state);assertEquals(0,factory.constructions)
            authority.cleanup(discovered.claim)
            assertTrue(reopened.unresolvedPersonalReviews().isEmpty())
            assertEquals("PUBLISHED",authority.execute(next.executionId,factory).state)
        }
    } }

    @Test fun `shared context purge revokes but cannot cascade away runtime ownership`()=fixture { _,store -> runBlocking {
        val binding=prepared(store)
        val source=store.intakeHistory(requireNotNull(binding.source.intakeId)).single()
        val context=store.createContext(AthleteContextRequest("note","user_report","synthetic",source.observedOn,content=source.content))
        store.associateIntake(source.intakeId,ContextIntakeAssociation(1,context.contextId,context.revision))
        val claim=reserve(store,binding);assertTrue(start(store,claim))
        val preview=store.previewContextDeletion(context.contextId,at)
        assertTrue(binding.executionId in preview.executionIds)
        store.redactAndDeleteContext(context.contextId,ContextDeletionRequest(context.revision,preview.digest,
            emptyList(),emptyList(),emptyList()),at)
        assertFailsWith<ConnectedReviewNotFound> { store.captureScopedExecution(binding.executionId) }
        val lease=store.unresolvedPersonalReviews().single()
        assertEquals(claim,lease.claim);assertEquals(PersonalRuntimePhase.STOPPING,lease.phase);assertFalse(lease.publicationAllowed)
        admission(store,Control()).cleanup(lease.claim)
        assertTrue(store.unresolvedPersonalReviews().isEmpty())
    } }

    @Test fun `publication cancellation and deadline checks share final owner transaction`()=fixture { _,store ->
        val binding=prepared(store);val claim=reserve(store,binding);val clock=ReviewTestClock(at)
        assertTrue(start(store,claim))
        val repo=ScopedExecutionRepository(store,claim,clock)
        assertNotNull(repo.claimConnectedReview(binding.executionId))
        assertTrue(store.authorizePersonalAttempt(claim,1,PersonalResourceObservation(resource,at,at.plusSeconds(600),true),clock))
        val output=(restoredScopedInvocation(binding).validate(final(binding)) as ConnectedReviewValidation.Accepted).output
        val publish=repo.prepareConnectedReviewPublication(binding.executionId,output,binding.dependencyBindingSha256,
            binding.context,binding.providerSelection.storageId,binding.providerSelection.modelId,binding.profile)
        // No intervening database write: final owner clock alone must reject expired publication.
        clock.now=at.plusSeconds(600)
        assertFailsWith<ConnectedReviewBindingConflict> { publish() }
        clock.now=at
        val second=repo.prepareConnectedReviewPublication(binding.executionId,output,binding.dependencyBindingSha256,
            binding.context,binding.providerSelection.storageId,binding.providerSelection.modelId,binding.profile)
        store.revokePersonalReview(claim)
        assertFailsWith<ConnectedReviewBindingConflict> { second() }
        assertNull(ScopedExecutionService(store).inspect(binding.executionId).validatedOutputJson)
    }

    @Test fun `hosted consent is exact and revocation during validation cannot publish`()=fixture { _,store -> runBlocking {
        val hosted=selection.copy(providerKind="hosted");val binding=prepared(store,hosted)
        val control=Control().also { it.observation=PersonalResourceObservation(resource.copy(selection=hosted),at,at.plusSeconds(600),true) }
        val consent=ConnectedReviewConsentLedger();val authority=admission(store,control,consent)
        val factory=Factory(binding) { consent.revoke(binding.executionId);final(binding) }
        assertEquals("consent_required",authority.execute(binding.executionId,factory).reason);assertEquals(0,factory.constructions)
        consent.grant(binding.executionId,hosted,binding.consentSha256)
        assertNotEquals("PUBLISHED",authority.execute(binding.executionId,factory).state)
        assertEquals(1,factory.calls);assertNull(ScopedExecutionService(store).inspect(binding.executionId).validatedOutputJson)
    } }

    @Test fun `per send observation cannot bypass revoked consent superseded binding or attempt time`()=fixture { _,store -> runBlocking {
        val hosted=selection.copy(providerKind="hosted");val binding=prepared(store,hosted)
        val consent=ConnectedReviewConsentLedger().also { it.grant(binding.executionId,hosted,binding.consentSha256) }
        val control=Control().also { c ->
            c.observation=PersonalResourceObservation(resource.copy(selection=hosted),at,at.plusSeconds(600),true)
            c.onObserve={ if(it==2) consent.revoke(binding.executionId) }
        }
        val factory=Factory(binding)
        assertNotEquals("PUBLISHED",admission(store,control,consent).execute(binding.executionId,factory).state)
        assertEquals(0,factory.calls)

        val superseded=prepared(store);val changedControl=Control().also { c -> c.onObserve={ if(it==2)
            ScopedExecutionService(store,ReviewTestClock(at)).prepare(superseded.profile,superseded.source,selection) } }
        val staleFactory=Factory(superseded)
        assertNotEquals("PUBLISHED",admission(store,changedControl).execute(superseded.executionId,staleFactory).state)
        assertEquals(0,staleFactory.calls)

        val timed=prepared(store);var nano=0L
        val timedControl=Control().also { c -> c.onObserve={ if(it==2) nano=301_000_000_000L } }
        val timedFactory=Factory(timed)
        val authority=PersonalReviewRuntimeAdmission(store,timedControl,PersonalProfileQualification { null },limits=personal,
            clock=ReviewTestClock(at),nanos={ nano })
        val result=authority.execute(timed.executionId,timedFactory)
        assertEquals("attempt_timeout",result.reason);assertEquals(0,timedFactory.calls)
    } }

    @Test fun `reservation start and attempt sample clocks after acquiring the owner monitor`()=fixture { _,store ->
        val binding=prepared(store);val clock=ReviewTestClock(at)
        val observation=PersonalResourceObservation(resource,at,at.plusSeconds(10),true)
        val capture=store.captureScopedExecution(binding.executionId)
        val epoch=store.personalOwnerEpoch()
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        fun <T> delayed(operation: ()->T): T {
            val waiting=java.util.concurrent.CountDownLatch(1)
            val call=synchronized(store) {
                val future=pool.submit<T> { waiting.countDown();operation() }
                assertTrue(waiting.await(5,java.util.concurrent.TimeUnit.SECONDS))
                clock.now=at.plusSeconds(11)
                future
            }
            return call.get(5,java.util.concurrent.TimeUnit.SECONDS)
        }
        try {
            assertNull(delayed { store.reservePersonalReview(capture,true,epoch,observation,resource,clock,600_000) })
            clock.now=at
            val claim=reserve(store,binding)
            assertFalse(delayed { store.beginPersonalStart(claim,observation,clock) })
            clock.now=at
            assertTrue(store.beginPersonalStart(claim,observation,clock))
            assertNotNull(ScopedExecutionRepository(store,claim,clock).claimConnectedReview(binding.executionId))
            assertFalse(delayed { store.authorizePersonalAttempt(claim,1,observation,clock) })
            assertEquals(0,store.personalRuntimeLease(claim.claimId)?.attempts)
        } finally { pool.shutdownNow() }
    }

    @Test fun `owned scoped repository cannot use ordinary or Development execution policy`()=fixture { _,store -> runBlocking {
        val binding=prepared(store);val claim=reserve(store,binding);assertTrue(start(store,claim))
        val factory=Factory(binding);val provider=factory.create(claim)
        val repository=ScopedExecutionRepository(store,claim,ReviewTestClock(at))
        for(policy in listOf(ConnectedReviewExecutionPolicy.ORDINARY,ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL)) {
            val result=ConnectedReviewExecutor(repository,ConnectedReviewExecutionGate { _,_->null },
                ConnectedReviewExecutionLimits(policy=policy)).executeManually(binding.executionId,selection,provider)
            assertEquals("personal_policy_required",result.reason)
        }
        assertEquals(0,factory.calls);assertEquals("IN_FLIGHT",ScopedExecutionService(store).inspect(binding.executionId).state)
        assertEquals(0,store.personalRuntimeLease(claim.claimId)?.attempts)
    } }

    @Test fun `blocked construction can be cancelled and stopped independently before returning`()=fixture { _,store -> runBlocking {
        val binding=prepared(store);val control=Control();val authority=admission(store,control)
        val entered=CompletableDeferred<PersonalReviewClaim>();val release=java.util.concurrent.CountDownLatch(1)
        val factory=Factory(binding).also { f -> f.onCreate={ claim -> entered.complete(claim);check(release.await(5,java.util.concurrent.TimeUnit.SECONDS)) } }
        val running=async(Dispatchers.Default) { authority.execute(binding.executionId,factory) }
        try {
            val claim=withTimeout(5000) { entered.await() }
            authority.cancel(claim);authority.cleanup(claim)
            assertEquals(PersonalRuntimePhase.STOP_CONFIRMED,store.personalRuntimeLease(claim.claimId)?.phase)
            release.countDown()
            assertNotEquals("PUBLISHED",running.await().state);assertEquals(0,factory.calls)
        } finally { release.countDown() }
    } }

    @Test fun `personal ceilings cannot inherit ordinary extended or development limits`()=fixture { _,store ->
        assertFails { personal.copy(attemptTimeoutMillis=300_001) }
        assertFails { personal.copy(totalTimeoutMillis=600_001) }
        assertFails { personal.copy(maxAttempts=3) }
        assertFails { personal.copy(maxPacketBytes=32*1024+1) }
        assertFails { personal.copy(maxResponseBytes=12*1024+1) }
        assertFails { personal.copy(maxHostedCostPerAttemptUsd=BigDecimal("0.26")) }
        assertFails { personal.copy(maxHostedCostTotalUsd=BigDecimal("0.51")) }
        for(policy in listOf(ConnectedReviewExecutionPolicy.ORDINARY,ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL))
            assertFails { PersonalReviewRuntimeAdmission(store,Control(),limits=ConnectedReviewExecutionLimits(policy=policy)) }
        assertTrue(ScopedProfileAdmission.inspectAll().all { !it.executionAllowed && it.qualificationState=="unqualified" })
    }
}
