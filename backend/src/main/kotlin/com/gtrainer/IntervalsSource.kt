package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class SourceCredential(private val key: String) {
    init {
        require(key.length in 1..256 && key.all { it.code in 33..126 && it != ':' }) {
            "Invalid source credential configuration"
        }
    }
    fun authorization(): String = "Basic " + Base64.getEncoder().encodeToString("API_KEY:$key".toByteArray())
    override fun toString(): String = "SourceCredential([REDACTED])"
}

/** Reads the projected Secret again on each request so source rotation is usable. */
class KeyFileProvider(private val path: Path) : () -> SourceCredential? {
    override fun invoke(): SourceCredential? = try {
        if (Files.size(path) !in 1..256) null else SourceCredential(Files.readString(path).trim())
    } catch (_: Exception) {
        null
    }
}

class IntervalsSource(
    client: HttpClient,
    private val credentials: () -> SourceCredential?,
    private val byteLimit: Int = 8 * 1024 * 1024,
) : HistorySource, AutoCloseable {
    // Enforce redirect policy even on injected clients; credentials must never
    // follow Location to another host or an unexpected endpoint.
    private val originalClient = client
    private val client = client.config {
        followRedirects = false
        expectSuccess = false
    }
    override val sourceId = "intervals.icu"

    init { require(byteLimit in 1..8 * 1024 * 1024) }

    override suspend fun activities(range: ReadRange) = read("activities", range, IntervalsNormalizer::activity) {
        java.time.LocalDate.parse(it.startLocal.take(10))
    }
    override suspend fun wellness(range: ReadRange) = read("wellness", range, IntervalsNormalizer::wellness) {
        java.time.LocalDate.parse(it.date)
    }

    private suspend fun <T> read(category: String, range: ReadRange, normalize: (JsonObject) -> Normalized<T>?,
                                observedDate: (T) -> java.time.LocalDate): ReadResult<T> {
        val credential = try {
            credentials()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return ReadResult(ReadStatus.NOT_CONFIGURED)
        return try {
            client.prepareGet("https://intervals.icu/api/v1/athlete/0/$category") {
                url.parameters.append("oldest", range.oldest.toString())
                url.parameters.append("newest", range.newest.toString())
                header(HttpHeaders.Authorization, credential.authorization())
                header(HttpHeaders.Accept, "application/json")
            }.execute { response ->
                val failure = when (response.status.value) {
                    200 -> null
                    401 -> ReadStatus.KEY_REJECTED
                    403 -> ReadStatus.ACCESS_DENIED // Filtering is not proof of an invalid key.
                    429 -> ReadStatus.RATE_LIMITED
                    in 300..399 -> ReadStatus.REDIRECT_BLOCKED
                    else -> ReadStatus.UPSTREAM_ERROR
                }
                if (failure != null) return@execute ReadResult(failure)
                val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && length > byteLimit) return@execute ReadResult(ReadStatus.RESPONSE_TOO_LARGE)
                val bytes = response.bodyAsChannel().readBuffer(byteLimit.toLong() + 1).readByteArray()
                if (bytes.size > byteLimit) return@execute ReadResult(ReadStatus.RESPONSE_TOO_LARGE)
                val records = try {
                    Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonArray
                } catch (_: Exception) { null }
                if (records == null || records.size > 50_000 || records.any { it !is JsonObject }) {
                    return@execute ReadResult(ReadStatus.INVALID_RESPONSE)
                }
                val normalized = records.map { row ->
                    normalize(row as JsonObject)?.takeIf { observedDate(it.record) in range.oldest..range.newest }
                }
                ReadResult(ReadStatus.SUCCESS, normalized.mapNotNull { it?.record }, normalized.count { it == null },
                    normalized.count { it?.incomplete == true })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReadResult(ReadStatus.TRANSPORT_ERROR) // Never propagate/log URL, key, body, or HTTP exception.
        }
    }

    override fun close() {
        client.close()
        originalClient.close()
    }

    companion object {
        fun production(keyFile: Path): IntervalsSource = IntervalsSource(HttpClient(CIO) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 45_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
            // Deliberately no Logging, cookies, or hosted-model client.
        }, KeyFileProvider(keyFile))
    }
}
