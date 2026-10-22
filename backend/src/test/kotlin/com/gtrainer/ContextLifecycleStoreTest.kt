package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextLifecycleStoreTest {
    private fun fixture(test: (Path, HistoryStore) -> Unit) {
        val dir = Files.createTempDirectory("synthetic-context-lifecycle-")
        val path = dir.resolve("history.sqlite3")
        try { HistoryStore(path).use { test(path, it) } }
        finally {
            Files.list(dir).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(dir)
        }
    }

    private fun context(category: String = "note", until: String? = null, content: String = "synthetic context") =
        AthleteContextRequest(category, "user_report", "synthetic author", "2026-10-01", applicableUntil = until, content = content,
            restrictionKind=if(category=="restriction") "blocked_sport" else null,restrictionValue=if(category=="restriction") "Run" else null)

    @Test fun `projector treats due boundary and end date inclusively and preserves historical safety review`() {
        val today = LocalDate.parse("2026-10-15")
        val note = AthleteContext("id", 1, "note", "user_report", "author", "authenticated_user", "2026-10-01",
            null, "2026-10-15", null, null, null, "synthetic", false)
        val due = projectContextLifecycle(note, ContextLifecycle(reviewDueOn = today.toString()), false, today)
        assertTrue(due.needsReview)
        assertEquals("needs_review", due.state)
        assertEquals("current",projectContextLifecycle(note,ContextLifecycle(),false,today).state)
        assertEquals("upcoming",projectContextLifecycle(note.copy(applicableFrom="2026-10-16",applicableUntil=null),ContextLifecycle(),false,today).state)
        assertTrue(projectContextLifecycle(note,ContextLifecycle(),true,today).needsReview)
        assertFalse(projectContextLifecycle(note, ContextLifecycle(reviewDueOn = "2026-10-16"), false, today).needsReview)
        val expired = note.copy(category = "symptom", applicableUntil = "2026-10-14")
        assertEquals("historical", projectContextLifecycle(expired, ContextLifecycle(), false, today).state)
        assertTrue(projectContextLifecycle(expired, ContextLifecycle(), false, today).needsReview)
        val retired = projectContextLifecycle(note.copy(category = "restriction", retired = true), ContextLifecycle(), false, today)
        assertTrue(retired.needsReview)
        assertEquals("historical", retired.state)
        assertEquals("resolved", projectContextLifecycle(expired, ContextLifecycle("resolved"), false, today).state)
        assertFalse(projectContextLifecycle(expired, ContextLifecycle("resolved"), false, today).needsReview)
    }

    @Test fun `missing lifecycle metadata defaults unresolved and expired or retired safety remains visible`() = fixture { _, store ->
        val expired = store.createContext(context("symptom", "2026-10-14"))
        val retired = store.createContext(context("restriction"))
        assertTrue(store.retireContext(retired.contextId))
        val items = store.contextLifecycle(LocalDate.parse("2026-10-15")).items.associateBy { it.context.contextId }
        for (id in listOf(expired.contextId, retired.contextId)) {
            assertEquals("unresolved", items.getValue(id).lifecycle.resolution)
            assertTrue(items.getValue(id).needsReview)
        }
    }

    @Test fun `lifecycle updates append same context identity and preserve content attribution and dates`() = fixture { path, store ->
        val original = store.createContext(context())
        val updated = store.updateContextLifecycle(original.contextId, ContextLifecycleUpdate(1, "resolved", null), LocalDate.parse("2026-10-15")).context
        assertEquals(original.copy(revision = 2), updated)
        assertEquals(listOf(2, 1), store.contextRevisions(original.contextId).map { it.revision })
        assertEquals(original.contextId, updated.contextId)
        assertEquals(original.content, updated.content)
        assertEquals(original.authorAttribution, updated.authorAttribution)
        assertEquals(original.enteredBy, updated.enteredBy)
        assertEquals(original.observedOn, updated.observedOn)
        HistoryStore(path).use { reopened ->
            val view=reopened.contextLifecycle(LocalDate.parse("2026-10-15")).items.single()
            assertEquals(updated,view.context)
            assertEquals("resolved",view.lifecycle.resolution)
        }
    }

    @Test fun `retirement carries lifecycle and exact intake provenance after source correction`() = fixture { _, store ->
        val original = store.createContext(context())
        val intake = store.createIntake(ContextIntakeRequest("synthetic source", "user_report", "source author", "2026-10-01"), java.time.Instant.EPOCH)
        store.associateIntake(intake.intakeId, ContextIntakeAssociation(1, original.contextId, 1))
        store.updateContextLifecycle(original.contextId, ContextLifecycleUpdate(1, "resolved", "2026-10-20"), LocalDate.parse("2026-10-15"))
        store.correctIntake(intake.intakeId, ContextIntakeCorrection(1, ContextIntakeRequest("corrected source", "user_report", "source author", "2026-10-01")), java.time.Instant.EPOCH)
        assertTrue(store.retireContext(original.contextId))
        val view = store.contextLifecycle(LocalDate.parse("2026-10-15")).items.single()
        assertEquals("resolved", view.lifecycle.resolution)
        assertEquals("2026-10-20", view.lifecycle.reviewDueOn)
        assertTrue(view.sourceChanged)
        assertTrue(view.needsReview)
        assertEquals(listOf(1,1,1), store.intakeLinks(intake.intakeId).map { it.intakeRevision })
        assertEquals(listOf(1,2,3), store.intakeLinks(intake.intakeId).map { it.contextRevision })
    }

    @Test fun `content correction resets resolution but preserves review date`() = fixture { _, store ->
        val original = store.createContext(context())
        store.updateContextLifecycle(original.contextId, ContextLifecycleUpdate(1, "resolved", "2026-10-20"), LocalDate.parse("2026-10-15"))
        val corrected = store.correctContext(original.contextId, context(content = "corrected synthetic context"))
        val view = store.contextLifecycle(LocalDate.parse("2026-10-15")).items.single()
        assertEquals(3, corrected.revision)
        assertEquals("unresolved", view.lifecycle.resolution)
        assertEquals("2026-10-20", view.lifecycle.reviewDueOn)
    }

    @Test fun `legacy null restriction details accept lifecycle revision without changing attribution`() = fixture { path, store ->
        val original = store.createContext(context("restriction"))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("UPDATE athlete_context SET restriction_kind=NULL,restriction_value=NULL,restriction_unit=NULL,entered_by='legacy recorded author' WHERE context_id='${original.contextId}' AND revision=1") }
        }
        val stored = store.contextRevisions(original.contextId).single()
        val revised = store.updateContextLifecycle(original.contextId, ContextLifecycleUpdate(1, "unresolved", null), LocalDate.parse("2026-10-15")).context
        assertEquals(stored.authorAttribution, revised.authorAttribution)
        assertEquals(stored.enteredBy, revised.enteredBy)
        assertEquals(stored.observedOn, revised.observedOn)
        assertEquals(null, revised.restrictionKind)
    }

    @Test fun `concurrent lifecycle compare and swap has exactly one winner`() = fixture { _, store ->
        val original = store.createContext(context())
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { n -> pool.submit<Boolean> {
                gate.await()
                try { store.updateContextLifecycle(original.contextId, ContextLifecycleUpdate(1, if (n == 1) "resolved" else "unresolved", null), LocalDate.parse("2026-10-15")); true }
                catch (_: ContextRevisionConflict) { false }
            } }
            gate.countDown()
            assertEquals(1, futures.count { it.get() })
            assertEquals(2, store.contextRevisions(original.contextId).size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `lifecycle insert failure rolls back appended revision and leaves connection usable`() = fixture { path, store ->
        val original = store.createContext(context())
        val source = store.createIntake(ContextIntakeRequest("synthetic source", "user_report", "source author", "2026-10-01"), java.time.Instant.EPOCH)
        val link = store.associateIntake(source.intakeId, ContextIntakeAssociation(1, original.contextId, 1))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_lifecycle_failure BEFORE INSERT ON context_lifecycle BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
            assertFailsWith<SQLException> { store.updateContextLifecycle(original.contextId, ContextLifecycleUpdate(1, "resolved", "2026-10-20"), LocalDate.parse("2026-10-15")) }
            assertEquals(listOf(original), store.contextRevisions(original.contextId))
            assertEquals(listOf(link), store.intakeLinks(source.intakeId))
            db.createStatement().use { it.execute("DROP TRIGGER synthetic_lifecycle_failure") }
        }
        val usable = store.updateContextLifecycle(original.contextId, ContextLifecycleUpdate(1, "resolved", null), LocalDate.parse("2026-10-15"))
        assertEquals(2, usable.context.revision)
        assertEquals("resolved", usable.lifecycle.resolution)
    }
}
