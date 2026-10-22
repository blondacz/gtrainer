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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Private offline stage bridge. It has no provider, runtime, application store or network access. */
class IntakeExtractionV2BridgeTest {
    @Serializable private data class Request(val caseId: String, val rawSha256: String)
    @Serializable private data class Batch(val version: String, val profile: String, val requests: List<Request>)
    private val json = IntakeExtractionV2Fixtures.json
    private val permissions = PosixFilePermissions.fromString("rw-------")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("backend/build.gradle.kts")) }.toRealPath()

    private fun readPrivate(path: Path, limit: Int): ByteArray {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS))
        require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == permissions)
        require((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1)
        require(Files.size(path) <= limit)
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) }
    }
    private fun utf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    private fun process(directory: Path): ByteArray {
        val batch = StrictProfileDraftJson.decode<Batch>(utf8(readPrivate(directory.resolve("request.json"), 32768)))
        require(batch.version == "intake-extraction-stage-bridge-v2" && batch.profile == IntakeExtractionV2.PROFILE)
        require(batch.requests.size <= 8 && batch.requests.map { it.caseId }.distinct().size == batch.requests.size)
        require(batch.requests.all { it.caseId in IntakeExtractionV2Fixtures.ids && it.rawSha256.matches(Regex("[0-9a-f]{64}")) })
        val results = batch.requests.map { request ->
            val raw = readPrivate(directory.resolve("${request.caseId}.raw"), 12288)
            require(sha(raw) == request.rawSha256)
            val result = IntakeExtractionV2.validate(utf8(raw), IntakeExtractionV2Fixtures.packet(request.caseId))
            buildJsonObject {
                put("caseId", request.caseId)
                put("rawSha256", request.rawSha256)
                put("stages", json.encodeToJsonElement(result))
            }
        }
        return buildJsonObject {
            put("version", "intake-extraction-stage-bridge-v2")
            put("results", JsonArray(results))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    @Test fun `private directory bridge when configured`() {
        val configured = System.getenv("GTRAINER_INTAKE_V2_VALIDATION_DIR") ?: return
        try {
            val directory = Path.of(configured).toAbsolutePath().normalize()
            require(generateSequence(directory) { it.parent }.none(Files::isSymbolicLink))
            require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !directory.startsWith(root()))
            require(Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
            // Python independently verifies both manifests before calling this bridge.
            val old = root().resolve("benchmarks/scoped-profiles/artifacts-v1.json")
            require(Files.readAllBytes(old).contentEquals(ScopedEvaluationFixtures.bytes()))
            val current = root().resolve("benchmarks/intake-extraction-v2/artifacts-v2.json")
            require(Files.readAllBytes(current).contentEquals(IntakeExtractionV2Fixtures.document().toString().toByteArray(Charsets.UTF_8)))
            val output = process(directory)
            FileChannel.open(directory.resolve("result.json"), setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(permissions)).use {
                val buffer = ByteBuffer.wrap(output)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
        } catch (_: Exception) { throw IllegalArgumentException("Experimental offline bridge refused") }
    }

    @Test fun `malformed bridge batches fail without exposing content`() {
        val directory = Files.createTempDirectory("intake-v2-bridge-test", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        try {
            val path = directory.resolve("request.json")
            val malformed = listOf(
                "{}", """{"version":"x","profile":"x","requests":[]}""",
                """{"version":"intake-extraction-stage-bridge-v2","profile":"context-intake-extraction-v2","requests":[{"caseId":"../secret","rawSha256":"${"a".repeat(64)}"}]}""",
                """{"version":"intake-extraction-stage-bridge-v2","version":"x","profile":"context-intake-extraction-v2","requests":[]}""")
            malformed.forEach { source ->
                Files.writeString(path, source)
                Files.setPosixFilePermissions(path, permissions)
                assertFails { process(directory) }
            }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `export fake response vectors privately when configured`() {
        val configured = System.getenv("GTRAINER_INTAKE_V2_FAKE_EXPORT_DIR") ?: return
        try {
            val directory = Path.of(configured).toAbsolutePath().normalize()
            require(!directory.startsWith(root()) && generateSequence(directory) { it.parent }.none(Files::isSymbolicLink))
            require(Files.getPosixFilePermissions(directory) == PosixFilePermissions.fromString("rwx------"))
            Files.newDirectoryStream(directory).use { require(!it.iterator().hasNext()) }
            val vectors = buildJsonObject {
                IntakeExtractionV2Fixtures.ids.forEach { put(it, IntakeExtractionV2Fixtures.fakeRaw(it)) }
            }.toString().toByteArray(Charsets.UTF_8)
            FileChannel.open(directory.resolve("fake-vectors.json"), setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(permissions)).use {
                val buffer = ByteBuffer.wrap(vectors)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
        } catch (_: Exception) { throw IllegalArgumentException("Experimental fake export refused") }
    }
}
