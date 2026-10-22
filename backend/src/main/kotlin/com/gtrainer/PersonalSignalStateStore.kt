package com.gtrainer

import java.sql.Connection
import kotlinx.serialization.encodeToString

/** SQL only; no reason admission or independent transaction. */
internal class PersonalSignalStateStore(private val connection: Connection) {
    private val json=ScopedEvidencePreparation.json

    fun replay(ruleId: String): StoredSignalReplay = connection.prepareStatement(
        "SELECT generation,replay_json,high_water_json FROM personal_signal_rule_state WHERE rule_id=?").use { p ->
        p.setString(1,ruleId);p.executeQuery().use { r ->
            if(!r.next()) StoredSignalReplay(0,null,null) else StoredSignalReplay(r.getLong(1),
                r.getString(2)?.let { json.decodeFromString<SignalReplayCheckpoint>(it) },
                r.getString(3)?.let { json.decodeFromString<SignalAdmissionHighWater>(it) })
        }
    }
    fun get(ruleId: String): Pair<Long,PersonalSignalState?> {
        return connection.prepareStatement("SELECT generation,state_json FROM personal_signal_rule_state WHERE rule_id=?").use { s ->
            s.setString(1,ruleId);s.executeQuery().use { r ->
                if(!r.next()) 0L to null else r.getLong(1) to ScopedEvidencePreparation.json.decodeFromString<PersonalSignalState>(r.getString(2))
            }
        }
    }
    fun save(ruleId: String,expectedGeneration: Long,state: PersonalSignalState) {
        require(state.ruleId==ruleId && expectedGeneration in 0 until Long.MAX_VALUE)
        if(get(ruleId).first!=expectedGeneration) throw PlanConflict()
        connection.prepareStatement("INSERT INTO personal_signal_rule_state(rule_id,generation,state_json) VALUES(?,?,?) ON CONFLICT(rule_id) DO UPDATE SET generation=excluded.generation,state_json=excluded.state_json,replay_json=NULL").use { s ->
            s.setString(1,ruleId);s.setLong(2,expectedGeneration+1);s.setString(3,ScopedEvidencePreparation.json.encodeToString(state));s.executeUpdate()
        }
    }

    fun saveReplay(ruleId: String,expectedGeneration: Long,state: PersonalSignalState,checkpoint: SignalReplayCheckpoint?,highWater: SignalAdmissionHighWater?) {
        require(state.ruleId==ruleId && (checkpoint==null || checkpoint.state==state) && (highWater==null || highWater.ruleId==ruleId))
        require(expectedGeneration in 0 until Long.MAX_VALUE)
        if(get(ruleId).first!=expectedGeneration) throw PlanConflict()
        val proof=checkpoint?.let { json.encodeToString(it).also { encoded -> require(encoded.toByteArray(Charsets.UTF_8).size<=8*1024*1024) } }
        connection.prepareStatement("INSERT INTO personal_signal_rule_state(rule_id,generation,state_json,replay_json,high_water_json) VALUES(?,?,?,?,?) " +
            "ON CONFLICT(rule_id) DO UPDATE SET generation=excluded.generation,state_json=excluded.state_json,replay_json=excluded.replay_json,high_water_json=excluded.high_water_json").use { p ->
            p.setString(1,ruleId);p.setLong(2,expectedGeneration+1);p.setString(3,json.encodeToString(state));p.setString(4,proof);p.setString(5,highWater?.let { json.encodeToString(it) });p.executeUpdate()
        }
    }

    /** Purge causal evidence without deleting the CAS generation or irreversible high-water. */
    fun invalidateProofs() {
        val rows=connection.createStatement().use { s -> s.executeQuery("SELECT rule_id,generation,state_json,high_water_json FROM personal_signal_rule_state ORDER BY rule_id LIMIT 21").use { r -> buildList {
            while(r.next()) { require(size<20);add(Triple(r.getString(1),r.getLong(2),json.decodeFromString<PersonalSignalState>(r.getString(3)) to r.getString(4))) }
        } } }
        rows.forEach { (id,generation,old) ->
            val water=old.second?.let { json.decodeFromString<SignalAdmissionHighWater>(it) }
            val unknown=PersonalSignalEpisode(lastEmittedUtc=water?.admittedUtc)
            val state=old.first.copy(date="0001-01-01",beforeDay=unknown,projected=unknown,sourceRevision=0)
            connection.prepareStatement("UPDATE personal_signal_rule_state SET generation=?,state_json=?,replay_json=NULL WHERE rule_id=?").use { p ->
                p.setLong(1,if(generation==Long.MAX_VALUE) generation else generation+1);p.setString(2,json.encodeToString(state));p.setString(3,id);p.executeUpdate()
            }
        }
    }

    fun deletionBinding(): String {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        connection.createStatement().use { s -> s.executeQuery("SELECT rule_id,generation,state_json,replay_json,high_water_json FROM personal_signal_rule_state ORDER BY rule_id LIMIT 21").use { r ->
            var rows=0
            while(r.next()) {
                require(++rows<=20)
                for(column in 1..5) {
                    val value=r.getString(column)
                    digest.update((value?.let { "${it.length}:$it" } ?: "-1:").toByteArray(Charsets.UTF_8))
                }
            }
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
