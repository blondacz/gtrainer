package com.gtrainer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

/** Invented inputs from the normal immutable preparation path; no provider or source request. */
internal suspend fun descriptiveFeasibilityInput(data: Path, compactModelInput: Boolean = false): JsonObject {
    val fixture = DescriptiveUiFixture(data)
    try {
        val cases = buildJsonArray {
            for (mode in listOf("activities", "period")) for (intent in listOf("review", "compare")) {
                val prepared = fixture.prepare(mode, intent)
                val canonical = prepared.binding.exactPrompt
                val prompt = if (compactModelInput) OwnerUatDescriptiveWireV1.prepare(canonical).modelPrompt else canonical
                add(buildJsonObject {
                    put("caseId", "$mode-$intent")
                    putJsonObject("exactPrompt") {
                        put("systemInstructions", prompt.systemInstructions)
                        put("userPacketJson", prompt.userPacketJson)
                    }
                })
            }
        }
        check(fixture.calls.get() == 0 && fixture.source.calls == 0 && fixture.observations.get() == 0)
        return buildJsonObject {
            put("version", "owner-uat-descriptive-feasibility-input-v1")
            put("syntheticOnly", true)
            put("cases", cases)
        }
    } finally {
        fixture.close()
    }
}

/** Opt-in test-classpath exporter only. The output is not a production context certificate. */
object OwnerUatContextFeasibilityExport {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size in 1..2 && (args.size == 1 || args[1] == "--compact"))
        val compact = args.size == 2
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        require(root.fileName.toString().startsWith("synthetic-owner-uat-context-"))
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
        require(Files.getOwner(root).name == System.getProperty("user.name"))
        require(Files.getPosixFilePermissions(root) == PosixFilePermissions.fromString("rwx------"))
        Files.list(root).use { require(it.findAny().isEmpty) { "Use a new empty private export directory" } }
        val data = Files.createDirectory(root.resolve("data"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        try {
            val input = descriptiveFeasibilityInput(data, compact)
            Files.writeString(root.resolve("input.json"), input.toString() + "\n", CREATE_NEW)
            Files.setPosixFilePermissions(root.resolve("input.json"), PosixFilePermissions.fromString("rw-------"))
            println("Four invented descriptive prompts exported; compact=$compact; zero source/provider/runtime requests. No admission proof.")
        } finally {
            Files.walk(data).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

}

class OwnerUatContextFeasibilityExportTest {
    @Test fun `compact export is opt in and contains the versioned reversible input only`() = runBlocking {
        val root = Files.createTempDirectory("synthetic-owner-uat-context-test-")
        try {
            val input = descriptiveFeasibilityInput(root, compactModelInput = true)
            for (row in input.getValue("cases").jsonArray) {
                val prompt = row.jsonObject.getValue("exactPrompt").jsonObject
                val wire = Json.parseToJsonElement(prompt.getValue("userPacketJson").jsonPrimitive.content).jsonObject
                assertEquals(JsonPrimitive(OwnerUatDescriptiveWireV1.VERSION), wire["wire"])
                assertTrue(prompt.getValue("systemInstructions").jsonPrimitive.content.contains("lossless encoding"))
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun `export uses unchanged compiled instructions and exact normal packets without sends`() = runBlocking {
        val root = Files.createTempDirectory("synthetic-owner-uat-context-test-")
        try {
            val input = descriptiveFeasibilityInput(root)
            assertEquals(JsonPrimitive(true), input["syntheticOnly"])
            val cases = input.getValue("cases").jsonArray.map { it.jsonObject }
            assertEquals(listOf("activities-review", "activities-compare", "period-review", "period-compare"),
                cases.map { it.getValue("caseId").jsonPrimitive.content })
            val instructionHashes = cases.map { row ->
                val prompt = row.getValue("exactPrompt").jsonObject
                val packet = ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptivePacketV1>(
                    prompt.getValue("userPacketJson").jsonPrimitive.content)
                val exact = ScopedDescriptiveContractV1.prompt(packet)
                assertEquals(exact.systemInstructions, prompt.getValue("systemInstructions").jsonPrimitive.content)
                assertEquals(exact.userPacketJson, prompt.getValue("userPacketJson").jsonPrimitive.content)
                assertEquals("off", packet.evidence.request.selection.wellnessPolicy)
                assertTrue(packet.references.isNotEmpty())
                AnalysisClaims.hash(exact.systemInstructions)
            }.sorted()
            assertEquals(ScopedDescriptiveContractV1.promptVariantSha256s(), instructionHashes)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
