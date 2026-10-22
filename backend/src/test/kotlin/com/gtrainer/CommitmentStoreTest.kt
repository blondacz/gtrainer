package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CommitmentStoreTest {
    private fun fixture(test: (Path, HistoryStore) -> Unit) {
        val directory = Files.createTempDirectory("commitment-store-")
        val path = directory.resolve("synthetic.sqlite3")
        try { HistoryStore(path).use { test(path, it) } }
        finally {
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    private fun intake(text: String = "synthetic commitment source") =
        ContextIntakeRequest(text, "user_report", "synthetic author", "2024-01-01")
    private fun context(text: String = "synthetic context") =
        AthleteContextRequest("note", "user_report", "synthetic author", "2024-01-01", content = text)
    private fun definition(start: String = "2024-01-01T09:00", end: String = "2024-01-01T10:00") =
        CommitmentDefinition("synthetic training", "commitment", "UTC", start, end)
    private fun confirmation(source: ContextIntake, definition: CommitmentDefinition = definition(),
                             context: AthleteContext? = null, eventId: String? = null) =
        CommitmentConfirmation(source.intakeId, source.revision, definition, context?.contextId,
            context?.revision, eventId)
    private val at = Instant.parse("2024-01-02T12:00:00Z")

    @Test fun `confirmation and exact source refs survive reopen`() = fixture { path, store ->
        val source = store.createIntake(intake(), at)
        val context = store.createContext(context())
        val confirmed = store.confirmCommitment(confirmation(source, context = context), at)
        assertEquals(1, confirmed.reference.revision)
        assertEquals(source.intakeId, confirmed.reference.intakeId)
        assertEquals(1, confirmed.reference.intakeRevision)
        assertEquals(context.contextId, confirmed.reference.contextId)
        assertEquals(1, confirmed.reference.contextRevision)
        assertEquals("synthetic author", confirmed.authorAttribution)
        assertEquals("user_report", confirmed.sourceCategory)
        assertEquals("authenticated_user", confirmed.enteredBy)
        assertEquals(at.toString(), confirmed.confirmedUtc)
        HistoryStore(path).use { reopened -> assertEquals(confirmed, reopened.commitmentHistory(confirmed.reference.commitmentId).single()) }
    }

    @Test fun `correction appends same commitment and projects current occurrences without rewriting history`() = fixture { _, store ->
        val source = store.createIntake(intake(), at)
        val original = store.confirmCommitment(confirmation(source), at)
        val corrected = store.correctCommitment(original.reference.commitmentId,
            CommitmentCorrection(1, confirmation(source, definition("2024-01-01T11:00", "2024-01-01T12:00"))), at.plusSeconds(60))
        assertEquals(original.reference.commitmentId, corrected.reference.commitmentId)
        assertEquals(2, corrected.reference.revision)
        assertEquals(listOf(2, 1), store.commitmentHistory(corrected.reference.commitmentId).map { it.reference.revision })
        assertEquals(original.definition, store.commitmentHistory(corrected.reference.commitmentId).last().definition)
        val projected = projectCommitments(store.commitments(), "2024-01-01", "2024-01-01", "UTC").occurrences.single()
        assertEquals(2, projected.reference.revision)
        assertEquals("2024-01-01T11:00", projected.startsLocal)
        assertEquals("2024-01-01T11:00:00Z", projected.startsUtc)
        assertEquals("2024-01-01T12:00:00Z", projected.endsUtc)
    }

    @Test fun `stale source and context revisions reject without partial commitment writes`() = fixture { _, store ->
        val source = store.createIntake(intake(), at)
        val context = store.createContext(context())
        store.correctIntake(source.intakeId, ContextIntakeCorrection(1, intake("new source")), at.plusSeconds(1))
        assertFailsWith<CommitmentConflict> { store.confirmCommitment(confirmation(source, context = context), at) }
        val freshSource = store.intakeHistory(source.intakeId).first()
        store.correctContext(context.contextId, context("new context"))
        assertFailsWith<CommitmentConflict> { store.confirmCommitment(confirmation(freshSource, context = context), at) }
        assertTrue(store.commitments().isEmpty())
    }

    @Test fun `source changed context cannot be confirmed and context lifecycle keeps calendar stale but intervals intact`() = fixture { _, store ->
        val contextEntry = store.createContext(context())
        val source = store.createIntake(intake(), at)
        store.associateIntake(source.intakeId, ContextIntakeAssociation(1, contextEntry.contextId, 1))
        store.correctIntake(source.intakeId, ContextIntakeCorrection(1, intake("corrected wording")), at.plusSeconds(1))
        assertFailsWith<CommitmentConflict> { store.confirmCommitment(confirmation(source, context = contextEntry), at) }

        val independentSource = store.createIntake(intake("independent synthetic source"), at)
        assertFailsWith<CommitmentConflict> { store.confirmCommitment(confirmation(independentSource,context=contextEntry),at) }
        val separateContext=store.createContext(context("independent calendar context"))
        val confirmed = store.confirmCommitment(confirmation(independentSource, context = separateContext), at)
        val state = store.updateContextLifecycle(separateContext.contextId, ContextLifecycleUpdate(1, "resolved", null), LocalDate.parse("2024-01-02"))
        assertEquals(2, state.context.revision)
        val resolvedContext = store.createContext(context("already resolved synthetic context"))
        val resolvedState = store.updateContextLifecycle(resolvedContext.contextId,
            ContextLifecycleUpdate(1, "resolved", null), LocalDate.parse("2024-01-02"))
        val resolvedSource = store.createIntake(intake("resolved context source"), at)
        assertFalse(store.confirmCommitment(confirmation(resolvedSource, context = resolvedState.context), at).contextChanged)
        val current = store.commitmentHistory(confirmed.reference.commitmentId).single()
        assertTrue(current.contextChanged)
        val calendar = projectCommitments(store.commitments(), "2024-01-01", "2024-01-01", "UTC")
        assertFalse(calendar.occurrences.single { it.reference==confirmed.reference }.authoritative)
        assertTrue(calendar.constraintsBlocked)
        assertEquals(listOf(confirmed.reference), calendar.needsReconfirmation)
        assertFailsWith<ContextIntakeConflict> { store.deleteContext(contextEntry.contextId) }
    }

    @Test fun `event association checks ID only and leaves event notes and goal out of commitment`() = fixture { _, store ->
        val source = store.createIntake(intake(), at)
        val event = ManualEvent("00000000-0000-4000-8000-000000000001", "2024-01-01", "2024-01-02", "Ride", "private synthetic goal", "private synthetic notes")
        store.saveEvent(event)
        assertTrue(store.commitments().isEmpty())
        assertTrue(store.contexts(LocalDate.parse("2024-01-01")).isEmpty())
        val confirmed = store.confirmCommitment(confirmation(source, eventId = event.id), at)
        assertEquals(event.id, confirmed.eventId)
        assertFalse(confirmed.toString().contains("private synthetic"))
        assertFalse(Json.encodeToString(confirmed).contains("private synthetic"))
        assertEquals(event,store.events().single())
        assertFailsWith<EventNotFound> { store.confirmCommitment(confirmation(source, eventId = "00000000-0000-4000-8000-000000000002"), at) }
        assertEquals(1, store.commitments().size)
    }

    @Test fun `context deletion is blocked by commitment dependency even without intake link`() = fixture { _, store ->
        val source = store.createIntake(intake(), at)
        val context = store.createContext(context())
        store.confirmCommitment(confirmation(source, context = context), at)
        assertTrue(store.intakeLinks(source.intakeId).isEmpty())
        assertFailsWith<ContextIntakeConflict> { store.deleteContext(context.contextId) }
        assertFailsWith<ContextIntakeConflict> { store.correctContext(context.contextId, context("rewrite")) }
        assertEquals(context, store.contextRevisions(context.contextId).single())
    }

    @Test fun `concurrent correction with same expected revision has exactly one winner`() = fixture { _, store ->
        val source = store.createIntake(intake(), at)
        val current = store.confirmCommitment(confirmation(source), at)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = (1..2).map { n -> pool.submit<Boolean> {
                gate.await()
                try {
                    store.correctCommitment(current.reference.commitmentId, CommitmentCorrection(1,
                        confirmation(source, definition("2024-01-01T${10+n}:00", "2024-01-01T${11+n}:00"))), at)
                    true
                } catch (_: CommitmentConflict) { false }
            } }
            gate.countDown()
            assertEquals(1, outcomes.count { it.get() })
            assertEquals(listOf(2, 1), store.commitmentHistory(current.reference.commitmentId).map { it.reference.revision })
        } finally { pool.shutdownNow() }
    }

    @Test fun `failed insert rolls back and immutable revision and foreign key constraints hold`() = fixture { path, store ->
        val source = store.createIntake(intake(), at)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_failure AFTER INSERT ON commitment_revisions BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
            assertFailsWith<SQLException> { store.confirmCommitment(confirmation(source), at) }
            assertTrue(store.commitments().isEmpty())
            db.createStatement().use { it.execute("DROP TRIGGER synthetic_failure") }
        }
        val saved = store.confirmCommitment(confirmation(source), at)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=ON")
                assertFailsWith<SQLException> { statement.executeUpdate("UPDATE commitment_revisions SET revision=9 WHERE commitment_id='${saved.reference.commitmentId}'") }
                assertFailsWith<SQLException> { statement.execute("INSERT INTO commitment_revisions(commitment_id,revision,intake_id,intake_revision,definition_json,entered_by,confirmed_utc) VALUES('00000000-0000-4000-8000-000000000000',1,'missing',1,'{}','authenticated_user','2024-01-02T12:00:00Z')") }
            }
        }
        assertEquals(saved, store.commitmentHistory(saved.reference.commitmentId).single())
    }

    @Test fun `historical context dependencies remain guarded after explicit reassociation`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        val oldContext=store.createContext(context())
        val first=store.confirmCommitment(confirmation(source,context=oldContext),at)
        store.correctCommitment(first.reference.commitmentId,CommitmentCorrection(1,confirmation(source)),at)
        assertFailsWith<ContextIntakeConflict> { store.deleteContext(oldContext.contextId) }
        assertEquals(2,store.commitmentHistory(first.reference.commitmentId).size)
        assertFalse(store.commitments().single().needsReconfirmation)
    }

    @Test fun `confirmation serializes with source and context corrections`() = fixture { _,store ->
        for(changeSource in listOf(true,false)) {
            val source=store.createIntake(intake(),at)
            val context=store.createContext(context())
            val gate=CountDownLatch(1)
            val pool=Executors.newFixedThreadPool(2)
            try {
                val confirm=pool.submit<ConfirmedCommitment?> {
                    gate.await()
                    try { store.confirmCommitment(confirmation(source,context=context),at) } catch (_: CommitmentConflict) { null }
                }
                val change=pool.submit {
                    gate.await()
                    if(changeSource) store.correctIntake(source.intakeId,ContextIntakeCorrection(1,intake("corrected")),at)
                    else store.updateContextLifecycle(context.contextId,ContextLifecycleUpdate(1,"resolved",null),LocalDate.parse("2024-01-02"))
                }
                gate.countDown()
                val saved=confirm.get(); change.get()
                if(saved!=null) {
                    val current=store.commitmentHistory(saved.reference.commitmentId).single()
                    assertTrue(current.needsReconfirmation)
                    assertEquals(changeSource,current.sourceChanged)
                    assertEquals(!changeSource,current.contextChanged)
                } else assertTrue(store.commitments().none { it.reference.intakeId==source.intakeId })
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun `record and revision capacities reject without truncating current records`() = fixture { path,store ->
        val source=store.createIntake(intake(),at)
        val first=store.confirmCommitment(confirmation(source),at)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.autoCommit=false
            db.prepareStatement("INSERT INTO commitment_revisions SELECT ?,revision,intake_id,intake_revision,context_id,context_revision,event_id,definition_json,entered_by,confirmed_utc FROM commitment_revisions WHERE commitment_id=? AND revision=1").use { insert ->
                repeat(999) { n -> insert.setString(1,"00000000-0000-4000-8000-${n.toString().padStart(12,'0')}"); insert.setString(2,first.reference.commitmentId); insert.addBatch() }
                insert.executeBatch()
            }
            db.prepareStatement("INSERT INTO commitment_revisions SELECT commitment_id,?,intake_id,intake_revision,context_id,context_revision,event_id,definition_json,entered_by,confirmed_utc FROM commitment_revisions WHERE commitment_id=? AND revision=1").use { insert ->
                for(revision in 2..999) { insert.setInt(1,revision); insert.setString(2,first.reference.commitmentId); insert.addBatch() }
                insert.executeBatch()
            }
            db.commit()
        }
        assertFailsWith<IllegalArgumentException> { store.confirmCommitment(confirmation(source),at) }
        assertEquals(1000,store.commitments().size)
        assertEquals(1000,store.correctCommitment(first.reference.commitmentId,CommitmentCorrection(999,confirmation(source)),at).reference.revision)
        assertEquals(1000,store.commitmentHistory(first.reference.commitmentId).size)
        assertFailsWith<IllegalArgumentException> { CommitmentCorrection(1000,confirmation(source)) }
    }
}
