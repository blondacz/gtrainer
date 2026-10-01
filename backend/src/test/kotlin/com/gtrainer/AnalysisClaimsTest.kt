package com.gtrainer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.*

internal fun analysisHistory(): HistoryResponse {
    val dates = listOf("2020-05-30", "2020-05-31", "2020-06-01", "2020-06-02")
    return HistoryResponse(dates.mapIndexed { index, date -> syntheticActivity().copy(
        sourceRecordId = "synthetic-analysis-$index", startLocal = "${date}T10:00:00", movingTime = Measurement(if (index < 2) 1200.0 else 1800.0, "seconds")) },
        dates.mapIndexed { index, date -> syntheticWellness().copy(sourceRecordId = date, date = date,
            measurements = mapOf("sleepSecs" to Measurement(if (index < 2) 28800.0 else 25200.0, "seconds"),
                "hrv" to Measurement(42.0, "ms", "GARMIN"), "weight" to Measurement(70.0, "kg"))) })
}

internal fun analysisReport(history: HistoryResponse = analysisHistory()): TrendReport = Trends.report(history,
    TrendRange(LocalDate.parse("2020-06-01"), LocalDate.parse("2020-06-02")), null, LocalDate.parse("2020-06-10"), emptyList())

internal fun validClaims(): String = """{"observations":[
    {"kind":"co_occurrence","evidence":[{"id":"e0","state":"increased"},{"id":"e1","state":"decreased"},{"id":"e2","state":"unchanged"}]}
]}"""

class AnalysisClaimsTest {
    private fun snapshot(): AnalysisSnapshot {
        val input = Trends.analysisInput(analysisReport())
        return AnalysisClaims.prepare(input)
    }
    private fun raw(): String = validClaims()

    @Test fun `mixed directions render exact facts dates and record-linked evidence without model prose`() {
        val snapshot = snapshot()
        assertTrue(AnalysisClaims.sufficient(snapshot))
        val rendered = assertNotNull(AnalysisClaims.validateAndRender(raw(), snapshot))
        assertContains(rendered.first().text, "increased: 2400.0 to 3600.0 seconds")
        assertContains(rendered.first().text, "decreased: 28800.0 to 25200.0 seconds")
        assertContains(rendered.first().text, "unchanged: 42.0 to 42.0 ms")
        assertContains(rendered.first().text, "2020-05-30–2020-05-31 and 2020-06-01–2020-06-02")
        assertContains(rendered.first().text, "do not establish cause, recovery, or readiness")
        assertTrue(rendered.first().supportingMetrics.all { it.evidenceId in rendered.first().evidenceIds })
        val packet = AnalysisClaims.json.encodeToString(snapshot.packet)
        assertFalse(packet.contains("synthetic-analysis"))
        assertFalse(packet.contains("sourceRecordId"))
        assertFalse(packet.contains("points"))
        assertEquals(snapshot.packetSha256, AnalysisClaims.hash(packet))
        assertEquals(Trends.analysisInput(analysisReport()).evidenceReportSha256, snapshot.input.evidenceReportSha256)
        assertEquals("AnalysisSnapshot([REDACTED])", snapshot.toString())
    }

    @Test fun `whole response rejected for unsupported prose arbitrary keys invented scores and bad states`() {
        for (bad in listOf(
            raw().replace("increased", "decreased"), raw().replace("e0", "fitnessAge"),
            raw().replace("co_occurrence", "diagnosis"), raw().replace("co_occurrence", "workout_prescription"),
            raw().replace("co_occurrence", "causal_claim"), raw().replace("co_occurrence", "workout_profile"),
            raw().replace("\"kind\":\"co_occurrence\"", "\"kind\":\"co_occurrence\",\"text\":\"Garmin fitness age is 25\""),
            raw().replace("\"id\":\"e0\"", "\"id\":\"e0\",\"value\":999"),
            raw().replace("\"observations\":", "\"prescription\":\"sprint daily\",\"observations\":"),
            raw().replace("co_occurrence", "unavailable_comparison"), // Actual Pi semantic failure: available comparisons claimed unavailable.
        )) assertNull(AnalysisClaims.validateAndRender(bad, snapshot()))
    }

    @Test fun `malformed duplicate escaped keys depth counts nulls and oversized outputs rejected`() {
        for (bad in listOf("", "not JSON", "```json\n${raw()}\n```", raw() + raw(), raw().dropLast(2),
            "{\"observations\":null}", "{\"observations\":[]}", "{\"observations\":[null]}",
            raw().replace("\"kind\":", "\"kind\":\"recorded_change\",\"k\\u0069nd\":"),
            raw().replace("\"e0\",\"state\":\"increased\"", "\"e0\",\"state\":\"increased\",\"state\":\"increased\""),
            " ".repeat(32_769) + raw(), "[".repeat(17) + "0" + "]".repeat(17),
            raw().replace("\"e0\"", "null"), raw().replace("\"increased\"", "NaN"),
            raw().replace("\"increased\"", "true"), raw().replace("\"increased\"", "0"),
            raw().replace("\"increased\"", "increased"), raw().replace("\"e0\"", "e0"),
            raw().replace("\"co_occurrence\"", "co_occurrence"),
        )) assertNull(AnalysisClaims.validateAndRender(bad, snapshot()))
    }

