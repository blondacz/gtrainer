package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

/** Separate synthetic app process. No source/model credentials, native clock or runtime commands. */
object OwnerUatSupervisionAppFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2 && args[1] in setOf("complete", "hold", "inspect"))
        val root = Path.of(args[0]); val mode = args[1]
        require(root.fileName.toString().startsWith("uat-"))
        require(Files.readString(root.resolve("synthetic-only")) == "synthetic-owner-supervision-v1\n")
        val clock = OwnerUatContinuousClock { StrictProfileDraftJson.decode<OwnerUatClockSample>(Files.readString(root.resolve("clock.json"))) }
        val client = OwnerUatSupervisorClient(clock, OwnerUatUnixSupervisorTransport(root.resolve("s")))
        val at = java.time.Instant.parse("2020-06-10T12:00:00Z")
        val control = object : PersonalRuntimeControl {
            override suspend fun observe(expected: PersonalResourceIdentity) = PersonalResourceObservation(expected, at, at.plusSeconds(1800), true)
            override suspend fun stop(claim: PersonalReviewClaim) = client.stop(claim)
        }
        lateinit var f: DescriptiveUiFixture
        f = DescriptiveUiFixture(root, runtimeControlOverride = control, supervision = client, providerOverride = { claim ->
            runBlocking {
                check(client.launch(claim) != null)
                val nonce=requireNotNull(client.status(claim)).launchNonce
                withTimeout(5_000) { while(!Files.exists(root.resolve("active-$nonce"))) delay(20) }
            }
            object : ConnectedReviewProvider {
                override val providerId = "synthetic"
                override val modelId = "fake-descriptive-model"
                override val hosted = false
                override val enforcedMaximumCostUsd: java.math.BigDecimal? = null
                override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
                    costLimitUsd: java.math.BigDecimal?): ConnectedReviewProviderReply {
                    check(client.phase(claim, "GENERATING"))
                    write(root.resolve("running.json"), buildJsonObject {
                        put("claimId", claim.claimId); put("executionId", claim.executionId)
                        put("claim", ScopedEvidencePreparation.json.encodeToJsonElement(claim))
                    })
                    if (mode == "hold") while (!Files.exists(root.resolve("finish"))) delay(20)
                    check(client.phase(claim, "VALIDATING"))
                    val packet = ScopedEvidencePreparation.json.decodeFromString<ScopedDescriptivePacketV1>(prompt.userPacketJson)
                    val source = packet.references.first { it.kind == "session" }
                    return ConnectedReviewProviderReply(ScopedDescriptiveContractV1.encodeDraft(ScopedDescriptiveDraftV1(
                        ScopedDescriptiveContractV1.PROFILE, "activities", "review", listOf(ScopedDescriptiveItem(
                            "observation", "Synthetic activity is recorded.", listOf(source), "high")), emptyList())), null)
                }
            }
        })
        try {
            if (mode == "inspect") {
                val prior = Json.parseToJsonElement(Files.readString(root.resolve("running.json"))).jsonObject
                val status = f.jobs.status(prior.getValue("executionId").jsonPrimitive.content)
                write(root.resolve("inspection.json"), ScopedEvidencePreparation.json.encodeToJsonElement(status))
                return@runBlocking
            }
            check(f.jobs.rearm(f.policyDigest()))
            val preview = f.prepare("activities")
            val input = OwnerUatJobRequest(f.policyDigest(), preview.binding.consentSha256)
            val submitted = f.jobs.request(preview.binding.executionId, input)
            check(submitted.accepted)
            // A duplicate normal request must only return the durable claim, never a second launch.
            check(f.jobs.request(preview.binding.executionId, input).accepted)
            withTimeout(20_000) { f.scope.coroutineContext[Job]!!.children.toList().joinAll() }
            val result = f.executions.inspect(preview.binding.executionId)
            write(root.resolve("completion.json"), buildJsonObject { put("state", result.state); put("executionId", result.binding.executionId) })
        } finally { f.close() }
    }

    private fun write(path: Path, data: JsonElement) {
        require(!Files.exists(path))
        val temporary=path.resolveSibling(path.fileName.toString()+".pending")
        java.nio.channels.FileChannel.open(temporary, setOf(CREATE_NEW, WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap((data.toString() + "\n").toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        Files.move(temporary,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
