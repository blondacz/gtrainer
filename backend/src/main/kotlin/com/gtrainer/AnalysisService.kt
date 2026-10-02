package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class ModelSelectionRequest(val modelId: String?)
@Serializable
data class ModelStatus(val selectedModelId: String?, val selectionVersion: Long, val models: List<LocalModelOption>,
                       val reason: String, val hostedEnabled: Boolean = false)
@Serializable
data class AnalysisRequest(val oldest: String, val newest: String, val sport: String? = null,
                           val evidenceReportSha256: String, val modelId: String, val selectionVersion: Long) {
    override fun toString() = "AnalysisRequest([REDACTED])"
}
@Serializable
data class AnalysisResponse(val status: String, val reason: String, val model: LocalModelOption?,
                            val evidenceReportSha256: String, val observations: List<AnalysisObservation> = emptyList(),
                            val unavailable: List<UnavailableMetric> = emptyList(), val limitations: List<String> = emptyList(),
                            val sourceStatus: List<CategoryStatus> = emptyList()) {
    override fun toString() = "AnalysisResponse([REDACTED])"
}

/** No access to source credentials, storage writes, or hosted providers. Selection is memory-only/off at restart. */
class AnalysisService(models: List<AnalysisModel> = emptyList(), private val configurationError: Boolean = false,
                      private val closeModels: () -> Unit = {}) : AutoCloseable {
    private data class Selection(val id: String?, val version: Long)
    private val models = models.associateBy { it.option.id }
    private val selection = AtomicReference(Selection(null, 0))
    private val operation = Mutex()
    private val running = AtomicReference<Deferred<String>?>(null)
    init { require(this.models.size == models.size && models.size <= 4) { "Invalid local model catalogue" } }

    fun status(): ModelStatus {
        val current = selection.get()
        return ModelStatus(current.id, current.version, models.values.map { it.option }, when {
            configurationError -> "local_configuration_invalid"
            models.isEmpty() -> "local_not_configured"
            current.id == null -> "model_not_selected"
            else -> "experimental_local_selected"
        })
    }
    fun select(modelId: String?): ModelStatus {
        require(modelId == null || modelId in models) { "Unknown local model selection" }
        val before = selection.getAndUpdate { if (it.id == modelId) it else Selection(modelId, it.version + 1) }
        if (before.id != modelId) running.get()?.cancel()
        return status()
    }

    suspend fun analyze(request: AnalysisRequest, report: TrendReport, freshReport: suspend () -> TrendReport): AnalysisResponse {
        require(request.evidenceReportSha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid evidence binding" }
        val input = Trends.analysisInput(report)
        val selected = selection.get()
        val model = selected.id?.let { models[it] }
        val limitations = input.limitations.filterNot { it.startsWith("No model is called by this endpoint.") } +
            listOf("No hosted transfer or automatic fallback. Local inference runs only after explicit selection and a generation request.") +
            input.facts.flatMap { it.flags }.distinct() +
            input.facts.filter { it.value == null }.map { "Unavailable: ${it.period} ${it.sport ?: "wellness"} ${it.label}, ${it.oldest}–${it.newest}." }
        fun unavailable(reason: String) = AnalysisResponse("unavailable", reason, model?.option, input.evidenceReportSha256,
            unavailable = input.unavailable, limitations = limitations, sourceStatus = input.sourceStatus)
        if (model == null) return unavailable("model_not_selected")
        if (request.modelId != selected.id || request.selectionVersion != selected.version) return unavailable("model_selection_changed")
        if (request.evidenceReportSha256 != input.evidenceReportSha256) return unavailable("evidence_changed")
        val snapshot = AnalysisClaims.prepare(input)
        if (!AnalysisClaims.sufficient(snapshot)) return unavailable("insufficient_input")
        if (!operation.tryLock()) return unavailable("analysis_busy")
        try {
            if (selection.get() != selected) return unavailable("model_selection_changed")
            val raw = try {
                supervisorScope {
                    val worker = async(start = CoroutineStart.LAZY) { withTimeout(130_000) { model.generate(snapshot.packet) } }
                    running.set(worker)
                    try {
                        if (selection.get() != selected) worker.cancel()
                        worker.await()
                    } finally { running.compareAndSet(worker, null) }
                }
            } catch (_: TimeoutCancellationException) { return unavailable("model_timeout") }
              catch (cancelled: CancellationException) {
                  if (selection.get() != selected) return unavailable("model_selection_changed")
                  throw cancelled
              }
              catch (_: Exception) { return unavailable("model_unavailable") }
            if (selection.get() != selected) return unavailable("model_selection_changed")
            if (Trends.analysisInput(freshReport()).evidenceReportSha256 != input.evidenceReportSha256) return unavailable("evidence_changed")
            val observations = AnalysisClaims.validateAndRender(raw, snapshot) ?: return unavailable("unusable_model_output")
            if (selection.get() != selected) return unavailable("model_selection_changed")
            val flags = snapshot.packet.evidence.flatMap { e -> snapshot.supports.getValue(e.id).let { (a, b) -> a.flags + b.flags } }.distinct()
            return AnalysisResponse("available", "validated_typed_observations", model.option, input.evidenceReportSha256,
                observations, input.unavailable, limitations +
                    listOf("Experimental model-selected comparisons; personal text, values, dates and disclosures are rendered by code, not the model.",
                        "At most two sports' moving-time comparisons and sleep and HRV are supplied to this bounded prototype; all other comparisons remain in factual charts.",
                        "Not a complete assessment, diagnosis, recovery conclusion, intensity classification, or training prescription.") + flags,
                input.sourceStatus)
        } finally { operation.unlock() }
    }

    override fun close() { running.get()?.cancel(); closeModels() }

    internal fun inferenceGate(): Mutex = operation // New profile shares the slot, never the prototype contract or selection.

    companion object {
        fun fromEnvironment(): AnalysisService = try {
            val config = OllamaAnalysisModel.configurationFromEnvironment()
            if (config == null) AnalysisService() else {
                val client = OllamaAnalysisModel.client()
                val providers = config.models.map { OllamaAnalysisModel(client, config.endpoint, it) }
                AnalysisService(providers, closeModels = { providers.forEach { it.close() }; client.close() })
            }
        } catch (_: Exception) { AnalysisService(configurationError = true) } // Invalid configuration never opens hosted egress or hides charts.
    }
}
