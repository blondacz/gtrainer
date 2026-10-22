package com.gtrainer

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.encodeToString

/** SQL-only durable reason heads and admission receipts. Transactions belong to the caller. */
internal class AdaptationReasonStore(private val connection: Connection) {
    private val json=ScopedEvidencePreparation.json

    fun get(id:String): AdaptationReasonHead? = connection.prepareStatement(
        "SELECT head_json FROM adaptation_reason_heads WHERE reason_id=?").use { s ->
        s.setString(1,id);s.executeQuery().use { r -> if(r.next()) json.decodeFromString(r.getString(1)) else null }
    }

    fun heads():List<AdaptationReasonHead> = connection.createStatement().use { s ->
        s.executeQuery("SELECT head_json FROM adaptation_reason_heads ORDER BY reason_id LIMIT 100001").use { r ->
            buildList { while(r.next()) { if(size==100000) throw IllegalArgumentException("Adaptation reason head limit reached");add(json.decodeFromString(r.getString(1))) } }
        }
    }

    fun supports(): List<AdaptationReasonSupport> = connection.createStatement().use { s ->
        s.executeQuery("SELECT support_json FROM adaptation_reason_heads WHERE support_json IS NOT NULL ORDER BY reason_id LIMIT 100001").use { r ->
            var bytes=0L
            buildList { while(r.next()) {
                require(size<100_000)
                val encoded=r.getString(1);bytes+=encoded.toByteArray(Charsets.UTF_8).size
                require(bytes<=8*1024*1024) { "Adaptation reason support exceeds its byte budget" }
                add(json.decodeFromString<AdaptationReasonSupport>(encoded))
            } }
        }
    }

    fun saveSupports(supports: List<AdaptationReasonSupport>) {
        require(supports.map { it.reasonId }.distinct().size==supports.size)
        var bytes=0L
        val encoded=supports.associate { support ->
            require(get(support.reasonId)!=null)
            support.reasonId to json.encodeToString(support).also { bytes+=it.toByteArray(Charsets.UTF_8).size }
        }
        val retained=this.supports().filter { it.reasonId !in encoded }.sumOf { json.encodeToString(it).toByteArray(Charsets.UTF_8).size.toLong() }
        require(bytes+retained<=8*1024*1024) { "Adaptation reason support exceeds its byte budget" }
        for((id,value) in encoded) connection.prepareStatement("UPDATE adaptation_reason_heads SET support_json=? WHERE reason_id=? AND (support_json IS NULL OR support_json<>?)").use { p ->
            p.setString(1,value);p.setString(2,id);p.setString(3,value);p.executeUpdate()
        }
    }

    fun save(head:AdaptationReasonHead,expectedRevision:Long):Boolean {
        require(expectedRevision>=0)
        val previous=get(head.reasonId)
        if((previous?.revision?:0L)!=expectedRevision) throw PlanConflict()
        if(previous==head) return false
        val observation=AdaptationReasonObservation(head.reasonId,head.kind,head.subjectId,head.condition,head.materialSha256,head.dependencySha256)
        require(AdaptationReasons.project(previous,observation)==head) { "Invalid adaptation reason transition" }
        if(previous!=null) {
            require(expectedRevision<Long.MAX_VALUE && head.revision==expectedRevision+1 && head.reasonId==previous.reasonId && head.kind==previous.kind && head.subjectId==previous.subjectId)
        } else {
            require(expectedRevision==0L && head.revision==1L)
            connection.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM adaptation_reason_heads").use { r -> r.next();require(r.getLong(1)<100000) { "Adaptation reason head limit reached" } } }
        }
        val encoded=json.encodeToString(head);require(encoded.toByteArray(Charsets.UTF_8).size<=8192) { "Adaptation reason head exceeds storage limit" }
        val sql=if(previous==null) "INSERT INTO adaptation_reason_heads(reason_id,head_json) VALUES(?,?)" else "UPDATE adaptation_reason_heads SET head_json=? WHERE reason_id=?"
        connection.prepareStatement(sql).use { s -> if(previous==null) { s.setString(1,head.reasonId);s.setString(2,encoded) } else { s.setString(1,encoded);s.setString(2,head.reasonId) };check(s.executeUpdate()==1) }
        return true
    }

