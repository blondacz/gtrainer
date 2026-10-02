package com.gtrainer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.*

class ReviewFocusProtocolTest {
    private fun input(history: HistoryResponse = analysisHistory()) = Trends.analysisInput(analysisReport(history))
    private fun snapshot(focus: String = "daily_combined") = ReviewFocusProtocol.prepare(input(), focus)
    private fun selection(vararg ids: String) = buildJsonObject {
        putJsonArray("focusIds") { ids.forEach { add(it) } }
    }.toString()
    private fun relevant(snapshot: ReviewFocusSnapshot) = snapshot.packet.candidates.filter { candidate ->
        snapshot.facts.focuses.any { it.kind == candidate.kind && it.groupId == candidate.groupId && it.factIds == candidate.factIds }
    }
    private fun rejected(raw: String, snapshot: ReviewFocusSnapshot, reason: String? = null) {
        val result = ReviewFocusProtocol.validate(raw, snapshot)
        assertNotNull(result.reason)
        if (reason != null) assertEquals(reason, result.reason)
        assertTrue(result.interpretations.isEmpty(), "A rejected whole response must not retain partial interpretations")
    }

    @Test fun `distinct app profile retains every group comparison and both exact supports`() {
        val history = analysisHistory()
        val multiple = history.copy(activities = listOf("Ride", "Run", "Swim").flatMap { sport ->
            history.activities.map { it.copy(sport = sport, sourceRecordId = it.sourceRecordId + sport) }
        })
        val input = input(multiple)
        val snapshot = ReviewFocusProtocol.prepare(input, "daily_combined")
        assertEquals("review-focus-app-v1", snapshot.packet.profile)
        assertNotEquals("prepared-focus-v1", snapshot.packet.profile)
        assertNotEquals(snapshot.facts.profile, snapshot.packet.profile)
        assertEquals("daily_combined", snapshot.packet.requestedFocus)
        val facts = snapshot.facts.groups.flatMap { it.facts }
        assertEquals(26, facts.size)
        assertEquals(input.comparisons.toSet(), facts.map { it.comparison }.toSet())
        assertEquals(input.facts.toSet(), facts.flatMap { it.supportingMetrics }.toSet())
        assertEquals(snapshot.facts.groups.map { it.id }, snapshot.packet.groups.map { it.id })
        for (group in snapshot.packet.groups) {
            val factual = snapshot.facts.groups.single { it.id == group.id }
            assertEquals(factual.periods, group.periods)
            assertEquals(factual.coverage, group.coverage)
            assertEquals(factual.facts.map { it.id }, group.evidence.map { it.id })
            for (evidence in group.evidence) {
                val fact = factual.facts.single { it.id == evidence.id }
                assertEquals(fact.comparison.sport, evidence.sport)
                assertEquals(fact.comparison.key, evidence.metric)
                assertEquals(fact.state, evidence.state)
                assertEquals(listOf("previous", "current"), fact.supportingMetrics.map { it.period })
            }
        }
        for (candidate in snapshot.packet.candidates) {
            val group = snapshot.packet.groups.single { it.id == candidate.groupId }
            assertTrue(candidate.factIds.isNotEmpty())
            assertTrue(candidate.factIds.all { id -> group.evidence.any { it.id == id } })
        }
        assertEquals(AnalysisClaims.hash(ReviewFocusProtocol.json.encodeToString(snapshot.packet)), snapshot.packetSha256)
        assertEquals(AnalysisClaims.hash(ReviewFocusProtocol.json.encodeToString(snapshot.facts)), snapshot.factsSha256)
        assertEquals(input.evidenceReportSha256, snapshot.facts.evidenceReportSha256)
        assertEquals("ReviewFocusSnapshot([REDACTED])", snapshot.toString())
    }