    @Test fun `incomplete selection duplicate references and duplicate claims fail closed`() {
        val single = """{"observations":[{"kind":"co_occurrence","evidence":[{"id":"e0","state":"increased"},{"id":"e1","state":"decreased"}]}]}"""
        assertNull(AnalysisClaims.validateAndRender(single, snapshot()))
        assertNull(AnalysisClaims.validateAndRender(raw().replace("\"e1\",\"state\":\"decreased\"", "\"e0\",\"state\":\"increased\""), snapshot()))
        val claim = Json.parseToJsonElement(raw()).jsonObject["observations"]!!.jsonArray.first()
        assertNull(AnalysisClaims.validateAndRender("{\"observations\":[$claim,$claim]}", snapshot()))
    }

    @Test fun `period mismatch sport-only connections and mutated snapshots rejected`() {
        val base = snapshot()
        val mismatch = base.packet.copy(evidence = base.packet.evidence.map { if (it.id == "e1") it.copy(currentOldest = "2020-02-01") else it })
        assertNull(AnalysisClaims.validateAndRender(raw(), base.copy(packet = mismatch, packetSha256 = AnalysisClaims.hash(AnalysisClaims.json.encodeToString(mismatch)))))
        assertNull(AnalysisClaims.validateAndRender(raw(), base.copy(packet = mismatch)))
        assertNull(AnalysisClaims.validateAndRender(raw().replace("co_occurrence", "sport_mix"), base))
        assertNull(AnalysisClaims.validateAndRender(raw().replace("co_occurrence", "recorded_change"), base))
    }

    @Test fun `real populated zero baseline stays explicit and sparse or missing inputs are insufficient`() {
        val history = analysisHistory()
        val zero = history.copy(activities = history.activities.map { if (it.startLocal < "2020-06") it.copy(movingTime = Measurement(0.0, "seconds")) else it })
        val snapshot = AnalysisClaims.prepare(Trends.analysisInput(analysisReport(zero)))
        assertContains(assertNotNull(AnalysisClaims.validateAndRender(raw(), snapshot)).first().text, "0.0 to 3600.0")
        assertTrue("zero_baseline_no_percentage" in snapshot.packet.evidence.first().flags)
        assertFalse(AnalysisClaims.sufficient(AnalysisClaims.prepare(Trends.analysisInput(analysisReport(HistoryResponse(emptyList(), emptyList()))))))
        val sparse = history.copy(activities = history.activities.take(1) + history.activities.takeLast(1))
        assertFalse(AnalysisClaims.sufficient(AnalysisClaims.prepare(Trends.analysisInput(analysisReport(sparse)))))
        val partial = history.copy(wellness = history.wellness.filter { it.date < "2020-06" })
        assertFalse(AnalysisClaims.sufficient(AnalysisClaims.prepare(Trends.analysisInput(analysisReport(partial)))))
    }

    @Test fun `partial wellness renders available connection and unavailable comparison with complete selection`() {
        val history = analysisHistory().let { it.copy(wellness = it.wellness.map { record -> record.copy(measurements = record.measurements - "hrv") }) }
        val snapshot = AnalysisClaims.prepare(Trends.analysisInput(analysisReport(history)))
        val raw = """{"observations":[
            {"kind":"co_occurrence","evidence":[{"id":"e0","state":"increased"},{"id":"e1","state":"decreased"}]},
            {"kind":"unavailable_comparison","evidence":[{"id":"e2","state":"unavailable"}]}]}"""
        assertTrue(AnalysisClaims.sufficient(snapshot))
        assertContains(assertNotNull(AnalysisClaims.validateAndRender(raw, snapshot)).last().text, "missing measurements are unknown, not zero")
        assertNull(AnalysisClaims.validateAndRender(raw.replace("unavailable_comparison", "recorded_change"), snapshot))
        assertNull(AnalysisClaims.validateAndRender(raw.replace("unavailable", "unchanged"), snapshot))
    }

    @Test fun `mixed sports retain separate directions and only identical-period recorded time can be connected`() {
        val history = analysisHistory()
        val mixed = history.copy(activities = history.activities + history.activities.map { it.copy(
            sourceRecordId = it.sourceRecordId + "-run", sport = "Run", movingTime = Measurement(600.0, "seconds")) })
        val snapshot = AnalysisClaims.prepare(Trends.analysisInput(analysisReport(mixed)))
        val raw = """{"observations":[
            {"kind":"co_occurrence","evidence":[{"id":"e0","state":"increased"},{"id":"e2","state":"decreased"},{"id":"e3","state":"unchanged"}]},
            {"kind":"sport_mix","evidence":[{"id":"e0","state":"increased"},{"id":"e1","state":"unchanged"}]}]}"""
        val rendered = assertNotNull(AnalysisClaims.validateAndRender(raw, snapshot))
        assertContains(rendered.last().text, "not equivalent effort or training stimulus")
        assertContains(rendered.last().text, "Run Recorded activity time")
        assertNull(AnalysisClaims.validateAndRender(raw.replace("sport_mix", "unavailable_comparison"), snapshot))
        val moreSports = mixed.copy(activities = mixed.activities + mixed.activities.filter { it.sport == "Run" }.map { it.copy(
            sourceRecordId = it.sourceRecordId + "-swim", sport = "Swim") })
        val packet = AnalysisClaims.prepare(Trends.analysisInput(analysisReport(moreSports))).packet
        assertEquals(listOf("Ride", "Run"), packet.evidence.mapNotNull { it.sport })
    }
}
