package com.gtrainer

import kotlinx.serialization.Serializable

@Serializable
data class ConnectedReviewProviderSelection(val providerId: String, val modelId: String, val providerKind: String) {
    init {
        require(providerId.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        require(modelId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        require(providerKind in setOf("local", "hosted"))
    }

    val storageId: String get() = "$providerKind:$providerId"
    override fun toString() = "ConnectedReviewProviderSelection([REDACTED])"
}

@Serializable
data class ConnectedReviewBinding(
    val requestId: String,
    val evidence: AnalysisInput,
    val retrieval: ContextRetrievalResult,
    val coverageFrom: String,
    val coverageUntil: String,
    val sport: String?,
    val providerSelection: ConnectedReviewProviderSelection,
    val contractVersion: String,
) {
    override fun toString() = "ConnectedReviewBinding([REDACTED])"
}

@Serializable
data class ConnectedReviewStatus(
    val snapshotId: String,
    val requestId: String,
    val generation: Long,
    val evidenceDigest: String,
    val packetDigest: String,
    val context: List<ContextRevisionRefV1>,
    val coverageFrom: String,
    val coverageUntil: String,
    val sport: String?,
    val provider: String,
    val model: String,
    val contractVersion: String,
    val state: String,
    val staleReason: String?,
    val publishedOutput: String?,
    val createdUtc: String,
) {
    override fun toString() = "ConnectedReviewStatus([REDACTED])"
}

class ConnectedReviewNotFound : NoSuchElementException("Connected review not found")

/** Output may only be supplied after an independent whole-response validator accepts it. */
class ValidatedConnectedReviewOutput internal constructor(val text: String) {
    override fun toString() = "ValidatedConnectedReviewOutput([REDACTED])"
}