    @Test fun `model packet contains only minimal categorical evidence and no values provenance account or secrets`() {
        val input = input().let { base -> base.copy(
            facts = base.facts.map { it.copy(sources = listOf("account-private"), recordOrigins = listOf("api-key-private"),
                metricOrigins = listOf("secret-private"), flags = it.flags + "token-private") },
            limitations = base.limitations + "password-private",
        ) }
        val snapshot = ReviewFocusProtocol.prepare(input, "daily_combined")
        val wire = ReviewFocusProtocol.json.encodeToString(snapshot.packet)
        for (forbidden in listOf("synthetic-analysis", "sourceRecordId", "points", "previousValue", "currentValue",
            "value", "sampleCount", "label", "unit", "supportingMetrics", "comparison", "key", "sources",
            "recordOrigins", "evidenceReportSha256", "account-private", "api-key-private", "secret-private", "token-private", "password-private")) {
            assertFalse(wire.contains(forbidden), "Packet leaked $forbidden")
        }
        val packet = Json.parseToJsonElement(wire).jsonObject
        assertEquals(setOf("profile", "requestedFocus", "groups", "candidates"), packet.keys)
        for (group in packet["groups"]!!.jsonArray) {
            assertEquals(setOf("id", "periods", "coverage", "evidence"), group.jsonObject.keys)
            for (evidence in group.jsonObject["evidence"]!!.jsonArray) {
                assertEquals(setOf("id", "sport", "metric", "state"), evidence.jsonObject.keys)
            }
        }
        fun assertCategorical(element: JsonElement) {
            when (element) {
                is JsonObject -> element.values.forEach { assertCategorical(it) }
                is JsonArray -> element.forEach { assertCategorical(it) }
                is JsonPrimitive -> assertTrue(element == JsonNull || element.isString)
            }
        }
        assertCategorical(packet)
        assertTrue(ReviewFocusProtocol.unchanged(snapshot))
        assertEquals("ReviewFocusPacket([REDACTED])", snapshot.packet.toString())
    }

    @Test fun `strict whole JSON rejects malformed escaped duplicate keys excessive depth size and prose`() {
        val snapshot = snapshot()
        val id = relevant(snapshot).single().id
        val valid = selection(id)
        for (bad in listOf("", "not JSON", "{", valid.dropLast(1), valid + valid,
            "```json\n$valid\n```", "Here is the selection: $valid", "$valid explanation",
            "{\"focusIds\":[\"$id\"],\"focusIds\":[\"$id\"]}",
            "{\"focusIds\":[\"$id\"],\"focus\\u0049ds\":[\"$id\"]}",
            "{\"focusIds\":[\"$id\"],}", "{focusIds:[\"$id\"]}",
            " ".repeat(32_769) + valid, "[".repeat(18) + "0" + "]".repeat(18),
            "{\"focusIds\":[NaN]}", "{\"focusIds\":[unquoted]}")) {
            rejected(bad, snapshot, "invalid_json")
        }
        assertNull(ReviewFocusProtocol.validate(" \n$valid\t", snapshot).reason)
    }

    @Test fun `shape counts types unknown IDs duplicate choices and arbitrary fields fail closed`() {
        val snapshot = snapshot()
        val id = relevant(snapshot).single().id
        for (bad in listOf("null", "[]", "true", "0", "{}", "{\"observations\":[]}",
            "{\"focusIds\":[\"$id\"],\"text\":\"you are ready\"}",
            "{\"focusIds\":[\"$id\"],\"score\":99}")) rejected(bad, snapshot, "invalid_shape")
        for (bad in listOf("null", "\"$id\"", "{}", "[]", "[null]", "[true]", "[42]", "[{}]", "[[]]",
            "[\"$id\",0]", "[\"$id\",\"f100\",\"f101\"]")) {
            rejected("{\"focusIds\":$bad}", snapshot, "invalid_focus_count")
        }
        rejected(selection(id, id), snapshot, "duplicate_focus")
        for (unknown in listOf("", "f999", "e0", "fitnessAge", "cross_metric_pattern")) {
            rejected(selection(unknown), snapshot, "unsupported_focus")
        }
        rejected(selection(id, "f999"), snapshot, "unsupported_focus")
    }

