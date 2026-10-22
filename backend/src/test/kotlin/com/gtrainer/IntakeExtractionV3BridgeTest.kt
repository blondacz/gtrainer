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

/** Offline v3 bridge only. No provider, application store or runtime access. */
class IntakeExtractionV3BridgeTest {
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
    private fun utf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    private fun writePrivate(path: Path, bytes: ByteArray) {
        FileChannel.open(path, setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(permissions)).use {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
    }
    private fun process(directory: Path): ByteArray {
        val batch = StrictProfileDraftJson.decode<Batch>(utf8(readPrivate(directory.resolve("request.json"), 32768)))
        require(batch.version == "intake-extraction-stage-bridge-v3" && batch.profile == IntakeExtractionV3.PROFILE)
        require(batch.requests.size <= 8 && batch.requests.map { it.caseId }.distinct().size == batch.requests.size)
        require(batch.requests.all { it.caseId in IntakeExtractionV2Fixtures.ids && it.rawSha256.matches(Regex("[0-9a-f]{64}")) })
        return buildJsonObject {
            put("version", "intake-extraction-stage-bridge-v3")
            putJsonArray("results") {
                batch.requests.forEach { request ->
                    val bytes = readPrivate(directory.resolve("${request.caseId}.raw"), 12288)
                    require(sha(bytes) == request.rawSha256)
                    val result = IntakeExtractionV3.validate(utf8(bytes), IntakeExtractionV2Fixtures.packet(request.caseId))
                    add(buildJsonObject {
                        put("caseId", request.caseId); put("rawSha256", request.rawSha256)
                        put("stages", json.encodeToJsonElement(result))
                    })
                }
            }
        }.toString().toByteArray(Charsets.UTF_8)
    }
    private fun directory(configured: String): Path {
        val path = Path.of(configured).toAbsolutePath().normalize()
        require(generateSequence(path) { it.parent }.none(Files::isSymbolicLink))
        require(Files.isDirectory(path, NOFOLLOW_LINKS) && !path.startsWith(root()))
        require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
        return path
    }

    @Test fun `private v3 stage bridge when configured`() {
        val configured = System.getenv("GTRAINER_INTAKE_V3_VALIDATION_DIR") ?: return
        try {
            val folder = directory(configured)
            // Python verifies all three manifests before invoking this bridge.
            require(Files.readAllBytes(root().resolve("benchmarks/scoped-profiles/artifacts-v1.json")).contentEquals(ScopedEvaluationFixtures.bytes()))
            require(Files.readAllBytes(root().resolve("benchmarks/intake-extraction-v2/artifacts-v2.json")).contentEquals(IntakeExtractionV2Fixtures.document().toString().toByteArray()))
            require(Files.readAllBytes(root().resolve("benchmarks/intake-extraction-v3/artifacts-v3.json")).contentEquals(IntakeExtractionV3.document().toString().toByteArray()))
            writePrivate(folder.resolve("result.json"), process(folder))
        } catch (_: Exception) { throw IllegalArgumentException("V3 offline bridge refused") }
    }

    @Test fun `cross version malformed duplicate and unsafe bridge identities fail`() {
        val folder = Files.createTempDirectory("intake-v3-bridge-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        try {
            val path = folder.resolve("request.json")
            for (raw in listOf("{}", """{"version":"intake-extraction-stage-bridge-v2","profile":"context-intake-extraction-v2","requests":[]}""",
                """{"version":"intake-extraction-stage-bridge-v3","version":"x","profile":"context-intake-extraction-v3","requests":[]}""",
                """{"version":"intake-extraction-stage-bridge-v3","profile":"context-intake-extraction-v3","requests":[{"caseId":"../private","rawSha256":"${"a".repeat(64)}"}]}""")) {
                Files.writeString(path, raw); Files.setPosixFilePermissions(path, permissions)
                assertFails { process(folder) }
            }
        } finally { Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun `export unchanged fake vectors privately when configured`() {
        val configured = System.getenv("GTRAINER_INTAKE_V3_FAKE_EXPORT_DIR") ?: return
        try {
            val folder = directory(configured)
            Files.newDirectoryStream(folder).use { require(!it.iterator().hasNext()) }
            val bytes = buildJsonObject { IntakeExtractionV2Fixtures.ids.forEach { put(it, IntakeExtractionV2Fixtures.fakeRaw(it)) } }.toString().toByteArray()
            writePrivate(folder.resolve("fake-vectors.json"), bytes)
        } catch (_: Exception) { throw IllegalArgumentException("V3 fake export refused") }
    }
}
