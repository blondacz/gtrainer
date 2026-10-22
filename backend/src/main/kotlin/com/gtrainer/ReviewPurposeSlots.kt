package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** No default time or implicit enrollment. One descriptive slot per purpose; never one preset per reporting scope. */
@Serializable
data class ReviewPurposeSlot(val slotId: String,val purpose: String,val enabled: Boolean,val localTime: String,
    val profile: String=ScopedDescriptiveContractV1.PROFILE,val providerSelection: ConnectedReviewProviderSelection?=null) {
    init {
        validateContextId(slotId);require(purpose in setOf("morning","evening"))
        require(profile==ScopedDescriptiveContractV1.PROFILE)
        require(Regex("[0-9]{2}:[0-9]{2}").matches(localTime));LocalTime.parse(localTime)
    }
}

@Serializable
data class ReviewPurposeOccurrence(val occurrenceId: String,val slotId: String,val purpose: String,val profile: String,
    val configurationVersion: Long,val timeZone: String,val localDate: String,val scheduledUtc: String,val routedUtc: String,
    val request: ScopedEvidenceRequest,val providerSelection: ConnectedReviewProviderSelection?=null) {
    override fun toString()="ReviewPurposeOccurrence([REDACTED])"
}
@Serializable
data class ReviewPurposeSlotStatus(val slotId: String,val purpose: String,val state: String,val nextUtc: String?,
    val limitation: String="routing_does_not_sync_sources_or_reconstruct_scheduled_time_knowledge")

internal data class ReviewPurposeSlotEvaluation(val due: List<ReviewPurposeOccurrence>,val highWater: Map<String,String>,
    val statuses: List<ReviewPurposeSlotStatus>)

/** Pure routing calculation only. The owner must atomically commit high-water marks with queue admission. */
internal object ReviewPurposeSlots {
    fun evaluate(slots: List<ReviewPurposeSlot>,timeZone: String,configurationVersion: Long,armedAt: Instant?,paused: Boolean,
        highWater: Map<String,String>,now: Instant): ReviewPurposeSlotEvaluation {
        require(slots.size<=2 && slots.map { it.slotId }.distinct().size==slots.size && slots.map { it.purpose }.distinct().size==slots.size)
        require(configurationVersion>=0 && highWater.size<=1000)
        highWater.forEach { (id,date)->validateContextId(id);validateContextDate(date) }
        val zone=ZoneId.of(timeZone)
        val marks=highWater.toMutableMap()
        val due=mutableListOf<ReviewPurposeOccurrence>()
        val statuses=slots.map { slot ->
            val blocked=when { !slot.enabled->"slot_disabled";paused->"schedules_paused";armedAt==null->"awaiting_explicit_rearm";else->null }
            if(blocked!=null) return@map ReviewPurposeSlotStatus(slot.slotId,slot.purpose,blocked,null)
            val occurrence=ReviewTimeOccurrences.resolve(now,zone,LocalTime.parse(slot.localTime),requireNotNull(armedAt),marks[slot.slotId])
            if(occurrence.due) {
                val date=occurrence.localDate.toString()
                val id=AnalysisClaims.hash(ScopedEvidencePreparation.json.encodeToString(listOf(slot.slotId,slot.purpose,timeZone,date)))
                due+=ReviewPurposeOccurrence(id,slot.slotId,slot.purpose,slot.profile,configurationVersion,timeZone,date,
                    occurrence.scheduledUtc.toString(),now.toString(),ScopedEvidenceRequest(selection=ScopedReviewRequest(
                        scope="day",anchorDate=date,wellnessPolicy="assessment"),purpose=slot.purpose),slot.providerSelection)
                marks[slot.slotId]=date
            }
            ReviewPurposeSlotStatus(slot.slotId,slot.purpose,if(occurrence.due) "factual_preparation_due" else "waiting_for_slot",occurrence.nextUtc.toString())
        }
        return ReviewPurposeSlotEvaluation(due,marks.toMap(),statuses)
    }
}