    @Test fun `valid irrelevant candidate and mixed right wrong candidates reject the entire answer without repair`() {
        val snapshot = snapshot()
        val right = relevant(snapshot).single()
        val wrong = snapshot.packet.candidates.first { it.kind == "wellness_pattern" }
        rejected(selection(wrong.id), snapshot, "irrelevant_focus")
        rejected(selection(right.id, wrong.id), snapshot, "irrelevant_focus")
        rejected(selection(wrong.id, right.id), snapshot, "irrelevant_focus")
        val rendered = ReviewFocusProtocol.validate(selection(right.id), snapshot)
        assertNull(rendered.reason)
        assertEquals(listOf("cross_metric_pattern"), rendered.interpretations.map { it.kind })
        assertEquals(snapshot.facts.focuses.single().text, rendered.interpretations.single().text)
        assertEquals(right.factIds, rendered.interpretations.single().supportingFacts.map { it.id })
        assertTrue(snapshot.facts.groups.flatMap { it.facts }.size > rendered.interpretations.single().supportingFacts.size)
    }

    @Test fun `requested topics accept only corresponding supported candidates`() {
        val history = analysisHistory()
        val mixed = history.copy(activities = history.activities + history.activities.map {
            it.copy(sport = "Run", sourceRecordId = it.sourceRecordId + "-run")
        })
        for ((focus, kind) in mapOf("daily_combined" to "cross_metric_pattern", "activity_balance" to "sport_mix",
            "wellness" to "wellness_pattern", "missing_wellness" to "coverage_gap")) {
            val snapshot = ReviewFocusProtocol.prepare(input(mixed), focus)
            val candidate = relevant(snapshot).single()
            assertEquals(kind, candidate.kind)
            val result = ReviewFocusProtocol.validate(selection(candidate.id), snapshot)
            assertNull(result.reason)
            assertEquals(kind, result.interpretations.single().kind)
            snapshot.packet.candidates.filter { it.kind != kind }.forEach {
                rejected(selection(it.id), snapshot, "irrelevant_focus")
            }
        }
        val noBalance = snapshot("activity_balance")
        assertTrue(relevant(noBalance).isEmpty())
        noBalance.packet.candidates.forEach { rejected(selection(it.id), noBalance, "irrelevant_focus") }
    }

    @Test fun `compatible groups render only their exact matching periods and two selections stay independent`() {
        val history = analysisHistory().let { it.copy(activities = it.activities.take(1) + it.activities.takeLast(1),
            wellness = it.wellness.take(1) + it.wellness.takeLast(1)) }
        val snapshot = ReviewFocusProtocol.prepare(input(history), "wellness")
        val candidates = relevant(snapshot)
        assertEquals(2, candidates.size)
        val result = ReviewFocusProtocol.validate(selection(*candidates.map { it.id }.toTypedArray()), snapshot)
        assertNull(result.reason)
        assertEquals(2, result.interpretations.size)
        for (interpretation in result.interpretations) {
            val group = snapshot.facts.groups.single { it.id == interpretation.groupId }
            val candidate = candidates.single { it.groupId == group.id }
            assertEquals(candidate.factIds.map { id -> group.facts.single { it.id == id } }, interpretation.supportingFacts)
            for (fact in interpretation.supportingFacts) {
                val (before, after) = fact.supportingMetrics
                assertEquals(group.periods, ReviewPeriods(before.oldest, before.newest, after.oldest, after.newest))
            }
        }
    }

