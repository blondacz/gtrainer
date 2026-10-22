package com.gtrainer

import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class PersonalSignalTransitionsTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private val config=PersonalSignalConfiguration("UTC",PersonalSignalSelector("hrv","ms","synthetic","fixture","rmssd","overnight"),
        PersonalSignalBaseline(1,1,1.0,1),"low","absolute_limit",10.0,15.0,1,1,3600)
    private val rule=PersonalSignalRule(id,1,true,config,"2020-06-01T00:00:00Z")
    private val at=Instant.parse("2020-06-04T12:00:00Z")
    private fun report(values: List<Double?>,end: Instant=at,usedRule: PersonalSignalRule=rule): PersonalSignalReport {
        val last=end.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val first=last.minusDays(values.size.toLong()-1)
        val samples=values.mapIndexedNotNull { index,value -> value?.let {
            ScopedSignalSample(requireNotNull(usedRule.configuration).selector.desiredIdentity(),first.plusDays(index.toLong()).toString(),it,"sample-$index")
        } }
        return PersonalSignalRules.evaluate(usedRule,PersonalSignalQuery(first.plusDays(1).toString(),last.toString()),end,samples)
    }
    private fun advance(view: PersonalSignalReport,prior: PersonalSignalState?=null,source: Long=1)=
        PersonalSignalTransitions.advance(view,prior,source,Instant.parse(view.asOf))

    @Test fun `unknown history cannot manufacture an unalerted episode but verified reset establishes anchor`() {
        val unknown=advance(report(listOf(20.0,5.0,5.0)))
        assertEquals("unknown_prior_state",unknown.status);assertNull(unknown.candidateEpisodeId)
        val current=advance(report(listOf(20.0,20.0,5.0)))
        assertEquals("admission_candidate",current.status)
        assertFalse(current.state!!.projected.emitted)
        assertNull(current.state.projected.lastEmittedUtc)
    }

    @Test fun `acknowledged continuous episode never repeats after cooldown and same day replacement keeps admission`() {
        val view=report(listOf(20.0,20.0,5.0))
        val admitted=PersonalSignalTransitions.acknowledge(advance(view),at)
        val later=at.plusSeconds(7200)
        val again=advance(view.copy(asOf=later.toString()),admitted)
        assertEquals("episode_already_emitted",again.status);assertNull(again.candidateEpisodeId)
        assertEquals(at.toString(),again.state!!.projected.lastEmittedUtc)
    }

    @Test fun `verified reset retrigger waits and cooldown expiry still requires current trigger`() {
        val slowRule=rule.copy(configuration=config.copy(cooldownSeconds=259200))
        val prior=PersonalSignalState(id,1,"2020-06-02",PersonalSignalEpisode(true),
            PersonalSignalEpisode(true,"a".repeat(64),true,"2020-06-02T12:00:00Z"),1)
        val newView=report(listOf(20.0,5.0,20.0,5.0),at,slowRule)
        val pending=advance(newView,prior)
        assertEquals("cooldown_wait",pending.status);assertNull(pending.candidateEpisodeId)
        val expired=at.plusSeconds(86400)
        val ready=advance(report(listOf(20.0,5.0,20.0,5.0,5.0),expired,slowRule),pending.state)
        assertEquals("admission_candidate",ready.status)
        val band=advance(report(listOf(20.0,5.0,20.0,5.0,12.0),expired,slowRule),pending.state)
        assertEquals("current_trigger_not_satisfied",band.status);assertNull(band.candidateEpisodeId)
        val unknown=advance(report(listOf(20.0,5.0,20.0,5.0,null),expired,slowRule),pending.state)
        assertNull(unknown.candidateEpisodeId)
    }

    @Test fun `missing and hysteresis days do not close an established episode`() {
        val prior=PersonalSignalState(id,1,"2020-06-03",PersonalSignalEpisode(true),
            PersonalSignalEpisode(true,"b".repeat(64),true,at.minusSeconds(86400).toString()),1)
        for(value in listOf(null,12.0)) {
            val result=advance(report(listOf(20.0,20.0,value)),prior)
            assertEquals(prior.projected,result.state!!.projected);assertNull(result.candidateEpisodeId)
        }
    }

    @Test fun `changed imports policy missing checkpoint or backward time require reevaluation and preserve facts`() {
        val previous=PersonalSignalTransitions.acknowledge(advance(report(listOf(20.0,20.0,5.0))),at)
        val view=report(listOf(20.0,20.0,5.0))
        for(prior in listOf(previous.copy(sourceRevision=2),previous.copy(ruleRevision=2),previous.copy(date="2020-06-05"),previous.copy(date="2020-06-01"))) {
            val result=advance(view,prior)
            assertEquals("needs_reevaluation",result.status);assertEquals(prior,result.state);assertNull(result.candidateEpisodeId)
        }
        assertEquals("needs_reevaluation",advance(report(listOf(20.0,20.0,20.0)),previous).status)
    }

    @Test fun `historical replay creates no candidate or state and acknowledgements require actual candidate`() {
        val view=report(listOf(20.0,20.0,5.0)).let { it.copy(query=PersonalSignalQuery(it.query.oldest,"2020-06-03"),days=it.days.dropLast(1)) }
        val result=advance(view)
        assertEquals("historical_inspection_only",result.status);assertNull(result.state)
        assertFailsWith<IllegalArgumentException> { PersonalSignalTransitions.acknowledge(result,at) }
    }

    @Test fun `durable continuation requires complete trigger and reset persistence lookback`() {
        val persistent=rule.copy(configuration=config.copy(triggerPersistenceDays=3,resetPersistenceDays=2))
        val prior=PersonalSignalState(id,1,"2020-06-03",PersonalSignalEpisode(true),PersonalSignalEpisode(true),1)
        assertEquals("needs_reevaluation",advance(report(listOf(5.0,5.0,5.0),at,persistent),prior).status)
        val complete=advance(report(listOf(20.0,5.0,5.0,5.0),at,persistent),prior)
        assertEquals("admission_candidate",complete.status)
        assertFailsWith<IllegalArgumentException> { PersonalSignalTransitions.acknowledge(complete,at.minusSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { PersonalSignalTransitions.acknowledge(complete,Instant.parse("2020-06-05T00:00:00Z")) }
        val later=at.plusSeconds(86400*2)
        // Warm-up must precede June 4 (the first new transition), not just June 6.
        val tooShort=report(listOf(20.0,20.0,5.0,5.0,5.0),later,persistent)
        assertEquals("2020-06-03",tooShort.query.oldest)
        assertEquals("needs_reevaluation",advance(tooShort,prior).status)
    }
}
