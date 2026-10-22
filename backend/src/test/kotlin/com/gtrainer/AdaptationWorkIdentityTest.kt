package com.gtrainer

import kotlin.test.*

class AdaptationWorkIdentityTest {
    private val plan=PlanRef("11111111-1111-4111-8111-111111111111",1)
    private val family=AdaptationFamily(plan,2,"UTC",plan.planId)
    private val a=AdaptationReasonRef("a".repeat(64),1,"b".repeat(64))
    private val b=AdaptationReasonRef("c".repeat(64),2,"d".repeat(64))
    private val signal=AdaptationPolicyRef("personal_signal","22222222-2222-4222-8222-222222222222",1)
    private val workload=AdaptationPolicyRef("extra_workload","33333333-3333-4333-8333-333333333333",1)
    private fun work()=AdaptationWorkVersion(family,ScopedDateRange("2026-10-16","2026-10-17"),listOf(a,b),listOf(signal,workload),"e".repeat(64))

    @Test fun rollingDatesChangeExactWorkButNotStableFamily() {
        val first=work();val next=first.copy(horizon=ScopedDateRange("2026-10-17","2026-10-18"))
        assertEquals(first.family.key(),next.family.key());assertNotEquals(first.key(),next.key())
        assertNotEquals(family.key(),family.copy(plan=plan.copy(revision=2)).key())
        assertNotEquals(family.key(),family.copy(horizonDays=3).key())
        assertNotEquals(family.key(),family.copy(timeZone="Europe/Prague").key())
    }

    @Test fun materialRevisionsPoliciesAndDependenciesChangeWorkNotFamily() {
        val first=work()
        for(next in listOf(first.copy(reasons=listOf(a.copy(revision=2),b)),
            first.copy(policies=listOf(signal.copy(revision=2),workload)),first.copy(evidenceDependencySha256="f".repeat(64)))) {
            assertEquals(first.family.key(),next.family.key());assertNotEquals(first.key(),next.key())
        }
    }

    @Test fun orderIsCanonicalAndAdmissionRoutingMetadataAreNotInputs() {
        val first=work()
        assertEquals(first.key(),first.copy(reasons=first.reasons.reversed(),policies=first.policies.reversed()).key())
        assertEquals(first.family.key(),family.key())
        val encoded=ScopedEvidencePreparation.json.encodeToString(AdaptationWorkVersion.serializer(),first)
        for(field in listOf("jobId","admittedUtc","sourceRevision","selectionGeneration","purpose","displayRange")) assertFalse(encoded.contains(field))
    }

    @Test fun duplicateReferencesWrongOwnerAndInconsistentHorizonFailClosed() {
        assertFailsWith<IllegalArgumentException> { work().copy(reasons=listOf(a,a)) }
        assertFailsWith<IllegalArgumentException> { work().copy(policies=listOf(signal,signal)) }
        assertFailsWith<IllegalArgumentException> { work().copy(horizon=ScopedDateRange("2026-10-16","2026-10-18")) }
        assertFailsWith<IllegalArgumentException> { family.copy(policyOwnerId=signal.ruleId) }
    }
}
