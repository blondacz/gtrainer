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
import kotlin.test.Test

/** Checks handwritten, unrelated prompt demonstrations offline, never model output. */
class IntakeUnrelatedDemonstrationsV4Test {
    @Serializable private data class Demonstration(val content: String, val observedOn: String, val raw: String)
    @Serializable private data class Batch(val version: String, val examples: List<Demonstration>)
    private val json = IntakeExtractionV2Fixtures.json
    private val fileMode = PosixFilePermissions.fromString("rw-------")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `private unrelated demonstrations pass unchanged strict validation when configured`() {
        val configured = System.getenv("GTRAINER_INTAKE_V4_DEMONSTRATION_DIR") ?: return
        try {
            val directory = Path.of(configured).toAbsolutePath().normalize()
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.exists(it.resolve("backend/build.gradle.kts")) }.toRealPath()
            require(generateSequence(directory) { it.parent }.none(Files::isSymbolicLink))
            require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !directory.startsWith(root))
            require(Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
            val input = directory.resolve("demonstrations.json")
            require(Files.isRegularFile(input, NOFOLLOW_LINKS) && !Files.isSymbolicLink(input))
            require(Files.getPosixFilePermissions(input, NOFOLLOW_LINKS) == fileMode)
            require((Files.getAttribute(input, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1)
            require(Files.size(input) <= 65536)
            val bytes = Files.newInputStream(input, NOFOLLOW_LINKS).use { it.readNBytes(65537) }
            require(bytes.size <= 65536)
            val batch = StrictProfileDraftJson.decode<Batch>(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
            require(batch.version == "intake-unrelated-demonstrations-v4" && batch.examples.size == 4)
            val packets = batch.examples.mapIndexed { index, example ->
                ContextIntakeSuggestionContractV1.packet(ContextIntake(
                    "44444444-4444-4444-8444-" + (index + 1).toString().padStart(12, '0'), 1,
                    example.content, "user_report", "athlete", "user", example.observedOn,
                    example.observedOn + "T10:00:00Z"))
            }
            val results = batch.examples.zip(packets).map { (example, packet) ->
                IntakeExtractionV2.validate(example.raw, packet).also {
                    require(it.wire == "accepted" && it.binding == "accepted" && it.semantic == "accepted")
                }
            }
            val context = StrictProfileDraftJson.decode<IntakeV2Wire>(batch.examples[1].raw)
            val suggestion = context.suggestions.single()
            val duplicate = context.copy(suggestions = listOf(suggestion.copy(unresolvedFields = suggestion.unresolvedFields + suggestion.unresolvedFields)))
            val duplicateResult = IntakeExtractionV2.validate(json.encodeToString(duplicate), packets[1])
            require(duplicateResult.wire == "rejected")
            val mismatch = context.copy(suggestions = listOf(suggestion.copy(context = suggestion.context!!.copy(applicableUntilMode = "unknown"))))
            val mismatchResult = IntakeExtractionV2.validate(json.encodeToString(mismatch), packets[1])
            require(mismatchResult.wire == "accepted" && mismatchResult.binding == "accepted" && mismatchResult.semantic == "rejected")
            val output = buildJsonObject {
                put("version", "intake-unrelated-demonstration-check-v4")
                put("inputSha256", sha(bytes))
                put("inferencePerformed", false)
                putJsonArray("results") { results.forEachIndexed { index, result -> add(buildJsonObject {
                    put("index", index); put("rawSha256", sha(batch.examples[index].raw.toByteArray(Charsets.UTF_8)))
                    put("stages", json.encodeToJsonElement(result))
                }) } }
                put("duplicateUnresolvedWire", duplicateResult.wire)
                put("unknownModeWithoutDisclosureSemantic", mismatchResult.semantic)
            }.toString().toByteArray(Charsets.UTF_8)
            require(output.size <= 65536)
            FileChannel.open(directory.resolve("result.json"), setOf(CREATE_NEW, WRITE), PosixFilePermissions.asFileAttribute(fileMode)).use {
                val buffer = ByteBuffer.wrap(output)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
        } catch (_: Exception) { throw IllegalArgumentException("Unrelated demonstration validation refused") }
    }
}
