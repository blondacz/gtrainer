package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstraintMaterialOwnerTest {
    private val at = Instant.parse("2026-10-15T12:00:00Z")
    private class OwnerClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
    private fun fixture(block: (Path, HistoryStore, OwnerClock) -> Unit) {
        val dir = Files.createTempDirectory("synthetic-constraint-material-")
        val path = dir.resolve("history.sqlite3")
        val clock = OwnerClock(at)
        try { HistoryStore(path, clock).use { block(path, it, clock) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }
    private fun context(value: String = "60", content: String = "synthetic prose") = AthleteContextRequest(
        "restriction", "user_report", "synthetic author", "2026-10-01", content = content,
        restrictionKind = "maximum_duration", restrictionValue = value, restrictionUnit = "minutes")
    private fun note(text: String = "synthetic source") = ContextIntakeRequest(text, "user_report", "synthetic author", "2026-10-01")
    private fun plan(store: HistoryStore, date: String = "2026-10-18", horizon: Int = 7, empty: Boolean = false): AuthoredPlan =
        store.createPlan(AuthoredPlanRequest("Synthetic plan", PlanAuthor("user", "synthetic"), if (empty) emptyList() else listOf(
            PlannedSession("33333333-3333-4333-8333-333333333333", PlanAuthor("user", "synthetic"), "run", "easy",
                PlanSchedule("date_only", date = date, timeZone = "UTC"))), PlanPolicy("UTC", horizon, 0)), at)
    private fun select(store: HistoryStore, plan: AuthoredPlan?) = store.selectPlan(PlanSelectionRequest(store.plans().selection.generation, plan?.reference))
    private fun prepared(store: HistoryStore, time: Instant = at) = AdaptationProjection.prepare(store.captureAdaptationEvidence(time))
    private fun sql(path: Path, block: (java.sql.Connection) -> Unit) = DriverManager.getConnection("jdbc:sqlite:$path").use(block)

    @Test fun `initial enrollment is baseline only and returning selection preserves it`() = fixture { _, store, _ ->
        store.createContext(context())
        val authored = plan(store); select(store, authored)
        val initial = prepared(store)
        assertTrue(initial.constraintChanges.available)
        assertTrue(initial.constraintChanges.eligibleEventIds.isEmpty())
        val baseline = initial.constraintChanges.baselineId
        assertTrue(baseline != null)
        select(store, null); select(store, authored); select(store, authored)
        assertEquals(baseline, prepared(store).constraintChanges.baselineId)
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
        assertTrue(store.captureAdaptationEvidence(at).reasonHeads.isEmpty())
        assertTrue(store.captureAdaptationEvidence(at).admissions.isEmpty())
    }

    @Test fun `selected correction enrolls distinct revision family without erasing prior baseline`() = fixture { _, store, _ ->
        val authored = plan(store); select(store, authored)
        val baseline = prepared(store).constraintChanges.baselineId
        val changed = store.correctPlan(authored.reference.planId, PlanCorrection(1, store.plans().selection.generation,
            authored.definition.copy(title = "Corrected")), at)
        assertEquals(changed.reference, prepared(store).family?.plan)
        assertNotEquals(baseline, prepared(store).constraintChanges.baselineId)
        assertTrue(prepared(store).constraintChanges.eligibleEventIds.isEmpty())
    }

    @Test fun `deselected A B A keeps both material events through reopen and repeated reads`() = fixture { path, store, clock ->
        val item = store.createContext(context())
        val authored = plan(store); select(store, authored)
        val baseline = prepared(store).constraintChanges.baselineId
        select(store, null)
        store.correctContext(item.contextId, context("30"))
        store.correctContext(item.contextId, context("60"))
        select(store, authored)
        val projected = prepared(store)
        assertEquals(2, projected.constraintChanges.eligibleEventIds.size)
        val events = projected.captured.constraintChanges!!.events
        assertEquals(events[0].proof.before!!.materialSha256, events[1].proof.after!!.materialSha256)
        assertTrue(events[0].sequence < events[1].sequence)
        assertEquals(projected, store.finishAdaptationProjection(projected))
        assertEquals(projected.constraintChanges, prepared(store).constraintChanges)
        HistoryStore(path, clock).use { reopened ->
            assertEquals(baseline, prepared(reopened).constraintChanges.baselineId)
            assertEquals(projected.constraintChanges, prepared(reopened).constraintChanges)
        }
    }

    @Test fun `provenance and association absence are frozen while dependency-only changes create no event`() = fixture { _, store, _ ->
        val authored = plan(store); select(store, authored)
        val item = store.createContext(context())
        val first = prepared(store).captured.constraintChanges!!.events.single()
        assertNull(first.proof.after!!.associations.single().intakeId)
        val source = store.createIntake(note(), at)
        store.associateIntake(source.intakeId, ContextIntakeAssociation(1, item.contextId, 1))
        assertEquals(1, prepared(store).captured.constraintChanges!!.events.size)
        store.correctIntake(source.intakeId, ContextIntakeCorrection(1, note("source correction")), at)
        val events = prepared(store).captured.constraintChanges!!.events
        assertEquals(2, events.size)
        val correction = events.last().proof
        assertFalse(correction.before!!.context!!.sourceChanged)
        assertTrue(correction.after!!.context!!.sourceChanged)
        assertEquals(1, correction.before.associations.single().observedIntakeHead)
        assertEquals(2, correction.after.associations.single().observedIntakeHead)
        store.correctIntake(source.intakeId, ContextIntakeCorrection(2, note("later correction")), at)
        assertEquals(events, prepared(store).captured.constraintChanges!!.events)
        assertNull(prepared(store).captured.constraintChanges!!.events.first().proof.after!!.associations.single().intakeId)
    }

    @Test fun `text-only edits advance coverage without material events and imports remain inert`() = fixture { _, store, _ ->
        val item = store.createContext(context())
        val authored = plan(store); select(store, authored)
        store.correctContext(item.contextId, context(content = "changed prose"))
        val before = prepared(store)
        assertTrue(before.constraintChanges.available)
        assertTrue(before.constraintChanges.eligibleEventIds.isEmpty())
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity())), at)
        val after = prepared(store)
        assertEquals(before.captured.constraintFrontier, after.captured.constraintFrontier)
        assertEquals(before.constraintChanges, after.constraintChanges)
    }

    @Test fun `clock and horizon entry expose existing irrelevant event without manufacturing one`() = fixture { _, store, clock ->
        val request = context().copy(applicableFrom = "2026-10-18", applicableUntil = "2026-10-18")
        val item = store.createContext(request)
        val authored = plan(store, horizon = 2); select(store, authored)
        store.correctContext(item.contextId, request.copy(restrictionValue = "30"))
        val before = prepared(store)
        assertTrue(before.constraintChanges.eligibleEventIds.isEmpty())
        assertEquals(1, before.captured.constraintChanges!!.events.size)
        clock.now = Instant.parse("2026-10-17T12:00:00Z")
        val after = prepared(store, clock.now)
        assertEquals(1, after.constraintChanges.eligibleEventIds.size)
        assertEquals(before.captured.constraintFrontier, after.captured.constraintFrontier)
        assertEquals(before.captured.constraintChanges!!.events, after.captured.constraintChanges!!.events)
    }

    @Test fun `commitment changes remain eligible with no authored session targets`() = fixture { _, store, _ ->
        val source = store.createIntake(note(), at)
        val original = CommitmentConfirmation(source.intakeId, 1,
            CommitmentDefinition("Synthetic", "commitment", "UTC", "2026-10-16T10:00", "2026-10-16T11:00"))
        val commitment = store.confirmCommitment(original, at)
        val authored = plan(store, empty = true); select(store, authored)
        store.correctCommitment(commitment.reference.commitmentId, CommitmentCorrection(1,
            original.copy(definition = original.definition.copy(startsLocal = "2026-10-17T10:00", endsLocal = "2026-10-17T11:00"))), at)
        assertEquals(1, prepared(store).constraintChanges.eligibleEventIds.size)
    }

    @Test fun `deletion scrubs proofs and baseline but preserves opaque continuity through reopen`() = fixture { path, store, clock ->
        val item = store.createContext(context())
        val authored = plan(store); select(store, authored)
        store.correctContext(item.contextId, context("30"))
        val before = prepared(store)
        val baseline = before.constraintChanges.baselineId
        assertTrue(store.deleteContext(item.contextId))
        assertFalse(prepared(store).constraintChanges.available)
        assertEquals(baseline, prepared(store).constraintChanges.baselineId)
        assertFailsWith<PlanConflict> { store.finishAdaptationProjection(before) }
        sql(path) { db -> db.createStatement().use { s ->
            s.executeQuery("SELECT proof_json FROM constraint_material_events").use { r -> assertTrue(r.next()); assertNull(r.getString(1)) }
            s.executeQuery("SELECT baseline_proof_json FROM constraint_family_enrollments").use { r -> assertTrue(r.next()); assertNull(r.getString(1)) }
        } }
        HistoryStore(path, clock).use { reopened ->
            select(reopened, null); select(reopened, authored)
            assertFalse(prepared(reopened).constraintChanges.available)
            assertEquals(baseline, prepared(reopened).constraintChanges.baselineId)
        }
    }

    @Test fun `new proof invalidates deletion preview and SQL failure rolls canonical event back`() = fixture { path, store, _ ->
        val item = store.createContext(context())
        val authored = plan(store); select(store, authored)
        val preview = store.previewContextDeletion(item.contextId, at)
        store.createContext(context("40"))
        assertFailsWith<ContextDeletionConflict> { store.redactAndDeleteContext(item.contextId,
            ContextDeletionRequest(preview.expectedRevision, preview.digest, emptyList(), emptyList(), emptyList()), at) }
        val before = prepared(store)
        sql(path) { db -> db.createStatement().use { it.execute("CREATE TRIGGER synthetic_material_failure BEFORE INSERT ON constraint_material_events BEGIN SELECT RAISE(ABORT,'synthetic refusal'); END") } }
        assertFailsWith<java.sql.SQLException> { store.correctContext(item.contextId, context("30")) }
        assertEquals(1, store.contextRevisions(item.contextId).single().revision)
        assertEquals(before.constraintChanges, prepared(store).constraintChanges)
        assertEquals(before.captured.constraintFrontier, prepared(store).captured.constraintFrontier)
        sql(path) { db -> db.createStatement().use { it.execute("DROP TRIGGER synthetic_material_failure") } }
    }

    @Test fun `lost cursor proof becomes terminal without silent reenrollment or blocking deletion`() = fixture { path, store, clock ->
        val item = store.createContext(context())
        val authored = plan(store); select(store, authored)
        val baseline = prepared(store).constraintChanges.baselineId
        sql(path) { db -> db.createStatement().use { it.execute("UPDATE constraint_observation_metadata SET mutation_sequence=mutation_sequence+1") } }
        store.correctContext(item.contextId, context("30"))
        assertFalse(prepared(store).constraintChanges.available)
        HistoryStore(path, clock).use { reopened ->
            select(reopened, null); select(reopened, authored)
            assertFalse(prepared(reopened).constraintChanges.available)
            assertEquals(baseline, prepared(reopened).constraintChanges.baselineId)
            assertTrue(reopened.deleteContext(item.contextId))
        }
    }

    @Test fun `proof byte exhaustion commits correction as unavailable and never blocks privacy deletion`() = fixture { path, store, clock ->
        val item = store.createContext(context())
        val authored = plan(store); select(store, authored)
        store.correctContext(item.contextId, context("30"))
        val baseline = prepared(store).constraintChanges.baselineId
        // Valid synthetic JSON padded to the aggregate storage limit. No production database
        // or malformed-proof fallback is used to exercise the next append's capacity refusal.
        sql(path) { db ->
            val total = db.createStatement().use { s -> s.executeQuery("SELECT " +
                "(SELECT sum(length(CAST(proof_json AS BLOB))) FROM constraint_material_events)+" +
                "(SELECT sum(length(CAST(baseline_proof_json AS BLOB))) FROM constraint_family_enrollments)").use { r -> r.next(); r.getInt(1) } }
            db.prepareStatement("UPDATE constraint_material_events SET proof_json=proof_json||?").use { p ->
                p.setString(1, " ".repeat(8 * 1024 * 1024 - total)); p.executeUpdate()
            }
        }
        store.correctContext(item.contextId, context("20"))
        assertEquals(3, store.contextRevisions(item.contextId).first().revision)
        assertFalse(prepared(store).constraintChanges.available)
        HistoryStore(path, clock).use { reopened ->
            select(reopened, authored)
            assertFalse(prepared(reopened).constraintChanges.available)
            assertEquals(baseline, prepared(reopened).constraintChanges.baselineId)
            assertTrue(reopened.deleteContext(item.contextId))
        }
    }

    @Test fun `shared-source deletion purges survivor endpoint history and immutable source proof`() = fixture { path, store, _ ->
        val target = store.createContext(context("319", "private target marker"))
        val survivor = store.createContext(context("319", "private survivor marker"))
        val source = store.createIntake(note("private source marker"), at)
        store.associateIntake(source.intakeId, ContextIntakeAssociation(1, target.contextId, 1))
        store.associateIntake(source.intakeId, ContextIntakeAssociation(1, survivor.contextId, 1))
        val commitment = store.confirmCommitment(CommitmentConfirmation(source.intakeId, 1,
            CommitmentDefinition("Synthetic commitment", "commitment", "UTC", "2026-10-16T10:00", "2026-10-16T11:00"), target.contextId, 1), at)
        val authored = plan(store); select(store, authored)
        val other = plan(store); select(store, other)
        store.correctIntake(source.intakeId, ContextIntakeCorrection(1, note("private source correction marker")), at)
        assertEquals(3, prepared(store).constraintChanges.eligibleEventIds.size)
        val preview = store.previewContextDeletion(target.contextId, at)
        val request = ContextDeletionRequest(preview.expectedRevision, preview.digest,
            listOf(SourceRedaction(source.intakeId, 2, note("clean source"))),
            listOf(ContextSurvivor(ContextConfirmationItem(context("30", "clean survivor"), ContextLifecycle(), survivor.contextId, 1), source.intakeId)),
            listOf(CommitmentDisposition(commitment.reference.commitmentId, 1, null)))
        store.redactAndDeleteContext(target.contextId, request, at)
        assertEquals(listOf(2), store.contextRevisions(survivor.contextId).map { it.revision })
        assertFalse(prepared(store).constraintChanges.available)
        sql(path) { db -> db.createStatement().use { s ->
            s.executeQuery("SELECT proof_json FROM constraint_material_events").use { r ->
                var count = 0
                while (r.next()) { count++; assertNull(r.getString(1)) }
                assertEquals(6, count)
            }
            s.executeQuery("SELECT baseline_proof_json FROM constraint_family_enrollments").use { r -> assertTrue(r.next()); assertNull(r.getString(1)) }
        } }
    }

    @Test fun `all enrolled families observe the same mutation without sharing event identity`() = fixture { _, store, _ ->
        val item = store.createContext(context())
        val first = plan(store); select(store, first)
        val firstBaseline = prepared(store).constraintChanges.baselineId
        val second = plan(store); select(store, second)
        val secondBaseline = prepared(store).constraintChanges.baselineId
        store.correctContext(item.contextId, context("30"))
        val secondEvent = prepared(store).constraintChanges.eligibleEventIds.single()
        select(store, first)
        assertEquals(firstBaseline, prepared(store).constraintChanges.baselineId)
        val firstEvent = prepared(store).constraintChanges.eligibleEventIds.single()
        assertNotEquals(firstEvent, secondEvent)
        select(store, second)
        assertEquals(secondBaseline, prepared(store).constraintChanges.baselineId)
        assertEquals(secondEvent, prepared(store).constraintChanges.eligibleEventIds.single())
    }

    @Test fun `persisted proof excludes original prose labels and attribution`() = fixture { path, store, _ ->
        val source = store.createIntake(note("unique intake prose"), at)
        val commitment = store.confirmCommitment(CommitmentConfirmation(source.intakeId, 1,
            CommitmentDefinition("unique commitment label", "commitment", "UTC", "2026-10-16T10:00", "2026-10-16T11:00")), at)
        val item = store.createContext(context(content = "unique context prose"))
        val authored = plan(store); select(store, authored)
        store.correctContext(item.contextId, context("30", "unique corrected prose"))
        store.correctCommitment(commitment.reference.commitmentId, CommitmentCorrection(1,
            CommitmentConfirmation(source.intakeId, 1, commitment.definition.copy(startsLocal = "2026-10-17T10:00", endsLocal = "2026-10-17T11:00"))), at)
        sql(path) { db -> db.createStatement().use { s -> s.executeQuery("SELECT proof_json FROM constraint_material_events").use { r ->
            var count = 0
            while (r.next()) {
                count++
                val proof = r.getString(1)
                for (marker in listOf("unique intake prose", "unique commitment label", "unique context prose", "unique corrected prose", "synthetic author")) assertFalse(proof.contains(marker))
            }
            assertEquals(2, count)
        } } }
    }

    @Test fun `missing event row cannot be acknowledged by intact cursor or later mutation`() = fixture { path, store, clock ->
        val item = store.createContext(context())
        val authored = plan(store); select(store, authored)
        store.correctContext(item.contextId, context("30"))
        val baseline = prepared(store).constraintChanges.baselineId
        sql(path) { db -> db.createStatement().use { it.executeUpdate("DELETE FROM constraint_material_events") } }
        assertFalse(prepared(store).constraintChanges.available)
        store.correctContext(item.contextId, context("20"))
        assertFalse(prepared(store).constraintChanges.available)
        HistoryStore(path, clock).use { reopened ->
            select(reopened, authored)
            assertFalse(prepared(reopened).constraintChanges.available)
            assertEquals(baseline, prepared(reopened).constraintChanges.baselineId)
            assertTrue(reopened.deleteContext(item.contextId))
        }
    }

    @Test fun `family saturation refuses enrollment without altering plan selection and permits deletion`() = fixture { path, store, _ ->
        val item = store.createContext(context())
        val authored = plan(store); select(store, authored)
        // Synthetic opaque family rows reach the documented count bound without authoring
        // unrelated plans or changing the selected family's valid baseline.
        sql(path) { db -> db.createStatement().use { it.executeUpdate("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<999) " +
            "INSERT INTO constraint_family_enrollments SELECT printf('synthetic-family-%d',x),family_json,printf('synthetic-baseline-%d',x)," +
            "baseline_sequence,baseline_deletion_generation,cursor_sequence,cursor_deletion_generation,available,baseline_proof_json,event_count " +
            "FROM n CROSS JOIN constraint_family_enrollments WHERE family_key NOT LIKE 'synthetic-family-%'") } }
        val next = plan(store)
        val selection = store.plans().selection
        assertFailsWith<IllegalArgumentException> { select(store, next) }
        assertEquals(selection, store.plans().selection)
        assertTrue(store.deleteContext(item.contextId))
    }

    @Test fun `retained event shell limit disables automation without rolling back correction`() = fixture { path, store, _ ->
        val item = store.createContext(context())
        val authored = plan(store); select(store, authored)
        sql(path) { db -> db.createStatement().use { s ->
            s.executeUpdate("INSERT INTO constraint_family_enrollments SELECT 'synthetic-full-family',family_json,'synthetic-full-baseline'," +
                "baseline_sequence,baseline_deletion_generation,cursor_sequence,cursor_deletion_generation,0,NULL,50000 FROM constraint_family_enrollments")
            s.executeUpdate("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<50000) " +
                "INSERT INTO constraint_material_events SELECT printf('synthetic-event-%d',x),'synthetic-full-family',1,'context','${item.contextId}',NULL FROM n")
        } }
        store.correctContext(item.contextId, context("30"))
        assertEquals(2, store.contextRevisions(item.contextId).first().revision)
        assertFalse(prepared(store).constraintChanges.available)
        assertTrue(store.deleteContext(item.contextId))
    }

    @Test fun `shared budget charges capture bytes and family multiplied proof work`() {
        val bytes = ConstraintProofBudget()
        bytes.bytes(8 * 1024 * 1024L - 1)
        assertFailsWith<ConstraintProofLimit> { bytes.bytes(2) }
        val work = ConstraintProofBudget()
        work.work(49_000)
        assertFailsWith<ConstraintProofLimit> { work.work(1001) }
    }
}
