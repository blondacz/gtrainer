package com.gtrainer

import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

internal val syntheticModelOption = LocalModelOption("synthetic-local", "Synthetic local model", "synthetic:3b", "a".repeat(64))
internal class SyntheticAnalysisModel(override val option: LocalModelOption = syntheticModelOption,
                                      val generate: suspend (ModelPacket) -> String = { validClaims() }) : AnalysisModel {
    var calls = 0
    var lastPacket: ModelPacket? = null
    override suspend fun generate(packet: ModelPacket): String {
        calls++; lastPacket = packet
        return generate.invoke(packet)
    }
}
internal fun analysisRequest(report: TrendReport, status: ModelStatus): AnalysisRequest = AnalysisRequest(
    report.current.oldest, report.current.newest, report.selectedSport, Trends.analysisInput(report).evidenceReportSha256,
    status.selectedModelId ?: syntheticModelOption.id, status.selectionVersion)

class AnalysisServiceTest {
    @Test fun `off by default selection and switching have no storage writes and no implicit inference`() = runBlocking {
        val first = SyntheticAnalysisModel()
        val second = SyntheticAnalysisModel(syntheticModelOption.copy(id = "synthetic-other", tag = "synthetic-other:3b"))
        val service = AnalysisService(listOf(first, second))
        assertNull(service.status().selectedModelId)
        assertFalse(service.status().hostedEnabled)
        val report = analysisReport()
        assertEquals("model_not_selected", service.analyze(analysisRequest(report, service.status()), report) { report }.reason)
        assertEquals(0, first.calls)
        val directory = Files.createTempDirectory("gtrainer-synthetic-selection-")
        try {
            HistoryStore(directory.resolve("synthetic.sqlite3")).use { store ->
                val history = analysisHistory()
                val instant = java.time.Instant.parse("2020-06-10T00:00:00Z")
                store.completeActivities(ReadResult(ReadStatus.SUCCESS, history.activities), instant)
                store.completeWellness(ReadResult(ReadStatus.SUCCESS, history.wellness), instant)
                val before = store.history(java.time.LocalDate.parse("2020-05-30"), java.time.LocalDate.parse("2020-06-02"))
                val firstStatus = service.select(first.option.id)
                assertEquals(0, first.calls)
                assertEquals(firstStatus.selectionVersion, service.select(first.option.id).selectionVersion)
                val result = service.analyze(analysisRequest(report, firstStatus), report) { report }
                assertEquals("available", result.status)
                assertEquals(1, first.calls)
                assertEquals(0, second.calls)
                assertEquals(first.option, result.model)
                assertEquals(3, first.lastPacket!!.evidence.size)
                assertTrue(result.limitations.any { it.contains("metric_origin_unknown") })
                assertTrue(result.unavailable.any { it.key == "fitnessAge" })
                val secondStatus = service.select(second.option.id)
                assertTrue(secondStatus.selectionVersion > firstStatus.selectionVersion)
                assertEquals("available", service.analyze(analysisRequest(report, secondStatus), report) { report }.status)
                assertEquals(1, second.calls)
                service.select(null)
                assertEquals(before, store.history(java.time.LocalDate.parse("2020-05-30"), java.time.LocalDate.parse("2020-06-02")))
                assertNull(AnalysisService(listOf(first)).status().selectedModelId) // Restart never preserves consent.
                assertEquals(2, result.observations.first().supportingMetrics.first().sampleCount)
            }
        } finally {
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `unknown model stale selection stale hash and sparse data fail without inference`() = runBlocking {
        val model = SyntheticAnalysisModel()
        val service = AnalysisService(listOf(model))
        assertFailsWith<IllegalArgumentException> { service.select("hosted-unknown") }
        val status = service.select(model.option.id)
        val report = analysisReport()
        val request = analysisRequest(report, status)
        assertEquals("evidence_changed", service.analyze(request.copy(evidenceReportSha256 = "b".repeat(64)), report) { report }.reason)
        assertEquals("model_selection_changed", service.analyze(request.copy(selectionVersion = 0), report) { report }.reason)
        assertEquals("model_selection_changed", service.analyze(request.copy(modelId = "wrong"), report) { report }.reason)
        assertFailsWith<IllegalArgumentException> { service.analyze(request.copy(evidenceReportSha256 = "bad"), report) { report } }
        val empty = analysisReport(HistoryResponse(emptyList(), emptyList()))
        assertEquals("insufficient_input", service.analyze(analysisRequest(empty, status), empty) { empty }.reason)
        assertEquals(0, model.calls)
    }

    @Test fun `outage malformed output and unsupported personal claims produce no observations or fallback`() = runBlocking {
        for (output in listOf("{", validClaims().replace("co_occurrence", "causation"), "Garmin endurance score is 99. Sprint daily.")) {
            val model = SyntheticAnalysisModel(generate = { output })
            val service = AnalysisService(listOf(model))
            val status = service.select(model.option.id)
            val report = analysisReport()
            val result = service.analyze(analysisRequest(report, status), report) { report }
            assertEquals("unusable_model_output", result.reason)
            assertTrue(result.observations.isEmpty())
            assertFalse(result.toString().contains(output))
        }
        val model = SyntheticAnalysisModel(generate = { throw IllegalStateException("sensitive-model-diagnostic") })
        val fallback = SyntheticAnalysisModel(syntheticModelOption.copy(id = "synthetic-unused", tag = "synthetic-unused:3b"))
        val service = AnalysisService(listOf(model, fallback))
        val status = service.select(model.option.id)
        val report = analysisReport()
        assertEquals("model_unavailable", service.analyze(analysisRequest(report, status), report) { report }.reason)
        assertEquals(0, fallback.calls)
    }

    @Test fun `changed evidence or read status invalidates completed inference and selection is rechecked after fresh snapshot`() = runBlocking {
        val model = SyntheticAnalysisModel()
        val service = AnalysisService(listOf(model))
        val status = service.select(model.option.id)
        val report = analysisReport()
        val result = service.analyze(analysisRequest(report, status), report) { report.copy(evaluatedOnUtc = "2020-06-11") }
        assertEquals("evidence_changed", result.reason)
        assertTrue(result.observations.isEmpty())
        val failedRead = report.copy(sourceStatus = listOf(CategoryStatus("activities", 4, null, null, "KEY_REJECTED", 0, 0, "2020-06-02", 8)))
        assertEquals("evidence_changed", service.analyze(analysisRequest(report, status), report) { failedRead }.reason)
        val changed = service.analyze(analysisRequest(report, status), report) { service.select(null); report }
        assertEquals("model_selection_changed", changed.reason)
        assertTrue(changed.observations.isEmpty())
    }

    @Test fun `single flight cancel on switching and cancellation propagate without locking charts`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val model = SyntheticAnalysisModel(generate = { started.complete(Unit); awaitCancellation() })
        val service = AnalysisService(listOf(model))
        val status = service.select(model.option.id)
        val report = analysisReport()
        val request = analysisRequest(report, status)
        val pending = async { service.analyze(request, report) { report } }
        started.await()
        assertEquals("analysis_busy", service.analyze(request, report) { report }.reason)
        assertEquals(report, analysisReport()) // Pure factual report does not share the inference gate.
        service.select(null)
        val result = withTimeout(2000) { pending.await() }
        assertEquals("model_selection_changed", result.reason)
        assertTrue(result.observations.isEmpty())
        val nextStatus = service.select(model.option.id)
        val cancelled = async { service.analyze(analysisRequest(report, nextStatus), report) { report } }
        yield(); cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
    }

    @Test fun `timeout classified and never leaks provider diagnostic`() = runBlocking {
        val model = SyntheticAnalysisModel(generate = { withTimeout(1) { delay(1000); validClaims() } })
        val service = AnalysisService(listOf(model))
        val report = analysisReport()
        val result = service.analyze(analysisRequest(report, service.select(model.option.id)), report) { report }
        assertEquals("model_timeout", result.reason)
        assertTrue(result.observations.isEmpty())
        assertEquals("AnalysisResponse([REDACTED])", result.toString())
    }
}
