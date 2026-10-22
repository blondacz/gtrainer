package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ConstraintObservationOwnerTest {
    private fun fixture(test: (Path, HistoryStore) -> Unit) {
        val dir = Files.createTempDirectory("synthetic-constraint-observation-")
        val path = dir.resolve("history.sqlite3")
        try { HistoryStore(path).use { test(path, it) } }
        finally {
            Files.list(dir).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(dir)
        }
    }

    private fun context(content: String = "synthetic context marker") = AthleteContextRequest(
        "restriction", "user_report", "synthetic author", "2026-10-01",
        content = content, restrictionKind = "blocked_sport", restrictionValue = "Run"
    )

    private fun metadata(path: Path): Triple<Long, Long, Long> = DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
        db.createStatement().use { s -> s.executeQuery("SELECT mutation_sequence,deletion_generation,retained_from_sequence FROM constraint_observation_metadata WHERE singleton=1").use { r ->
            check(r.next()); Triple(r.getLong(1), r.getLong(2), r.getLong(3))
        } }
    }

    @Test fun `unpolled canonical A B A edits append distinct observations`() = fixture { path, store ->
        val initial = store.createContext(context("A marker"))
        assertEquals(1L, metadata(path).first)
        val second = store.correctContext(initial.contextId, context("B marker").copy(restrictionValue="Ride"))
        assertEquals(2L, metadata(path).first)
        val third = store.correctContext(initial.contextId, context("A marker"))
        assertEquals(3L, metadata(path).first)
        assertEquals(3, third.revision)
        assertEquals(2, second.revision)
    }

    @Test fun `delete invalidates mutation coverage and appends opaque tombstone`() = fixture { path, store ->
        val marker = "private canonical marker 2026-10-01"
        val item = store.createContext(context(marker))
        val before = metadata(path)
        assertTrue(store.deleteContext(item.contextId))
        val deleted = metadata(path)
        assertEquals(before.first + 1, deleted.first)
        assertEquals(before.second + 1, deleted.second)
        assertEquals(before.first + 1, deleted.third)
        assertFalse(store.deleteContext(item.contextId))
        assertEquals(deleted, metadata(path))

        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            val raw = buildString {
                for (table in listOf("constraint_observation_metadata", "constraint_observation_inventory", "constraint_observation_mutations")) {
                    db.createStatement().use { s -> s.executeQuery("SELECT * FROM $table").use { r ->
                        val n = r.metaData.columnCount
                        while (r.next()) for (i in 1..n) append(r.getString(i)).append('|')
                    } }
                }
            }
            assertFalse(raw.contains(marker))
            assertFalse(raw.contains("A marker"))
            assertFalse(raw.contains("2026-10-01"));assertFalse(raw.contains("blocked_sport"));assertFalse(raw.contains("Run"))
            assertTrue(raw.contains(item.contextId))
            val inventory=ConstraintMutationStore(db).inventory()
            assertEquals(listOf(ConstraintSubjectRef("context",item.contextId,1,false)),inventory)
            assertEquals(listOf(ConstraintSubjectRef("context",item.contextId,1,false)),ConstraintMutationStore(db).capture(before.first,deleted.second).records.single().refs)
        }
    }

    @Test fun `adaptation root capture and finish are observation-write inert`() = fixture { path, store ->
        val before = metadata(path)
        val captured = store.captureAdaptationEvidence(Instant.parse("2026-10-15T12:00:00Z"))
        val capturedAgain = store.captureAdaptationEvidence(Instant.parse("2026-10-15T12:00:00Z"))
        val prepared=AdaptationProjection.prepare(captured)
        assertEquals(prepared,store.finishAdaptationProjection(prepared))
        assertEquals(before, metadata(path))
        assertTrue(captured.constraintInventory.isEmpty())
        assertEquals(captured.constraintFrontier, capturedAgain.constraintFrontier)
        assertEquals(before, metadata(path))
    }

    @Test fun `association and source corrections bind unchanged context revision but exact association replay is inert`() = fixture { path,store ->
        val at=Instant.parse("2026-10-15T12:00:00Z")
        val item=store.createContext(context())
        val note=store.createIntake(ContextIntakeRequest("synthetic source","user_report","synthetic author","2026-10-01"),at)
        val before=metadata(path)
        val association=ContextIntakeAssociation(1,item.contextId,1)
        store.associateIntake(note.intakeId,association)
        val linked=metadata(path);assertEquals(before.first+1,linked.first)
        store.associateIntake(note.intakeId,association)
        assertEquals(linked,metadata(path))
        val prepared=AdaptationProjection.prepare(store.captureAdaptationEvidence(at))
        store.correctIntake(note.intakeId,ContextIntakeCorrection(1,ContextIntakeRequest("corrected source","user_report","synthetic author","2026-10-01")),at)
        val changed=metadata(path);assertEquals(linked.first+1,changed.first)
        assertEquals(1,store.contextRevisions(item.contextId).first().revision)
        assertFailsWith<PlanConflict> { store.finishAdaptationProjection(prepared) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            val observed=ConstraintMutationStore(db).capture(linked.first,0)
            assertTrue(observed.complete())
            assertEquals(setOf(ConstraintSubjectRef("context",item.contextId,1,true),ConstraintSubjectRef("intake",note.intakeId,2,true)),observed.records.single().refs.toSet())
        }
    }

    @Test fun `journal failure rolls original canonical change and opaque frontier back together`() = fixture { path,store ->
        val before=metadata(path);val revisions=store.scopedRoutingRevisions()
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_observation_failure BEFORE INSERT ON constraint_observation_inventory BEGIN SELECT RAISE(ABORT,'synthetic refusal'); END") }
        }
        assertFailsWith<java.sql.SQLException> { store.createContext(context()) }
        assertEquals(before,metadata(path));assertEquals(revisions,store.scopedRoutingRevisions())
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            for(table in listOf("athlete_context","constraint_observation_inventory","constraint_observation_mutations")) {
                db.createStatement().use { it.executeQuery("SELECT count(*) FROM $table").use { r -> r.next();assertEquals(0,r.getInt(1)) } }
            }
            db.createStatement().use { it.execute("DROP TRIGGER synthetic_observation_failure") }
        }
        store.createContext(context())
        assertEquals(before.first+1,metadata(path).first)
    }

    @Test fun `commitment observes transitive context source correction without rewriting either confirmed revision`() = fixture { path,store ->
        val at=Instant.parse("2026-10-15T12:00:00Z")
        val item=store.createContext(context())
        fun note(text:String)=store.createIntake(ContextIntakeRequest(text,"user_report","synthetic author","2026-10-01"),at)
        val ownSource=note("commitment source");val contextSource=note("separate context source")
        store.associateIntake(contextSource.intakeId,ContextIntakeAssociation(1,item.contextId,1))
        val commitment=store.confirmCommitment(CommitmentConfirmation(ownSource.intakeId,1,
            CommitmentDefinition("Synthetic","commitment","UTC","2026-10-16T10:00","2026-10-16T11:00"),item.contextId,1),at)
        val before=metadata(path)
        store.correctIntake(contextSource.intakeId,ContextIntakeCorrection(1,ContextIntakeRequest("corrected context source","user_report","synthetic author","2026-10-01")),at)
        val current=store.commitments().single()
        assertEquals(1,current.reference.revision);assertTrue(current.contextChanged)
        assertEquals(1,store.contextRevisions(item.contextId).first().revision)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            val refs=ConstraintMutationStore(db).capture(before.first,before.second).records.single().refs
            assertTrue(ConstraintSubjectRef("commitment",commitment.reference.commitmentId,1,true) in refs)
            assertTrue(ConstraintSubjectRef("context",item.contextId,1,true) in refs)
            assertTrue(ConstraintSubjectRef("intake",contextSource.intakeId,2,true) in refs)
            assertFalse(refs.any { it.id==ownSource.intakeId })
        }
    }

    @Test fun `counter exhaustion makes ancestry terminally unavailable but never prevents privacy deletion`() = fixture { path,store ->
        val item=store.createContext(context("source that must be removed"))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("UPDATE constraint_observation_metadata SET mutation_sequence=${Long.MAX_VALUE}") }
        }
        assertTrue(store.deleteContext(item.contextId))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            val observation=ConstraintMutationStore(db)
            assertEquals(Long.MAX_VALUE,observation.frontier().sequence)
            assertFalse(observation.frontier().available)
            assertFalse(observation.capture(Long.MAX_VALUE,0).complete())
            assertFalse(observation.inventory().single().present)
            db.createStatement().use { it.executeQuery("SELECT count(*) FROM athlete_context").use { r -> r.next();assertEquals(0,r.getInt(1)) } }
        }
        HistoryStore(path).use { reopened ->
            assertFalse(reopened.captureAdaptationEvidence(Instant.parse("2026-10-15T12:00:00Z")).constraintFrontier.available)
        }
    }
}
