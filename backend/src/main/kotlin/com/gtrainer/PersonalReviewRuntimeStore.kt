package com.gtrainer

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** SQL-only ledger. Caller owns synchronization and transaction boundaries. */
internal class PersonalReviewRuntimeStore(private val connection: Connection) {
    private val json get() = ScopedEvidencePreparation.json

    fun seed() {
        connection.prepareStatement("INSERT OR IGNORE INTO personal_review_runtime_owner(singleton,epoch,generation) VALUES(1,?,0)").use {
            it.setString(1, UUID.randomUUID().toString()); it.executeUpdate()
        }
    }

    fun epoch(): String = connection.createStatement().use { s -> s.executeQuery("SELECT epoch FROM personal_review_runtime_owner WHERE singleton=1").use { check(it.next()); it.getString(1) } }

    fun advanceEpoch(): String {
        val next = UUID.randomUUID().toString()
        connection.prepareStatement("UPDATE personal_review_runtime_owner SET epoch=? WHERE singleton=1").use { it.setString(1,next); check(it.executeUpdate()==1) }
        connection.prepareStatement("UPDATE personal_review_runtime_claims SET phase='UNKNOWN',publication_allowed=0 WHERE phase<>'STOP_CONFIRMED'").use { it.executeUpdate() }
        return next
    }

