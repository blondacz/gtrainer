package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

internal data class DevelopmentNodeBoundary(val nodeName: String, val bootId: String, val containerId: String) {
    init {
        require(nodeName.matches(Regex("[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?")))
        require(UUID.fromString(bootId).toString() == bootId)
        require(containerId.matches(Regex("[0-9a-f]{64}")))
    }
}

/** No arbitrary command/node/path inputs. Transport must be operator-configured and pinned/authenticated. */
internal data class DevelopmentNodeProofRequest(val boundary: DevelopmentNodeBoundary) {
    val arguments: List<String> get() = listOf("sudo", "-n", "/usr/local/libexec/gtrainer-development-node-verifier",
        "verify", boundary.containerId, boundary.bootId)
    val timeoutMillis: Long get() = 15_000
    val maximumBytes: Int get() = 4096
}

internal fun interface DevelopmentNodeProofTransport {
    suspend fun read(request: DevelopmentNodeProofRequest): ByteArray?
}

internal enum class DevelopmentNodeProofResult { CONTAINER_EXITED, HOST_REBOOTED, UNCONFIRMED }

@Serializable
private data class DevelopmentNodeProofEnvelope(val profile: String, val containerId: String? = null,
    val observedBootId: String? = null, val result: String)

/** Proof has a private constructor; API requests cannot construct it from an "isStopped" flag. */
internal class DevelopmentNodeStopProof private constructor(val expected: DevelopmentNodeBoundary,
    val observedBootId: String, val result: DevelopmentNodeProofResult) {
    companion object {
        fun decode(expected: DevelopmentNodeBoundary, bytes: ByteArray): DevelopmentNodeStopProof? {
            if (bytes.size > 4096) return null
            val envelope = Json.decodeFromJsonElement<DevelopmentNodeProofEnvelope>(StrictModelJson.parse(bytes.decodeToString(throwOnInvalidSequence = true)))
            if (envelope.profile != "gtrainer-development-node-proof-v1" || envelope.containerId != expected.containerId) return null
            val boot = envelope.observedBootId ?: return null
            if (UUID.fromString(boot).toString() != boot) return null
            val result = DevelopmentNodeProofResult.valueOf(envelope.result)
            val valid = when (result) {
                DevelopmentNodeProofResult.CONTAINER_EXITED -> boot == expected.bootId
                DevelopmentNodeProofResult.HOST_REBOOTED -> boot != expected.bootId
                DevelopmentNodeProofResult.UNCONFIRMED -> false
            }
            return if (valid) DevelopmentNodeStopProof(expected, boot, result) else null
        }
    }
}

internal class DevelopmentNodeTerminationVerifier(private val pinnedNodeName: String? = null,
    private val transport: DevelopmentNodeProofTransport = DevelopmentNodeProofTransport { null }) {
    suspend fun verify(boundary: DevelopmentNodeBoundary): DevelopmentNodeStopProof? {
        if (pinnedNodeName == null || boundary.nodeName != pinnedNodeName) return null
        return try {
            withTimeoutOrNull(15_000) {
                transport.read(DevelopmentNodeProofRequest(boundary))?.let { DevelopmentNodeStopProof.decode(boundary, it) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}
