package com.gtrainer

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DevelopmentReviewGateTest {
    private val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))
    private val config = DevelopmentReviewConfiguration("127.0.0.1", Path.of("/development/isolated.sqlite"),
        "ollama", "model-a", "Synthetic model", "model-a:synthetic", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1,
        DevelopmentReviewPolicyBinding(900_000, 1_840_000))

    @Test fun `gate requires synthetic immutable input model health and actual ownership without qualification`() = runBlocking {
        val path = Files.createTempDirectory("development-gate").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                val run = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
                val claim = assertNotNull(owner.claim(run.id, boundary))
                assertTrue(owner.started(claim))
                val token = OwnedOllamaRuntimeToken(claim.owner.number, claim.generation, boundary.containerId, config.binding())
                var owned = true
                val source = DevelopmentRuntimeHealthSource {
                    DevelopmentHealthReadings(DevelopmentHealthMeasurement(DevelopmentRuntimeHealth(token.epoch,
                        token.runtimeGeneration, boundary, token.desiredBinding, true, 0), 0), DevelopmentHealthMeasurement(
                        DevelopmentHostResources(DevelopmentHealthPolicy.HOST_HEADROOM_BYTES, DevelopmentHealthPolicy.PI_APPLIANCE_BYTES), 0))
                }
                DevelopmentReviewHealthObserver(token, boundary, DevelopmentHealthPolicy(DevelopmentHealthPolicy.PI_APPLIANCE_BYTES, false),
                    this, source, { 0L }).use { observer ->
                    val provider = fakeProvider()
                    val gate = DevelopmentReviewGate(store, claim, token, observer) { owned }
                    val status = store.connectedReviewStatus(run.id)
                    assertEquals("runtime_health_unavailable", gate.refusalReason(status, provider))
                    assertNull(observer.opportunity().refusal)
                    assertNull(gate.refusalReason(status, provider))
                    assertTrue(gate.owns(token))
                    assertFalse(gate.owns(token.copy(runtimeGeneration = 17)))
                    assertEquals("packet_changed", gate.refusalReason(status.copy(packetDigest = "b".repeat(64)), provider))
                    assertEquals("snapshot_changed", gate.refusalReason(status.copy(state = "CANCELLED"), provider))
                    assertEquals("provider_changed", gate.refusalReason(status, fakeProvider("model-b")))
                    assertEquals("snapshot_changed", gate.refusalReason(status, fakeProvider(hosted = true)))
                    owned = false
                    assertFalse(gate.owns(token))
                    assertEquals("runtime_health_unavailable", gate.refusalReason(status, provider))
                    assertEquals("runtime_health_unavailable", DevelopmentReviewGate(store, claim, token, observer).refusalReason(status, provider))
                }
            }
        }
    }

    @Test fun `composed reads timestamp each command before IO and establish Pi baseline only when requested`() = runBlocking {
        var now = 0L
        val token = OwnedOllamaRuntimeToken(1, 1, boundary.containerId, config.binding())
        val actions = mutableListOf<String>()
        val app = DevelopmentProtectedAppState(listOf(DevelopmentAppContainer("synthetic-pod", "app", "synthetic-image", true, 3)))
        val source = DevelopmentReadOnlyHealthSource(
            runtime = DevelopmentOwnedRuntimeHealthRead {
                actions += "runtime"; now += 1_000_000_000
                DevelopmentRuntimeHealth(1, 1, boundary, config.binding(), true, 0)
            },
            resources = DevelopmentHostResourceRead {
                actions += "resources"; now += 1_000_000_000
                DevelopmentHostResources(DevelopmentHealthPolicy.HOST_HEADROOM_BYTES, DevelopmentHealthPolicy.PI_APPLIANCE_BYTES)
            },
            application = DevelopmentProtectedApplicationRead { actions += "application"; now += 1_000_000_000; app },
            nanos = { now }, baselinePause = { actions += "baseline_wait"; now += 10_000_000_000 })
        val first = assertNotNull(source.collect(DevelopmentHealthRequest(token, boundary, true)))
        assertEquals(listOf("application", "baseline_wait", "application", "runtime", "resources"), actions)
        assertEquals(0, first.initialBaseline?.first?.startedAtNanos)
        assertEquals(11_000_000_000, first.initialBaseline?.second?.startedAtNanos)
        assertEquals(12_000_000_000, first.runtime.startedAtNanos)
        assertEquals(13_000_000_000, first.resources.startedAtNanos)
        actions.clear()
        val second = assertNotNull(source.collect(DevelopmentHealthRequest(token, boundary, false)))
        assertEquals(listOf("application", "runtime", "resources"), actions)
        assertNull(second.initialBaseline)
    }

    private fun fakeProvider(model: String = "model-a", hosted: Boolean = false) = object : ConnectedReviewProvider {
        override val providerId = "ollama"
        override val modelId = model
        override val hosted = hosted
        override val enforcedMaximumCostUsd: java.math.BigDecimal? = null
        override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
            costLimitUsd: java.math.BigDecimal?): ConnectedReviewProviderReply = error("Gate test must not generate")
    }
}