    fun reserve(expectedEpoch:String,binding:ScopedExecutionBinding,resource:PersonalResourceIdentity,at:Instant,deadline:Instant):PersonalReviewClaim? {
        if(epoch()!=expectedEpoch || deadline<=at || binding.profile !in ScopedProfileAdmission.profiles) return null
        if(connection.prepareStatement("SELECT 1 FROM personal_review_runtime_claims WHERE domain_id=? AND phase<>'STOP_CONFIRMED'").use { it.setString(1,resource.domain);it.executeQuery().use(ResultSet::next) }) return null
        if(connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM personal_review_runtime_claims").use { r->r.next();r.getLong(1)>=1000 } }) return null
        val generation=connection.prepareStatement("SELECT generation FROM personal_review_runtime_owner WHERE singleton=1 AND epoch=?").use { it.setString(1,expectedEpoch);it.executeQuery().use { r->if(!r.next()) return null; Math.addExact(r.getLong(1),1) } }
        connection.prepareStatement("UPDATE personal_review_runtime_owner SET generation=? WHERE singleton=1 AND epoch=?").use { it.setLong(1,generation);it.setString(2,expectedEpoch);if(it.executeUpdate()!=1)return null }
        val claim=PersonalReviewClaim(UUID.randomUUID().toString(),expectedEpoch,generation,binding.executionId,binding.generation,binding.profile,resource,binding.consentSha256,binding.dependencyBindingSha256,at.toString(),deadline.toString())
        insert(claim,PersonalRuntimePhase.RESERVED,true,0); return claim
    }

    fun get(claimId:String):PersonalRuntimeLease? = select(claimId)
    /** Read-only recovery discovery includes expired/old-epoch claims and deleted execution sources. */
    fun unresolved(): List<PersonalRuntimeLease> {
        val ids=connection.createStatement().use { statement ->
            statement.executeQuery("SELECT claim_id FROM personal_review_runtime_claims WHERE phase<>'STOP_CONFIRMED' ORDER BY claim_id LIMIT 1001").use { rows ->
                buildList { while(rows.next()) { check(size<1000);add(rows.getString(1)) } }
            }
        }
        return ids.map { requireNotNull(select(it)) }
    }
    fun beginStart(claim:PersonalReviewClaim,at:Instant)=transition(claim,at,PersonalRuntimePhase.RESERVED,PersonalRuntimePhase.START_INTENT)
    fun claimExecution(claim:PersonalReviewClaim,at:Instant)=transition(claim,at,PersonalRuntimePhase.START_INTENT,PersonalRuntimePhase.EXECUTING)
    fun authorizeAttempt(claim:PersonalReviewClaim,number:Int,at:Instant):Boolean {
        val lease=live(claim,at)?:return false
        if(lease.phase!=PersonalRuntimePhase.EXECUTING || number !in 1..2 || number!=lease.attempts+1)return false
        return update(claim,lease.phase,true,number)
    }
    fun publicationCurrent(claim:PersonalReviewClaim,at:Instant):Boolean = live(claim,at)?.let { it.phase==PersonalRuntimePhase.EXECUTING && it.attempts>=1 }==true
    fun consumePublication(claim:PersonalReviewClaim,at:Instant):Boolean = publicationCurrent(claim,at) && update(claim,PersonalRuntimePhase.EXECUTING,false,requireNotNull(get(claim.claimId)).attempts)

    fun revoke(claim:PersonalReviewClaim):PersonalRuntimeLease? {
        val l=select(claim.claimId)?:return null;if(l.claim!=claim)return null
        if(l.phase!=PersonalRuntimePhase.STOP_CONFIRMED) update(claim,PersonalRuntimePhase.STOPPING,false,l.attempts)
        return select(claim.claimId)
    }
    fun revokeExecution(executionId:String) { connection.prepareStatement("UPDATE personal_review_runtime_claims SET phase='STOPPING',publication_allowed=0 WHERE execution_id=? AND phase<>'STOP_CONFIRMED'").use { it.setString(1,executionId);it.executeUpdate() } }

    fun confirmStop(authorityEpoch:String,claim:PersonalReviewClaim,evidence:PersonalStopEvidence,at:Instant):Boolean {
        val l=select(claim.claimId)?:return false
        if(epoch()!=authorityEpoch || l.claim!=claim || evidence.claim!=claim || l.phase !in setOf(PersonalRuntimePhase.STOPPING,PersonalRuntimePhase.UNKNOWN) || !evidence.physicallyStopped || !evidence.lateStartsFenced || evidence.observedAt<Instant.parse(claim.reservedUtc) || evidence.observedAt>at)return false
        return update(claim,PersonalRuntimePhase.STOP_CONFIRMED,false,l.attempts)
    }
    fun unknown(authorityEpoch:String,claim:PersonalReviewClaim):Boolean {
        val l=select(claim.claimId)?:return false
        return epoch()==authorityEpoch && l.claim==claim && l.phase!=PersonalRuntimePhase.STOP_CONFIRMED && update(claim,PersonalRuntimePhase.UNKNOWN,false,l.attempts)
    }

    private fun transition(c:PersonalReviewClaim,at:Instant,from:PersonalRuntimePhase,to:PersonalRuntimePhase):Boolean {
        val l=live(c,at)?:return false
        return l.phase==from && update(c,to,true,l.attempts)
    }
    private fun live(c:PersonalReviewClaim,at:Instant):PersonalRuntimeLease? = select(c.claimId)?.takeIf { it.claim==c && it.claim.ownerEpoch==epoch() && it.publicationAllowed && it.phase !in setOf(PersonalRuntimePhase.STOPPING,PersonalRuntimePhase.STOP_CONFIRMED,PersonalRuntimePhase.UNKNOWN) && at>=Instant.parse(c.reservedUtc) && at<Instant.parse(c.deadlineUtc) }
    private fun insert(c:PersonalReviewClaim,p:PersonalRuntimePhase,a:Boolean,n:Int) { connection.prepareStatement("INSERT INTO personal_review_runtime_claims(claim_id,domain_id,execution_id,claim_json,phase,publication_allowed,attempts) VALUES(?,?,?,?,?,?,?)").use { it.setString(1,c.claimId);it.setString(2,c.resource.domain);it.setString(3,c.executionId);it.setString(4,json.encodeToString(c));it.setString(5,p.name);it.setInt(6,if(a)1 else 0);it.setInt(7,n);it.executeUpdate() } }
    private fun update(c:PersonalReviewClaim,p:PersonalRuntimePhase,a:Boolean,n:Int):Boolean=connection.prepareStatement("UPDATE personal_review_runtime_claims SET phase=?,publication_allowed=?,attempts=? WHERE claim_id=? AND claim_json=?").use { it.setString(1,p.name);it.setInt(2,if(a)1 else 0);it.setInt(3,n);it.setString(4,c.claimId);it.setString(5,json.encodeToString(c));it.executeUpdate()==1 }
    private fun select(id:String):PersonalRuntimeLease?=connection.prepareStatement("SELECT domain_id,execution_id,claim_json,phase,publication_allowed,attempts FROM personal_review_runtime_claims WHERE claim_id=?").use { it.setString(1,id);it.executeQuery().use { r->if(!r.next())null else { val c=json.decodeFromString<PersonalReviewClaim>(r.getString(3));check(c.claimId==id && c.resource.domain==r.getString(1) && c.executionId==r.getString(2));PersonalRuntimeLease(c,PersonalRuntimePhase.valueOf(r.getString(4)),when(r.getInt(5)){0->false;1->true;else->error("Invalid publication flag")},r.getInt(6).also { check(it in 0..2) }) } } }
}
