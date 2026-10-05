package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

/** No inference. Read only the owned supervisor status/version/catalogue, with fixed loopback paths. */
internal class DevelopmentOllamaRuntimeHealthRead(client: HttpClient,
    private val supervisedStatus: suspend (DevelopmentHealthRequest) -> DevelopmentRuntimeHealth? = { null },
    private val ownsProcessBoundary: suspend (DevelopmentHealthRequest) -> Boolean = { false }) : DevelopmentOwnedRuntimeHealthRead, AutoCloseable {
    private val original = client
    private val http = client.config {
        followRedirects = false; expectSuccess = false
        install(HttpRequestRetry) { maxRetries = 0 }
        install(HttpTimeout) { requestTimeoutMillis = 15_000; socketTimeoutMillis = 15_000; connectTimeoutMillis = 5_000 }
    }

    override suspend fun read(request: DevelopmentHealthRequest): DevelopmentRuntimeHealth? {
        if (!ownsProcessBoundary(request)) return null
        val status = supervisedStatus(request) ?: return null
        if (!status.ready) return status
        val binding = request.token.desiredBinding
        val version = get("/api/version", 4096)
        val tags = get("/api/tags", 64 * 1024)["models"] as? JsonArray ?: return null
        val loaded = get("/api/ps", 64 * 1024)["models"] as? JsonArray ?: return null
        if (!ownsProcessBoundary(request)) return null
        val finalStatus = supervisedStatus(request) ?: return null
        val matching = tags.filterIsInstance<JsonObject>().filter { it["name"] == JsonPrimitive(binding.tag) }
        val matches = version["version"] == JsonPrimitive(binding.ollamaVersion) && matching.size == 1 &&
            matching.single()["digest"] == JsonPrimitive(binding.manifestDigest) && status.binding == binding && finalStatus.binding == binding &&
            loaded.size <= 1 && loaded.all { it is JsonObject && it["name"] == JsonPrimitive(binding.tag) && it["digest"] == JsonPrimitive(binding.manifestDigest) }
        // Residency can refuse an unrelated model, but is never idle/termination evidence.
        return finalStatus.copy(ready = finalStatus.ready && matches)
    }

    private suspend fun get(path: String, maximumBytes: Int): JsonObject = http.prepareGet("${OllamaConnectedReviewProvider.ENDPOINT}$path") {
        header(HttpHeaders.Accept, ContentType.Application.Json)
    }.execute { response ->
        check(response.status.value in 200..299)
        val bytes = response.bodyAsChannel().readBuffer(maximumBytes.toLong() + 1).readByteArray()
        check(bytes.size <= maximumBytes)
        StrictModelJson.parse(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
    }

    override fun close() { http.close(); original.close() }
}
