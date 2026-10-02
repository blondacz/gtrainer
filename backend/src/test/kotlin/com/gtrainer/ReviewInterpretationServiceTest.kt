package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlin.test.*

internal fun relevantFocus(packet: ReviewFocusPacket): String {
    val kind = when (packet.requestedFocus) {
        "daily_combined" -> "cross_metric_pattern"
        "activity_balance" -> "sport_mix"
        "missing_wellness" -> "coverage_gap"
        else -> if (packet.candidates.any { it.kind == "wellness_pattern" }) "wellness_pattern" else "coverage_gap"
    }
    return """{"focusIds":["${packet.candidates.first { it.kind == kind }.id}"]}"""
}
internal class SyntheticReviewModel(override val option: LocalModelOption = syntheticModelOption,
                                  private val produce: suspend (ReviewFocusPacket, String?) -> String = { packet, _ -> relevantFocus(packet) }) : ReviewFocusModel {
    val packets = mutableListOf<String>()
    val feedback = mutableListOf<String?>()
    override suspend fun generate(packet: ReviewFocusPacket, feedback: String?): String {
        packets += ReviewFocusProtocol.json.encodeToString(packet)
        this.feedback += feedback
        return produce(packet, feedback)
    }
}
internal fun reviewRequest(report: TrendReport, status: ReviewModelStatus, focus: String = "daily_combined") = ReviewInterpretationRequest(
    ReviewFocusProtocol.PROFILE, report.current.oldest, report.current.newest, report.selectedSport, focus,
    Trends.analysisInput(report).evidenceReportSha256, status.selectedModelId ?: syntheticModelOption.id, status.selectionVersion)
internal fun reviewSelection(modelId: String? = syntheticModelOption.id, enabled: Boolean = true, correction: Boolean = false) =
    ReviewSelectionRequest(ReviewFocusProtocol.PROFILE, modelId, enabled, correction)

class ReviewInterpretationServiceTest {
    private val correctionPolicy = ReviewExecutionPolicy(100, 1000, true)

    @Test fun `configuration and selection never enable by default or silently reuse prototype selection`() = runBlocking {
        val model = SyntheticReviewModel()
        val service = ReviewInterpretationService(listOf(model), runtimeGuard = { true })
        val report = analysisReport()
        assertFalse(service.status().enabled || service.status().hostedEnabled || service.status().correctionEnabled)
        assertNull(service.status().selectedModelId)
        assertEquals("interpretation_disabled", service.interpret(reviewRequest(report, service.status()), report) { report }.reason)
        service.select(reviewSelection(enabled = false))
        assertEquals("interpretation_disabled", service.interpret(reviewRequest(report, service.status()), report) { report }.reason)
        assertTrue(model.packets.isEmpty())
        assertFailsWith<IllegalArgumentException> { service.select(reviewSelection().copy(profile = "prepared-focus-v1")) }
        assertFailsWith<IllegalArgumentException> { service.select(reviewSelection("hosted-unknown")) }
        assertFailsWith<IllegalArgumentException> { service.select(reviewSelection(modelId = null)) }
        assertFailsWith<IllegalArgumentException> { service.select(reviewSelection(correction = true)) }
        val status = service.select(reviewSelection())
        assertEquals(status, service.select(reviewSelection()))
        assertTrue(model.packets.isEmpty())
        val result = service.interpret(reviewRequest(report, status), report) { report }
        assertEquals("available", result.status)
        assertEquals("validated_focus_selection", result.reason)
        assertEquals(1, model.packets.size)
        assertEquals(listOf(ReviewAttempt(1, "accepted", null)), result.attempts)
        assertTrue(result.interpretations.all { it.supportingFacts.isNotEmpty() })
        assertFalse(ReviewInterpretationService(listOf(model), runtimeGuard = { true }).status().enabled)
    }

    @Test fun `correction requires operator permission user opt in and enough remaining budget and is a new whole response`() = runBlocking {
        val report = analysisReport()
        for (enabled in listOf(false, true)) {
            val model = SyntheticReviewModel { packet, feedback ->
                if (feedback == null) """{"focusIds":["${packet.candidates.first { it.kind == "activity_pattern" }.id}"]}"""
                else relevantFocus(packet)
            }
            val service = ReviewInterpretationService(listOf(model), correctionPolicy, { true })
            val status = service.select(reviewSelection(correction = enabled))
            val result = service.interpret(reviewRequest(report, status), report) { report }
            assertEquals(if (enabled) "available" else "unavailable", result.status)
            assertEquals(if (enabled) 2 else 1, model.packets.size)
            assertEquals("irrelevant_focus", result.attempts.first().reason)
            assertEquals(if (enabled) listOf(null, "irrelevant_focus") else listOf(null), model.feedback)
            if (enabled) {
                assertEquals(model.packets[0], model.packets[1])
                assertEquals("accepted", result.attempts.last().status)
            } else assertTrue(result.interpretations.isEmpty())
        }
        val fails = SyntheticReviewModel { _, _ -> "not JSON; private rejected text" }
        val service = ReviewInterpretationService(listOf(fails), correctionPolicy, { true })
        val result = service.interpret(reviewRequest(report, service.select(reviewSelection(correction = true))), report) { report }
        assertEquals(2, fails.packets.size)
        assertEquals("invalid_json", result.reason)
        assertTrue(result.interpretations.isEmpty())
        assertFalse(ReviewFocusProtocol.json.encodeToString(result).contains("private rejected text"))
        assertEquals(listOf(null, "invalid_json"), fails.feedback)
    }

