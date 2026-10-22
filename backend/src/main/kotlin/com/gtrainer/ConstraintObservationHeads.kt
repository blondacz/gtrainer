package com.gtrainer

import java.sql.Connection

/** Opaque revision/provenance comparisons only; no values, applicability, content or fingerprints. */
internal data class ConstraintObservationHead(val ref: ConstraintSubjectRef,val dependencies: List<String>) {
    override fun toString()="ConstraintObservationHead([REDACTED])"
}

/** SQL-only current presence capture, including ineligible categories and post-cutoff revisions. */
internal object ConstraintObservationHeads {
    fun capture(connection: Connection): Map<Pair<String,String>,ConstraintObservationHead> {
        fun revisions(table:String,id:String): Map<String,Int> = connection.createStatement().use { s ->
            s.executeQuery("SELECT $id,max(revision) FROM $table GROUP BY $id ORDER BY $id LIMIT 1001").use { r ->
                buildMap { while(r.next()) { require(size<1000);put(r.getString(1),r.getInt(2)) } }
            }
        }
        val contexts=revisions("athlete_context","context_id")
        val intakes=revisions("context_intakes","intake_id")
        val links=connection.createStatement().use { s ->
            s.executeQuery("SELECT l.context_id,l.intake_id,l.intake_revision FROM context_intake_links l JOIN " +
                "(SELECT context_id,max(revision) revision FROM athlete_context GROUP BY context_id) c " +
                "ON c.context_id=l.context_id AND c.revision=l.context_revision ORDER BY l.context_id LIMIT 1001").use { r ->
                buildMap { while(r.next()) { require(size<1000);put(r.getString(1),listOf(r.getString(2),r.getInt(3).toString(),intakes.getValue(r.getString(2)).toString())) } }
            }
        }
        val result=linkedMapOf<Pair<String,String>,ConstraintObservationHead>()
        fun put(ref:ConstraintSubjectRef,dependencies:List<String> = emptyList()) { check(result.put(ref.kind to ref.id,ConstraintObservationHead(ref,dependencies))==null) }
        contexts.forEach { (id,revision) -> put(ConstraintSubjectRef("context",id,revision,true),links[id].orEmpty()) }
        intakes.forEach { (id,revision) -> put(ConstraintSubjectRef("intake",id,revision,true)) }
        connection.createStatement().use { s ->
            s.executeQuery("SELECT c.commitment_id,c.revision,c.intake_id,c.intake_revision,c.context_id,c.context_revision FROM commitment_revisions c " +
                "JOIN (SELECT commitment_id,max(revision) revision FROM commitment_revisions GROUP BY commitment_id) h " +
                "ON c.commitment_id=h.commitment_id AND c.revision=h.revision ORDER BY c.commitment_id LIMIT 1001").use { r ->
                var count=0
                while(r.next()) {
                    require(++count<=1000)
                    val context=r.getString(5);val intake=r.getString(3)
                    put(ConstraintSubjectRef("commitment",r.getString(1),r.getInt(2),true),listOf(intake,r.getInt(4).toString(),intakes.getValue(intake).toString(),
                        context.orEmpty(),r.getObject(6)?.toString().orEmpty(),context?.let { contexts.getValue(it).toString() }.orEmpty())+
                        context?.let { links[it] }.orEmpty())
                }
            }
        }
        return result
    }
}
