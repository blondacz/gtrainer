package com.gtrainer

import java.time.Clock
import java.util.UUID

/** Local preparation/inspection only. There is no runtime selection, provider construction or inference here. */
internal class ScopedExecutionService(private val store: HistoryStore,private val clock: Clock=Clock.systemUTC()) {
    fun prepare(profile: String,source: ScopedExecutionSource,selection: ConnectedReviewProviderSelection,
        dateResolution: ContextIntakeDateResolutionV1? = null): ScopedExecutionInspection {
        return inspection(store.saveScopedExecution(assemble(profile,source,selection,dateResolution)))
    }

    /** Capture briefly under the owner; exact prompt/validation/serialization occur outside its monitor. */
    internal fun assemble(profile: String,source: ScopedExecutionSource,selection: ConnectedReviewProviderSelection,
        dateResolution: ContextIntakeDateResolutionV1? = null): PreparedScopedExecution {
        require(profile in ScopedProfileAdmission.profiles)
        require(profile!=AmendmentSelectionContractV1.PROFILE) { "Amendment inference preparation remains closed; use factual candidate and draft inspection" }
        require((profile==ContextIntakeSuggestionContractV1.PROFILE)==(source.intakeId!=null))
        require(source.intakeId!=null || dateResolution==null)
        val captured=store.captureScopedExecutionSource(profile,source)
        val dependencyDigest=requireNotNull(captured.currentDigest())
        val snapshot=captured.snapshot?.snapshot
        if(snapshot!=null && (snapshot.state!="PREPARED" || requireNotNull(captured.snapshot).bindingSha256!=dependencyDigest)) throw ScopedEvidenceConflict()
        val intake=captured.intake
        if(intake!=null && intake.revision!=source.intakeRevision) throw ContextIntakeConflict()
        val invocation=if(profile==ContextIntakeSuggestionContractV1.PROFILE)
            ConnectedReviewInvocation.intake(ContextIntakeSuggestionContractV1.packet(requireNotNull(intake),dateResolution))
        else ConnectedReviewInvocation.descriptive(ScopedDescriptiveContractV1.packet(requireNotNull(snapshot?.packet)))
        val scope=snapshot?.manifest?.scope
        val dates=scope?.activityDates.orEmpty()
        val from=scope?.evidenceRange?.oldest ?: dates.minOrNull() ?: requireNotNull(intake).observedOn
        val until=scope?.evidenceRange?.newest ?: dates.maxOrNull() ?: requireNotNull(intake).observedOn
        val refs=snapshot?.manifest?.dependencies.orEmpty().filter { it.kind=="context" }
            .map { ContextRevisionRefV1(it.identity,it.revision) }.distinct().sortedWith(compareBy({it.contextId},{it.revision}))
        val binding=ScopedExecutionBinding(UUID.randomUUID().toString(),scopedExecutionRequestId(profile,source.snapshotId ?: requireNotNull(source.intakeId)),
            Math.addExact(captured.generation,1),profile,source,selection,invocation.exactPrompt,requireNotNull(invocation.exactCorrectionPrompt),
            invocation.consentDigest,dependencyDigest,null,refs,from,until,scope?.request?.sport,clock.instant().toString(),captured.validUntil?.toString())
        return PreparedScopedExecution.prepare(binding,captured)
    }

    fun inspect(id: String): ScopedExecutionInspection {
        val capture=store.captureScopedExecution(id)
        val current=capture.current()
        // Validate persisted bytes before the final existence/write fence, not after returning private content.
        restoredScopedInvocation(capture.stored.binding)
        return inspection(store.finishScopedExecutionCheck(capture,current))
    }
    private fun inspection(stored: StoredScopedExecution): ScopedExecutionInspection {
        val admission=ScopedProfileAdmission.inspect(stored.binding.profile).copy(selectedProviderModel=stored.binding.providerSelection,
            blockingReasons=ScopedProfileAdmission.inspect(stored.binding.profile).blockingReasons.filter { it!="provider_model_not_selected" })
        return ScopedExecutionInspection(stored.binding,stored.state,stored.staleReason,stored.output,admission)
    }
    fun discard(id: String)=store.discardScopedExecution(id)
    fun savedSuggestions(intakeId: String,expectedRevision: Int): SavedIntakeSuggestions {
        val unavailable=SavedIntakeSuggestions(intakeId,expectedRevision,"unavailable")
        val id=store.savedIntakeSuggestionExecutionId(intakeId,expectedRevision) ?: return unavailable
        val capture=store.captureScopedExecution(id)
        val current=capture.current()
        val binding=capture.stored.binding
        require(binding.profile==ContextIntakeSuggestionContractV1.PROFILE && binding.source.intakeId==intakeId && binding.source.intakeRevision==expectedRevision)
        val raw=capture.stored.output
        val draft=if(current && capture.stored.state=="PUBLISHED" && raw!=null) {
            val packet=ScopedEvidencePreparation.json.decodeFromString<ContextIntakeSuggestionPacketV1>(binding.exactPrompt.userPacketJson)
            restoredScopedInvocation(binding)
            ContextIntakeSuggestionContractV1.decodeDraft(raw,packet)
        } else null
        val final=store.finishScopedExecutionCheck(capture,current)
        return if(final.state=="PUBLISHED" && draft!=null) SavedIntakeSuggestions(intakeId,expectedRevision,"available",id,draft) else unavailable
    }
}

