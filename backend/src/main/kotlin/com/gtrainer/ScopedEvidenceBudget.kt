package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class ScopedEvidenceOmissions(val wellnessObservationDetails: Int = 0, val optionalContexts: Int = 0,
    val policy: String = "category_atomic_optional_detail_only_no_subject_or_mandatory_truncation")

@Serializable
data class ScopedBoundedEvidence(val packet: ScopedEvidencePacket?, val packetBytes: Int,
    val omitted: ScopedEvidenceOmissions, val blockedReasons: List<String>) {
    override fun toString()="ScopedBoundedEvidence([REDACTED])"
}

internal object ScopedEvidenceBudget {
    const val MAX_PACKET_BYTES=32_768

    /** Existing ceiling, never a guarantee that future profile instructions also fit. */
    fun bound(input: ScopedEvidencePacket): ScopedBoundedEvidence {
        fun bytes(packet: ScopedEvidencePacket)=ScopedEvidencePreparation.json.encodeToString(packet).toByteArray(Charsets.UTF_8).size
        val blocked=buildList {
            if(input.facts.scope.status!="available") addAll(input.facts.scope.reasons)
            addAll(input.context.evidence.blockedReasons)
            if(input.request.purpose=="adaptation") {
                addAll(input.context.amendments.blockedReasons)
                if(input.plan.plan==null) add("authored_base_plan_required")
                if(input.planningHorizon==null) add("plan_policy_required")
                if(input.commitments.any { it.constraintsBlocked }) add("commitment_needs_confirmation")
            }
        }.distinct().toMutableList()
        var packet=input
        var droppedWellness=0
        var droppedOptional=0
        // The exact selected session identities and every mandatory restriction remain untouched.
        // Annual optional raw observations can be inspected separately; aggregate arithmetic is retained.
        if(bytes(packet)>MAX_PACKET_BYTES && packet.facts.wellness.isNotEmpty()) {
            droppedWellness=packet.facts.wellness.size
            packet=packet.copy(facts=packet.facts.copy(wellness=emptyList()))
        }
        if(bytes(packet)>MAX_PACKET_BYTES) {
            droppedOptional=packet.context.evidence.optionalContexts.size+packet.context.amendments.optionalContexts.size
            packet=packet.copy(context=packet.context.copy(
                evidence=packet.context.evidence.copy(optionalContexts=emptyList(),
                    omittedOptionalCount=packet.context.evidence.omittedOptionalCount+packet.context.evidence.optionalContexts.size),
                amendments=packet.context.amendments.copy(optionalContexts=emptyList(),
                    omittedOptionalCount=packet.context.amendments.omittedOptionalCount+packet.context.amendments.optionalContexts.size)))
        }
        val omissions=ScopedEvidenceOmissions(droppedWellness,droppedOptional)
        packet=packet.copy(omitted=omissions)
        val size=bytes(packet)
        if(size>MAX_PACKET_BYTES) blocked.add("mandatory_or_explicit_subject_packet_overflow")
        return ScopedBoundedEvidence(if(blocked.isEmpty()) packet else null,size,omissions,blocked.distinct())
    }
}