    fun admissions(reasonIds:Set<String>):List<AdaptationAdmission> {
        require(reasonIds.size<=100000 && reasonIds.all { it.matches(Regex("[a-f0-9]{64}")) })
        val result=ArrayList<AdaptationAdmission>()
        reasonIds.sorted().chunked(400).forEach { chunk ->
            val marks=chunk.joinToString(",") { "?" }
            connection.prepareStatement("SELECT admission_json FROM adaptation_admissions WHERE reason_id IN ($marks) ORDER BY reason_id,episode LIMIT ${100001-result.size}").use { s ->
                chunk.forEachIndexed { i,id -> s.setString(i+1,id) }
                s.executeQuery().use { r -> while(r.next()) { if(result.size==100000) throw IllegalArgumentException("Adaptation admission limit reached");result.add(json.decodeFromString(r.getString(1))) } }
            }
            if(result.size>100000) throw IllegalArgumentException("Adaptation admission limit reached")
        }
        return result
    }

    fun admit(head:AdaptationReasonHead,jobId:String,at:Instant):AdaptationAdmission {
        validateContextId(jobId)
        if(get(head.reasonId)!=head || head.condition!="active" || !head.open) throw PlanConflict()
        connection.prepareStatement("SELECT admission_json FROM adaptation_admissions WHERE reason_id=? AND episode=?").use { s ->
            s.setString(1,head.reasonId);s.setLong(2,head.episode);s.executeQuery().use { r -> if(r.next()) return json.decodeFromString(r.getString(1)) }
        }
        connection.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM adaptation_admissions").use { r -> r.next();require(r.getLong(1)<100000) { "Adaptation admission limit reached" } } }
        val receipt=AdaptationAdmission(head.reasonId,head.episode,jobId,at.toString())
        val encoded=json.encodeToString(receipt);require(encoded.toByteArray(Charsets.UTF_8).size<=8192) { "Adaptation admission exceeds storage limit" }
        connection.prepareStatement("INSERT INTO adaptation_admissions(reason_id,episode,admission_json) VALUES(?,?,?)").use { s -> s.setString(1,receipt.reasonId);s.setLong(2,receipt.episode);s.setString(3,encoded);s.executeUpdate() }
        return receipt
    }

    fun unfulfilled(heads: List<AdaptationReasonHead>): Set<String> = heads.filter { head ->
        connection.prepareStatement("SELECT 1 FROM adaptation_admissions WHERE reason_id=? AND episode=? AND fulfilled=0").use { p ->
            p.setString(1,head.reasonId);p.setLong(2,head.episode);p.executeQuery().use { it.next() }
        }
    }.map { it.reasonId }.toSet()

    fun fulfill(heads: List<AdaptationReasonHead>) {
        for(head in heads) connection.prepareStatement("UPDATE adaptation_admissions SET fulfilled=1 WHERE reason_id=? AND episode=? AND fulfilled=0").use { p ->
            p.setString(1,head.reasonId);p.setLong(2,head.episode);p.executeUpdate()
        }
    }

    /** Privacy deletion is not an ordinary transition. Keep opaque episode/admission facts,
     * scrub health-content hashes even when the revision counter cannot advance. */
    fun redact() {
        connection.createStatement().use { it.executeUpdate("UPDATE adaptation_reason_heads SET support_json=NULL WHERE support_json IS NOT NULL") }
        val redacted = AnalysisClaims.hash("adaptation_reason_proof_unavailable")
        for (previous in heads()) {
            val next = previous.copy(revision = if (previous.revision == Long.MAX_VALUE) Long.MAX_VALUE else previous.revision + 1,
                condition = "unevaluable", materialSha256 = redacted, dependencySha256 = redacted)
            if (previous.condition == next.condition && previous.materialSha256 == redacted && previous.dependencySha256 == redacted) continue
            connection.prepareStatement("UPDATE adaptation_reason_heads SET head_json=? WHERE reason_id=? AND head_json=?").use { p ->
                p.setString(1,json.encodeToString(next));p.setString(2,previous.reasonId);p.setString(3,json.encodeToString(previous))
                check(p.executeUpdate()==1)
            }
        }
    }

    fun deletionBinding(): String = AnalysisClaims.hash(json.encodeToString(heads())+json.encodeToString(supports()))
}
