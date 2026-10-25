package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ScopedExecutionSource(val snapshotId: String? = null,val intakeId: String? = null,val intakeRevision: Int? = null) {
    init {
        require((snapshotId!=null)!=(intakeId!=null))
        require((intakeId==null)==(intakeRevision==null))
        snapshotId?.let(::validateContextId);intakeId?.let(::validateContextId)
        intakeRevision?.let { require(it in 1..1000) }
    }
}

/** Separate provider-specific execution identity; an inert evidence snapshot is never execution consent. */
@Serializable
data class ScopedExecutionBinding(val executionId: String,val requestId: String,val generation: Long,val profile: String,
    val source: ScopedExecutionSource,val providerSelection: ConnectedReviewProviderSelection,
    val exactPrompt: InterpretationPromptV1,val exactCorrectionPrompt: InterpretationPromptV1,val consentSha256: String,
    val dependencyBindingSha256: String,val candidateSetSha256: String?,val context: List<ContextRevisionRefV1>,
    val coverageFrom: String,val coverageUntil: String,val sport: String?,val createdUtc: String,
    val sourceValidUntilUtc: String? = null) {
    init {
        validateContextId(executionId)
        require(profile in ScopedProfileAdmission.profiles && generation>0)
        require(requestId==scopedExecutionRequestId(profile,source.snapshotId ?: requireNotNull(source.intakeId)))
        require((profile==ContextIntakeSuggestionContractV1.PROFILE)==(source.intakeId!=null))
        require((profile==AmendmentSelectionContractV1.PROFILE)==(candidateSetSha256!=null))
        listOfNotNull(consentSha256,dependencyBindingSha256,candidateSetSha256).forEach { require(it.matches(Regex("[a-f0-9]{64}"))) }
        validateContextDate(coverageFrom);validateContextDate(coverageUntil);require(coverageFrom<=coverageUntil)
        java.time.Instant.parse(createdUtc)
        sourceValidUntilUtc?.let { require(java.time.Instant.parse(it)>java.time.Instant.parse(createdUtc)) }
        require(context.distinct().size==context.size)
        context.forEach { validateContextId(it.contextId);require(it.revision in 1..1000) }
    }
    override fun toString()="ScopedExecutionBinding([REDACTED])"
}

internal fun scopedExecutionRequestId(profile: String,sourceId: String)="scoped-execution:$profile:$sourceId"

@Serializable
data class ScopedExecutionInspection(val binding: ScopedExecutionBinding,val state: String,val staleReason: String?,
    val validatedOutputJson: String?,val admission: ScopedProfileAdmissionStatus,
    val validatedOutputSha256: String?,val manualRequestAdmission: OwnerUatRequestAdmission,
    val limitation: String="inspection_is_not_execution_consent_confirmation_or_plan_approval") {
    override fun toString()="ScopedExecutionInspection([REDACTED])"
}

@Serializable
data class ScopedExecutionSummary(val executionId: String,val source: ScopedExecutionSource,val profile: String,
    val providerSelection: ConnectedReviewProviderSelection,val coverageFrom: String,val coverageUntil: String,
    val createdUtc: String,val state: String,val staleReason: String?,val outputSha256: String?)

@Serializable
data class ScopedExecutionHistory(val items: List<ScopedExecutionSummary>,val nextOffset: Int?,
    val limitation: String="stored_outcomes_open_result_to_recheck_source_currentness")

@Serializable
internal data class ScopedExecutionFeedbackRequest(val expectedOutputSha256: String,val feedback: ReviewFeedbackRequest) {
    init { require(expectedOutputSha256.matches(Regex("[a-f0-9]{64}"))) }
    override fun toString()="ScopedExecutionFeedbackRequest([REDACTED])"
}

internal class ScopedExecutionFeedbackConflict: IllegalStateException("Review has linked feedback")

@Serializable
internal data class SavedIntakeSuggestions(val intakeId: String,val intakeRevision: Int,val status: String,
    val executionId: String? = null,val draft: ContextIntakeSuggestionDraftV1? = null) {
    override fun toString()="SavedIntakeSuggestions([REDACTED])"
}

internal data class StoredScopedExecution(val binding: ScopedExecutionBinding,val state: String,val staleReason: String?,val output: String?) {
    fun status()=ConnectedReviewStatus(binding.executionId,binding.requestId,binding.generation,binding.dependencyBindingSha256,
        binding.consentSha256,binding.context,binding.coverageFrom,binding.coverageUntil,binding.sport,
        binding.providerSelection.storageId,binding.providerSelection.modelId,binding.profile,state,staleReason,output,binding.createdUtc)
    override fun toString()="StoredScopedExecution([REDACTED])"
}

internal data class CapturedExecutionSource(val source: ScopedExecutionSource,val scoped: CapturedScopedEvidence?,
    val snapshot: StoredScopedEvidence?,val intake: ContextIntake?,val generation: Long,val totalChanges: Long) {
    val validUntil: java.time.Instant? get()=scoped?.adaptationReference?.stored?.validUntil
    fun currentDigest(): String? {
        return if(source.intakeId!=null) intake?.let { AnalysisClaims.hash(ScopedEvidencePreparation.json.encodeToString(it)) } else {
            val captured=scoped ?: return null
            val reference=captured.adaptationReference
            if(reference!=null) {
                reference.requireCurrent()
                if(snapshot?.snapshot?.snapshotId==reference.stored.snapshot.snapshotId) return PreparedAdaptationResult.prepare(CapturedAdaptationResult(reference.current,
                    captured.copy(adaptationReference=null),snapshot.request,null)).evidence.bindingSha256
            }
            val report=ScopedTrends.report(captured.read.scope,captured.read.history,captured.read.statuses)
            ScopedEvidencePreparation.binding(ScopedEvidencePreparation.packet(requireNotNull(snapshot).request,captured,report),captured.read)
        }
    }
    override fun toString()="CapturedExecutionSource([REDACTED])"
}

