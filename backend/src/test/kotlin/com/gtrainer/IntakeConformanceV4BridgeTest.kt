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

/** Offline test-only bridge. No provider, runtime, application store or repair. */
class IntakeConformanceV4BridgeTest {
    @Serializable private data class Request(val caseId: String, val rawSha256: String)
    @Serializable private data class Batch(val version: String, val profile: String, val requests: List<Request>)
    private val json = IntakeExtractionV2Fixtures.json
    private val permissions = PosixFilePermissions.fromString("rw-------")
    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("backend/build.gradle.kts")) }.toRealPath()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun readPrivate(path: Path, limit: Int): ByteArray {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS))
        require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == permissions)
        require((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1)
        require(Files.size(path) <= limit)
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) }
    }
    private fun decode(bytes: ByteArray): Batch {
        val raw = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val batch = StrictProfileDraftJson.decode<Batch>(raw)
        require(batch.version == "intake-conformance-stage-bridge-v4" && batch.profile == "context-intake-extraction-v4")
        require(batch.requests.size <= 8 && batch.requests.map { it.caseId }.distinct().size == batch.requests.size)
        require(batch.requests.all { it.caseId in IntakeExtractionV2Fixtures.ids && it.rawSha256.matches(Regex("[0-9a-f]{64}")) })
        return batch
    }
    private fun writePrivate(path: Path, bytes: ByteArray) {
        FileChannel.open(path, setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(permissions)).use {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
    }

    @Test fun `private v4 stage bridge when configured`() {
        val configured = System.getenv("GTRAINER_INTAKE_V4_VALIDATION_DIR") ?: return
        try {
            val directory = Path.of(configured).toAbsolutePath().normalize()
            require(generateSequence(directory) { it.parent }.none(Files::isSymbolicLink))
            require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !directory.startsWith(root()))
            require(Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
            // Python verifies the full v4 manifest before invoking this bridge.
            require(Files.readAllBytes(root().resolve("benchmarks/scoped-profiles/artifacts-v1.json")).contentEquals(ScopedEvaluationFixtures.bytes()))
            require(Files.readAllBytes(root().resolve("benchmarks/intake-extraction-v2/artifacts-v2.json")).contentEquals(IntakeExtractionV2Fixtures.document().toString().toByteArray()))
            require(Files.readAllBytes(root().resolve("benchmarks/intake-extraction-v3/artifacts-v3.json")).contentEquals(IntakeExtractionV3.document().toString().toByteArray()))
            val batch = decode(readPrivate(directory.resolve("request.json"), 32768))
            val result = buildJsonObject {
                put("version", "intake-conformance-stage-bridge-v4")
                putJsonArray("results") {
                    batch.requests.forEach { request ->
                        val bytes = readPrivate(directory.resolve("${request.caseId}.raw"), 12288)
                        require(sha(bytes) == request.rawSha256)
                        val raw = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                        val checked = IntakeExtractionV2.validate(raw, IntakeExtractionV2Fixtures.packet(request.caseId))
                        add(buildJsonObject {
                            put("caseId", request.caseId); put("rawSha256", request.rawSha256)
                            put("stages", json.encodeToJsonElement(checked))
                        })
                    }
                }
            }
            writePrivate(directory.resolve("result.json"), result.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) { throw IllegalArgumentException("V4 offline bridge refused") }
    }

    @Test fun `malformed cross version duplicate and unsafe identities refuse`() {
        for (raw in listOf("{}", """{"version":"intake-extraction-stage-bridge-v3","profile":"context-intake-extraction-v3","requests":[]}""",
            """{"version":"intake-conformance-stage-bridge-v4","version":"x","profile":"context-intake-extraction-v4","requests":[]}""",
            """{"version":"intake-conformance-stage-bridge-v4","profile":"context-intake-extraction-v4","requests":[{"caseId":"../private","rawSha256":"${"a".repeat(64)}"}]}""")) {
            assertFails { decode(raw.toByteArray()) }
        }
    }

    @Test fun `unchanged strict validator rejects malformed wire`() {
        val result = IntakeExtractionV2.validate("{}", IntakeExtractionV2Fixtures.packet("i01"))
        assertEquals("rejected", result.wire)
        assertEquals("not_run", result.binding)
        assertEquals("not_run", result.semantic)
    }
}