    @Test fun `different activity and wellness periods never gain a combined candidate`() {
        val base = input()
        val shifted = base.copy(facts = base.facts.map { fact -> if (fact.sport != null) fact else fact.copy(
            oldest = LocalDate.parse(fact.oldest).minusDays(7).toString(),
            newest = LocalDate.parse(fact.newest).minusDays(7).toString()) })
        val snapshot = ReviewFocusProtocol.prepare(shifted, "daily_combined")
        assertEquals(2, snapshot.packet.groups.map { it.periods }.distinct().size)
        assertTrue(snapshot.packet.candidates.none { it.kind == "cross_metric_pattern" })
        snapshot.packet.candidates.forEach { rejected(selection(it.id), snapshot, "irrelevant_focus") }
    }

    @Test fun `mutated packet facts digest and candidate support bindings are rejected without substitution`() {
        val base = snapshot()
        val candidate = relevant(base).single()
        val raw = selection(candidate.id)
        val packet = base.packet.copy(groups = base.packet.groups.mapIndexed { index, group ->
            if (index == 0) group.copy(periods = group.periods.copy(currentOldest = "2020-02-01")) else group
        })
        val facts = base.facts.copy(groups = base.facts.groups.mapIndexed { index, group ->
            if (index == 0) group.copy(facts = group.facts.map { it.copy(text = "untrusted replacement") }) else group
        })
        for (changed in listOf(base.copy(packet = packet), base.copy(facts = facts),
            base.copy(packetSha256 = "0".repeat(64)), base.copy(factsSha256 = "0".repeat(64)))) {
            assertFalse(ReviewFocusProtocol.unchanged(changed))
            rejected(raw, changed, "evidence_changed")
        }
        val rebound = base.packet.copy(candidates = base.packet.candidates.map {
            if (it.id == candidate.id) it.copy(factIds = it.factIds.dropLast(1)) else it
        })
        val reboundSnapshot = base.copy(packet = rebound, packetSha256 = AnalysisClaims.hash(ReviewFocusProtocol.json.encodeToString(rebound)))
        assertTrue(ReviewFocusProtocol.unchanged(reboundSnapshot))
        rejected(raw, reboundSnapshot, "evidence_changed")
        assertNull(ReviewFocusProtocol.validate(raw, base).reason)
    }

    @Test fun `schema is closed focus IDs only and separate from frozen legacy and benchmark contracts`() {
        val packet = snapshot().packet
        val expected = buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonArray("required") { add("focusIds") }
            putJsonObject("properties") {
                putJsonObject("focusIds") {
                    put("type", "array"); put("minItems", 1); put("maxItems", 2)
                    putJsonObject("items") { put("type", "string"); putJsonArray("enum") { packet.candidates.forEach { add(it.id) } } }
                }
            }
        }
        assertEquals(expected, ReviewFocusProtocol.schema(packet))
        val legacy = AnalysisClaims.prepare(input())
        assertEquals(setOf("evidence"), Json.parseToJsonElement(AnalysisClaims.json.encodeToString(legacy.packet)).jsonObject.keys)
        assertEquals(setOf("observations"), AnalysisClaims.schema(legacy.packet)["properties"]!!.jsonObject.keys)
        assertNotNull(AnalysisClaims.validateAndRender(validClaims(), legacy))
        assertNull(AnalysisClaims.validateAndRender(selection(relevant(snapshot()).single().id), legacy))
        rejected(validClaims(), snapshot(), "invalid_shape")
        assertEquals("9e544322e7eb0c976dbcef4c2973b3b0859fb5e5505701d3f76c549ca558e314", AnalysisClaims.hash(AnalysisClaims.system))
        assertNotEquals(AnalysisClaims.system, ReviewFocusProtocol.system)
        val benchmark = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("benchmarks/ollama/prepared_review.py") }.first { Files.isRegularFile(it) }
        assertEquals("309dc4e72611f094d6fdc1b9baef81f24c5a213cbfcece9baa7053501946f93d",
            AnalysisClaims.hash(Files.readString(benchmark)))
    }
}