    @Test fun `remaining budget never turns rejection into an extra late attempt`() = runBlocking {
        var now = 0L
        val model = SyntheticReviewModel { _, _ -> now = 90_000_000; "{" }
        val service = ReviewInterpretationService(listOf(model), ReviewExecutionPolicy(100, 150, true), { true }, nanoTime = { now })
        val report = analysisReport()
        val result = service.interpret(reviewRequest(report, service.select(reviewSelection(correction = true))), report) { report }
        assertEquals("invalid_json", result.reason)
        assertEquals(1, model.packets.size)
    }

    @Test fun `missing or failed guard blocks inference and fault after response cannot cause correction`() = runBlocking {
        val report = analysisReport()
        val model = SyntheticReviewModel()
        val missing = ReviewInterpretationService(listOf(model), correctionPolicy)
        assertEquals("runtime_guard_unavailable", missing.interpret(reviewRequest(report, missing.select(reviewSelection(correction = true))), report) { report }.reason)
        assertTrue(model.packets.isEmpty())
        for (guard in listOf<suspend () -> Boolean>({ false }, { throw IllegalStateException("sensitive guard diagnostic") })) {
            val failed = ReviewInterpretationService(listOf(model), correctionPolicy, guard)
            assertEquals("runtime_guard_failed", failed.interpret(reviewRequest(report, failed.select(reviewSelection(correction = true))), report) { report }.reason)
            assertTrue(model.packets.isEmpty())
        }
        var healthy = true
        val interrupted = SyntheticReviewModel { _, _ -> healthy = false; "{" }
        val failed = ReviewInterpretationService(listOf(interrupted), correctionPolicy, { healthy })
        val result = failed.interpret(reviewRequest(report, failed.select(reviewSelection(correction = true))), report) { report }
        assertEquals("runtime_guard_failed", result.reason)
        assertEquals(1, interrupted.packets.size)
        assertTrue(result.interpretations.isEmpty())
        assertEquals("invalidated", result.attempts.single().status)
    }

    @Test fun `stale request source status snapshot model version and incompatible focus fail without corrective retries`() = runBlocking {
        val report = analysisReport()
        val model = SyntheticReviewModel()
        val service = ReviewInterpretationService(listOf(model), correctionPolicy, { true })
        val status = service.select(reviewSelection(correction = true))
        val request = reviewRequest(report, status)
        for (stale in listOf(request.copy(evidenceReportSha256 = "b".repeat(64)), request.copy(oldest = "1999-01-01")))
            assertEquals("evidence_changed", service.interpret(stale, report) { report }.reason)
        assertEquals("model_selection_changed", service.interpret(request.copy(selectionVersion = 0), report) { report }.reason)
        assertEquals("insufficient_input", service.interpret(request.copy(focus = "activity_balance"), report) { report }.reason)
        assertTrue(model.packets.isEmpty())
        var freshCalls = 0
        val result = service.interpret(request, report) {
            if (++freshCalls == 1) report else report.copy(sourceStatus = listOf(CategoryStatus("activities", 4, null, null, "KEY_REJECTED", 0, 0, "2020-06-02", 8)))
        }
        assertEquals("evidence_changed", result.reason)
        assertEquals(1, model.packets.size)
        assertTrue(result.interpretations.isEmpty())
        assertEquals(FactualReviews.prepare(Trends.analysisInput(report)), FactualReviews.prepare(Trends.analysisInput(analysisReport())))
    }

    @Test fun `transport failure timeout job guard timeout and cancellation are never semantic retry causes`() = runBlocking {
        val report = analysisReport()
        val outage = SyntheticReviewModel { _, _ -> throw IllegalStateException("private provider diagnostic") }
        val failed = ReviewInterpretationService(listOf(outage), correctionPolicy, { true })
        val failure = failed.interpret(reviewRequest(report, failed.select(reviewSelection(correction = true))), report) { report }
        assertEquals("model_unavailable", failure.reason)
        assertEquals(1, outage.packets.size)
        assertFalse(failure.toString().contains("private"))
        val slow = SyntheticReviewModel { _, _ -> awaitCancellation() }
        val service = ReviewInterpretationService(listOf(slow), ReviewExecutionPolicy(20, 1000, true), { true })
        assertEquals("attempt_timeout", service.interpret(reviewRequest(report, service.select(reviewSelection(correction = true))), report) { report }.reason)
        assertEquals(1, slow.packets.size)
        val guardSlow = ReviewInterpretationService(listOf(outage), ReviewExecutionPolicy(30, 30, true), { delay(1000); true })
        assertEquals("job_timeout", guardSlow.interpret(reviewRequest(report, guardSlow.select(reviewSelection(correction = true))), report) { report }.reason)
        assertEquals(1, outage.packets.size)
    }

