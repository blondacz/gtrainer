package com.gtrainer

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AthleteContextRetrievalTest {
    private val asOf = LocalDate.parse("2025-01-10")

    private fun context(id: Int, category: String = "note", observed: String = "2025-01-01",
                        from: String? = null, until: String? = null, sport: String? = null,
                        activity: String? = null, review: String? = null, retired: Boolean = false,
                        revision: Int = 1, restrictionKind: String? = null, restrictionValue: String? = null) =
        AthleteContext("00000000-0000-0000-0000-${id.toString().padStart(12, '0')}", revision, category,
            "user_report", "athlete", "authenticated_user", observed, from, until, sport, activity, review,
            "synthetic-$id", retired, restrictionKind, restrictionValue, if (restrictionKind == "maximum_duration") "minutes" else null)

    @Test fun `retrieval applies date sport activity and review filters then orders optional context deterministically`() {
        val entries = listOf(
            context(1, "restriction", from = "2025-01-01", until = "2025-01-31", sport = "Run", activity = "a1", review = "r1",
                restrictionKind = "blocked_sport", restrictionValue = "Run"),
            context(2, "goal", observed = "2025-01-09"),
            context(3, "preference", observed = "2025-01-08"),
            context(4, "note", until = "2025-01-09"),
            context(5, "note", from = "2025-01-11"),
            context(6, "note", sport = "Ride"),
            context(7, "note", activity = "a2"),
            context(8, "note", review = "r2"),
            context(9, "note", observed = "2025-01-11"),
            context(10, "note", retired = true),
            context(11, "note", revision = 1),
            context(11, "goal", revision = 2, observed = "2025-01-07"),
        )
        val result = retrieveAthleteContext(entries,
            ContextRetrievalQuery(asOf, sport = "Run", activityId = "a1", reviewId = "r1", optionalLimit = 1))
        assertEquals(listOf("00000000-0000-0000-0000-000000000001"), result.mandatoryRestrictions.map { it.contextId })
        assertEquals(listOf("00000000-0000-0000-0000-000000000002"), result.optionalContexts.map { it.contextId })
        assertFalse(result.mandatoryOverflow)
        assertFalse(result.hasUnstructuredRestrictions)
    }

    @Test fun `all applicable restrictions bypass optional ranking and mandatory overflow is never truncated`() {
        val mandatory = listOf(
            context(1, "restriction", restrictionKind = "blocked_activity", restrictionValue = "running"),
            context(2, "restriction", restrictionKind = "maximum_duration", restrictionValue = "30"),
            context(3, "restriction"), // legacy unstructured restriction: surface, never infer from content
        )
        val optional = listOf(context(4, "goal", observed = "2025-01-09"), context(5, "note", observed = "2025-01-08"))
        val result = retrieveAthleteContext(mandatory + optional,
            ContextRetrievalQuery(asOf, optionalLimit = 1, hardPacketLimit = 2))
        assertEquals(3, result.mandatoryRestrictions.size)
        assertEquals(1, result.optionalContexts.size)
        assertTrue(result.mandatoryOverflow)
        assertTrue(result.hasUnstructuredRestrictions)
        assertTrue(result.reviewBlocked)
    }

    @Test fun `conflicting blocked and allowed rules remain included and block review`() {
        val entries = listOf(
            context(1, "restriction", restrictionKind = "blocked_activity", restrictionValue = "Running"),
            context(2, "restriction", restrictionKind = "allowed_activity", restrictionValue = "running"),
        )
        val result = retrieveAthleteContext(entries, ContextRetrievalQuery(asOf, optionalLimit = 0, hardPacketLimit = 10))
        assertEquals(2, result.mandatoryRestrictions.size)
        assertEquals(listOf("blocked_and_allowed"), result.conflicts.map { it.reason })
        assertEquals("restriction_conflict", result.blockedReasons.single())
        assertTrue(result.reviewBlocked)
    }

    @Test fun `different maximum duration values conflict but numerically equal values do not`() {
        val blocked = context(1, "restriction", restrictionKind = "maximum_duration", restrictionValue = "30")
        val longer = context(2, "restriction", restrictionKind = "maximum_duration", restrictionValue = "45")
        val equivalent = context(3, "restriction", restrictionKind = "maximum_duration", restrictionValue = "30.0")
        assertEquals("different_maximum_duration", retrieveAthleteContext(listOf(blocked, longer),
            ContextRetrievalQuery(asOf, hardPacketLimit = 10)).conflicts.single().reason)
        assertTrue(retrieveAthleteContext(listOf(blocked, equivalent), ContextRetrievalQuery(asOf, hardPacketLimit = 10)).conflicts.isEmpty())
    }

    @Test fun `unconfigured hard packet bound blocks review even without conflicts`() {
        val result = retrieveAthleteContext(listOf(context(1)), ContextRetrievalQuery(asOf))
        assertEquals(listOf("packet_budget_unconfigured"), result.blockedReasons)
        assertTrue(result.reviewBlocked)
    }
}
