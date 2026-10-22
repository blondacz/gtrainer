package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Private startup-only admission. This class never calls a provider or normal app assembly. */
@Serializable
internal data class LiveIntakeCapture(
    val profile: String, val evidenceMode: String, val acquisitionId: String,
    val packetJson: String, val packetSha256: String, val requestSha256: String,
    val raw: String, val rawSha256: String, val draftJson: String, val draftSha256: String,
    val receiptSha256: String,
) {
    val packet: ContextIntakeSuggestionPacketV1 get() = StrictProfileDraftJson.decode(packetJson)
    fun validate(expectedReceipt: String, expectedMode: String): ContextIntakeSuggestionDraftV1 {
        require(profile == PROFILE && evidenceMode == expectedMode && expectedMode in setOf("live", "synthetic_fake"))
        require(UUID.fromString(acquisitionId).toString() == acquisitionId)
        require(receiptSha256 == expectedReceipt && listOf(packetSha256, requestSha256, rawSha256, draftSha256, receiptSha256).all { it.matches(Regex("[a-f0-9]{64}")) })
        require(packetJson.toByteArray().size <= 16_384 && raw.toByteArray().size <= 12_288 && draftJson.toByteArray().size <= 12_288)
        require(sha(packetJson) == packetSha256 && sha(raw) == rawSha256 && sha(draftJson) == draftSha256)
        val source = packet
        val result = IntakeExtractionV2.validate(raw, source)
        require(result.wire == "accepted" && result.binding == "accepted" && result.semantic == "accepted")
        require(result.assembledDraft == draftJson)
        val draft = ContextIntakeSuggestionContractV1.decodeDraft(draftJson, source)
        require(draft.suggestions.any { it.kind == "commitment" && it.unresolvedFields.isNotEmpty() })
        return draft
    }
    companion object {
        const val PROFILE = "guarded-live-intake-pilot-v1"
        fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun projectRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("backend/build.gradle.kts")) }.toRealPath()

        fun load(directory: Path, receipt: String, mode: String): LiveIntakeCapture {
            require(receipt.matches(Regex("[a-f0-9]{64}")) && mode in setOf("live", "synthetic_fake"))
            require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
            val scratch = Files.createTempDirectory("gtrainer-live-capture-admission-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            try {
                val output = Files.createFile(scratch.resolve("verified.json"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                val error = Files.createFile(scratch.resolve("error.log"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                val command = listOf("python3", projectRoot().resolve("benchmarks/live-intake-pilot/seal_capture.py").toString(),
                    "--directory", directory.toString(), "--expected-sha256", receipt, "--mode", mode)
                val process = ProcessBuilder(command).directory(projectRoot().toFile()).redirectOutput(output.toFile()).redirectError(error.toFile()).start()
                try {
                    require(process.waitFor(30, TimeUnit.SECONDS))
                    require(process.exitValue() == 0 && Files.size(output) in 1..131_072)
                    val capture = StrictProfileDraftJson.decode<LiveIntakeCapture>(Files.readString(output))
                    capture.validate(receipt, mode)
                    return capture
                } finally {
                    if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
                }
            } catch (_: Exception) {
                throw IllegalArgumentException("Live intake capture admission refused")
            } finally {
                Files.walk(scratch).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
}
