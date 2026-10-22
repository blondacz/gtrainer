package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextIntakeStoreTest {
    private fun fixture(test: (Path, HistoryStore) -> Unit) {
        val directory = Files.createTempDirectory("context-intake-store-")
        val path = directory.resolve("synthetic.sqlite3")
        try { HistoryStore(path).use { test(path, it) } }
        finally {
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    private fun intake(text: String = "synthetic source wording") =
        ContextIntakeRequest(text, "user_report", "synthetic author", "2020-06-01")

    private fun context(text: String = "synthetic context") = AthleteContextRequest(
        "note", "user_report", "synthetic author", "2020-06-01", content = text)

    private fun snapshot(store: HistoryStore, context: AthleteContext): ConnectedReviewStatus {
        val at=Instant.parse("2020-06-10T12:00:00Z")
        val evidence=AnalysisInput(1,"a".repeat(64),at.toString(),null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        return store.createConnectedReviewSnapshot(ConnectedReviewBinding("intake-atomicity",evidence,
            retrieveAthleteContext(listOf(context),ContextRetrievalQuery(LocalDate.parse("2020-06-10"),hardPacketLimit=10)),
            "2020-06-01","2020-06-10",null,ConnectedReviewProviderSelection("synthetic","synthetic","local"),InterpretationContractV1.PROFILE),at)
    }

    @Test fun `intake history and provenance association survive reopen without rewriting context`() = fixture { path, store ->
        val original = store.createContext(context())
        val created = store.createIntake(intake(), Instant.parse("2020-06-10T12:00:00Z"))
        assertFalse(created.toString().contains(created.content))
        val correction = store.correctIntake(created.intakeId,
            ContextIntakeCorrection(1, intake("corrected source wording")), Instant.parse("2020-06-11T12:00:00Z"))
        assertEquals(2, correction.revision)
        assertFailsWith<ContextIntakeConflict> {
            store.correctIntake(created.intakeId, ContextIntakeCorrection(1, intake("stale overwrite")), Instant.parse("2020-06-12T00:00:00Z"))
        }
        val link = store.associateIntake(created.intakeId, ContextIntakeAssociation(2, original.contextId, original.revision))
        assertEquals(link, store.associateIntake(created.intakeId, ContextIntakeAssociation(2, original.contextId, original.revision)))
        assertEquals(1, store.intakeLinks(created.intakeId).size)

        HistoryStore(path).use { reopened ->
            assertEquals(created.intakeId, reopened.intakeHistory(created.intakeId).last().intakeId)
            assertEquals(listOf(2, 1), reopened.intakeHistory(created.intakeId).map { it.revision })
            assertEquals("synthetic source wording", reopened.intakeHistory(created.intakeId).last().content)
            assertEquals("corrected source wording", reopened.intakeHistory(created.intakeId).first().content)
            assertEquals("synthetic author", correction.authorAttribution)
            assertEquals("authenticated_user", correction.enteredBy)
            assertEquals("2020-06-01", correction.observedOn)
            assertEquals("2020-06-11T12:00:00Z", correction.recordedUtc)
            assertEquals(original, reopened.contextRevisions(original.contextId).first())
            assertEquals(listOf(link), reopened.intakeLinks(created.intakeId))
            assertFalse(reopened.intakeLinks(created.intakeId).single().sourceChanged)
            reopened.correctIntake(created.intakeId, ContextIntakeCorrection(2,intake("later correction")),Instant.parse("2020-06-12T12:00:00Z"))
            assertTrue(reopened.intakeLinks(created.intakeId).single().sourceChanged)
            assertEquals(original.content, reopened.contextRevisions(original.contextId).last().content)
        }
    }

    @Test fun `missing intake unknown historical or retired context and mismatched revision are refused`() = fixture { _, store ->
        val entry = store.createContext(context())
        val intake = store.createIntake(intake(), Instant.parse("2020-06-10T12:00:00Z"))
        val missingId = "00000000-0000-0000-0000-000000000000"
        assertFailsWith<ContextIntakeNotFound> { store.intakeHistory(missingId) }
        assertFailsWith<ContextIntakeNotFound> {
            store.associateIntake(missingId, ContextIntakeAssociation(1, entry.contextId, 1))
        }
        assertFailsWith<ContextNotFound> {
            store.associateIntake(intake.intakeId, ContextIntakeAssociation(1, missingId, 1))
        }
        assertFailsWith<ContextIntakeConflict> {
            store.associateIntake(intake.intakeId, ContextIntakeAssociation(1, entry.contextId, entry.revision + 1))
        }
        val corrected = store.correctContext(entry.contextId, context("new context revision"))
        assertFailsWith<ContextIntakeConflict> {
            store.associateIntake(intake.intakeId, ContextIntakeAssociation(1, entry.contextId, entry.revision))
        }
        assertEquals(2, corrected.revision)
        assertTrue(store.retireContext(entry.contextId))
        assertFailsWith<ContextIntakeConflict> {
            store.associateIntake(intake.intakeId, ContextIntakeAssociation(1, entry.contextId, 3))
        }
        assertTrue(store.intakeLinks(intake.intakeId).isEmpty())
    }

    @Test fun `one source per exact context revision and foreign keys prevent dangling links`() = fixture { path, store ->
        val entry = store.createContext(context())
        val first = store.createIntake(intake(), Instant.EPOCH)
        val second = store.createIntake(intake("other source"), Instant.EPOCH)
        store.associateIntake(first.intakeId,ContextIntakeAssociation(1,entry.contextId,1))
        assertFailsWith<ContextIntakeConflict> { store.associateIntake(second.intakeId,ContextIntakeAssociation(1,entry.contextId,1)) }
        assertTrue(store.intakeLinks(second.intakeId).isEmpty())
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { s ->
                s.execute("PRAGMA foreign_keys=ON")
                assertFailsWith<SQLException> { s.execute("INSERT INTO context_intake_links VALUES('missing',1,'${first.intakeId}',1)") }
                assertFailsWith<SQLException> { s.execute("INSERT INTO context_intake_links VALUES('${entry.contextId}',2,'missing',1)") }
                s.executeQuery("SELECT count(*) FROM context_intake_links").use { rows -> rows.next(); assertEquals(1,rows.getInt(1)) }
            }
        }
    }

    @Test fun `failed association rolls back and leaves connection usable`() = fixture { path, store ->
        val entry = store.createContext(context())
        val source = store.createIntake(intake(),Instant.EPOCH)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_failure AFTER INSERT ON context_intake_links BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
            assertFailsWith<SQLException> { store.associateIntake(source.intakeId,ContextIntakeAssociation(1,entry.contextId,1)) }
            assertTrue(store.intakeLinks(source.intakeId).isEmpty())
            assertEquals(entry,store.contextRevisions(entry.contextId).single())
            db.createStatement().use { it.execute("DROP TRIGGER synthetic_failure") }
        }
        assertEquals(1,store.associateIntake(source.intakeId,ContextIntakeAssociation(1,entry.contextId,1)).intakeRevision)
    }

    @Test fun `capacity and revision bounds reject writes without truncation`() = fixture { path, store ->
        val first = store.createIntake(intake(),Instant.EPOCH)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.autoCommit=false
            db.prepareStatement("INSERT INTO context_intakes SELECT ?,revision,content,source_category,author_attribution,entered_by,observed_on,recorded_utc FROM context_intakes WHERE intake_id=? AND revision=1").use { insert ->
                repeat(999) { i -> insert.setString(1,"00000000-0000-4000-8000-${i.toString().padStart(12,'0')}"); insert.setString(2,first.intakeId); insert.addBatch() }
                insert.executeBatch()
            }
            db.prepareStatement("INSERT INTO context_intakes SELECT intake_id,?,content,source_category,author_attribution,entered_by,observed_on,recorded_utc FROM context_intakes WHERE intake_id=? AND revision=1").use { insert ->
                for (revision in 2..999) { insert.setInt(1,revision); insert.setString(2,first.intakeId); insert.addBatch() }
                insert.executeBatch()
            }
            db.commit()
        }
        assertEquals(1000,store.intakes().size)
        assertFailsWith<IllegalArgumentException> { store.createIntake(intake(),Instant.EPOCH) }
        assertEquals(1000,store.correctIntake(first.intakeId,ContextIntakeCorrection(999,intake()),Instant.EPOCH).revision)
        assertEquals(1000,store.intakeHistory(first.intakeId).size)
        assertFailsWith<IllegalArgumentException> { ContextIntakeCorrection(1000,intake()) }
        assertEquals(1000,store.intakeHistory(first.intakeId).first().revision)
    }

    @Test fun `source revision and dependent invalidation roll back as one transaction`() = fixture { path, store ->
        val entry=store.createContext(context())
        val source=store.createIntake(intake(),Instant.EPOCH)
        store.associateIntake(source.intakeId,ContextIntakeAssociation(1,entry.contextId,1))
        val review=snapshot(store,entry)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_failure BEFORE UPDATE ON connected_review_snapshot_state BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
            assertFailsWith<SQLException> { store.correctIntake(source.intakeId,ContextIntakeCorrection(1,intake("must roll back")),Instant.EPOCH) }
            assertEquals(listOf(source),store.intakeHistory(source.intakeId))
            assertFalse(store.intakeLinks(source.intakeId).single().sourceChanged)
            assertEquals(review,store.connectedReviewStatus(review.snapshotId))
            db.createStatement().use { it.execute("DROP TRIGGER synthetic_failure") }
        }
        assertEquals(2,store.correctIntake(source.intakeId,ContextIntakeCorrection(1,intake("accepted correction")),Instant.EPOCH).revision)
        assertEquals("STALE",store.connectedReviewStatus(review.snapshotId).state)
    }

    @Test fun `linked feedback prevents indirect deletion of source text and its review`() = fixture { _, store ->
        val entry=store.createContext(context())
        val review=snapshot(store,entry)
        store.publishConnectedReview(review.snapshotId,ValidatedConnectedReviewOutput("synthetic output"),review.evidenceDigest,
            review.context,review.provider,review.model,review.contractVersion)
        val feedback=store.recordReviewFeedback(review.snapshotId,ReviewFeedbackRequest(rating="useful"),LocalDate.parse("2020-06-10"))
        val source=store.createIntake(intake("synthetic feedback source"),Instant.EPOCH)
        val link=store.associateIntake(source.intakeId,ContextIntakeAssociation(1,feedback.contextId,feedback.revision))
        assertFailsWith<ContextIntakeConflict> { store.deleteContext(entry.contextId) }
        assertEquals(entry,store.contextRevisions(entry.contextId).single())
        assertEquals(feedback,store.contextRevisions(feedback.contextId).single())
        assertEquals(listOf(link),store.intakeLinks(source.intakeId))
        assertEquals("PUBLISHED",store.connectedReviewStatus(review.snapshotId).state)
    }

    @Test fun `linked context corrections and deletion are blocked and concurrent expected revision has one winner`() = fixture { _, store ->
        val entry = store.createContext(context())
        val source = store.createIntake(intake(), Instant.parse("2020-06-10T12:00:00Z"))
        store.associateIntake(source.intakeId, ContextIntakeAssociation(1, entry.contextId, entry.revision))
        assertFailsWith<ContextIntakeConflict> { store.correctContext(entry.contextId, context("rewrite")) }
        assertFailsWith<ContextIntakeConflict> { store.deleteContext(entry.contextId) }
        assertEquals(entry, store.contextRevisions(entry.contextId).single())

        val raced = store.createIntake(intake("race original"), Instant.parse("2020-06-10T12:00:00Z"))
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = (1..2).map { n -> pool.submit<Boolean> {
                gate.await()
                try {
                    store.correctIntake(raced.intakeId,
                        ContextIntakeCorrection(1, intake("race correction $n")), Instant.parse("2020-06-11T12:00:00Z"))
                    true
                } catch (_: ContextIntakeConflict) { false }
            } }
            gate.countDown()
            assertEquals(1, outcomes.count { it.get() })
            assertEquals(listOf(2, 1), store.intakeHistory(raced.intakeId).map { it.revision })
        } finally { pool.shutdownNow() }
    }
}