    @Test fun `attempt deadline includes preflight postflight and non suspending validation publication`() = runBlocking {
        val report = analysisReport()
        val preflightModel = SyntheticReviewModel()
        val preflight = ReviewInterpretationService(listOf(preflightModel), ReviewExecutionPolicy(20, 1000, true), { delay(1000); true })
        val preflightResult = preflight.interpret(reviewRequest(report, preflight.select(reviewSelection(correction = true))), report) { report }
        assertEquals("attempt_timeout", preflightResult.reason)
        assertTrue(preflightModel.packets.isEmpty())
        assertTrue(preflightResult.attempts.isEmpty())

        val postflightModel = SyntheticReviewModel()
        var checks = 0
        val postflight = ReviewInterpretationService(listOf(postflightModel), ReviewExecutionPolicy(20, 1000, true), {
            if (++checks > 1) delay(1000)
            true
        })
        val postflightResult = postflight.interpret(reviewRequest(report, postflight.select(reviewSelection(correction = true))), report) { report }
        assertEquals("attempt_timeout", postflightResult.reason)
        assertEquals(1, postflightModel.packets.size)
        assertTrue(postflightResult.interpretations.isEmpty())
        assertEquals("failed", postflightResult.attempts.single().status)

        var now = 0L
        var finalChecks = 0
        val lateModel = SyntheticReviewModel()
        val late = ReviewInterpretationService(listOf(lateModel), correctionPolicy, {
            if (++finalChecks == 3) now = 150_000_000
            true
        }, nanoTime = { now })
        val lateResult = late.interpret(reviewRequest(report, late.select(reviewSelection(correction = true))), report) { report }
        assertEquals("attempt_timeout", lateResult.reason)
        assertEquals(1, lateModel.packets.size)
        assertTrue(lateResult.interpretations.isEmpty())
        assertFalse(lateResult.attempts.any { it.status == "accepted" })
    }

    @Test fun `corrective preflight consuming headroom cannot start another generation`() = runBlocking {
        var now = 0L
        var checks = 0
        val model = SyntheticReviewModel { _, _ -> now = 60_000_000; "{" }
        val service = ReviewInterpretationService(listOf(model), ReviewExecutionPolicy(100, 200, true), {
            if (++checks == 4) now = 120_000_000
            true
        }, nanoTime = { now })
        val report = analysisReport()
        val result = service.interpret(reviewRequest(report, service.select(reviewSelection(correction = true))), report) { report }
        assertEquals(4, checks)
        assertEquals("invalid_json", result.reason)
        assertEquals(1, model.packets.size)
        assertEquals(1, result.attempts.size)
    }

    @Test fun `disable model switch and caller cancellation prevent publication and release shared prototype slot`() = runBlocking {
        val report = analysisReport()
        val started = Channel<Unit>(Channel.UNLIMITED)
        val model = SyntheticReviewModel { _, _ -> started.send(Unit); awaitCancellation() }
        val prototype = AnalysisService(listOf(SyntheticAnalysisModel()))
        val service = ReviewInterpretationService(listOf(model), correctionPolicy, { true }, prototype.inferenceGate())
        val status = service.select(reviewSelection(correction = true))
        val request = reviewRequest(report, status)
        val pending = async { service.interpret(request, report) { report } }
        withTimeout(5000) { started.receive() }
        assertEquals("analysis_busy", service.interpret(request, report) { report }.reason)
        val prototypeStatus = prototype.select(syntheticModelOption.id)
        assertEquals("analysis_busy", prototype.analyze(analysisRequest(report, prototypeStatus), report) { report }.reason)
        assertEquals(16, FactualReviews.prepare(Trends.analysisInput(report)).groups.sumOf { it.facts.size })
        service.select(reviewSelection(enabled = false))
        val denied = withTimeout(5000) { pending.await() }
        assertEquals("model_selection_changed", denied.reason)
        assertTrue(denied.interpretations.isEmpty())
        assertFalse(service.current(denied))
        assertEquals("available", prototype.analyze(analysisRequest(report, prototypeStatus), report) { report }.status)
        val next = service.select(reviewSelection())
        val cancelled = async { service.interpret(reviewRequest(report, next), report) { report } }
        withTimeout(5000) { started.receive() }
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertEquals(2, model.packets.size)
        prototype.close(); service.close()
    }
}
