package com.gtrainer

import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.encodeToString

/** SQL-only typed evidence ledger. HistoryStore owns all locks and complete transactions. */
internal class ActivityEffortStore(private val connection: Connection) {
    private val json=ScopedEvidencePreparation.json
    private val select="SELECT r.report_json,l.detached,l.detachment_generation FROM activity_effort_reports r JOIN activity_effort_lineages l USING(report_id)"

    fun scale(id: String): EffortScale {
        validateContextId(id)
        return connection.prepareStatement("SELECT definition_json FROM plan_effort_scales WHERE scale_id=?").use { s ->
            s.setString(1,id);s.executeQuery().use { r -> if(!r.next()) throw PlanNotFound();json.decodeFromString(r.getString(1)) }
        }
    }
    private fun read(row: java.sql.ResultSet)=json.decodeFromString<ActivityEffortReport>(row.getString(1)).copy(detached=row.getInt(2)!=0,detachmentGeneration=row.getLong(3))

    fun history(activityId: String,scaleId: String): List<ActivityEffortReport> {
        require(activityId.matches(Regex("[a-f0-9]{64}")));validateContextId(scaleId)
        return connection.prepareStatement("$select WHERE l.activity_id=? AND l.scale_id=? ORDER BY r.revision LIMIT 1001").use { s ->
            s.setString(1,activityId);s.setString(2,scaleId);s.executeQuery().use { r -> buildList {
                while(r.next()) { check(size<1000);add(read(r)) }
            } }
        }
    }

    fun heads(): List<ActivityEffortReport> = connection.createStatement().use { s ->
        s.executeQuery("$select WHERE r.revision=(SELECT max(h.revision) FROM activity_effort_reports h WHERE h.report_id=r.report_id) ORDER BY l.activity_id,l.scale_id LIMIT 10001").use { r -> buildList {
            while(r.next()) { if(size==10_000) throw ScopedEvidenceInspectionLimit();add(read(r)) }
        } }
    }

    fun receipt(request: ActivityEffortRequest): ActivityEffortReceipt? = connection.prepareStatement(
        "SELECT request_json,report_id,revision FROM activity_effort_operations WHERE operation_id=?").use { s ->
        s.setString(1,request.operationId);s.executeQuery().use { r ->
            if(!r.next()) null else {
                if(json.decodeFromString<ActivityEffortRequest>(r.getString(1))!=request) throw PlanConflict()
                val id=r.getString(2);val revision=r.getInt(3)
                val saved=history(request.activityId,request.scaleId).single { it.reportId==id && it.revision==revision }
                // Receipt acknowledges the historical write; detachment is projected from current lineage state.
                ActivityEffortReceipt(saved,true)
            }
        }
    }

    fun save(request: ActivityEffortRequest,capture: CapturedPlanReconciliation?,at: Instant): ActivityEffortReceipt {
        val scale=scale(request.scaleId)
        val previous=history(request.activityId,request.scaleId).lastOrNull()
        if(previous?.reportId!=request.expectedReportId || (previous?.revision?:0)!=request.expectedRevision ||
            (previous?.detachmentGeneration?:0)!=request.expectedDetachmentGeneration) throw PlanConflict()
        val revision=(previous?.revision?:0)+1
        // One revision per lineage and one row per nonwithdrawn head are reserved for explicit withdrawal.
        val total=connection.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM activity_effort_reports").use { r -> r.next();r.getInt(1) } }
        val nonwithdrawn=heads().count { !it.withdrawn }
        val nextNonwithdrawn=nonwithdrawn-(if(previous!=null && !previous.withdrawn) 1 else 0)+(if(request.action=="report") 1 else 0)
        require(total+1+nextNonwithdrawn<=10_000 && revision<=if(request.action=="withdraw") 1000 else 999) { "Effort report capacity reached; withdrawal remains reserved" }
        val report=if(request.action=="withdraw") {
            if(previous==null || previous.withdrawn) throw PlanConflict()
            previous.copy(revision=revision,previousRevision=previous.revision,recordedUtc=at.toString(),withdrawn=true)
        } else {
            val identity=capture?.activities?.singleOrNull { it.activityId==request.activityId } ?: throw PlanConflict()
            if(identity.revision!=request.expectedActivityRevision || identity.recordSha256!=request.expectedRecordSha256 ||
                identity.startInstant?.let { Instant.parse(it)>at }==true) throw PlanConflict()
            require(java.time.LocalDate.parse(request.observedOn)<=at.atZone(ZoneOffset.UTC).toLocalDate())
            ActivityEffortReport(previous?.reportId?:UUID.randomUUID().toString(),revision,previous?.revision,request.activityId,
                identity.revision,identity.recordSha256,scale,requireNotNull(request.value),requireNotNull(request.observedOn),
                requireNotNull(request.authorAttribution),"authenticated_user",at.toString(),false,false,request.expectedDetachmentGeneration)
        }
        if(previous==null) connection.prepareStatement("INSERT INTO activity_effort_lineages(report_id,activity_id,scale_id,detached,detachment_generation) VALUES(?,?,?,0,0)").use { s ->
            s.setString(1,report.reportId);s.setString(2,report.activityId);s.setString(3,scale.scaleId);s.executeUpdate()
        } else if(request.action=="report") connection.prepareStatement("UPDATE activity_effort_lineages SET detached=0 WHERE report_id=?").use { s -> s.setString(1,report.reportId);s.executeUpdate() }
        connection.prepareStatement("INSERT INTO activity_effort_reports(report_id,revision,report_json) VALUES(?,?,?)").use { s ->
            s.setString(1,report.reportId);s.setInt(2,revision);s.setString(3,json.encodeToString(report));s.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO activity_effort_operations(operation_id,request_json,report_id,revision) VALUES(?,?,?,?)").use { s ->
            s.setString(1,request.operationId);s.setString(2,json.encodeToString(request));s.setString(3,report.reportId);s.setInt(4,revision);s.executeUpdate()
        }
        return ActivityEffortReceipt(report,false)
    }

    fun detachImports() { connection.createStatement().use { it.executeUpdate("UPDATE activity_effort_lineages SET detached=1,detachment_generation=detachment_generation+1") } }
}
