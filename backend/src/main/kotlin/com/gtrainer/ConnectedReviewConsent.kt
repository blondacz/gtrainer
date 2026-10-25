package com.gtrainer

import kotlinx.serialization.Serializable

@Serializable
data class ConnectedReviewPreview(
    val snapshotId: String,
    val providerSelection: ConnectedReviewProviderSelection,
    val packetDigest: String,
    val exactPrompt: InterpretationPromptV1,
    val exactCorrectionPrompt: InterpretationPromptV1? = null,
) {
    override fun toString() = "ConnectedReviewPreview([REDACTED])"
}

@Serializable
data class ConnectedReviewConsentRequest(
    val providerSelection: ConnectedReviewProviderSelection,
    val packetDigest: String,
    val approved: Boolean,
) {
    init { require(packetDigest.matches(Regex("[a-f0-9]{64}"))) }
    override fun toString() = "ConnectedReviewConsentRequest([REDACTED])"
}

@Serializable
data class ConnectedReviewRunRequest(val providerSelection: ConnectedReviewProviderSelection) {
    override fun toString() = "ConnectedReviewRunRequest([REDACTED])"
}

@Serializable
data class ConnectedReviewConsentReceipt(val snapshotId: String, val approved: Boolean,
                                         val providerSelection: ConnectedReviewProviderSelection, val packetDigest: String)

/** Ephemeral, one-snapshot permission. Restarting the process requires hosted consent again. */
class ConnectedReviewConsentLedger {
    private data class Consent(val selection: ConnectedReviewProviderSelection, val packetDigest: String)
    private val consents = LinkedHashMap<String, Consent>()

    @Synchronized fun grant(snapshotId: String, selection: ConnectedReviewProviderSelection, packetDigest: String) {
        validateContextId(snapshotId)
        require(selection.providerKind == "hosted") { "Hosted consent only applies to hosted providers" }
        require(packetDigest.matches(Regex("[a-f0-9]{64}")))
        require(snapshotId in consents || consents.size < 128) { "Hosted consent capacity reached" }
        consents[snapshotId] = Consent(selection, packetDigest)
    }

    @Synchronized fun permits(snapshotId: String, selection: ConnectedReviewProviderSelection, packetDigest: String): Boolean =
        selection.providerKind == "hosted" && consents[snapshotId] == Consent(selection, packetDigest)

    @Synchronized fun revoke(snapshotId: String): Boolean = consents.remove(snapshotId) != null

    /** Synchronous publication and revocation have one linearization boundary; no lock spans inference. */
    @Synchronized internal fun <T> publishIfPermitted(snapshotId: String,selection: ConnectedReviewProviderSelection,
        packetDigest: String,publish: ()->T): T? = if(permits(snapshotId,selection,packetDigest)) publish() else null
}

/** Composes qualification/health and packet-scoped consent before every hosted send. */
class ConnectedReviewService(
    private val repository: ConnectedReviewExecutionRepository,
    private val catalog: ConnectedReviewProviderCatalog,
    private val consent: ConnectedReviewConsentLedger = ConnectedReviewConsentLedger(),
    private val qualificationAndHealth: ConnectedReviewExecutionGate =
        ConnectedReviewExecutionGate { _, _ -> "qualification_gate_disabled" },
    private val limits: ConnectedReviewExecutionLimits = ConnectedReviewExecutionLimits(),
    private val monotonicNanos: ()->Long=System::nanoTime,
    private val durableClaimOriginNanos: Long?=null,
    private val personalAttemptAuthority: (suspend (Int)->Boolean)?=null,
) {
    init {
        require(limits.policy == ConnectedReviewExecutionPolicy.ORDINARY ||
            (limits.policy in setOf(ConnectedReviewExecutionPolicy.PERSONAL,ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT) && durableClaimOriginNanos!=null && personalAttemptAuthority!=null)) {
            "Connected-review service requires ordinary or explicitly owned personal execution policy"
        }
    }

    private fun storedSelection(snapshot: ConnectedReviewStatus): ConnectedReviewProviderSelection {
        val kind = snapshot.provider.substringBefore(':')
        val providerId = snapshot.provider.substringAfter(':', "")
        return ConnectedReviewProviderSelection(providerId, snapshot.model, kind)
    }

    fun providerOptions(): List<ConnectedReviewProviderSelection> = catalog.options()

    fun inspect(snapshotId: String): ConnectedReviewInspection = repository.connectedReviewInspection(snapshotId)

    fun preview(snapshotId: String): ConnectedReviewPreview {
        val snapshot = repository.connectedReviewStatus(snapshotId)
        val selection = storedSelection(snapshot)
        require(catalog.resolve(selection) != null) { "Selected provider is unavailable" }
        val invocation = repository.connectedReviewInvocation(snapshotId)
        require(invocation.profile==snapshot.contractVersion && invocation.consentDigest == snapshot.packetDigest) { "Review packet changed" }
        return ConnectedReviewPreview(snapshotId, selection, snapshot.packetDigest, invocation.exactPrompt,invocation.exactCorrectionPrompt)
    }

    fun consent(snapshotId: String, request: ConnectedReviewConsentRequest): Boolean {
        val exact = preview(snapshotId)
        require(request.providerSelection == exact.providerSelection && request.packetDigest == exact.packetDigest) {
            "Consent must match the exact provider and packet preview"
        }
        require(request.providerSelection.providerKind == "hosted") { "Local providers do not use hosted consent" }
        if (!request.approved) {
            consent.revoke(snapshotId)
            return false
        }
        consent.grant(snapshotId, request.providerSelection, request.packetDigest)
        return true
    }

    fun revokeConsent(snapshotId: String): Boolean = consent.revoke(snapshotId)

    suspend fun executeManually(snapshotId: String, selection: ConnectedReviewProviderSelection): ConnectedReviewExecutionResult {
        val gate = ConnectedReviewExecutionGate { snapshot, provider ->
            qualificationAndHealth.refusalReason(snapshot, provider)?.let { return@ConnectedReviewExecutionGate it }
            if (provider.hosted) {
                val exactSelection = storedSelection(snapshot)
                if (!consent.permits(snapshot.snapshotId, exactSelection, snapshot.packetDigest)) {
                    if(snapshot.state=="RUNNING") "consent_revoked" else "consent_required"
                } else null
            } else null
        }
        val fenced=object : ConnectedReviewExecutionRepository by repository {
            override fun prepareConnectedReviewPublication(snapshotId: String,output: ValidatedConnectedReviewOutput,currentEvidenceDigest: String,
                currentContext: List<ContextRevisionRefV1>,provider: String,model: String,contractVersion: String): ()->ConnectedReviewStatus {
                val publish=repository.prepareConnectedReviewPublication(snapshotId,output,currentEvidenceDigest,currentContext,provider,model,contractVersion)
                if(selection.providerKind!="hosted") return publish
                val current=repository.connectedReviewStatus(snapshotId)
                return { consent.publishIfPermitted(snapshotId,selection,current.packetDigest,publish)
                    ?: repository.failConnectedReview(snapshotId,"consent_revoked")
                }
            }
        }
        return ConnectedReviewExecutor(fenced,gate,limits,monotonicNanos,durableClaimOriginNanos,
            personalAttemptAuthority=personalAttemptAuthority).executeManually(snapshotId, selection, catalog)
    }
}
