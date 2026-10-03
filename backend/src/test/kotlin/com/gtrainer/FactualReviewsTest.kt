package com.gtrainer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import kotlin.test.*

class FactualReviewsTest {
    private fun input(history: HistoryResponse = analysisHistory()) = Trends.analysisInput(analysisReport(history))

    @Test fun `all metrics and all sports are retained once with both exact supports and missing scores`() {
        val history = analysisHistory()
        val multiple = history.copy(activities = listOf("Ride", "Run", "Swim").flatMap { sport -> history.activities.map {
            it.copy(sport = sport, sourceRecordId = it.sourceRecordId + sport)
        } })
        val input = input(multiple)
        val review = FactualReviews.prepare(input)
        val facts = review.groups.flatMap { it.facts }
        assertEquals(26, facts.size) // Five comparisons per sport plus eleven wellness comparisons, not prototype's four.
        assertEquals(input.comparisons.toSet(), facts.map { it.comparison }.toSet())
        assertEquals(facts.size, facts.map { it.id }.distinct().size)
        assertEquals(input.facts.toSet(), facts.flatMap { it.supportingMetrics }.toSet())
        assertEquals(input.evidenceReportSha256, review.evidenceReportSha256)
        assertEquals(listOf("cross_metric_pattern"), review.focuses.map { it.kind })
        assertEquals(setOf("Ride", "Run", "Swim"), review.groups.flatMap { it.facts }.filter { it.id in review.focuses.single().factIds }
            .mapNotNull { it.comparison.sport }.toSet())
        assertTrue(review.applicationGenerated)
        assertEquals(input.unavailable, review.unavailable)
        assertEquals(review, FactualReviews.prepare(input))
        val serialized = AnalysisClaims.json.encodeToString(review)
        assertFalse(serialized.contains("sourceRecordId"))
        assertFalse(serialized.contains("synthetic-analysis-"))
        assertFalse(serialized.contains("\"points\""))
        assertEquals("FactualReview([REDACTED])", review.toString())
        assertTrue(facts.all { it.toString() == "ReviewFact([REDACTED])" })
    }

    @Test fun `directions missing current or previous values and measured zeros remain independent facts`() {
        val history = analysisHistory()
        val modified = history.copy(activities = history.activities.map {
            it.copy(movingTime = Measurement(if (it.startLocal < "2020-06") 0.0 else 1200.0, "seconds"))
        }, wellness = history.wellness.map {
            it.copy(measurements = if (it.date < "2020-06") it.measurements - "hrv" else it.measurements - "sleepSecs")
        })
        val review = FactualReviews.prepare(input(modified))
        val facts = review.groups.flatMap { it.facts }
        val moving = facts.single { it.comparison.key == "movingTime" }
        assertEquals("increased", moving.state)
        assertEquals(0.0, moving.supportingMetrics.first().value)
        assertContains(moving.comparison.flags, "zero_baseline_no_percentage")
        assertNull(moving.comparison.percentChange)
        assertEquals("unchanged", facts.single { it.comparison.key == "weight" }.state)
        for (key in listOf("hrv", "sleepSecs")) {
            val missing = facts.single { it.comparison.key == key }
            assertEquals("unavailable", missing.state)
            assertContains(missing.text, "missing measurements are unknown, not zero")
            assertEquals(1, missing.supportingMetrics.count { it.value == null })
            assertTrue(review.groups.single { missing in it.facts }.coverage == "unavailable")
        }
        assertEquals(listOf("cross_metric_pattern"), review.focuses.map { it.kind }) // Available weight still supports a descriptive connection.
    }

