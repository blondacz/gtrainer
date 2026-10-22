package com.gtrainer

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopedContextRetrievalTest {
    private fun view(id: String, category: String = "restriction", observed: String = "2026-10-01", from: String? = null,
                     until: String? = null, sport: String? = null, activity: String? = null, review: String? = null,
                     kind: String? = "blocked_sport", value: String? = "run", retired: Boolean = false,
                     resolution: String = "unresolved", due: String? = null, changed: Boolean = false, revision: Int = 1) =
        ContextLifecycleView(AthleteContext(id, revision, category, "user_report", "author", "user", observed, from, until,
            sport, activity, review, "synthetic", retired, kind, value,if(kind=="maximum_duration") "minutes" else null), ContextLifecycle(resolution, due), "current", false, changed, emptyList())

    private fun target(from: String, until: String = from, sport: String? = null, activity: String? = null, review: String? = null) =
        ContextRetrievalTarget(LocalDate.parse(from), LocalDate.parse(until), sport, activity, review)

    @Test fun `separates evidence and amendment targets and keeps tomorrow restriction`() {
        val item = view("tomorrow", from = "2026-10-16", until = "2026-10-16")
        val result = retrieveScopedAthleteContext(listOf(item), ScopedContextRetrievalQuery(LocalDate.parse("2026-10-15"),
            listOf(target("2026-10-15")), listOf(target("2026-10-16")), mandatoryLimit = 5))
        assertTrue(result.evidence.mandatoryRestrictions.isEmpty())
        assertEquals(listOf("tomorrow"), result.amendments.mandatoryRestrictions.map { it.contextId })
    }

    @Test fun `selected tuple targets do not broaden across days sports or scoped identities`() {
        val items = listOf(view("one", from = "2026-10-01", until = "2026-10-10", sport = "run", activity = "a", review = "r"),
            view("between", from = "2026-10-02", until = "2026-10-02", sport = "bike", activity = "b", review = "r"),
            view("wrong-activity", from = "2026-10-01", until = "2026-10-10", sport = "run", activity = "other", review = "r"))
        val result = retrieveScopedAthleteContext(items, ScopedContextRetrievalQuery(LocalDate.parse("2026-10-10"),
            listOf(target("2026-10-01", sport = "run", activity = "a", review = "r"), target("2026-10-03", sport = "bike", activity = "b", review = "r")), mandatoryLimit = 10))
        assertEquals(listOf("one"), result.evidence.mandatoryRestrictions.map { it.contextId })
    }

    @Test fun `latest canonical revision is selected before cutoff and old observation remains attributed`() {
        val old = view("same", observed = "2020-01-01", from = "2020-01-01", until = "2030-01-01")
        val newest = view("same", observed = "2026-10-16", from = "2020-01-01", until = "2030-01-01", revision = 2)
        val result = retrieveScopedAthleteContext(listOf(old, newest), ScopedContextRetrievalQuery(LocalDate.parse("2026-10-15"), listOf(target("2026-10-15")), mandatoryLimit = 5))
        assertTrue(result.evidence.mandatoryRestrictions.isEmpty())
        val retained=retrieveScopedAthleteContext(listOf(old),ScopedContextRetrievalQuery(LocalDate.parse("2026-10-15"),listOf(target("2026-10-15")),mandatoryLimit=5))
        assertEquals("2020-01-01",retained.evidence.mandatoryRestrictions.single().observedOn)
    }

    @Test fun `resolved restrictions deactivate and source changes and due unresolved illness clarify`() {
        val resolved = view("resolved", resolution = "resolved")
        val illness = view("ill", category = "symptom", kind = null, value = null, due = "2026-10-15", changed = true)
        val result = retrieveScopedAthleteContext(listOf(resolved, illness), ScopedContextRetrievalQuery(LocalDate.parse("2026-10-15"), listOf(target("2026-10-15")), optionalLimit = 0, mandatoryLimit = 5))
        assertTrue(result.evidence.mandatoryRestrictions.isEmpty())
        assertEquals(listOf("ill"), result.evidence.mandatoryClarifications.map { it.context.contextId })
        assertEquals(0, result.evidence.optionalContexts.size)
        assertTrue(result.evidence.blocked)
    }

    @Test fun `validates target length limits and budgets`() {
        assertFailsWith<IllegalArgumentException> { target("2026-01-01", "2027-01-02") }
        assertFailsWith<IllegalArgumentException> { ScopedContextRetrievalQuery(LocalDate.now(), emptyList(), optionalLimit = 1001) }
        assertFalse(retrieveScopedAthleteContext(emptyList(), ScopedContextRetrievalQuery(LocalDate.now(), emptyList(), mandatoryLimit = 0)).evidence.blocked)
        assertTrue(retrieveScopedAthleteContext(emptyList(), ScopedContextRetrievalQuery(LocalDate.now(), emptyList())).evidence.blocked)
        assertFailsWith<IllegalArgumentException> { ScopedContextRetrievalQuery(LocalDate.now(),List(1001) { target("2026-10-15") }) }
        assertFailsWith<IllegalArgumentException> { target("2026-10-16","2026-10-15") }
        assertFailsWith<IllegalArgumentException> { target("2026-10-15",activity="") }
    }

    @Test fun `expired illness and retired restrictions clarify future targets without current instructions`() {
        val expired=view("ill",category="symptom",until="2026-10-10",kind=null,value=null,sport="Run")
        val retired=view("withdrawn",from="2026-10-01",until="2026-10-30",sport="Run",retired=true)
        val result=retrieveScopedAthleteContext(listOf(expired,retired),ScopedContextRetrievalQuery(LocalDate.parse("2026-10-15"),
            listOf(target("2026-10-15",sport="Ride")),listOf(target("2026-10-16",sport="Run")),optionalLimit=0,mandatoryLimit=5))
        assertTrue(result.evidence.mandatoryClarifications.isEmpty())
        assertFalse(result.evidence.blocked)
        assertTrue(result.amendments.mandatoryRestrictions.isEmpty())
        assertEquals(setOf("ill","withdrawn"),result.amendments.mandatoryClarifications.map { it.context.contextId }.toSet())
        assertTrue(result.amendments.mandatoryClarifications.all { it.state=="historical" && it.needsReview })
        assertTrue(result.amendments.blocked)
    }

    @Test fun `open valid restrictions remain enforceable while unknown symptom applicability clarifies`() {
        val restriction=view("restriction",sport="Run")
        val symptom=view("ill",category="symptom",kind=null,value=null,sport="Run")
        val result=retrieveScopedAthleteContext(listOf(restriction,symptom),ScopedContextRetrievalQuery(LocalDate.parse("2026-10-15"),emptyList(),
            listOf(target("2026-10-16",sport="Run")),mandatoryLimit=5))
        assertEquals(listOf("restriction"),result.amendments.mandatoryRestrictions.map { it.contextId })
        assertEquals(listOf("ill"),result.amendments.mandatoryClarifications.map { it.context.contextId })
        val future=view("not-yet",category="symptom",from="2026-10-20",kind=null,value=null,sport="Run")
        val before=retrieveScopedAthleteContext(listOf(future),ScopedContextRetrievalQuery(LocalDate.parse("2026-10-15"),emptyList(),
            listOf(target("2026-10-16",sport="Run")),mandatoryLimit=0))
        assertFalse(before.amendments.blocked)
    }

    @Test fun `explicit resolution deactivates future blockers but retains attributed historical context`() {
        val resolved=view("resolved",resolution="resolved",retired=true,changed=true)
        val result=retrieveScopedAthleteContext(listOf(resolved),ScopedContextRetrievalQuery(LocalDate.parse("2026-10-15"),emptyList(),
            listOf(target("2026-10-16")),mandatoryLimit=0))
        assertFalse(result.amendments.blocked)
        assertTrue(result.amendments.mandatoryRestrictions.isEmpty())
        assertTrue(result.amendments.mandatoryClarifications.isEmpty())
        assertEquals("resolved",result.amendments.optionalContexts.single().lifecycle.resolution)
        assertTrue(result.amendments.optionalContexts.single().sourceChanged)
    }

    @Test fun `conflicts outside selected target dates do not block but shared interval conflicts do`() {
        val blocked=view("a",from="2026-10-01",until="2026-10-20")
        val allowed=view("b",from="2026-10-15",until="2026-10-31",kind="allowed_sport")
        val outside=ScopedContextRetrievalQuery(LocalDate.parse("2026-10-31"),listOf(target("2026-10-05"),target("2026-10-25")),mandatoryLimit=10)
        val input=listOf(blocked,allowed)
        assertTrue(retrieveScopedAthleteContext(input,outside).evidence.conflicts.isEmpty())
        val inside=outside.copy(evidenceTargets=listOf(target("2026-10-16")))
        val result=retrieveScopedAthleteContext(input,inside)
        assertEquals(listOf("blocked_and_allowed"),result.evidence.conflicts.map { it.reason })
        assertTrue(result.evidence.blockedReasons.contains("restriction_conflict"))
        assertEquals(result,retrieveScopedAthleteContext(input.reversed(),inside))
        assertEquals(blocked,input.first())
    }

    @Test fun `maximum duration conflict never chooses stricter value and equivalent numbers remain compatible`() {
        val first=view("a",kind="maximum_duration",value="30")
        val second=view("b",kind="maximum_duration",value="45")
        val query=ScopedContextRetrievalQuery(LocalDate.parse("2026-10-15"),listOf(target("2026-10-15")),mandatoryLimit=5)
        val conflict=retrieveScopedAthleteContext(listOf(first,second),query).evidence
        assertEquals(2,conflict.mandatoryRestrictions.size)
        assertEquals("different_maximum_duration",conflict.conflicts.single().reason)
        val same=second.copy(context=second.context.copy(restrictionValue="30.0"))
        assertFalse(retrieveScopedAthleteContext(listOf(first,same),query).evidence.blocked)
    }

    @Test fun `mandatory overflow and malformed restrictions never disappear through optional ranking`() {
        val entries=listOf(view("a",changed=true),view("b",kind=null,value=null)) +
            (1..5).map { view("note-$it",category="note",kind=null,value=null) }
        val query=ScopedContextRetrievalQuery(LocalDate.parse("2026-10-15"),listOf(target("2026-10-15")),optionalLimit=1,mandatoryLimit=1)
        val result=retrieveScopedAthleteContext(entries,query).evidence
        assertEquals(setOf("a","b"),result.mandatoryRestrictions.map { it.contextId }.toSet())
        assertEquals(listOf("a"),result.mandatoryClarifications.map { it.context.contextId })
        assertEquals(4,result.omittedOptionalCount)
        assertTrue(result.blockedReasons.containsAll(listOf("mandatory_context_overflow","unstructured_restriction","context_needs_confirmation")))
        assertFalse(retrieveScopedAthleteContext(entries,query.copy(mandatoryLimit=2)).evidence.blockedReasons.contains("mandatory_context_overflow"))
    }

    @Test fun `high fanout emits each conflicting pair once rather than once per target`() {
        val entries=(1..200).map { view(it.toString().padStart(3,'0'),observed="2020-01-01",kind="maximum_duration",value=it.toString()) }
        val targets=(0 until 1000).map { index ->
            val date=LocalDate.parse("2026-01-01").plusDays((index%365).toLong())
            ContextRetrievalTarget(date,date,activityId="session-$index")
        }
        val result=retrieveScopedAthleteContext(entries,ScopedContextRetrievalQuery(LocalDate.parse("2026-12-31"),targets,mandatoryLimit=200)).evidence
        assertEquals(19_900,result.conflicts.size)
        assertEquals(19_900,result.conflicts.distinct().size)
        assertEquals(200,result.mandatoryRestrictions.size)
    }
}