/** Existing executor lifecycle adapter. It never adapts a new packet into the frozen V1 decoder. */
internal class ScopedExecutionRepository(private val store: HistoryStore,private val claim: PersonalReviewClaim? = null,
    private val clock: Clock=Clock.systemUTC()): ConnectedReviewExecutionRepository {
    private fun <T> checked(id: String,operation: (CapturedExecutionCheck)->T): T {
        val capture=store.captureScopedExecution(id)
        return try { operation(capture) } catch(_: ScopedEvidenceConflict) { throw ConnectedReviewBindingConflict(capture.stored.binding.consentSha256) }
    }
    override fun connectedReviewStatus(snapshotId: String): ConnectedReviewStatus {
        return checked(snapshotId) { capture -> store.finishScopedExecutionCheck(capture,capture.current()).status() }
    }
    override fun connectedReviewInvocation(snapshotId: String): ConnectedReviewInvocation {
        return checked(snapshotId) { capture ->
            val current=capture.current()
            val invocation=restoredScopedInvocation(capture.stored.binding)
            val stored=store.finishScopedExecutionCheck(capture,current)
            if(stored.state !in setOf("IN_FLIGHT","RUNNING")) throw ConnectedReviewBindingConflict(stored.binding.consentSha256)
            invocation
        }
    }
    override fun connectedReviewPacket(snapshotId: String): InterpretationPacketV1 =
        throw UnsupportedOperationException("New profiles do not use the legacy packet decoder")
    override fun connectedReviewInspection(snapshotId: String): ConnectedReviewInspection =
        throw UnsupportedOperationException("Use the separate new-profile execution inspection")
    override fun claimConnectedReview(snapshotId: String): ConnectedReviewStatus? {
        val owned=claim ?: return null
        return checked(snapshotId) { capture -> store.claimPersonalReview(owned,capture,capture.current(),clock.instant())?.status() }
    }
    override fun publishConnectedReview(snapshotId: String,output: ValidatedConnectedReviewOutput,currentEvidenceDigest: String,
        currentContext: List<ContextRevisionRefV1>,provider: String,model: String,contractVersion: String): ConnectedReviewStatus {
        return prepareConnectedReviewPublication(snapshotId,output,currentEvidenceDigest,currentContext,provider,model,contractVersion)()
    }
    override fun prepareConnectedReviewPublication(snapshotId: String,output: ValidatedConnectedReviewOutput,currentEvidenceDigest: String,
        currentContext: List<ContextRevisionRefV1>,provider: String,model: String,contractVersion: String): ()->ConnectedReviewStatus {
        val capture=store.captureScopedExecution(snapshotId)
        val owned=claim ?: throw PersonalRuntimeConflict()
        val binding=capture.stored.binding
        val current=capture.current() && binding.dependencyBindingSha256==currentEvidenceDigest && binding.context==currentContext &&
            binding.providerSelection.storageId==provider && binding.providerSelection.modelId==model && binding.profile==contractVersion
        val validated=restoredScopedInvocation(binding).validate(output.text)
        require(validated is ConnectedReviewValidation.Accepted && validated.output.text==output.text) { "Invalid execution output" }
        return {
            try { store.publishPersonalReview(owned,capture,current,output,clock).status() }
            catch(_: ScopedEvidenceConflict) { throw ConnectedReviewBindingConflict(binding.consentSha256) }
            catch(_: PersonalRuntimeConflict) { throw ConnectedReviewBindingConflict(binding.consentSha256) }
        }
    }
    override fun failConnectedReview(snapshotId: String,reason: String): ConnectedReviewStatus {
        claim?.let { store.revokePersonalReview(it,reason) }
        return store.failScopedExecution(snapshotId,reason).status()
    }
}
