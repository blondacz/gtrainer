package com.gtrainer

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection

class HistorySchemaMismatch : IllegalStateException(
    "Database schema is incompatible or incomplete; no migrations are available. Explicitly reset the disposable database to create a fresh schema; no reset was performed."
)

object HistorySchema {
    const val VERSION = 1
    const val APPLICATION_ID = 0x47544E52
    private val json = Json { encodeDefaults = true }

    fun createIfEmpty(connection: Connection): Boolean {
        val version = pragma(connection, "user_version")
        val applicationId = pragma(connection, "application_id")
        if (version == VERSION && applicationId == APPLICATION_ID) return false
        if (version != 0 || applicationId != 0 || hasSchemaObjects(connection)) throw HistorySchemaMismatch()
        try {
            statements().forEach { sql -> connection.createStatement().use { it.execute(sql) } }
            connection.createStatement().use {
                it.execute("PRAGMA application_id=$APPLICATION_ID")
                it.execute("PRAGMA user_version=$VERSION")
            }
        } catch (_: Exception) {
            throw HistorySchemaMismatch()
        }
        return true
    }

    fun validate(connection: Connection) {
        try {
            if (pragma(connection, "user_version") != VERSION || pragma(connection, "application_id") != APPLICATION_ID)
                throw HistorySchemaMismatch()
            connection.createStatement().use { s -> s.executeQuery("PRAGMA foreign_keys").use { check(it.next() && it.getInt(1) == 1) } }
            val expected = statements().associateBy(::objectName)
            val actual = mutableMapOf<String, String>()
            connection.prepareStatement("SELECT name,sql FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*'").use { p ->
                p.executeQuery().use { rows -> while (rows.next()) actual[rows.getString(1)] = rows.getString(2) }
            }
            check(actual.keys == expected.keys)
            expected.forEach { (name, sql) -> check(normalize(actual.getValue(name)) == normalize(sql)) }
            connection.createStatement().use { s -> s.executeQuery("PRAGMA foreign_key_check").use { check(!it.next()) } }
            connection.createStatement().use { s ->
                s.executeQuery("SELECT singleton,state_json FROM review_schedule").use { rows ->
                    check(rows.next() && rows.getInt(1) == 1)
                    decodeState(rows.getString(2))
                    check(!rows.next())
                }
                s.executeQuery("SELECT singleton,timezone_confirmed FROM scoped_review_configuration").use { rows ->
                    check(rows.next() && rows.getInt(1) == 1 && rows.getInt(2) in 0..1 && !rows.next())
                }
                s.executeQuery("SELECT singleton,generation,selected_plan_id,selected_revision FROM plan_selection").use { rows ->
                    check(rows.next() && rows.getInt(1)==1 && rows.getLong(2)>=0)
                    val id=rows.getString(3)
                    val revision=rows.getObject(4)
                    check((id==null)==(revision==null))
                    id?.let { PlanRef(it,(revision as Number).toInt()) }
                    check(!rows.next())
                }
                s.executeQuery("SELECT singleton,source_revision,plan_reason_revision FROM scoped_queue_metadata").use { rows ->
                    check(rows.next() && rows.getInt(1)==1 && rows.getLong(2)>=0 && rows.getLong(3)>=0 && !rows.next())
                }
                s.executeQuery("SELECT singleton,epoch,generation FROM personal_review_runtime_owner").use { rows ->
                    check(rows.next() && rows.getInt(1)==1 && rows.getLong(3)>=0)
                    validateContextId(rows.getString(2));check(!rows.next())
                }
                s.executeQuery("SELECT claim_id,state_json FROM owner_uat_job_supervision").use { rows ->
                    while(rows.next()) {
                        val job=StrictProfileDraftJson.decode<OwnerUatSupervisedJob>(rows.getString(2))
                        check(job.claim.claimId==rows.getString(1) && job.claim.supervisionRequired)
                        val lease=PersonalReviewRuntimeStore(connection).get(job.claim.claimId)
                        check(lease!=null && lease.claim==job.claim)
                        if(job.phase=="STOP_CONFIRMED") check(lease.phase==PersonalRuntimePhase.STOP_CONFIRMED)
                    }
                }
                s.executeQuery("SELECT claim_json FROM personal_review_runtime_claims").use { rows ->
                    while(rows.next()) {
                        val claim=StrictProfileDraftJson.decode<PersonalReviewClaim>(rows.getString(1))
                        if(claim.supervisionRequired) check(OwnerUatSupervisionStore(connection).get(claim.claimId)?.claim==claim)
                    }
                }
                s.executeQuery("SELECT singleton,removal_generation,typeof(removal_generation) FROM signal_evidence_epoch").use { rows ->
                    check(rows.next() && rows.getInt(1)==1 && rows.getString(3)=="integer" && rows.getLong(2)>=0 && !rows.next())
                }
                s.executeQuery("SELECT singleton,mutation_sequence,deletion_generation,retained_from_sequence,typeof(mutation_sequence),typeof(deletion_generation),typeof(retained_from_sequence),available,typeof(available) FROM constraint_observation_metadata").use { rows ->
                    check(rows.next() && rows.getInt(1)==1 && (5..7).all { rows.getString(it)=="integer" })
                    check(rows.getString(9)=="integer" && rows.getInt(8) in 0..1)
                    val sequence=rows.getLong(2);val retained=rows.getLong(4)
                    check(sequence>=0 && rows.getLong(3)>=0 && retained>=1 &&
                        (retained<=sequence || sequence<Long.MAX_VALUE && retained==sequence+1) && !rows.next())
                }
            }
        } catch (_: Exception) {
            throw HistorySchemaMismatch()
        }
    }

    /** Persistence is written with every default field; missing fields are corruption, not an upgrade. */
    internal fun decodeState(encoded: String): ReviewSchedulerState {
        val state = json.decodeFromString<ReviewSchedulerState>(encoded)
        check(state.version >= 0 && state.cursor >= 0)
        check(json.parseToJsonElement(encoded) == json.parseToJsonElement(json.encodeToString(state)))
        return state
    }

    private fun statements(): List<String> = HistorySchema::class.java.getResourceAsStream("/history-schema.json")
        ?.bufferedReader()?.use { json.decodeFromString<List<String>>(it.readText()) } ?: throw HistorySchemaMismatch()

    private fun objectName(sql: String): String = Regex("(?is)^CREATE\\s+(?:UNIQUE\\s+)?(?:TABLE|INDEX|TRIGGER)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[\\\"`\\[]?([a-zA-Z0-9_]+)").find(sql)
        ?.groupValues?.get(1) ?: throw HistorySchemaMismatch()

    private fun normalize(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()
    private fun pragma(connection: Connection, name: String): Int = connection.createStatement().use { s ->
        s.executeQuery("PRAGMA $name").use { check(it.next()); it.getInt(1) }
    }
    private fun hasSchemaObjects(connection: Connection): Boolean = connection.createStatement().use { s ->
        s.executeQuery("SELECT 1 FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' LIMIT 1").use { it.next() }
    }
}
