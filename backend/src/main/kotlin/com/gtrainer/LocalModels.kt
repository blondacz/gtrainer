package com.gtrainer

import kotlinx.serialization.Serializable

@Serializable
data class LocalModelOption(val id: String, val label: String, val tag: String, val digest: String,
                            val experimental: Boolean = true) {
    init {
        require(experimental) { "Only experimental local models are supported" }
        require(id.matches(Regex("[a-z0-9-]{1,64}"))) { "Invalid local model configuration" }
        require(label.matches(Regex("[A-Za-z0-9 .()-]{1,80}"))) { "Invalid local model configuration" }
        require(tag.matches(Regex("[a-z0-9][a-z0-9_.-]{0,63}:[a-z0-9][a-z0-9_.-]{0,63}")) &&
            !tag.contains("cloud")) { "Invalid local model configuration" }
        require(digest.matches(Regex("[a-f0-9]{64}"))) { "Invalid local model configuration" }
    }
}

@Serializable
data class LocalModelConfiguration(val endpoint: String, val models: List<LocalModelOption>) {
    init {
        require(endpoint in LOCAL_ENDPOINTS) { "Only explicitly supported local model endpoints are allowed" }
        require(models.size in 1..4 && models.map { it.id }.distinct().size == models.size &&
            models.map { it.tag }.distinct().size == models.size) { "Invalid local model configuration" }
    }
    companion object {
        const val RUNTIME_VERSION = "0.35.0"
        val LOCAL_ENDPOINTS = setOf("http://127.0.0.1:11434", "http://gtrainer-ollama.gtrainer-models.svc.cluster.local:11434")
    }
}

class ModelUnavailable : IllegalStateException("Local inference unavailable; diagnostics withheld")
