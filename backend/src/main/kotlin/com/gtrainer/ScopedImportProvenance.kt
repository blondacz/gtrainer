package com.gtrainer

import kotlinx.serialization.Serializable

/** Latest changed import receipt, not a measurement time or proof of upstream freshness. */
@Serializable
data class ScopedImportProvenance(
    val category: String,
    val source: String,
    val sourceRecordId: String,
    val revision: Long?,
    val receivedUtc: String?,
) {
    override fun toString() = "ScopedImportProvenance([REDACTED])"
}

/** Internal consistent read. Never serialized as provider input. */
internal data class CapturedScopedRead(
    val scope: ResolvedReviewScope,
    val history: HistoryResponse,
    val statuses: List<CategoryStatus>,
    val provenance: List<ScopedImportProvenance>,
) {
    override fun toString() = "CapturedScopedRead([REDACTED])"
}
