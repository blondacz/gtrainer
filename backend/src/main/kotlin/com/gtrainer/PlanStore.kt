package com.gtrainer

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Cohesive plan SQL; the HistoryStore monitor and owner transaction cover each complete operation. */
internal class PlanStore(private val connection: Connection) {
    private val json=Json { encodeDefaults=true }
    private val columns="plan_id,revision,previous_revision,definition_json,recorded_by,recorded_utc"

    fun selection(): PlanSelection = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT generation,selected_plan_id,selected_revision FROM plan_selection WHERE singleton=1").use { row ->
            check(row.next())
            PlanSelection(row.getLong(1),row.getString(2)?.let { PlanRef(it,row.getInt(3)) })
        }
    }

    fun list(): PlansView {
        val plans=connection.createStatement().use { statement ->
            statement.executeQuery("SELECT $columns FROM plan_revisions WHERE (plan_id,revision) IN " +
                "(SELECT plan_id,max(revision) FROM plan_revisions GROUP BY plan_id) ORDER BY plan_id LIMIT 101").use { rows ->
                buildList { while(rows.next()) { require(size<100); add(record(rows)) } }
            }
        }
        return PlansView(plans,selection())
    }

    fun get(id: String, revision: Int? = null): AuthoredPlan {
        validateContextId(id); revision?.let { require(it in 1..1000) }
        return connection.prepareStatement("SELECT $columns FROM plan_revisions WHERE plan_id=?" +
            if(revision==null) " ORDER BY revision DESC LIMIT 1" else " AND revision=?").use {
            it.setString(1,id); revision?.let { value -> it.setInt(2,value) }
            it.executeQuery().use { row -> if(row.next()) record(row) else throw PlanNotFound() }
        }
    }

    fun create(request: AuthoredPlanRequest, at: Instant): AuthoredPlan {
        val count=connection.createStatement().use { statement -> statement.executeQuery("SELECT count(DISTINCT plan_id) FROM plan_revisions").use { it.next(); it.getInt(1) } }
        require(count<100)
        return insert(UUID.randomUUID().toString(),1,null,request,at)
    }

    fun correct(id: String, request: PlanCorrection, at: Instant): AuthoredPlan {
        val current=get(id)
        val selected=selection()
        if(current.reference.revision!=request.expectedRevision || selected.generation!=request.expectedSelectionGeneration) throw PlanConflict()
        val saved=insert(id,request.expectedRevision+1,request.expectedRevision,request.definition,at)
        if(selected.selected?.planId==id) setSelection(PlanSelection(Math.addExact(selected.generation,1),saved.reference))
        return saved
    }

    /** Approval cannot silently normalize a value outside the exact reviewed diff. */
    fun correctExact(id: String,request: PlanCorrection,at: Instant): AuthoredPlan {
        if(normalize(request.definition)!=request.definition) throw PlanConflict()
        return correct(id,request,at).also { if(it.definition!=request.definition) throw PlanConflict() }
    }

    fun select(request: PlanSelectionRequest): PlanSelection {
        val current=selection()
        if(current.generation!=request.expectedGeneration) throw PlanConflict()
        request.selected?.let { if(get(it.planId).reference!=it) throw PlanConflict() }
        // Even A -> A is an explicit selection revision; an A -> B -> A race is detectable.
        return PlanSelection(Math.addExact(current.generation,1),request.selected).also(::setSelection)
    }

    private fun setSelection(selection: PlanSelection) {
        connection.prepareStatement("UPDATE plan_selection SET generation=?,selected_plan_id=?,selected_revision=? WHERE singleton=1").use {
            it.setLong(1,selection.generation); it.setString(2,selection.selected?.planId); it.setObject(3,selection.selected?.revision)
            check(it.executeUpdate()==1)
        }
    }

    private fun insert(id: String, revision: Int, previous: Int?, request: AuthoredPlanRequest, at: Instant): AuthoredPlan {
        require(revision in 1..1000)
        val normalized=normalize(request)
        require(json.encodeToString(normalized).toByteArray(Charsets.UTF_8).size<=32_768)
        normalized.sessions.flatMap { it.targets }.mapNotNull { it.effortScale }.forEach(::bindScale)
        connection.prepareStatement("INSERT INTO plan_revisions($columns) VALUES(?,?,?,?,'authenticated_user',?)").use {
            it.setString(1,id); it.setInt(2,revision); it.setObject(3,previous); it.setString(4,json.encodeToString(normalized)); it.setString(5,at.toString()); it.executeUpdate()
        }
        return AuthoredPlan(PlanRef(id,revision),previous,normalized,"authenticated_user",at.toString())
    }

    private fun normalize(request: AuthoredPlanRequest)=request.copy(sessions=request.sessions.map { session ->
            val schedule=session.schedule
            if(schedule.kind=="date_only") session else session.copy(schedule=schedule.copy(
                startOffset=ZoneId.of(schedule.timeZone).rules.getOffset(schedule.startInstant()).id,
                endOffset=ZoneId.of(schedule.timeZone).rules.getOffset(schedule.endInstant()).id))
        })

    private fun bindScale(scale: EffortScale) {
        val encoded=json.encodeToString(scale)
        val existing=connection.prepareStatement("SELECT definition_json FROM plan_effort_scales WHERE scale_id=?").use {
            it.setString(1,scale.scaleId); it.executeQuery().use { row -> if(row.next()) row.getString(1) else null }
        }
        if(existing!=null) {
            if(json.decodeFromString<EffortScale>(existing)!=scale) throw PlanConflict()
        } else connection.prepareStatement("INSERT INTO plan_effort_scales(scale_id,definition_json) VALUES(?,?)").use {
            it.setString(1,scale.scaleId); it.setString(2,encoded); it.executeUpdate()
        }
    }

    private fun record(row: ResultSet) = AuthoredPlan(PlanRef(row.getString(1),row.getInt(2)),
        (row.getObject(3) as Number?)?.toInt(),json.decodeFromString(row.getString(4)),row.getString(5),row.getString(6))
}
