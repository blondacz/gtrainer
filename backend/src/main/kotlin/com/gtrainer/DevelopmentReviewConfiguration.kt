package com.gtrainer

import java.nio.file.Path
import kotlinx.serialization.Serializable

/** Explicit operator-selected local development model. No environment lookup or model default exists. */
data class DevelopmentReviewConfiguration(
    val bindAddress: String,
    val databasePath: Path,
    val providerId: String,
    val modelId: String,
    val label: String,
    val tag: String,
    val manifestDigest: String,
    val ollamaVersion: String,
    val contextWindow: Int,
    val tokenLimit: Int,
    val threads: Int,
    val seed: Int,
    val temperature: Double,
    val executionPolicy: DevelopmentReviewPolicyBinding,
) {
    init {
        require(databasePath.isAbsolute && databasePath.parent != null) { "Invalid development review configuration" }
        binding() // Validates all identity/settings fields without reading an environment or database.
    }
    override fun toString() = "DevelopmentReviewConfiguration([REDACTED])"
}

@Serializable
data class DevelopmentReviewModelBinding(
    val bindAddress: String, val providerId: String, val modelId: String, val label: String, val tag: String,
    val manifestDigest: String, val ollamaVersion: String, val contextWindow: Int, val tokenLimit: Int,
    val threads: Int, val seed: Int, val temperature: Double,
) {
    init {
        require(bindAddress == "127.0.0.1") { "Invalid development review configuration" }
        require(providerId.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid development review configuration" }
        require(modelId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Invalid development review configuration" }
        require(label.isNotBlank() && label.length <= 200) { "Invalid development review configuration" }
        require(tag.matches(Regex("[A-Za-z0-9_./:-]{1,256}"))) { "Invalid development review configuration" }
        require(manifestDigest.matches(Regex("[0-9a-f]{64}"))) { "Invalid development review configuration" }
        require(ollamaVersion.matches(Regex("[A-Za-z0-9_.+-]{1,64}"))) { "Invalid development review configuration" }
        require(contextWindow in 1..131072 && tokenLimit in 1..32768 && threads in 1..256) { "Invalid development review configuration" }
        require(temperature.isFinite() && temperature in 0.0..2.0) { "Invalid development review configuration" }
    }
    override fun toString() = "DevelopmentReviewModelBinding([REDACTED])"
}

@Serializable
data class DevelopmentReviewPolicyBinding(val attemptTimeoutMillis: Long, val totalTimeoutMillis: Long,
    val profile: String = ConnectedReviewExecutionLimits.DEVELOPMENT_PROFILE) {
    init {
        require(profile == ConnectedReviewExecutionLimits.DEVELOPMENT_PROFILE) { "Invalid development review policy" }
        require(attemptTimeoutMillis in 1..ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL.attemptCapMillis) { "Invalid development review policy" }
        require(totalTimeoutMillis in attemptTimeoutMillis..ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL.totalCapMillis) { "Invalid development review policy" }
    }

    fun forRemainingTtl(remainingMillis: Long) =
        ConnectedReviewExecutionLimits.developmentLocal(remainingMillis, attemptTimeoutMillis, totalTimeoutMillis)
}

fun DevelopmentReviewConfiguration.binding() = DevelopmentReviewModelBinding(bindAddress, providerId, modelId, label,
    tag, manifestDigest, ollamaVersion, contextWindow, tokenLimit, threads, seed, temperature)