internal const val MAX_SCOPED_EXECUTION_STORAGE_BYTES=192*1024
internal class PreparedScopedExecution private constructor(val binding: ScopedExecutionBinding,val bindingJson: String,val totalChanges: Long,
    val adaptationCapture: CapturedSignalAdaptation?) {
    override fun toString()="PreparedScopedExecution([REDACTED])"
    companion object {
        fun prepare(binding: ScopedExecutionBinding,capture: CapturedExecutionSource): PreparedScopedExecution {
            val json=ScopedEvidencePreparation.json
            require(binding.source==capture.source && binding.generation==Math.addExact(capture.generation,1))
            require(binding.dependencyBindingSha256==capture.currentDigest())
            require(binding.sourceValidUntilUtc==capture.validUntil?.toString())
            for(prompt in listOf(binding.exactPrompt,binding.exactCorrectionPrompt)) require(json.encodeToString(prompt).toByteArray(Charsets.UTF_8).size<=32*1024)
            restoredScopedInvocation(binding)
            when(binding.profile) {
                ContextIntakeSuggestionContractV1.PROFILE -> {
                    val packet=json.decodeFromString<ContextIntakeSuggestionPacketV1>(binding.exactPrompt.userPacketJson)
                    require(packet.intake==capture.intake && packet.intake.intakeId==binding.source.intakeId && packet.intake.revision==binding.source.intakeRevision)
                    require(binding.context.isEmpty() && binding.coverageFrom==packet.intake.observedOn && binding.coverageUntil==packet.intake.observedOn && binding.sport==null)
                }
                else -> {
                    val snapshot=requireNotNull(capture.snapshot)
                    require(snapshot.bindingSha256==binding.dependencyBindingSha256 && snapshot.snapshot.state=="PREPARED")
                    val evidence=if(binding.profile==ScopedDescriptiveContractV1.PROFILE)
                        json.decodeFromString<ScopedDescriptivePacketV1>(binding.exactPrompt.userPacketJson).evidence
                    else json.decodeFromString<AmendmentSelectionPacketV1>(binding.exactPrompt.userPacketJson).also {
                        require(it.sourceSnapshotId==binding.source.snapshotId && it.dependencyBindingSha256==binding.dependencyBindingSha256 && it.candidateSetSha256==binding.candidateSetSha256)
                    }.evidence.evidence
                    require(evidence==snapshot.snapshot.packet)
                    val scope=snapshot.snapshot.manifest.scope
                    require(binding.coverageFrom==(scope.evidenceRange?.oldest ?: scope.activityDates.minOrNull()) &&
                        binding.coverageUntil==(scope.evidenceRange?.newest ?: scope.activityDates.maxOrNull()) && binding.sport==scope.request.sport)
                    val refs=snapshot.snapshot.manifest.dependencies.filter { it.kind=="context" }.map { ContextRevisionRefV1(it.identity,it.revision) }.toSet()
                    require(binding.context.toSet()==refs)
                }
            }
            val encoded=json.encodeToString(binding)
            require(encoded.toByteArray(Charsets.UTF_8).size<=MAX_SCOPED_EXECUTION_STORAGE_BYTES)
            return PreparedScopedExecution(binding,json.encodeToString(json.decodeFromString<ScopedExecutionBinding>(encoded)),capture.totalChanges,capture.scoped?.adaptationReference?.current)
        }
    }
}
internal data class CapturedExecutionCheck(val stored: StoredScopedExecution,val source: CapturedExecutionSource) {
    fun current(): Boolean = try {
        source.generation==stored.binding.generation && source.currentDigest()==stored.binding.dependencyBindingSha256 &&
            (source.source.intakeRevision==null || source.intake?.revision==source.source.intakeRevision)
    } catch(_: IllegalArgumentException) { false }
      catch(_: TrendSizeLimit) { false }
      catch(_: CommitmentProjectionLimit) { false }
      catch(_: CommitmentTimeUncertain) { false }
    override fun toString()="CapturedExecutionCheck([REDACTED])"
}

internal fun restoredScopedInvocation(binding: ScopedExecutionBinding): ConnectedReviewInvocation {
    val json=Json { encodeDefaults=true }
    val invocation=when(binding.profile) {
        ScopedDescriptiveContractV1.PROFILE -> ConnectedReviewInvocation.descriptive(json.decodeFromString<ScopedDescriptivePacketV1>(binding.exactPrompt.userPacketJson))
        ContextIntakeSuggestionContractV1.PROFILE -> ConnectedReviewInvocation.intake(json.decodeFromString<ContextIntakeSuggestionPacketV1>(binding.exactPrompt.userPacketJson))
        AmendmentSelectionContractV1.PROFILE -> ConnectedReviewInvocation.amendment(json.decodeFromString<AmendmentSelectionPacketV1>(binding.exactPrompt.userPacketJson))
        else -> throw IllegalArgumentException("Unsupported execution profile")
    }
    require(invocation.exactPrompt==binding.exactPrompt && invocation.exactCorrectionPrompt==binding.exactCorrectionPrompt && invocation.consentDigest==binding.consentSha256)
    return invocation
}