    @Test fun `focus rules answer requested topic and never use irrelevant substitute or sparse patterns`() {
        val input = input()
        assertEquals(listOf("wellness_pattern"), FactualReviews.prepare(input, "wellness").focuses.map { it.kind })
        assertTrue(FactualReviews.prepare(input, "missing_wellness").focuses.all { it.kind == "coverage_gap" })
        assertEquals("no_supported_focus", FactualReviews.prepare(input, "activity_balance").reason)
        assertTrue(FactualReviews.prepare(input, "activity_balance").focuses.isEmpty())
        val history = analysisHistory()
        val mixed = history.copy(activities = history.activities + history.activities.map { it.copy(sport = "Run", sourceRecordId = it.sourceRecordId + "run") })
        assertEquals(listOf("sport_mix"), FactualReviews.prepare(input(mixed), "activity_balance").focuses.map { it.kind })
        val sparse = history.copy(activities = history.activities.take(1) + history.activities.takeLast(1),
            wellness = history.wellness.take(1) + history.wellness.takeLast(1))
        val review = FactualReviews.prepare(input(sparse))
        assertTrue(review.groups.any { it.coverage == "sparse" && it.facts.any { f -> f.comparison.key == "movingTime" } })
        assertTrue(review.focuses.isEmpty())
        assertEquals(listOf("coverage_gap", "coverage_gap"), FactualReviews.prepare(input(sparse), "wellness").focuses.map { it.kind })
        assertFailsWith<IllegalArgumentException> { FactualReviews.prepare(input, "training_plan") }
    }

    @Test fun `incompatible periods are separate and cannot support a combined focus`() {
        val base = input()
        val shifted = base.copy(facts = base.facts.map { fact -> if (fact.sport != null) fact else fact.copy(
            oldest = LocalDate.parse(fact.oldest).minusDays(7).toString(), newest = LocalDate.parse(fact.newest).minusDays(7).toString()) })
        val review = FactualReviews.prepare(shifted)
        assertTrue(review.groups.map { it.periods }.distinct().size == 2)
        assertEquals(base.comparisons.size, review.groups.sumOf { it.facts.size })
        assertTrue(review.focuses.isEmpty())
    }

    @Test fun `empty imports retain all unavailable wellness facts and no invented activity pattern`() {
        val input = input(HistoryResponse(emptyList(), emptyList()))
        val review = FactualReviews.prepare(input)
        assertEquals(11, review.groups.flatMap { it.facts }.size)
        assertTrue(review.groups.all { it.coverage == "unavailable" })
        assertTrue(review.focuses.isEmpty())
        assertEquals(listOf("coverage_gap"), FactualReviews.prepare(input, "wellness").focuses.map { it.kind })
    }

    @Test fun `missing duplicate mismatched supports are not repaired into complete factual claims`() {
        val input = input()
        for (bad in listOf(input.copy(facts = input.facts.drop(1)), input.copy(facts = input.facts + input.facts.first()),
            input.copy(comparisons = input.comparisons + input.comparisons.first()),
            input.copy(comparisons = input.comparisons.map { it.copy(absoluteChange = 999.0) }),
            input.copy(comparisons = input.comparisons.map { it.copy(percentChange = 999.0) }),
            input.copy(facts = input.facts.mapIndexed { i, f -> if (i == 0) f.copy(value = 999.0) else f }),
            input.copy(evidenceReportSha256 = "not-a-hash"))) {
            assertFails { FactualReviews.prepare(bad) }
        }
    }

    @Test fun `model rejection and invalidation leave complete deterministic facts unchanged without retries`() = runBlocking {
        val report = analysisReport()
        val facts = FactualReviews.prepare(Trends.analysisInput(report))
        val model = SyntheticAnalysisModel(generate = { validClaims().replace("co_occurrence", "diagnosis") })
        val service = AnalysisService(listOf(model))
        val selected = service.select(model.option.id)
        val result = service.analyze(analysisRequest(report, selected), report) { report }
        assertEquals("unusable_model_output", result.reason)
        assertTrue(result.observations.isEmpty())
        assertEquals(1, model.calls) // Existing optional interpretation keeps zero corrective retries; no new model contract.
        assertEquals(facts, FactualReviews.prepare(Trends.analysisInput(report)))
        val stale = report.copy(evaluatedOnUtc = "2020-06-11")
        assertNotEquals(facts.evidenceReportSha256, FactualReviews.prepare(Trends.analysisInput(stale)).evidenceReportSha256)
        service.select(null)
        assertEquals("model_not_selected", service.analyze(analysisRequest(report, selected), report) { report }.reason)
        assertEquals(1, model.calls)
        assertEquals(facts, FactualReviews.prepare(Trends.analysisInput(report)))
    }
}
