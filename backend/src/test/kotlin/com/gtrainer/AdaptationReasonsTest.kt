package com.gtrainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AdaptationReasonsTest {
    private val jobA="11111111-1111-4111-8111-111111111111"
    private val jobB="22222222-2222-4222-8222-222222222222"
    private fun hash(char: Char) = char.toString().repeat(64)
    private fun observation(
        kind: String = "planned_range", condition: String = "active",
        material: String = hash('a'), dependency: String = hash('b')
    ) = AdaptationReasonObservation(hash('c'), kind, hash('d'), condition, material, dependency)

    @Test fun exactObservationIsIdempotentAndFirstEpisodeNeedsAdmissionOnlyOnce() {
        val first = AdaptationReasons.project(null, observation())
        assertEquals(first, AdaptationReasons.project(first, observation()))
        assertTrue(AdaptationReasons.requiresAdmission(first, emptyList()))
        val receipt = AdaptationAdmission(first.reasonId, first.episode, jobA, "2025-01-01T00:00:00Z")
        assertFalse(AdaptationReasons.requiresAdmission(first, listOf(receipt)))
    }

    @Test fun changedReadingAndDependencyReviseWithoutReadmission() {
        val first = AdaptationReasons.project(null, observation())
        val receipt = AdaptationAdmission(first.reasonId, first.episode, jobA, "2025-01-01T00:00:00Z")
        for (changed in listOf(observation(material = hash('e')), observation(dependency = hash('f')))) {
            val next = AdaptationReasons.project(first, changed)
            assertEquals(first.episode, next.episode)
            assertEquals(first.revision + 1, next.revision)
            assertFalse(AdaptationReasons.requiresAdmission(next, listOf(receipt)))
        }
    }

    @Test fun unknownAndConfirmationStatesKeepEpisodeOpenAndDoNotReadmit() {
        for (state in listOf("unevaluable", "provisional", "needs_confirmation")) {
            val first = AdaptationReasons.project(null, observation())
            val receipt = AdaptationAdmission(first.reasonId, first.episode, jobA, "2025-01-01T00:00:00Z")
            val uncertain = AdaptationReasons.project(first, observation(condition = state, dependency = hash('f')))
            assertTrue(uncertain.open)
            assertFalse(AdaptationReasons.requiresAdmission(uncertain, listOf(receipt)))
            val active = AdaptationReasons.project(uncertain, observation(material = hash('e')))
            assertEquals(first.episode, active.episode)
            assertFalse(AdaptationReasons.requiresAdmission(active, listOf(receipt)))
        }
    }

    @Test fun onlyClearClosesEpisodeAndRetriggerAdmitsNewEpisode() {
        val first = AdaptationReasons.project(null, observation())
        val receipt = AdaptationAdmission(first.reasonId, first.episode, jobA, "2025-01-01T00:00:00Z")
        val clear = AdaptationReasons.project(first, observation(condition = "clear"))
        assertFalse(clear.open)
        val retrigger = AdaptationReasons.project(clear, observation())
        assertEquals(first.episode + 1, retrigger.episode)
        assertTrue(AdaptationReasons.requiresAdmission(retrigger, listOf(receipt)))
    }

    @Test fun changedConstraintCreatesEpisodeOnlyWhenMaterialChanges() {
        val first = AdaptationReasons.project(null, observation(kind = "changed_constraint"))
        val dependencyOnly = AdaptationReasons.project(first, observation(kind = "changed_constraint", dependency = hash('f')))
        assertEquals(first.episode, dependencyOnly.episode)
        val material = AdaptationReasons.project(dependencyOnly, observation(kind = "changed_constraint", material = hash('e')))
        assertEquals(first.episode + 1, material.episode)
        assertTrue(AdaptationReasons.requiresAdmission(material, emptyList()))
    }

    @Test fun identityMismatchAndCounterOverflowAreRefused() {
        val first = AdaptationReasons.project(null, observation())
        assertFailsWith<IllegalArgumentException> { AdaptationReasons.project(first, observation().copy(subjectId = hash('e'))) }
        assertFailsWith<IllegalStateException> {
            AdaptationReasons.project(first.copy(episode = Long.MAX_VALUE,open=false,condition="clear"), observation(material = hash('e')))
        }
        assertFailsWith<IllegalStateException> {
            AdaptationReasons.project(first.copy(revision = Long.MAX_VALUE), observation(material = hash('e')))
        }
    }

    @Test fun admissionMetadataDoesNotAffectProjectedHead() {
        val head = AdaptationReasons.project(null, observation())
        val one = AdaptationAdmission(head.reasonId, head.episode, jobA, "2025-01-01T00:00:00Z")
        val two = AdaptationAdmission(head.reasonId, head.episode, jobB, "2026-06-01T00:00:00Z")
        assertEquals(head, AdaptationReasons.project(null, observation()))
        assertTrue(AdaptationReasons.requiresAdmission(head, emptyList()))
        assertFalse(AdaptationReasons.requiresAdmission(head, listOf(one, two)))
    }
}
