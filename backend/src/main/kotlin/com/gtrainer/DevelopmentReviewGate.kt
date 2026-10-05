package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/** Synthetic-only permission is not qualification and never relaxes the production service gate. */
internal class DevelopmentReviewGate(private val store: DevelopmentReviewStore,
    private val claim: DevelopmentOwnedClaim, private val token: OwnedOllamaRuntimeToken,
    private val observer: DevelopmentReviewHealthObserver,
    private val stillOwned: suspend () -> Boolean = { false }) : ConnectedReviewExecutionGate, OllamaRuntimeOwnershipCheck {
    override suspend fun refusalReason(snapshot: ConnectedReviewStatus, provider: ConnectedReviewProvider): String? = try {
        when {
            snapshot.snapshotId != claim.runId || snapshot.state !in setOf("IN_FLIGHT", "RUNNING") || provider.hosted -> "snapshot_changed"
            token.epoch != claim.owner.number || token.runtimeGeneration != claim.generation ||
                token.containerIdentity != claim.boundary.containerId || !store.ownedActive(claim) || !stillOwned() -> "runtime_health_unavailable"
            provider.providerId != token.desiredBinding.providerId || provider.modelId != token.desiredBinding.modelId -> "provider_changed"
            !syntheticMatches(snapshot) -> "packet_changed"
            else -> observer.refusal()?.reason
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { "safety_gate_unavailable" }

    private fun syntheticMatches(snapshot: ConnectedReviewStatus): Boolean {
        val task = store.task(claim.runId)
        if (task.model != token.desiredBinding) return false
        val fixture = DevelopmentReviewFixtures.load()[task.caseId] ?: return false
        val packet = store.connectedReviewPacket(claim.runId)
        val expected = Json.decodeFromString<InterpretationPacketV1>(fixture.packetJson)
        return packet == expected && InterpretationContractV1.promptSha256(packet) == snapshot.packetDigest
    }

    override suspend fun owns(token: OwnedOllamaRuntimeToken): Boolean = try {
        token == this.token && store.ownedActive(claim) && stillOwned() && observer.refusal() == null
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}
