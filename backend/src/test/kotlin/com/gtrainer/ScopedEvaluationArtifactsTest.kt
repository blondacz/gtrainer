package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith

class ScopedEvaluationArtifactsTest {
    private val json=Json { encodeDefaults=true }

    private fun descriptive(id:String)=json.decodeFromString<ScopedDescriptivePacketV1>(ScopedEvaluationFixtures.descriptive(id).exactPrompt.userPacketJson).evidence

    @Test fun concreteScopeWellnessAndComparisonCasesRemainDistinct() {
        val display=descriptive("d01");val assessed=descriptive("d02")
        assertEquals(display.facts.sessions,assessed.facts.sessions)
        assertTrue(display.facts.wellness.isEmpty());assertTrue(assessed.facts.wellness.isNotEmpty())
        assertEquals(setOf("oct-run-1","oct-run-2"),descriptive("d03").facts.sessions.map { it.sourceRecordId }.toSet())
        assertTrue(descriptive("d04").facts.sessionComparisons.isNotEmpty())
        val mixed=descriptive("d05")
        assertEquals(setOf("run","ride","swim"),mixed.facts.sessions.map { it.sport }.toSet())
        assertEquals(1,mixed.facts.wellness.map { it.sourceRecordId }.distinct().size)
        assertEquals(mixed.facts.wellness.size,mixed.facts.wellness.map { it.evidenceId }.distinct().size)
        assertEquals("assessment",descriptive("d06").facts.scope.wellnessPolicy)
        assertTrue(descriptive("d07").facts.scope.comparison.overlapping)
        assertTrue(descriptive("d07").facts.scope.comparison.unequalDuration)
        assertEquals("2026-10-06",descriptive("d08").facts.scope.comparison.comparisonFull!!.oldest)
    }

    @Test fun checkpointTemporalContextAndPreparationGuardsRemainHonest() {
        val morning=descriptive("d09")
        assertEquals("America/Los_Angeles",morning.facts.scope.timeZone)
        assertEquals("2026-10-17",morning.latestAvailableWellness!!.evaluationDate)
        assertTrue(morning.latestAvailableWellness.days.all { it.sourceDate<"2026-10-17" })
        assertTrue("training_readiness_unsupported" in morning.latestAvailableWellness.missing)
        assertTrue(morning.context.amendments.mandatoryRestrictions.any { it.observedOn=="2026-06-01" })
        val before=descriptive("d10");val after=descriptive("d11")
        assertTrue(before.facts.aggregates.none { it.key=="movingTime" && it.sampleCount>0 })
        assertTrue(after.facts.aggregates.any { it.key=="movingTime" && it.value==2000.0 })
        assertNotEquals(before.facts.scope.asOf,after.facts.scope.asOf)
        val clarified=descriptive("d12")
        assertTrue(clarified.context.evidence.optionalContexts.any { it.context.revision==2 && it.lifecycle.resolution=="unresolved" })
        assertTrue(clarified.facts.wellness.map { it.upstreamSource }.distinct().size>=2)
        assertEquals(3,ScopedEvaluationFixtures.guards().size)
        assertTrue(ScopedEvaluationFixtures.guards().all { it.modelCalls==0 })
    }

    @Test fun intakeRevisionsAndAmendmentCandidatesComeFromActualContracts() {
        val intake=json.decodeFromString<ContextIntakeSuggestionPacketV1>(ScopedEvaluationFixtures.intake("i07").exactPrompt.userPacketJson)
        assertEquals(2,intake.intake.revision);assertTrue(intake.intake.content.contains("🏊"))
        val relative=json.decodeFromString<ContextIntakeSuggestionPacketV1>(ScopedEvaluationFixtures.intake("i06").exactPrompt.userPacketJson)
        assertEquals("2026-10-17",relative.dateResolution!!.referenceDate)
        for((id,operation) in listOf("a01" to "move_session","a02" to "reduce_duration","a03" to "omit_session")) {
            val packet=json.decodeFromString<AmendmentSelectionPacketV1>(ScopedEvaluationFixtures.amendment(id).exactPrompt.userPacketJson)
            assertTrue(packet.candidates.all { it.operation==operation })
            val candidate=packet.candidates.first()
            val choice=packet.explanationChoices.single { it.text.startsWith("Candidate ${candidate.candidateId}:") }
            val draft=AmendmentSelectionDraftV1(AmendmentSelectionContractV1.PROFILE,packet.candidateSetSha256,"select",listOf(candidate.candidateId),listOf(choice))
            AmendmentSelectionContractV1.decodeDraft(AmendmentSelectionContractV1.encodeDraft(draft),packet)
            assertFailsWith<IllegalArgumentException> { AmendmentSelectionContractV1.decodeDraft(AmendmentSelectionContractV1.encodeDraft(draft.copy(
                explanations=listOf(choice.copy(text=choice.text+" Altered.")))),packet) }
        }
        assertEquals(2,ScopedEvaluationFixtures.amendmentInput("a07").plan!!.reference.revision)
        assertTrue(ScopedEvaluationFixtures.amendmentInput("a08").contexts.isEmpty())
    }

