package com.gtrainer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

data class DevelopmentReviewFixture(val caseId: String, val packetJson: String, val inputRevision: Long = 2) {
    init { require(inputRevision > 0) }
    override fun toString() = "DevelopmentReviewFixture([REDACTED])"
}

/** Trusted server-side catalogue seam; no HTTP request may supply packets or revisions. */
internal fun interface DevelopmentReviewFixtureSource {
    fun fixture(caseId: String): DevelopmentReviewFixture?
}

/** Loads only the curated, frozen benchmark resource; packet text never comes from a caller. */
object DevelopmentReviewFixtures {
    const val RESOURCE = "/development-review/cases-v2.json"
    const val SHA256 = "025a58daf40401df02d45621d30ca3c2b0eebfa7cf1fc762ac8299678044332b"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(bytes: ByteArray = resourceBytes()): Map<String, DevelopmentReviewFixture> {
        require(sha256(bytes) == SHA256) { "Development fixtures unavailable" }
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        return root.getValue("cases").jsonArray.associate { element ->
            val obj = element.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            id to DevelopmentReviewFixture(id, obj.getValue("packet").toString())
        }.also { require(it.size == 9) { "Development fixtures unavailable" } }
    }

    private fun resourceBytes() = requireNotNull(DevelopmentReviewFixtures::class.java.getResourceAsStream(RESOURCE)) {
        "Development fixtures unavailable"
    }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
