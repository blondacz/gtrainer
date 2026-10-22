package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextConfirmationTest {
    private fun fixture(test: (Path, HistoryStore) -> Unit) {
        val directory=Files.createTempDirectory("context-confirmation-")
        val path=directory.resolve("synthetic.sqlite3")
        try { HistoryStore(path).use { test(path,it) } }
        finally { Files.list(directory).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(directory) }
    }
    private fun intake(text:String="synthetic note")=ContextIntakeRequest(text,"user_report","synthetic author","2020-06-01")
    private fun context(text:String="synthetic symptom", category:String="symptom")=
        AthleteContextRequest(category,"user_report","synthetic author","2020-06-01",content=text)
    private fun definition(label:String="synthetic training")=CommitmentDefinition(label,"commitment","UTC","2020-06-02T09:00","2020-06-02T10:00")
    private val at=Instant.parse("2020-06-10T12:00:00Z")
    private fun request(revision:Int, contexts:List<ContextConfirmationItem> = emptyList(), commitments:List<CommitmentConfirmationItem> = emptyList(), id:String=UUID.randomUUID().toString())=
        ContextConfirmationRequest(revision,contexts,commitments,id)

    @Test fun `saving source note alone is inert and batch creates provenance-linked items`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        assertTrue(store.contexts(java.time.LocalDate.parse("2020-06-10")).isEmpty())
        assertTrue(store.commitments().isEmpty())
        assertTrue(store.intakeLinks(source.intakeId).isEmpty())
        val result=store.confirmIntake(source.intakeId,request(1,
            listOf(ContextConfirmationItem(context())),listOf(CommitmentConfirmationItem(definition()))),at)
        val saved=result.contexts.single(); val commitment=result.commitments.single()
        assertEquals(listOf(saved.contextId),store.intakeLinks(source.intakeId).map { it.contextId })
        assertEquals(1,store.intakeLinks(source.intakeId).single().contextRevision)
        assertEquals(source.intakeId,commitment.reference.intakeId)
        assertEquals(1,commitment.reference.intakeRevision)
        assertEquals("synthetic author",commitment.authorAttribution)
        assertEquals("authenticated_user",saved.enteredBy)
        assertEquals("unresolved",store.contextLifecycle(java.time.LocalDate.parse("2020-06-10")).items.single().lifecycle.resolution)
    }

    @Test fun `confirmation honors explicit lifecycle and keeps retired state`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        val original=store.createContext(context("retired assertion"))
        store.associateIntake(source.intakeId,ContextIntakeAssociation(1,original.contextId,original.revision))
        assertTrue(store.retireContext(original.contextId))
        val retired=store.contextRevisions(original.contextId).first()
        val result=store.confirmIntake(source.intakeId,request(1,listOf(
            ContextConfirmationItem(context("confirmed revision"),ContextLifecycle("resolved","2020-07-01"),original.contextId,retired.revision))),at)
        assertEquals(retired.revision+1,result.contexts.single().revision)
        assertTrue(result.contexts.single().retired)
        val life=store.contextLifecycle(java.time.LocalDate.parse("2020-06-10")).items.single().lifecycle
        assertEquals("resolved",life.resolution); assertEquals("2020-07-01",life.reviewDueOn)
    }

    @Test fun `source correction flags association while reconfirmation appends exact context revision`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        val original=store.confirmIntake(source.intakeId,request(1,listOf(ContextConfirmationItem(context()))),at).contexts.single()
        store.correctIntake(source.intakeId,ContextIntakeCorrection(1,intake("corrected note")),at)
        assertTrue(store.intakeLinks(source.intakeId).single().sourceChanged)
        assertEquals(listOf(original),store.contextRevisions(original.contextId))
        val revised=store.confirmIntake(source.intakeId,request(2,listOf(
            ContextConfirmationItem(context("same identity, updated text"),contextId=original.contextId,expectedContextRevision=1))),at).contexts.single()
        assertEquals(original.contextId,revised.contextId); assertEquals(2,revised.revision)
        assertEquals(listOf(2,1),store.intakeLinks(source.intakeId).map { it.contextRevision }.sortedDescending())
        assertTrue(store.intakeLinks(source.intakeId).first { it.contextRevision==2 }.let { !it.sourceChanged })
        assertTrue(store.intakeLinks(source.intakeId).first { it.contextRevision==1 }.sourceChanged)
        assertEquals(listOf(2,1),store.contextRevisions(original.contextId).map { it.revision })
    }

    @Test fun `foreign provenance and mismatched source attribution reject without writes`() = fixture { _,store ->
        val source=store.createIntake(intake(),at); val foreign=store.createIntake(intake("foreign"),at)
        val item=store.confirmIntake(foreign.intakeId,request(1,listOf(ContextConfirmationItem(context()))),at).contexts.single()
        assertFailsWith<ContextIntakeConflict> { store.confirmIntake(source.intakeId,request(1,listOf(
            ContextConfirmationItem(context("cannot steal",category="note"),contextId=item.contextId,expectedContextRevision=1))),at) }
        assertFailsWith<IllegalArgumentException> { store.confirmIntake(source.intakeId,request(1,listOf(
            ContextConfirmationItem(context().copy(authorAttribution="different author")))),at) }
        val unlinked=store.createContext(context("not automatically adopted"))
        assertFailsWith<ContextIntakeConflict> { store.confirmIntake(source.intakeId,request(1,listOf(
            ContextConfirmationItem(context(),contextId=unlinked.contextId,expectedContextRevision=1))),at) }
        assertTrue(store.intakeLinks(source.intakeId).isEmpty())
        assertEquals(listOf(item),store.contextRevisions(item.contextId))
    }

    @Test fun `late stale revision rolls back earlier context and commitment writes`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        val prior=store.confirmIntake(source.intakeId,request(1,listOf(ContextConfirmationItem(context("already exists")))),at).contexts.single()
        val oldCommit=store.confirmCommitment(CommitmentConfirmation(source.intakeId,1,definition("old")),at)
        assertFailsWith<ContextRevisionConflict> { store.confirmIntake(source.intakeId,request(1,
            listOf(ContextConfirmationItem(context("first new")),ContextConfirmationItem(context("stale"),contextId=prior.contextId,expectedContextRevision=2)),
            listOf(CommitmentConfirmationItem(definition("must rollback")))),at) }
        assertEquals(listOf(prior),store.contextRevisions(prior.contextId))
        assertEquals(1,store.commitmentHistory(oldCommit.reference.commitmentId).size)
        assertTrue(store.commitments().none { it.definition.label=="must rollback" })
        assertEquals(listOf(prior.contextId),store.intakeLinks(source.intakeId).map { it.contextId })
    }

    @Test fun `link trigger failure rolls back batch inserts`() = fixture { path,store ->
        val source=store.createIntake(intake(),at)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_confirmation_failure BEFORE INSERT ON context_intake_links BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
            assertFailsWith<SQLException> { store.confirmIntake(source.intakeId,request(1,
                listOf(ContextConfirmationItem(context())),listOf(CommitmentConfirmationItem(definition()))),at) }
            assertTrue(store.contexts(java.time.LocalDate.parse("2020-06-10")).isEmpty())
            assertTrue(store.commitments().isEmpty()); assertTrue(store.intakeLinks(source.intakeId).isEmpty())
            db.createStatement().use { it.execute("DROP TRIGGER synthetic_confirmation_failure") }
        }
    }

    @Test fun `commitment correction retains identity and optional associations`() = fixture { _,store ->
        val source=store.createIntake(intake(),at); val ctx=store.confirmIntake(source.intakeId,request(1,listOf(ContextConfirmationItem(context()))),at).contexts.single()
        val eventId="00000000-0000-4000-8000-000000000001"
        store.saveEvent(ManualEvent(eventId,"2020-06-02","2020-06-02","run","goal","synthetic"))
        val original=store.confirmCommitment(CommitmentConfirmation(source.intakeId,1,definition(),ctx.contextId,1,eventId),at)
        val corrected=store.confirmIntake(source.intakeId,request(1,commitments=listOf(
            CommitmentConfirmationItem(definition("corrected"),original.reference.commitmentId,1))),at).commitments.single()
        assertEquals(original.reference.commitmentId,corrected.reference.commitmentId)
        assertEquals(ctx.contextId,corrected.reference.contextId); assertEquals(1,corrected.reference.contextRevision)
        assertEquals(eventId,corrected.eventId)
        assertEquals(listOf(2,1),store.commitmentHistory(corrected.reference.commitmentId).map { it.reference.revision })
    }

    @Test fun `optional stale context reference conflicts rather than detaching`() = fixture { _,store ->
        val source=store.createIntake(intake(),at); val ctx=store.confirmIntake(source.intakeId,request(1,listOf(ContextConfirmationItem(context()))),at).contexts.single()
        assertFailsWith<CommitmentConflict> { store.confirmIntake(source.intakeId,request(1,
            commitments=listOf(CommitmentConfirmationItem(definition(),context=ContextRevisionRefV1(ctx.contextId,2)))),at) }
        assertTrue(store.commitments().isEmpty())
    }

    @Test fun `confirmation request limits and duplicate IDs are validated`() {
        val duplicate="00000000-0000-4000-8000-000000000001"
        assertFailsWith<IllegalArgumentException> { ContextConfirmationRequest(1,listOf(
            ContextConfirmationItem(context(),contextId=duplicate,expectedContextRevision=1),
            ContextConfirmationItem(context("duplicate"),contextId=duplicate,expectedContextRevision=1)),confirmationId=UUID.randomUUID().toString()) }
        assertFailsWith<IllegalArgumentException> { ContextConfirmationRequest(1,(1..21).map { ContextConfirmationItem(context("item $it")) },confirmationId=UUID.randomUUID().toString()) }
    }

    @Test fun `same confirmation ID replays exact references without writes or plaintext receipt`() = fixture { path,store ->
        val source=store.createIntake(intake(),at)
        val originalRequest=request(1,listOf(ContextConfirmationItem(context())),listOf(CommitmentConfirmationItem(definition())))
        val first=store.confirmIntake(source.intakeId,originalRequest,at)
        val replay=store.confirmIntake(source.intakeId,originalRequest,at)
        assertTrue(replay.replayed)
        assertEquals(first.contexts, replay.contexts); assertEquals(first.commitments,replay.commitments)
        assertEquals(1,store.contextRevisions(first.contexts.single().contextId).size)
        assertEquals(1,store.commitmentHistory(first.commitments.single().reference.commitmentId).size)
        assertFailsWith<ContextIntakeConflict> {
            store.confirmIntake(source.intakeId,originalRequest.copy(contexts=listOf(ContextConfirmationItem(context("altered")))),at)
        }
        assertFailsWith<ContextIntakeConflict> { store.confirmIntake(store.createIntake(intake("other"),at).intakeId,originalRequest,at) }
        val newAction=store.confirmIntake(source.intakeId,originalRequest.copy(confirmationId=UUID.randomUUID().toString()),at)
        assertFalse(newAction.replayed)
        assertTrue(newAction.contexts.single().contextId!=first.contexts.single().contextId)
        assertEquals(2,store.contexts(java.time.LocalDate.parse("2020-06-10")).size)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.prepareStatement("SELECT refs_json FROM context_confirmation_receipts WHERE confirmation_id=?").use { q ->
                q.setString(1,originalRequest.confirmationId); q.executeQuery().use { rows ->
                    assertTrue(rows.next()); val refs=rows.getString(1)
                    assertFalse(refs.contains("synthetic symptom")); assertFalse(refs.contains("synthetic training"))
                    assertFalse(refs.contains("synthetic note"))
                }
            }
        }
    }

    @Test fun `receipt trigger failure rolls back confirmation and retry after source correction preserves original refs`() = fixture { path,store ->
        val source=store.createIntake(intake(),at)
        val firstRequest=request(1,listOf(ContextConfirmationItem(context())))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_receipt_failure BEFORE INSERT ON context_confirmation_receipts BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
            assertFailsWith<SQLException> { store.confirmIntake(source.intakeId,firstRequest,at) }
            assertTrue(store.intakeLinks(source.intakeId).isEmpty())
            assertTrue(store.contexts(java.time.LocalDate.parse("2020-06-10")).isEmpty())
            db.createStatement().use { it.execute("DROP TRIGGER synthetic_receipt_failure") }
        }
        val saved=store.confirmIntake(source.intakeId,firstRequest,at).contexts.single()
        store.correctIntake(source.intakeId,ContextIntakeCorrection(1,intake("corrected after confirmation")),at)
        val replay=store.confirmIntake(source.intakeId,firstRequest,at)
        assertTrue(replay.replayed); assertEquals(saved,replay.contexts.single())
        assertEquals(listOf(1),store.contextRevisions(saved.contextId).map { it.revision })
        assertEquals(listOf(1),store.intakeLinks(source.intakeId).map { it.contextRevision })
    }

    @Test fun `retry after later linked context revision returns original revision`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        val firstRequest=request(1,listOf(ContextConfirmationItem(context("original"))))
        val original=store.confirmIntake(source.intakeId,firstRequest,at).contexts.single()
        val revised=store.confirmIntake(source.intakeId,request(1,listOf(
            ContextConfirmationItem(context("later edit"),contextId=original.contextId,expectedContextRevision=1))),at).contexts.single()
        assertEquals(2,revised.revision)
        val replay=store.confirmIntake(source.intakeId,firstRequest,at)
        assertTrue(replay.replayed); assertEquals(original,replay.contexts.single())
        assertEquals(listOf(2,1),store.contextRevisions(original.contextId).map { it.revision })
    }

    @Test fun `batch can explicitly link a calendar item to its resulting confirmed context revision`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        val created=store.confirmIntake(source.intakeId,request(1,listOf(ContextConfirmationItem(context())),
            listOf(CommitmentConfirmationItem(definition(),contextItemIndex=0))),at)
        val context=created.contexts.single(); val commitment=created.commitments.single()
        assertEquals(context.contextId,commitment.reference.contextId)
        assertEquals(context.revision,commitment.reference.contextRevision)
        val updated=store.confirmIntake(source.intakeId,request(1,listOf(ContextConfirmationItem(context("updated"),contextId=context.contextId,expectedContextRevision=1)),
            listOf(CommitmentConfirmationItem(definition(),commitment.reference.commitmentId,1,contextItemIndex=0))),at)
        assertEquals(2,updated.commitments.single().reference.contextRevision)
        assertEquals(commitment.reference.commitmentId,updated.commitments.single().reference.commitmentId)
        assertFalse(updated.commitments.single().needsReconfirmation)
        assertFailsWith<IllegalArgumentException> { request(1,commitments=listOf(CommitmentConfirmationItem(definition(),contextItemIndex=0))) }
    }

    @Test fun `concurrent identical confirmations create once and replay without invalidating later reviews`() = fixture { _,store ->
        val source=store.createIntake(intake(),at)
        val input=request(1,listOf(ContextConfirmationItem(context())))
        val gate=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val calls=(1..2).map { pool.submit<ContextConfirmationResult> { gate.await(); store.confirmIntake(source.intakeId,input,at) } }
            gate.countDown()
            val results=calls.map { it.get() }
            assertEquals(1,results.count { it.replayed })
            val context=results.first().contexts.single()
            assertEquals(listOf(context),store.contextRevisions(context.contextId))
            val date=java.time.LocalDate.parse("2020-06-10")
            val evidence=AnalysisInput(1,"a".repeat(64),at.toString(),null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
            val review=store.createConnectedReviewSnapshot(ConnectedReviewBinding("confirmation-replay",evidence,
                retrieveAthleteContext(listOf(context),ContextRetrievalQuery(date,hardPacketLimit=10)),
                "2020-06-01",date.toString(),null,ConnectedReviewProviderSelection("synthetic","synthetic","local"),InterpretationContractV1.PROFILE),at)
            assertTrue(store.confirmIntake(source.intakeId,input,at).replayed)
            assertEquals(review,store.connectedReviewStatus(review.snapshotId))
        } finally { pool.shutdownNow() }
    }
}
