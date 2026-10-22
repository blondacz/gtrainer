package com.gtrainer

import kotlinx.serialization.Serializable

/** Content-free active reason set and exact workload version for adaptation work. */
@Serializable
data class AdaptationQueuePayload(
    val family: AdaptationFamily,
    val workVersionSha256: String,
    val selectionGeneration: Long,
    val reasonIds: List<String>,
    val workVersion: AdaptationWorkVersion? = null,
) {
    init {
        require(workVersionSha256.matches(Regex("[a-f0-9]{64}")))
        require(selectionGeneration >= 0)
        require(reasonIds.size in 1..1000)
        require(reasonIds.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(reasonIds == reasonIds.distinct().sorted())
        workVersion?.let { work -> require(work.family==family && work.key()==workVersionSha256 && reasonIds.all { id -> work.reasons.any { it.reasonId==id } }) }
    }

    override fun toString() = "AdaptationQueuePayload([REDACTED])"
}
