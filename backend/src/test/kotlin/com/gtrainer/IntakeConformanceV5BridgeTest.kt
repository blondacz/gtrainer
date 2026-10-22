package com.gtrainer

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.test.*

/** Offline packet-bound test bridge. No provider, runtime, store, or repair. */
class IntakeConformanceV5BridgeTest {
    @Serializable private data class Request(val caseId: String, val packetJson: String, val packetSha256: String, val rawSha256: String)
    @Serializable private data class Batch(val version: String, val profile: String, val suiteManifestSha256: String, val requests: List<Request>)
    private val permissions = PosixFilePermissions.fromString("rw-------")
    private val json = IntakeExtractionV2Fixtures.json
    private val version = "intake-conformance-stage-bridge-v5"
    private val profile = "context-intake-extraction-v5"
    private val frozenManifestSha256 = "64ebc651b21f3d091a84386453b8741e264f32c323dcbe0df6c7e014c4a3a276"
    private val ids = (1..8).map { "u${it.toString().padStart(2, '0')}" }
    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("backend/build.gradle.kts")) }.toRealPath()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun readPrivate(path: Path, limit: Int): ByteArray {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS))
        require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == permissions)
        require((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1 && Files.size(path) <= limit)
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) }
    }
    private fun utf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    private fun manifest(directory: Path): ByteArray {
        val base = root()
        val raw = Files.readAllBytes(base.resolve("benchmarks/intake-extraction-v5/manifest-v5.json"))
        require(sha(raw) == frozenManifestSha256)
        val value = json.parseToJsonElement(utf8(raw)).jsonObject
        require(value["version"]?.jsonPrimitive?.content == "intake-conformance-manifest-v5")
        require(value["profile"]?.jsonPrimitive?.content == profile && value["modelCallsPermitted"]?.jsonPrimitive?.boolean == false)
        val files = value["files"]!!.jsonObject
        require(files.keys.all { !Path.of(it).isAbsolute && !it.split('/').contains("..") })
        files.forEach { (name, digest) -> require(sha(Files.readAllBytes(base.resolve(name))) == digest.jsonPrimitive.content) }
        require(directory != base && !directory.startsWith(base))
        return raw
    }
    private fun decode(bytes: ByteArray, manifestSha: String): Batch {
        val batch = StrictProfileDraftJson.decode<Batch>(utf8(bytes))
        require(batch.version == version && batch.profile == profile && batch.suiteManifestSha256 == manifestSha)
        require(batch.requests.size in 1..8 && batch.requests.map { it.caseId }.distinct().size == batch.requests.size)
        require(batch.requests.all { it.caseId in ids && it.packetSha256.matches(Regex("[0-9a-f]{64}")) && it.rawSha256.matches(Regex("[0-9a-f]{64}")) })
        val inputs = json.parseToJsonElement(Files.readString(root().resolve("benchmarks/intake-extraction-v5/inputs-v5.json"))).jsonObject
        require(inputs["profile"]?.jsonPrimitive?.content == profile)
        val packets = inputs["cases"]!!.jsonArray.associate { row ->
            val item = row.jsonObject
            item["caseId"]!!.jsonPrimitive.content to item["userPacketJson"]!!.jsonPrimitive.content
        }
        batch.requests.forEach { request ->
            val packetBytes = request.packetJson.toByteArray(Charsets.UTF_8)
            require(sha(packetBytes) == request.packetSha256 && packets[request.caseId] == request.packetJson)
            StrictProfileDraftJson.decode<ContextIntakeSuggestionPacketV1>(request.packetJson)
        }
        return batch
    }
    private fun writePrivate(path: Path, bytes: ByteArray) {
        FileChannel.open(path, setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(permissions)).use { channel ->
            val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
        }
    }

    @Test fun `private v5 stage bridge when configured`() {
        val configured = System.getenv("GTRAINER_INTAKE_V5_VALIDATION_DIR") ?: return
        try {
            val directory = Path.of(configured).toAbsolutePath().normalize()
            require(generateSequence(directory) { it.parent }.none(Files::isSymbolicLink))
            require(Files.isDirectory(directory, NOFOLLOW_LINKS) && Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
            val manifestBytes = manifest(directory)
            val manifestSha = sha(manifestBytes)
            val batch = decode(readPrivate(directory.resolve("request.json"), 65536), manifestSha)
            // All packet identities are checked before any response file is opened.
            val result = buildJsonObject {
                put("version", version); put("profile", profile); put("suiteManifestSha256", manifestSha)
                putJsonArray("results") {
                    batch.requests.forEach { request ->
                        val packet = StrictProfileDraftJson.decode<ContextIntakeSuggestionPacketV1>(request.packetJson)
                        val bytes = readPrivate(directory.resolve("${request.caseId}.raw"), 12288)
                        require(sha(bytes) == request.rawSha256)
                        val raw = utf8(bytes)
                        val stages = IntakeExtractionV2.validate(raw, packet)
                        add(buildJsonObject { put("caseId", request.caseId); put("packetSha256", request.packetSha256); put("rawSha256", request.rawSha256); put("stages", json.encodeToJsonElement(stages)) })
                    }
                }
            }
            writePrivate(directory.resolve("result.json"), result.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) { throw IllegalArgumentException("V5 offline bridge refused") }
    }

    @Test fun `cross version duplicate unknown and packet substitution refuse`() {
        val packet = "{}"
        val request = """{"caseId":"u01","packetJson":"$packet","packetSha256":"${sha(packet.toByteArray())}","rawSha256":"${"0".repeat(64)}"}"""
        val good = """{"version":"$version","profile":"$profile","suiteManifestSha256":"${"a".repeat(64)}","requests":[$request]}"""
        val substituted = "{\"substituted\":true}"
        val substitutedRequest = buildJsonObject {
            put("caseId", "u01"); put("packetJson", substituted); put("packetSha256", sha(substituted.toByteArray())); put("rawSha256", "0".repeat(64))
        }
        val substitutedBatch = buildJsonObject {
            put("version", version); put("profile", profile); put("suiteManifestSha256", "a".repeat(64)); putJsonArray("requests") { add(substitutedRequest) }
        }.toString()
        assertFails { decode(good.replace(version, "intake-conformance-stage-bridge-v4").toByteArray(), "a".repeat(64)) }
        assertFails { decode(good.replace("\"requests\":[$request]", "\"requests\":[$request,$request]").toByteArray(), "a".repeat(64)) }
        assertFails { decode(good.replace("u01", "../raw").toByteArray(), "a".repeat(64)) }
        assertFails { decode(substitutedBatch.toByteArray(), "a".repeat(64)) }
        assertFails { decode(good.replace("\"version\":", "\"version\":\"duplicate\",\"version\":").toByteArray(), "a".repeat(64)) }
    }

    @Test fun `unchanged strict validator rejects malformed wire`() {
        val result = IntakeExtractionV2.validate("{}", IntakeExtractionV2Fixtures.packet("i01"))
        assertEquals("rejected", result.wire); assertEquals("not_run", result.binding); assertEquals("not_run", result.semantic)
    }

    @Test fun `fresh frozen packet preserves wire binding and semantic rejections`() {
        val base = root().resolve("benchmarks/intake-extraction-v5")
        val inputs = json.parseToJsonElement(Files.readString(base.resolve("inputs-v5.json"))).jsonObject
        val fixtures = json.parseToJsonElement(Files.readString(base.resolve("fixtures-v5.json"))).jsonObject
        fun packet(id: String): ContextIntakeSuggestionPacketV1 {
            val row = inputs["cases"]!!.jsonArray.first { it.jsonObject["caseId"]!!.jsonPrimitive.content == id }.jsonObject
            return StrictProfileDraftJson.decode(row["userPacketJson"]!!.jsonPrimitive.content)
        }
        val uncertain = fixtures["u02"]!!.toString()
        val duplicate = uncertain.replace("[\"startsLocal\",\"endsLocal\"", "[\"startsLocal\",\"startsLocal\",\"endsLocal\"")
        assertNotEquals(uncertain, duplicate)
        assertEquals("rejected", IntakeExtractionV2.validate(duplicate, packet("u02")).wire)
        val wrongSource = uncertain.replace("Travel on 2026-11-04, 08:00 to 09:00 UTC.", "Absent source")
        assertEquals("rejected", IntakeExtractionV2.validate(wrongSource, packet("u02")).binding)
        val known = fixtures["u01"]!!.toString()
        val inconsistent = known.replace("\"frequency\":\"one_off\"", "\"frequency\":\"weekly\"")
        assertNotEquals(known, inconsistent)
        val rejected = IntakeExtractionV2.validate(inconsistent, packet("u01"))
        assertEquals("accepted", rejected.wire)
        assertEquals("accepted", rejected.binding)
        assertEquals("rejected", rejected.semantic)
    }
}