    @Test fun artifactsAreStableValidAndFrozenOrExplicitlyExported() {
        val bytes=ScopedEvaluationFixtures.bytes()
        assertEquals(bytes.toList(),ScopedEvaluationFixtures.bytes().toList())
        val document=json.decodeFromString<ScopedEvaluationFixtures.Document>(bytes.decodeToString())
        assertEquals("scoped-profile-artifacts-v1",document.version)
        assertEquals(28,document.cases.size)
        assertEquals((1..12).map { "d${it.toString().padStart(2,'0')}" }+
            (1..8).map { "i${it.toString().padStart(2,'0')}" }+
            (1..8).map { "a${it.toString().padStart(2,'0')}" },document.cases.map { it.caseId })
        assertEquals(28,document.cases.map { it.exactPrompt.userPacketJson }.toSet().size)
        for (case in document.cases) {
            assertTrue(case.exactPrompt.userPacketJson.isNotEmpty())
            assertTrue(case.exactPrompt.userPacketJson.toByteArray(Charsets.UTF_8).size<=32*1024)
            assertTrue(json.encodeToString(case.exactPrompt).toByteArray(Charsets.UTF_8).size<=32*1024)
            assertTrue(json.encodeToString(case.exactCorrectionPrompt).toByteArray(Charsets.UTF_8).size<=32*1024)
            assertEquals(64,case.consentDigest.length)
            assertTrue(case.consentDigest.all { it in "0123456789abcdef" })
            assertTrue(case.exactCorrectionPrompt!!.userPacketJson==case.exactPrompt.userPacketJson)
            assertTrue(case.exactCorrectionPrompt.systemInstructions!=case.exactPrompt.systemInstructions)
            when(case.profile) {
                "scoped-descriptive-v1" -> {
                    val packet=json.decodeFromString<ScopedDescriptivePacketV1>(case.exactPrompt.userPacketJson)
                    val ref=packet.references.first { it.kind in setOf("session","aggregate","wellness","baseline","adaptation") }
                    val draft=ScopedDescriptiveDraftV1(ScopedDescriptiveContractV1.PROFILE,packet.evidence.request.selection.mode,
                        packet.evidence.request.selection.intent,listOf(ScopedDescriptiveItem("observation","Synthetic measurements are available.",listOf(ref),"unknown")),emptyList())
                    ScopedDescriptiveContractV1.decodeDraft(ScopedDescriptiveContractV1.encodeDraft(draft),packet)
                }
                "context-intake-suggestion-v1" -> {
                    val packet=json.decodeFromString<ContextIntakeSuggestionPacketV1>(case.exactPrompt.userPacketJson)
                    val draft=ContextIntakeSuggestionDraftV1(ContextIntakeSuggestionContractV1.PROFILE,packet.intake.intakeId,packet.intake.revision,emptyList(),emptyList())
                    ContextIntakeSuggestionContractV1.decodeDraft(ContextIntakeSuggestionContractV1.encodeDraft(draft),packet)
                }
                "plan-amendment-selection-v1" -> {
                    val packet=json.decodeFromString<AmendmentSelectionPacketV1>(case.exactPrompt.userPacketJson)
                    val noChange=packet.explanationChoices.single { it.text.startsWith("No amendment candidate") }
                    val draft=AmendmentSelectionDraftV1(AmendmentSelectionContractV1.PROFILE,packet.candidateSetSha256,"no_change",emptyList(),listOf(noChange))
                    AmendmentSelectionContractV1.decodeDraft(AmendmentSelectionContractV1.encodeDraft(draft),packet)
                    val input=ScopedEvaluationFixtures.amendmentInput(case.caseId)
                    for(candidate in packet.candidates)assertTrue(PlanAmendmentPolicy.checkWholeResult(input,ScopedEvaluationFixtures.at,listOf(candidate)).isEmpty())
                    if(case.caseId=="a06")assertTrue(PlanAmendmentPolicy.checkWholeResult(input,ScopedEvaluationFixtures.at,packet.candidates).any { it.reason=="combined_plan_session_overlap" })
                }
                else -> error("Unexpected profile ${case.profile}")
            }
        }
        assertEquals(12,document.cases.count { it.profile=="scoped-descriptive-v1" })
        assertEquals(8,document.cases.count { it.profile=="context-intake-suggestion-v1" })
        assertEquals(8,document.cases.count { it.profile=="plan-amendment-selection-v1" })

        val export=System.getenv("GTRAINER_SCOPED_EVALUATION_EXPORT_DIR")
        if(export!=null) {
            val dir=Path.of(export)
            assertTrue(Files.isDirectory(dir),"Export directory must already exist and be empty")
            Files.newDirectoryStream(dir).use { assertFalse(it.iterator().hasNext(),"Export directory must be empty") }
            Files.write(dir.resolve("artifacts-v1.json"),bytes,java.nio.file.StandardOpenOption.CREATE_NEW)
        } else {
            assertTrue(Files.exists(Path.of("../benchmarks/scoped-profiles/artifacts-v1.json")) ||
                Files.exists(Path.of("benchmarks/scoped-profiles/artifacts-v1.json")),"Frozen artifact missing; export explicitly first")
            val frozen=sequenceOf(Path.of("../benchmarks/scoped-profiles/artifacts-v1.json"),Path.of("benchmarks/scoped-profiles/artifacts-v1.json"))
                .first(Files::exists)
            assertTrue(Files.readAllBytes(frozen).contentEquals(bytes),"Frozen artifacts differ from production-generated prompts")
        }
    }
}
