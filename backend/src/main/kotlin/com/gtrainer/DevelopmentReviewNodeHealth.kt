package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Two exact operations, never arbitrary selectors, commands or file names. */
internal class DevelopmentNodeHealthRequest private constructor(val nodeName: String, val boundary: DevelopmentNodeBoundary?) {
    val arguments: List<String> get() = listOf("sudo", "-n", "/usr/local/libexec/gtrainer-development-node-observer") +
        if (boundary == null) listOf("application") else listOf("observe", boundary.containerId, boundary.bootId)
    companion object {
        fun resources(boundary: DevelopmentNodeBoundary) = DevelopmentNodeHealthRequest(boundary.nodeName, boundary)
        fun application(nodeName: String): DevelopmentNodeHealthRequest {
            require(nodeName.matches(Regex("[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?")))
            return DevelopmentNodeHealthRequest(nodeName, null)
        }
    }
}

@Serializable
private data class DevelopmentNodeResourcesEnvelope(val profile: String, val containerId: String, val bootId: String,
    val availableBytes: Long, val applianceMemoryLimitBytes: Long, val containerAttempt: Int)
@Serializable
private data class DevelopmentNodeAppEnvelope(val profile: String, val containers: List<DevelopmentNodeAppContainer>)
@Serializable
private data class DevelopmentNodeAppContainer(val podId: String, val containerName: String, val imageIdentity: String,
    val ready: Boolean, val restartCount: Int)

internal class DevelopmentNodeHealthReader(private val config: DevelopmentNodeSshConfiguration,
    private val runner: DevelopmentNodeCommandRunner = BoundedDevelopmentNodeCommandRunner()) : DevelopmentHostResourceRead, DevelopmentProtectedApplicationRead {
    override suspend fun read(boundary: DevelopmentNodeBoundary): DevelopmentHostResources? {
        val data = bytes(DevelopmentNodeHealthRequest.resources(boundary)) ?: return null
        val sample = Json.decodeFromJsonElement<DevelopmentNodeResourcesEnvelope>(data)
        if (sample.profile != PROFILE || sample.containerId != boundary.containerId || sample.bootId != boundary.bootId ||
            sample.containerAttempt != 0 || sample.availableBytes < 0 || sample.applianceMemoryLimitBytes <= 0) return null
        return DevelopmentHostResources(sample.availableBytes, sample.applianceMemoryLimitBytes)
    }

    override suspend fun read(): DevelopmentProtectedAppState? {
        val data = bytes(DevelopmentNodeHealthRequest.application(config.nodeName)) ?: return null
        val sample = Json.decodeFromJsonElement<DevelopmentNodeAppEnvelope>(data)
        if (sample.profile != PROFILE || sample.containers.size !in 1..16 || sample.containers.any {
            it.podId.length !in 1..128 || it.containerName.length !in 1..128 || it.imageIdentity.length !in 1..256 ||
                listOf(it.podId, it.containerName, it.imageIdentity).any { identity -> identity.any { c -> c.code < 32 } }
        }) return null
        return DevelopmentProtectedAppState(sample.containers.map {
            DevelopmentAppContainer(it.podId, it.containerName, it.imageIdentity, it.ready, it.restartCount)
        })
    }

    private suspend fun bytes(request: DevelopmentNodeHealthRequest): kotlinx.serialization.json.JsonElement? {
        config.verifyFiles()
        val data = runner.read(config.command(request), 15_000, 4096) ?: return null
        if (data.size > 4096) return null
        return StrictModelJson.parse(data.decodeToString(throwOnInvalidSequence = true))
    }

    companion object { const val PROFILE = "gtrainer-development-node-health-v1" }
}
