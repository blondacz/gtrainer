package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContextDeletionTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private fun fixture(test:(Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("synthetic-context-deletion-"); val path=dir.resolve("history.sqlite3")
        try { HistoryStore(path).use { test(path,it) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }
    private fun intake(text:String="synthetic source")=ContextIntakeRequest(text,"user_report","source author","2026-10-01")
    private fun context(text:String="synthetic context",category:String="note",review:String?=null)=
        AthleteContextRequest(category,"user_report","source author","2026-10-01",reviewId=review,content=text)
    private fun confirmation(id:String,revision:Int,definition:CommitmentDefinition)=
        CommitmentConfirmation(id,revision,definition)

    /** A deterministic form-ready request; it preserves all provenance identity and explicitly reconfirms lifecycle. */
    private fun request(store:HistoryStore,preview:ContextDeletionPreview,marker:String="MARKER",keepNotes:Boolean=true):ContextDeletionRequest {
        val sourceNotes=preview.sources.associate { source -> source.intakeId to SourceRedaction(source.intakeId,source.revision,
            intake(source.content.replace(marker,"[removed]"))) }
        val survivors=preview.survivors.map { view ->
            val source=preview.sourceLinks.firstOrNull { it.contextId==view.context.contextId }?.intakeId
            val clean=view.context.content.replace(marker,"[removed]")
            ContextSurvivor(ContextConfirmationItem(context(clean,view.context.category,view.context.reviewId),view.lifecycle,
                view.context.contextId,view.context.revision),if(keepNotes) source else null)
        }
        val commitments=preview.commitments.map { old ->
            val source=sourceNotes[old.reference.intakeId]
            val conf=source?.let { confirmation(it.intakeId,it.expectedRevision+1,old.definition) }
            CommitmentDisposition(old.reference.commitmentId,old.reference.revision,conf)
        }
        return ContextDeletionRequest(preview.expectedRevision,preview.digest,sourceNotes.values.toList(),survivors,commitments)
    }

    @Test fun `preview is read only and stable across repeated reads`()=fixture { path,store ->
        val src=store.createIntake(intake("MARKER original"),at); val target=store.createContext(context("MARKER target"))
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,target.contextId,1))
        val before=Files.readAllBytes(path); val a=store.previewContextDeletion(target.contextId,at); val b=store.previewContextDeletion(target.contextId,at)
        assertEquals(a.digest,b.digest); assertEquals(before.toList(),Files.readAllBytes(path).toList())
    }

    @Test fun `preview digest changes when an association edge is added`()=fixture { _,store ->
        val src=store.createIntake(intake(),at); val target=store.createContext(context())
        val preview=store.previewContextDeletion(target.contextId,at)
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,target.contextId,1))
        assertNotEquals(preview.digest,store.previewContextDeletion(target.contextId,at).digest)
        assertFailsWith<ContextDeletionConflict> { store.redactAndDeleteContext(target.contextId,request(store,preview),at) }
        assertEquals(1,store.contextRevisions(target.contextId).size)
    }

    @Test fun `redaction preserves source and survivor identity while purging old histories`()=fixture { _,store ->
        val src=store.createIntake(intake("MARKER shared"),at)
        val target=store.createContext(context("MARKER target")); val survivor=store.createContext(context("MARKER survivor"))
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,target.contextId,1))
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,survivor.contextId,1))
        store.correctIntake(src.intakeId,ContextIntakeCorrection(1,intake("MARKER prior revision")),at)
        val preview=store.previewContextDeletion(target.contextId,at); val req=request(store,preview)
        store.redactAndDeleteContext(target.contextId,req,at)
        assertEquals(listOf(3),store.intakeHistory(src.intakeId).map { it.revision })
        assertFalse(store.intakeHistory(src.intakeId).any { it.content.contains("MARKER") })
        assertEquals(listOf(2),store.contextRevisions(survivor.contextId).map { it.revision })
        assertEquals("[removed] survivor",store.contextRevisions(survivor.contextId).first().content)
        assertFailsWith<ContextNotFound> { store.contextRevisions(target.contextId) }
    }

    @Test fun `commitment dependency must be explicitly disposed and deletion preserves identity fence`()=fixture { _,store ->
        val src=store.createIntake(intake(),at); val target=store.createContext(context())
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,target.contextId,1))
        val def=CommitmentDefinition("synthetic plan","commitment","UTC","2026-10-16T09:00","2026-10-16T10:00")
        val c=store.confirmCommitment(confirmation(src.intakeId,1,def).copy(contextId=target.contextId,expectedContextRevision=1),at)
        val p=store.previewContextDeletion(target.contextId,at)
        assertFailsWith<ContextDeletionConflict> { store.redactAndDeleteContext(target.contextId,request(store,p).copy(commitments=emptyList()),at) }
        assertEquals(1,store.commitmentHistory(c.reference.commitmentId).size)
        val valid=request(store,p).copy(commitments=listOf(CommitmentDisposition(c.reference.commitmentId,1,null)))
        store.redactAndDeleteContext(target.contextId,valid,at)
        assertFailsWith<CommitmentNotFound> { store.commitmentHistory(c.reference.commitmentId) }
    }

    @Test fun `unlinked target deletes without demanding source replacement`()=fixture { _,store ->
        val target=store.createContext(context("standalone")); val p=store.previewContextDeletion(target.contextId,at)
        assertTrue(p.sources.isEmpty()); assertTrue(p.survivors.isEmpty())
        store.redactAndDeleteContext(target.contextId,request(store,p),at)
        assertFailsWith<ContextNotFound> { store.contextRevisions(target.contextId) }
    }

    @Test fun `late purge trigger failure rolls all replacement writes back`()=fixture { path,store ->
        val src=store.createIntake(intake("MARKER source"),at); val target=store.createContext(context("MARKER target"))
        val sibling=store.createContext(context("MARKER sibling"))
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,target.contextId,1)); store.associateIntake(src.intakeId,ContextIntakeAssociation(1,sibling.contextId,1))
        val p=store.previewContextDeletion(target.contextId,at); val req=request(store,p)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("CREATE TRIGGER synthetic_deletion_failure BEFORE DELETE ON athlete_context BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
            assertFailsWith<SQLException> { store.redactAndDeleteContext(target.contextId,req,at) }
            assertEquals(listOf(1),store.intakeHistory(src.intakeId).map { it.revision })
            assertEquals(listOf(1),store.contextRevisions(sibling.contextId).map { it.revision })
            assertTrue(store.intakeHistory(src.intakeId).single().content.contains("MARKER"))
            db.createStatement().use { it.execute("DROP TRIGGER synthetic_deletion_failure") }
        }
    }

    @Test fun `stale source revision conflicts without changing target`()=fixture { _,store ->
        val src=store.createIntake(intake("MARKER source"),at); val target=store.createContext(context())
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,target.contextId,1)); val p=store.previewContextDeletion(target.contextId,at)
        val req=request(store,p)
        store.correctIntake(src.intakeId,ContextIntakeCorrection(1,intake("new source")),at)
        assertFailsWith<ContextDeletionConflict> { store.redactAndDeleteContext(target.contextId,req,at) }
        assertEquals(listOf(target),store.contextRevisions(target.contextId))
    }

    @Test fun `every revision of each reached context is covered by preview digest`()=fixture { _,store ->
        val target=store.createContext(context("target")); store.correctContext(target.contextId,context("target newer"))
        val p=store.previewContextDeletion(target.contextId,at)
        assertEquals(2,p.expectedRevision)
        assertEquals(p.digest,store.previewContextDeletion(target.contextId,at).digest)
        store.redactAndDeleteContext(target.contextId,request(store,p),at)
        assertFailsWith<ContextNotFound> { store.contextRevisions(target.contextId) }
    }

    @Test fun `missing survivor replacement is rejected before writes`()=fixture { _,store ->
        val src=store.createIntake(intake("MARKER"),at); val target=store.createContext(context()); val sibling=store.createContext(context("keep"))
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,target.contextId,1)); store.associateIntake(src.intakeId,ContextIntakeAssociation(1,sibling.contextId,1))
        val p=store.previewContextDeletion(target.contextId,at); val req=request(store,p)
        assertFailsWith<ContextDeletionConflict> { store.redactAndDeleteContext(target.contextId,req.copy(survivors=emptyList()),at) }
        assertEquals(listOf(1),store.intakeHistory(src.intakeId).map { it.revision })
        assertEquals(listOf(sibling),store.contextRevisions(sibling.contextId))
    }

    private fun snapshot(store:HistoryStore,id:String,contexts:List<AthleteContext>,reviewFilter:String?=id):ConnectedReviewStatus {
        val date=java.time.LocalDate.parse("2026-10-15")
        val evidence=AnalysisInput(1,"a".repeat(64),at.toString(),null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        return store.createConnectedReviewSnapshot(ConnectedReviewBinding(id,evidence,
            retrieveAthleteContext(contexts,ContextRetrievalQuery(date,reviewId=reviewFilter,hardPacketLimit=10)),
            "2026-10-01",date.toString(),null,ConnectedReviewProviderSelection("synthetic","synthetic","local"),
            InterpretationContractV1.PROFILE),at)
    }

    @Test fun `legacy delete recursively purges feedback identity and dependent snapshots but leaves independent input`()=fixture { _,store ->
        val target=store.createContext(context("target")); val independent=store.createContext(context("independent"))
        val first=snapshot(store,"feedback-a",listOf(target,independent))
        store.publishConnectedReview(first.snapshotId,ValidatedConnectedReviewOutput("MARKER output"),first.evidenceDigest,first.context,first.provider,first.model,first.contractVersion)
        val feedback=store.recordReviewFeedback(first.snapshotId,ReviewFeedbackRequest(correction="feedback marker"),java.time.LocalDate.parse("2026-10-15"))
        val updatedFeedback=store.correctContext(feedback.contextId,context("MARKER revised feedback",review=null))
        val second=snapshot(store,"feedback-b",listOf(updatedFeedback))
        assertTrue(store.deleteContext(target.contextId))
        assertFailsWith<ContextNotFound> { store.contextRevisions(feedback.contextId) }
        assertEquals(listOf(independent),store.contextRevisions(independent.contextId))
        assertFailsWith<ConnectedReviewNotFound> { store.connectedReviewStatus(first.snapshotId) }
        assertFailsWith<ConnectedReviewNotFound> { store.connectedReviewStatus(second.snapshotId) }
        assertFailsWith<ContextRevisionConflict> { snapshot(store,"stale-target",listOf(target)) }
        assertFailsWith<ContextRevisionConflict> { snapshot(store,"stale-feedback",listOf(feedback),reviewFilter=first.snapshotId) }
    }

    @Test fun `redaction removes confirmation replay receipt and marker from every stored text column`()=fixture { path,store ->
        val source=store.createIntake(intake("MARKER private source"),at)
        val originalRequest=ContextConfirmationRequest(1,listOf(ContextConfirmationItem(context("MARKER assertion"))),emptyList(),UUID.randomUUID().toString())
        val made=store.confirmIntake(source.intakeId,originalRequest,at).contexts.single()
        val preview=store.previewContextDeletion(made.contextId,at)
        store.redactAndDeleteContext(made.contextId,request(store,preview),at)
        assertFailsWith<ContextIntakeConflict> { store.confirmIntake(source.intakeId,originalRequest,at) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { s -> s.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { tables ->
                while(tables.next()) {
                    val table=tables.getString(1)
                    val names=db.createStatement().use { q -> q.executeQuery("PRAGMA table_info(\"$table\")").use { columns ->
                        buildList { while(columns.next()) if(columns.getString("type").contains("TEXT",true)) add(columns.getString("name")) }
                    } }
                    names.forEach { name -> db.createStatement().use { q -> q.executeQuery("SELECT \"$name\" FROM \"$table\" WHERE \"$name\" LIKE '%MARKER%' LIMIT 1").use { rows -> assertFalse(rows.next(),"marker retained in $table.$name") } } }
                }
            } }
        }
        assertTrue(store.intakeHistory(source.intakeId).single().content.contains("[removed]"))
    }

    @Test fun `retained commitment is revised redacted detached and event survives`()=fixture { _,store ->
        val src=store.createIntake(intake("MARKER source"),at); val target=store.createContext(context("MARKER target")); val keep=store.createContext(context("MARKER keep"))
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,target.contextId,1)); store.associateIntake(src.intakeId,ContextIntakeAssociation(1,keep.contextId,1))
        val eventId=UUID.randomUUID().toString(); store.saveEvent(ManualEvent(eventId,"2026-10-16","2026-10-16","run","race","independent event"))
        val definition=CommitmentDefinition("MARKER commitment","commitment","UTC","2026-10-16T09:00","2026-10-16T10:00")
        val old=store.confirmCommitment(confirmation(src.intakeId,1,definition).copy(contextId=target.contextId,expectedContextRevision=1,eventId=eventId),at)
        val p=store.previewContextDeletion(target.contextId,at); val req=request(store,p)
        val disp=CommitmentDisposition(old.reference.commitmentId,1,CommitmentConfirmation(src.intakeId,2,
            definition.copy(label="[removed] commitment"),eventId=eventId))
        store.redactAndDeleteContext(target.contextId,req.copy(commitments=listOf(disp)),at)
        val history=store.commitmentHistory(old.reference.commitmentId)
        assertEquals(listOf(2),history.map { it.reference.revision }); assertEquals(old.reference.commitmentId,history.single().reference.commitmentId)
        assertEquals(null,history.single().reference.contextId); assertEquals(eventId,history.single().eventId)
        assertEquals(1,store.events().count { it.id==eventId })
        assertEquals(listOf(2),store.intakeHistory(src.intakeId).map { it.revision })
    }

    @Test fun `commitment-only source is included by transitive preview and must be replaced`()=fixture { _,store ->
        val source=store.createIntake(intake("MARKER commitment source"),at)
        val target=store.createContext(context("target")); val linked=store.createIntake(intake("context source"),at)
        store.associateIntake(linked.intakeId,ContextIntakeAssociation(1,target.contextId,1))
        val def=CommitmentDefinition("plan","commitment","UTC","2026-10-16T09:00","2026-10-16T10:00")
        store.confirmCommitment(confirmation(source.intakeId,1,def).copy(contextId=target.contextId,expectedContextRevision=1),at)
        val p=store.previewContextDeletion(target.contextId,at)
        assertTrue(p.sources.any { it.intakeId==source.intakeId })
        assertFailsWith<ContextDeletionConflict> { store.redactAndDeleteContext(target.contextId,request(store,p).copy(sources=request(store,p).sources.filterNot { it.intakeId==source.intakeId }),at) }
        assertEquals("MARKER commitment source",store.intakeHistory(source.intakeId).single().content)
    }

    @Test fun `revision one past the supported context range is rejected before any mutation`()=fixture { path,store ->
        val source=store.createIntake(intake("MARKER source"),at); val target=store.createContext(context("target")); val survivor=store.createContext(context("survivor"))
        store.associateIntake(source.intakeId,ContextIntakeAssociation(1,target.contextId,1)); store.associateIntake(source.intakeId,ContextIntakeAssociation(1,survivor.contextId,1))
        DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.prepareStatement("UPDATE athlete_context SET revision=1000 WHERE context_id=?").use { it.setString(1,survivor.contextId); it.executeUpdate() } }
        val originalSource=store.intakeHistory(source.intakeId); val p=store.previewContextDeletion(target.contextId,at)
        assertFailsWith<IllegalArgumentException> { request(store,p) }
        assertEquals(originalSource,store.intakeHistory(source.intakeId)); assertEquals(1,store.contextRevisions(target.contextId).size)
        assertEquals(1000,store.contextRevisions(survivor.contextId).single().revision)
    }

    @Test fun `receipt graph work limit aborts preview without changing database`()=fixture { path,store ->
        val target=store.createContext(context("target"))
        val unrelatedSource=store.createIntake(intake("unrelated receipt source"),at)
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.autoCommit=false
            db.prepareStatement("INSERT INTO context_confirmation_receipts(confirmation_id,intake_id,intake_revision,request_sha256,refs_json) VALUES(?,?,1,?,?)").use { p ->
                repeat(50_001) { n -> p.setString(1,UUID.randomUUID().toString()); p.setString(2,unrelatedSource.intakeId); p.setString(3,"a".repeat(64)); p.setString(4,"{\"contexts\":[],\"commitments\":[]}"); p.addBatch(); if(n%1000==999) p.executeBatch() }; p.executeBatch()
            }
            db.commit()
        }
        val receiptCount=DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { q -> q.executeQuery("SELECT count(*) FROM context_confirmation_receipts").use { it.next();it.getInt(1) } } }
        assertFailsWith<ContextDeletionLimit> { store.previewContextDeletion(target.contextId,at) }
        assertEquals(receiptCount,DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { q -> q.executeQuery("SELECT count(*) FROM context_confirmation_receipts").use { it.next();it.getInt(1) } } })
        assertEquals(listOf(target),store.contextRevisions(target.contextId))
    }

    @Test fun `new transitive context edge invalidates preview instead of losing added sibling`()=fixture { _,store ->
        val src=store.createIntake(intake("shared"),at); val target=store.createContext(context("target")); val sibling=store.createContext(context("sibling"))
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,target.contextId,1)); val p=store.previewContextDeletion(target.contextId,at)
        store.associateIntake(src.intakeId,ContextIntakeAssociation(1,sibling.contextId,1))
        assertFailsWith<ContextDeletionConflict> { store.redactAndDeleteContext(target.contextId,request(store,p),at) }
        assertEquals(listOf(sibling),store.contextRevisions(sibling.contextId))
    }
}
