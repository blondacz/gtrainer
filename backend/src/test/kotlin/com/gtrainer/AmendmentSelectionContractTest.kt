package com.gtrainer

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlin.test.*

class AmendmentSelectionContractTest {
    private fun packet(): Pair<AmendmentSelectionPacketV1, AmendmentCandidateV1> {
        val records=listOf(syntheticActivity())
        val request=ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="custom",oldest="2020-06-01",newest="2020-06-02",intent="review"),purpose="adaptation")
        val scope=ReviewScopeResolver.resolve(request.selection,Instant.parse("2020-06-10T12:00:00Z"),"UTC",records.associateBy(::scopedActivityId))
        val read=CapturedScopedRead(scope,HistoryResponse(records,emptyList()),emptyList(),emptyList())
        val captured=CapturedScopedEvidence(read,emptyList(),emptyList(),emptyMap(),emptyList(),PlanSelection(0,null),null,0)
        val prepared=ScopedEvidencePreparation.packet(request,captured,ScopedTrends.report(scope,read.history,emptyList()))
        val author=PlanAuthor("user","Synthetic author")
        val before=PlannedSession(UUID.randomUUID().toString(),author,"run","Authored session",PlanSchedule("date_only",date="2020-06-12",timeZone="UTC"),
            targets=listOf(PlannedTarget("duration","seconds","moving",1800.0,3600.0)))
        val plan=PlanRef(UUID.randomUUID().toString(),2)
        val evidence=ScopedDescriptiveContractV1.packet(prepared.copy(plan=ScopedPlanEvidence(PlanSelection(1,plan),plan,author,"Authored plan",PlanPolicy("UTC",7,0),listOf(before))))
        val candidate=AmendmentCandidateV1("c".repeat(64),before.sessionId,"omit_session",before,before.copy(omitted=true))
        return AmendmentSelectionContractV1.packet(UUID.randomUUID().toString(),"d".repeat(64),evidence,listOf(candidate)) to candidate
    }

    private fun valid(packet: AmendmentSelectionPacketV1,candidate: AmendmentCandidateV1): AmendmentSelectionDraftV1 {
        val choice=packet.explanationChoices.single { it.text.startsWith("Candidate ${candidate.candidateId}:") }
        return AmendmentSelectionDraftV1(AmendmentSelectionContractV1.PROFILE,packet.candidateSetSha256,"select",listOf(candidate.candidateId),listOf(choice))
    }

    private fun decode(draft: AmendmentSelectionDraftV1,packet: AmendmentSelectionPacketV1)=
        AmendmentSelectionContractV1.decodeDraft(AmendmentSelectionContractV1.encodeDraft(draft),packet)

    @Test fun `only exact candidate-bound explanation survives whole-draft validation`() {
        val (packet,candidate)=packet(); val draft=valid(packet,candidate)
        assertEquals(draft,decode(draft,packet))
        val text=draft.explanations.single().text
        val changes=listOf("2020-06-12" to "2020-06-13", "whole-day" to "two-hours", "1800.0..3600.0" to "1800.0..7200.0",
            "seconds" to "minutes", "not verified coach/clinician guidance" to "clinician verified", "Candidate" to "Safe")
        changes.forEach { (from,to) -> assertFails { decode(draft.copy(explanations=listOf(draft.explanations.single().copy(text=text.replace(from,to)))),packet) } }
        assertFails { decode(draft.copy(explanations=listOf(draft.explanations.single().copy(text=text.replace(" ","\u00a0",ignoreCase=false)))),packet) }
        assertFails { decode(draft.copy(explanations=listOf(draft.explanations.single().copy(uncertainty="low"))),packet) }
        val alteredSource=draft.explanations.single().copy(sources=listOf(draft.explanations.single().sources.single().copy(revision=1)))
        assertFails { decode(draft.copy(explanations=listOf(alteredSource)),packet) }
    }

    @Test fun `generic outcomes require only their exact generic choice`() {
        val (packet,candidate)=packet()
        for(outcome in listOf("no_change","needs_clarification")) {
            val phrase=if(outcome=="no_change") "No amendment candidate" else "Clarification is requested"
            val choice=packet.explanationChoices.single { it.text.startsWith(phrase) }
            val draft=AmendmentSelectionDraftV1(AmendmentSelectionContractV1.PROFILE,packet.candidateSetSha256,outcome,emptyList(),listOf(choice))
            assertEquals(draft,decode(draft,packet))
            assertFails { decode(draft.copy(explanations=listOf(valid(packet,candidate).explanations.single())),packet) }
            assertFails { decode(draft.copy(explanations=listOf(choice,choice)),packet) }
        }
    }

    @Test fun `selection cannot omit selected explanation or add unselected explanation`() {
        val (packet,candidate)=packet(); val draft=valid(packet,candidate)
        assertFails { decode(draft.copy(explanations=emptyList()),packet) }
        val generic=packet.explanationChoices.first()
        assertFails { decode(draft.copy(explanations=listOf(draft.explanations.single(),generic)),packet) }
        assertFails { decode(draft.copy(candidateIds=listOf("e".repeat(64))),packet) }
        assertFails { decode(draft.copy(outcome="invented"),packet) }
        assertFails { AmendmentSelectionContractV1.decodeDraft("{}",packet) }
        assertFails { AmendmentSelectionContractV1.decodeDraft(AmendmentSelectionContractV1.encodeDraft(draft).dropLast(1)+",\"extra\":true}",packet) }
    }

    @Test fun `correction prompt shares exact UTF8 escaping budget with first packet`() {
        val (base,candidate)=packet();val json=ScopedEvidencePreparation.json
        val contexts=(1..6).map { index -> AthleteContext(UUID.nameUUIDFromBytes("context$index".toByteArray()).toString(),1,"restriction","user_report","synthetic",
            "authenticated_user","2020-06-01",content="synthetic",restrictionKind="blocked_sport",restrictionValue="ride") }
        fun padded(n:Int):AmendmentSelectionPacketV1 {
            val evidence=base.evidence.evidence
            val context=evidence.context.copy(evidence=evidence.context.evidence.copy(mandatoryRestrictions=contexts.map { it.copy(content="é\"".repeat(n)) }))
            return AmendmentSelectionContractV1.packet(base.sourceSnapshotId,base.dependencyBindingSha256,
                ScopedDescriptiveContractV1.packet(evidence.copy(context=context)),listOf(candidate))
        }
        var low=0;var high=2000
        while(low<high) {
            val mid=(low+high+1)/2
            if(runCatching { AmendmentSelectionContractV1.prompt(padded(mid)) }.isSuccess)low=mid else high=mid-1
        }
        assertTrue(low in 1..1999)
        val justFits=padded(low);val first=AmendmentSelectionContractV1.prompt(justFits)
        assertTrue(json.encodeToString(first).toByteArray(Charsets.UTF_8).size<=32*1024)
        assertFailsWith<IllegalArgumentException> { ConnectedReviewInvocation.amendment(justFits) }
        assertFailsWith<IllegalArgumentException> { AmendmentSelectionContractV1.prompt(padded(low+1)) }
        assertNotNull(ConnectedReviewInvocation.amendment(padded(0)).exactCorrectionPrompt)
    }
}
