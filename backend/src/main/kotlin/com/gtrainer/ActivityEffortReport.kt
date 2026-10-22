package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Instant

/** Typed attributed evidence contract; no prose extraction, scale conversion or runtime admission. */
@Serializable
data class ActivityEffortReport(
    val reportId: String,val revision: Int,val previousRevision: Int?,val activityId: String,
    val activityRevision: Long?,val activityRecordSha256: String,val scale: EffortScale,val value: Double,
    val observedOn: String,val authorAttribution: String,val recordedBy: String,val recordedUtc: String,
    val withdrawn: Boolean,val detached: Boolean=false,val detachmentGeneration: Long=0,
) {
    init {
        validateContextId(reportId);require(revision in 1..1000)
        require(previousRevision==if(revision==1) null else revision-1)
        require(activityId.matches(Regex("[a-f0-9]{64}")) && activityRecordSha256.matches(Regex("[a-f0-9]{64}")))
        require(activityRevision==null || activityRevision>0)
        require(value.isFinite() && value>=scale.minimum && value<=scale.maximum)
        val steps=(value-scale.minimum)/scale.step
        require(kotlin.math.abs(steps-kotlin.math.round(steps))<1e-8)
        validateContextDate(observedOn);validateContextText(authorAttribution,160,true)
        require(recordedBy=="authenticated_user");Instant.parse(recordedUtc)
        require(detachmentGeneration>=0)
    }
    override fun toString()="ActivityEffortReport([REDACTED])"
}

@Serializable
data class EffortReportOrigin(val plan: PlanRef,val sessionId: String,val expectedSelectionGeneration: Long,
    val expectedDigest: String,val asOf: String,val query: PlanReconciliationQuery) {
    init { validateContextId(sessionId);require(expectedSelectionGeneration>=0 && expectedDigest.matches(Regex("[a-f0-9]{64}")));Instant.parse(asOf) }
}

@Serializable
data class ActivityEffortRequest(val operationId: String,val activityId: String,val scaleId: String,
    val expectedReportId: String?,val expectedRevision: Int,val expectedDetachmentGeneration: Long,
    val expectedActivityRevision: Long?,val expectedRecordSha256: String?,val action: String,
    val value: Double?,val observedOn: String?,val authorAttribution: String?,val origin: EffortReportOrigin?=null) {
    init {
        validateContextId(operationId);validateContextId(scaleId);expectedReportId?.let(::validateContextId)
        require(activityId.matches(Regex("[a-f0-9]{64}")) && expectedRevision in 0..1000 && expectedDetachmentGeneration>=0)
        require((expectedReportId==null)==(expectedRevision==0));require(expectedActivityRevision==null || expectedActivityRevision>0)
        require(action in setOf("report","withdraw"))
        if(action=="report") {
            require(expectedRecordSha256?.matches(Regex("[a-f0-9]{64}"))==true && value!=null && value.isFinite())
            validateContextDate(requireNotNull(observedOn));validateContextText(requireNotNull(authorAttribution),160,true)
        } else {
            require(expectedReportId!=null && value==null && observedOn==null && authorAttribution==null &&
                expectedRecordSha256==null && expectedActivityRevision==null && origin==null)
        }
    }
    override fun toString()="ActivityEffortRequest([REDACTED])"
}

@Serializable
data class ActivityEffortHistory(val activityId: String,val scale: EffortScale,val currentActivity: ReconciliationActivity?,
    val reports: List<ActivityEffortReport>) {
    override fun toString()="ActivityEffortHistory([REDACTED])"
}
@Serializable
data class ActivityEffortReceipt(val report: ActivityEffortReport,val replayed: Boolean) {
    override fun toString()="ActivityEffortReceipt([REDACTED])"
}

interface ActivityEffortRepository {
    fun effortReceipt(request: ActivityEffortRequest): ActivityEffortReceipt?
    fun effortHistory(activityId: String,scaleId: String): ActivityEffortHistory
    fun saveEffortReport(request: ActivityEffortRequest,capture: CapturedPlanReconciliation?,clock: java.time.Clock): ActivityEffortReceipt
}
