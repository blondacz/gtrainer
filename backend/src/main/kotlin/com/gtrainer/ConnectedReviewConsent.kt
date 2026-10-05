package com.gtrainer

import kotlinx.serialization.Serializable

@Serializable
data class ConnectedReviewPreview(
    val snapshotId: String,
    val providerSelection: ConnectedReviewProviderSelection,
    val packetDigest: String,
    val exactPrompt: InterpretationPromptV1,
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
}

/** Composes qualification/health and packet-scoped consent before every hosted send. */
class ConnectedReviewService(
    private val repository: ConnectedReviewExecutionRepository,
    private val catalog: ConnectedReviewProviderCatalog,
    private val consent: ConnectedReviewConsentLedger = ConnectedReviewConsentLedger(),
    private val qualificationAndHealth: ConnectedReviewExecutionGate =
        ConnectedReviewExecutionGate { _, _ -> "qualification_gate_disabled" },
    private val limits: ConnectedReviewExecutionLimits = ConnectedReviewExecutionLimits(),
) {
    init {
        require(limits.policy == ConnectedReviewExecutionPolicy.ORDINARY) {
            "Production connected-review service requires ordinary execution policy"
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
        val packet = repository.connectedReviewPacket(snapshotId)
        require(InterpretationContractV1.promptSha256(packet) == snapshot.packetDigest) { "Review packet changed" }
        return ConnectedReviewPreview(snapshotId, selection, snapshot.packetDigest, InterpretationContractV1.prompt(packet))
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
                if (!consent.permits(snapshot.snapshotId, exactSelection, snapshot.packetDigest)) "consent_required" else null
            } else null
        }
        return ConnectedReviewExecutor(repository, gate, limits).executeManually(snapshotId, selection, catalog)
    }
}
