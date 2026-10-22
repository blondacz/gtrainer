package com.gtrainer

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.*

/** Linked owner journeys supplement isolated contract/policy tests without invoking a provider. */
class ContextAmendmentJourneyTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private val clock=Clock.fixed(at,ZoneOffset.UTC)
    private val json=ScopedEvidencePreparation.json
    private fun id()=UUID.randomUUID().toString()
    private suspend fun fixture(test:suspend(HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("synthetic-context-amendment-journey-")
        try { HistoryStore(dir.resolve("synthetic.db"),clock).use { test(it) } }
        finally { Files.list(dir).use { files->files.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun context(content:String,category:String="note")=AthleteContextRequest(category,"user_report","Synthetic athlete","2026-10-01",content=content,
        restrictionKind=if(category=="restriction")"blocked_sport"else null,restrictionValue=if(category=="restriction")"run"else null)
    private fun definition()=AuthoredPlanRequest("Synthetic authored base",PlanAuthor("coach","Synthetic unverified coach"),listOf(
        PlannedSession(id(),PlanAuthor("coach","Synthetic unverified coach"),"run","Existing easy run",
            PlanSchedule("timed",startsLocal="2026-10-18T09:00",endsLocal="2026-10-18T10:00",timeZone="UTC"),
            listOf(PlannedTarget("duration","seconds","moving",1800.0,2400.0)),permissions=AmendmentPermissions(
                move=PlanMoveWindow("2026-10-19T09:00","2026-10-20T09:00"),omit=true))),PlanPolicy("UTC",10,0))
    private fun source(store:HistoryStore)=store.publishAdaptationResult(PreparedAdaptationResult.prepare(store.captureAdaptationResult(null,at)),at)

    @Test fun singleEntryConfirmationStaleIllnessRestrictionProposalAndDeletionKeepAuthoritySeparate()=runBlocking { fixture { store ->
        val plans=PlanService(store,clock)
        val base=plans.create(definition());plans.select(PlanSelectionRequest(0,base.reference))
        store.updateReviewSchedule(0,ReviewScheduleConfiguration(timeZone="UTC"),at,null,null)
        val imported=syntheticActivity().copy(sourceRecordId="journey-existing",sport="ride",startLocal="2026-10-14T10:00:00",startInstant="2026-10-14T10:00:00Z")
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(imported)),at)
        val aclText="Reported ACL guidance observed 2026-06-01: no running, ongoing with no end date. PRIVATE_JOURNEY_ACL"
        val illnessText="Improving illness since 2026-10-01; applicability ended 2026-10-05 without a recovery report."
        val dutyText="Climbing every week from 2026-10-19, 09:00 to 10:00 UTC, through 2026-11-02."
        val note=store.createIntake(ContextIntakeRequest("$aclText $illnessText $dutyText","user_report","Synthetic athlete","2026-10-01"),at)
        val extractionPacket=ContextIntakeSuggestionContractV1.packet(note)
        val suggestion=ContextIntakeSuggestionV1("context",0,aclText.length,aclText,
            context=ProposedContextSuggestionV1("restriction","2026-06-01",null,null,aclText,"open","open"),unresolvedFields=emptyList())
        val draft=ContextIntakeSuggestionDraftV1(ContextIntakeSuggestionContractV1.PROFILE,note.intakeId,1,listOf(suggestion),emptyList())
        val decoded=ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(draft),extractionPacket)
        assertTrue(store.contextLifecycle(LocalDate.parse("2026-10-15")).items.isEmpty())
        assertTrue(store.commitments().isEmpty());assertEquals(base,plans.get(base.reference.planId))
        val aclRequest=context(decoded.suggestions.single().context!!.content,"restriction").copy(observedOn="2026-06-01",restrictionKind="blocked_sport",restrictionValue="run")
        val illnessRequest=context(illnessText,"symptom").copy(applicableFrom="2026-10-01",applicableUntil="2026-10-05")
        val duty=CommitmentDefinition("Weekly climbing","commitment","UTC","2026-10-19T09:00","2026-10-19T10:00",CommitmentRecurrence("weekly",1,"2026-11-02"))
        val confirmation=ContextConfirmationRequest(1,listOf(ContextConfirmationItem(aclRequest),
            ContextConfirmationItem(illnessRequest,ContextLifecycle(reviewDueOn="2026-10-10"))),listOf(CommitmentConfirmationItem(duty)),id())
        val saved=store.confirmIntake(note.intakeId,confirmation,at)
        val replay=store.confirmIntake(note.intakeId,confirmation,at)
        assertTrue(replay.replayed);assertEquals(saved.contexts,replay.contexts);assertEquals(saved.commitments,replay.commitments)
        assertEquals(setOf(note.intakeId),store.intakeLinks(note.intakeId).map { it.intakeId }.toSet())
        assertEquals(note.intakeId,saved.commitments.single().reference.intakeId)
        val acl=saved.contexts.single { it.category=="restriction" };val illness=saved.contexts.single { it.category=="symptom" }
        val lifecycle=store.contextLifecycle(LocalDate.parse("2026-10-15")).items.single { it.context.contextId==illness.contextId }
        assertTrue(lifecycle.needsReview);assertEquals("unresolved",lifecycle.lifecycle.resolution)
        assertTrue(plans.amendmentCandidates().candidates.isEmpty())
        val blocked=source(store)
        assertEquals("BLOCKED",blocked.state);assertNull(blocked.packet)
        assertEquals(base,plans.get(base.reference.planId))
        // Explicit user resolution changes operational blocking, not a medical recovery finding.
        store.updateContextLifecycle(illness.contextId,ContextLifecycleUpdate(1,"resolved","2026-10-10"),LocalDate.parse("2026-10-15"))
        val current=source(store)
        assertEquals("PREPARED",current.state)
        val packet=current.packet!!
        assertTrue(packet.context.amendments.mandatoryRestrictions.any { it.contextId==acl.contextId && it.observedOn=="2026-06-01" })
        assertEquals(saved.commitments.single().reference.commitmentId,packet.commitments.flatMap { it.occurrences }.first().reference.commitmentId)
        val calendar=projectCommitments(store.commitments(),"2026-10-15","2026-10-29","UTC")
        assertEquals(listOf("2026-10-19","2026-10-26"),calendar.occurrences.map { it.originalDate })
        val inspection=plans.amendmentSelection(current.snapshotId)
        assertTrue(inspection.packet!!.candidates.all { it.operation=="omit_session" })
        val before=base.definition.sessions.single()
        val input=store.capturePlanAmendment(at).input
        assertTrue(PlanAmendmentPolicy.checkOperation(input,at,before,before.copy(targets=listOf(before.targets.single().copy(maximum=3600.0))),"reduce_duration").isNotEmpty())
        assertTrue(PlanAmendmentPolicy.checkOperation(input,at,before,before.copy(sport="ride"),"move_session").isNotEmpty())
        val candidate=inspection.packet.candidates.single()
        val choice=inspection.packet.explanationChoices.single { it.text.startsWith("Candidate ${candidate.candidateId}:") }
        val selected=AmendmentSelectionDraftV1(AmendmentSelectionContractV1.PROFILE,inspection.packet.candidateSetSha256,"select",listOf(candidate.candidateId),listOf(choice))
        val raw=AmendmentSelectionContractV1.encodeDraft(selected)
        val malformed=raw.dropLast(1)+",\"operations\":[\"increase_duration\"]}"
        val rejected=plans.generateAmendmentProposal(AmendmentValidationRequest(current.snapshotId,inspection.exactPromptBindingSha256!!,malformed))
        assertEquals("rejected",rejected.state)
        val pending=plans.generateAmendmentProposal(AmendmentValidationRequest(current.snapshotId,inspection.exactPromptBindingSha256,raw))
        assertEquals("pending",pending.state)
        assertEquals(base,plans.get(base.reference.planId));assertEquals(calendar,projectCommitments(store.commitments(),"2026-10-15","2026-10-29","UTC"))
        val approval=PlanAmendmentApprovalRequest(id(),base.reference,pending.proposalSha256,pending.proposal!!.selectionGeneration)
        val corrected=plans.correct(base.reference.planId,PlanCorrection(1,1,base.definition.copy(title="Explicit user correction")))
        assertEquals("stale",plans.amendmentProposal(pending.proposalId).state)
        assertFailsWith<PlanConflict> { plans.approveAmendmentProposal(pending.proposalId,approval) }
        assertEquals(corrected,plans.get(base.reference.planId));assertFalse(corrected.definition.sessions.single().omitted)

        val preview=store.previewContextDeletion(acl.contextId,at)
        val source=preview.sources.single()
        val survivor=preview.survivors.single { it.context.contextId==illness.contextId }
        val deletion=ContextDeletionRequest(preview.expectedRevision,preview.digest,
            listOf(SourceRedaction(note.intakeId,source.revision,ContextIntakeRequest("$illnessText $dutyText","user_report","Synthetic athlete","2026-10-01"))),
            listOf(ContextSurvivor(ContextConfirmationItem(illnessRequest,survivor.lifecycle,illness.contextId,survivor.context.revision),note.intakeId)),
            preview.commitments.map { old->CommitmentDisposition(old.reference.commitmentId,old.reference.revision,
                CommitmentConfirmation(note.intakeId,source.revision+1,old.definition)) })
        store.redactAndDeleteContext(acl.contextId,deletion,at)
        assertFailsWith<PlanNotFound> { plans.approveAmendmentProposal(pending.proposalId,approval) }
        assertFailsWith<ScopedEvidenceNotFound> { store.storedScopedEvidence(current.snapshotId) }
        assertFalse(json.encodeToString(store.intakeHistory(note.intakeId)).contains("PRIVATE_JOURNEY_ACL"))
        assertEquals(corrected,plans.get(base.reference.planId))
        assertEquals(listOf(imported),store.history(LocalDate.parse("2026-10-14"),LocalDate.parse("2026-10-14")).activities)
        assertEquals("resolved",store.contextLifecycle(LocalDate.parse("2026-10-15")).items.single().lifecycle.resolution)
    } }

    @Test fun competingRecordingsStayAmbiguousUntilExplicitConfirmationWithoutChangingTheAuthoredPlan()=runBlocking { fixture { store ->
        val service=PlanService(store,clock)
        val session=definition().sessions.single().copy(schedule=PlanSchedule("timed",startsLocal="2026-10-14T09:00",endsLocal="2026-10-14T10:00",timeZone="UTC"))
        val base=service.create(definition().copy(sessions=listOf(session)));service.select(PlanSelectionRequest(0,base.reference))
        val records=listOf("watch","bike-computer").map { key->syntheticActivity().copy(sourceRecordId="journey-$key",sport="run",startLocal="2026-10-14T09:10:00",
            startInstant="2026-10-14T09:10:00Z",timeContext="upstream_instant") }
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,records),at)
        val view=service.reconciliation(PlanReconciliationQuery("2026-10-14","2026-10-14","UTC"))
        assertEquals("ambiguous",view.sessions.single().state)
        assertEquals(2,view.sessions.single().candidateActivityIds.size)
        val request=PlanReconciliationConfirmationRequest(id(),base.reference,session.sessionId,"match",scopedActivityId(records.first()),
            view.selection.generation,null,view.digest,view.asOf,view.query)
        service.confirmReconciliation(request)
        val confirmed=service.reconciliation(view.query)
        assertEquals("matched",confirmed.sessions.single().state);assertEquals("user_confirmation",confirmed.sessions.single().basis)
        assertEquals(base,service.get(base.reference.planId));assertEquals(1L,service.list().selection.generation)
    } }
}
