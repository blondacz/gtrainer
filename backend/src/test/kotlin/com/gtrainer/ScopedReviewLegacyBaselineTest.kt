package com.gtrainer

import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScopedReviewLegacyBaselineTest {
    private val codec = Json { encodeDefaults = true }
    private val comparisonInput = run {
        fun fact(period: String, from: String, until: String, value: Double) = SummaryFact(
            "moving-$period", period, from, until, "run", "movingTime", "Moving time", "seconds", "sum",
            value, 3, 3, 7, until, until, listOf("synthetic"), listOf("synthetic"), listOf("synthetic"), 0, emptyList())
        AnalysisInput(1, "b".repeat(64), "2025-01-14T00:00:00Z", "run",
            listOf(fact("previous", "2025-01-01", "2025-01-07", 1200.0), fact("current", "2025-01-08", "2025-01-14", 1500.0)),
            listOf(MetricComparison("run", "movingTime", "Moving time", "seconds", 1500.0, 1200.0, 300.0, 25.0, 3, 3, emptyList())),
            emptyList(), emptyList(), emptyList())
    }

    @Test fun `legacy profile identifiers remain distinct and stable`() {
        val profiles = resource().getValue("profiles").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(profiles[0], FactualReviews.prepare(
            AnalysisInput(1, "a".repeat(64), "2025-01-01T00:00:00Z", null,
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), "wellness").profile)
        assertEquals(profiles[1], ReviewFocusProtocol.PROFILE)
        assertEquals(profiles[2], InterpretationContractV1.PROFILE)
    }

    @Test fun `frozen synthetic fixture bytes match captured baseline`() {
        fixtureBaselines().forEach { (name, expected) ->
            val bytes = Files.readAllBytes(Path.of(name))
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual, name)
        }
    }

    @Test fun `actual Kotlin contract packets and prompts match captured hashes for every v2 case`() {
        val cases = Json.parseToJsonElement(Files.readString(Path.of("../benchmarks/connected-review/cases-v2.json")))
            .jsonObject.getValue("cases").jsonArray
        val expected = resource().getValue("connectedReviewCasesV2").jsonObject
        assertEquals(9, cases.size)
        assertEquals(expected.keys, cases.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
        cases.forEach { element ->
            val item = element.jsonObject
            val id = item.getValue("id").jsonPrimitive.content
            val packet = Json.decodeFromJsonElement<InterpretationPacketV1>(item.getValue("packet"))
            assertEquals(expected.getValue(id).jsonObject.getValue("packetSha256").jsonPrimitive.content, sha(codec.encodeToString(InterpretationPacketV1.serializer(), packet)), "$id packet")
            assertEquals(expected.getValue(id).jsonObject.getValue("promptSha256").jsonPrimitive.content, InterpretationContractV1.promptSha256(packet), "$id prompt")
        }
    }

    @Test fun `historical qualification snapshot is represented as completed of total not qualification`() {
        val ledger = resource().getValue("qualificationLedger").jsonObject
        assertEquals(20, ledger.getValue("completed").toString().toInt())
        assertEquals(22, ledger.getValue("total").toString().toInt())
        assertEquals(listOf("6.2", "6.3"), ledger.getValue("incompleteTaskIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, ledger.getValue("qualified").toString().toBoolean())
    }

    @Test fun `actual nonempty earlier profile facts and focus packet match independent goldens`() {
        val factual = FactualReviews.prepare(comparisonInput, "wellness")
        val focus = ReviewFocusProtocol.prepare(comparisonInput, "wellness").packet
        val expected = resource().getValue("earlierProfiles").jsonObject
        assertTrue(factual.groups.isNotEmpty())
        assertTrue(focus.candidates.isNotEmpty())
        assertEquals(expected.getValue("factualReviewFactsSha256").jsonPrimitive.content,
            sha(codec.encodeToString(FactualReview.serializer(), factual)))
        assertEquals(expected.getValue("reviewFocusPacketSha256").jsonPrimitive.content,
            sha(codec.encodeToString(ReviewFocusPacket.serializer(), focus)))
        assertEquals(expected.getValue("reviewFocusSystemSha256").jsonPrimitive.content, sha(ReviewFocusProtocol.system))
    }

    private fun resource() = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/scoped-review-legacy-baseline.json")).readText()).jsonObject
    private fun fixtureBaselines() = resource().getValue("frozenFixtures").jsonObject.mapValues { it.value.toString().trim('"') }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

}
