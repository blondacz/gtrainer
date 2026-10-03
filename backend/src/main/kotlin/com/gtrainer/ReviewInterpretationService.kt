package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class ReviewExecutionPolicy(val callTimeoutMillis: Long = 120_000, val jobTimeoutMillis: Long = 130_000,
                                 val allowCorrectiveAttempt: Boolean = false) {
    init { require(callTimeoutMillis in 1..240_000 && jobTimeoutMillis in callTimeoutMillis..600_000) { "Invalid review budgets" } }
}

interface ReviewFocusModel {
    val option: LocalModelOption
    suspend fun generate(packet: ReviewFocusPacket, feedback: String?): String
}

@Serializable
data class ReviewSelectionRequest(val profile: String, val modelId: String?, val enabled: Boolean,
                                  val allowCorrectiveAttempt: Boolean = false)
@Serializable
data class ReviewModelStatus(val profile: String, val selectedModelId: String?, val selectionVersion: Long, val enabled: Boolean,
                             val correctionEnabled: Boolean, val runtimeGuardConfigured: Boolean, val models: List<LocalModelOption>,
                             val reason: String, val hostedEnabled: Boolean, val executionPolicy: ReviewExecutionPolicy = ReviewExecutionPolicy())
@Serializable
data class ReviewInterpretationRequest(val profile: String, val oldest: String, val newest: String, val sport: String? = null,
                                      val focus: String, val evidenceReportSha256: String, val modelId: String, val selectionVersion: Long) {
    override fun toString() = "ReviewInterpretationRequest([REDACTED])"
}
@Serializable
data class ReviewAttempt(val number: Int, val status: String, val reason: String?)
@Serializable
data class ReviewInterpretationResponse(val profile: String, val status: String, val reason: String, val model: LocalModelOption?,
                                      val selectionVersion: Long, val evidenceReportSha256: String, val packetSha256: String,
                                      val interpretations: List<ReviewInterpretation>, val attempts: List<ReviewAttempt>) {
    override fun toString() = "ReviewInterpretationResponse([REDACTED])"
}

/** Explicit separate profile; memory-only/off at restart. Missing qualification guard fails closed.
 * The guard must observe the model's restart/OOM/resource/live-health state, not just HTTP availability.
 */
class ReviewInterpretationService(models: List<ReviewFocusModel> = emptyList(), private val policy: ReviewExecutionPolicy = ReviewExecutionPolicy(),
                                 private val runtimeGuard: (suspend () -> Boolean)? = null, private val operation: Mutex = Mutex(),
                                 private val configurationError: Boolean = false, private val closeModels: () -> Unit = {},
                                 private val nanoTime: () -> Long = System::nanoTime) : AutoCloseable {
    private data class Selection(val modelId: String?, val version: Long, val enabled: Boolean, val correction: Boolean)
    private val models = models.associateBy { it.option.id }
    private val selection = AtomicReference(Selection(null, 0, false, false))
    private val selectionLock = Any()
    private val running = AtomicReference<Deferred<ReviewInterpretationResponse>?>(null)
    init { require(this.models.size == models.size && models.size <= 4) { "Invalid review model catalogue" } }

    fun status(): ReviewModelStatus {
        val current = selection.get()
        val reason = when {
            configurationError -> "review_configuration_invalid"
            models.isEmpty() -> "review_not_configured"
            !current.enabled -> "interpretation_disabled"
            current.modelId == null -> "model_not_selected"
            runtimeGuard == null -> "runtime_guard_unavailable"
            else -> "experimental_review_selected"
        }
        return ReviewModelStatus(ReviewFocusProtocol.PROFILE, current.modelId, current.version, current.enabled, current.correction,
            runtimeGuard != null, models.values.map { it.option }, reason, false, policy)
    }

    fun select(request: ReviewSelectionRequest): ReviewModelStatus = synchronized(selectionLock) {
        require(request.profile == ReviewFocusProtocol.PROFILE)
        require(request.modelId == null || request.modelId in models)
        require(!request.enabled || request.modelId != null)
        require(!request.allowCorrectiveAttempt || policy.allowCorrectiveAttempt)
        val before = selection.getAndUpdate { prior ->
            if (prior.modelId == request.modelId && prior.enabled == request.enabled && prior.correction == request.allowCorrectiveAttempt) prior
            else Selection(request.modelId, prior.version + 1, request.enabled, request.allowCorrectiveAttempt)
        }
        if (selection.get() != before) running.get()?.cancel()
        status()
    }

    internal fun <T> publishIfSelected(modelId: String, version: Long, publish: () -> T): T? = synchronized(selectionLock) {
        val current = selection.get()
        if (!current.enabled || current.modelId != modelId || current.version != version) null else publish()
    }

    internal fun permitsScheduleBudget(budget: ReviewExecutionPolicy): Boolean =
        budget.callTimeoutMillis <= policy.callTimeoutMillis && budget.jobTimeoutMillis <= policy.jobTimeoutMillis &&
            (!budget.allowCorrectiveAttempt || policy.allowCorrectiveAttempt)

    suspend fun interpret(request: ReviewInterpretationRequest, report: TrendReport, freshReport: suspend () -> TrendReport): ReviewInterpretationResponse =
        runInterpretation(request, report, freshReport, policy) { true }

    internal suspend fun interpretQueued(request: ReviewInterpretationRequest, report: TrendReport, budget: ReviewExecutionPolicy,
                                         stillCurrent: () -> Boolean): ReviewInterpretationResponse {
        require(permitsScheduleBudget(budget))
        // Data arrivals do not cancel/restart a fixed running snapshot. The queue labels its coverage afterward.
        return runInterpretation(request, report, { report }, budget, stillCurrent)
    }

    private suspend fun runInterpretation(request: ReviewInterpretationRequest, report: TrendReport, freshReport: suspend () -> TrendReport,
                                         policy: ReviewExecutionPolicy, stillCurrent: () -> Boolean): ReviewInterpretationResponse {
        require(request.profile == ReviewFocusProtocol.PROFILE && request.focus in FactualReviews.supportedFocuses)
        require(request.evidenceReportSha256.matches(Regex("[a-f0-9]{64}")))
        val start = nanoTime()
        val snapshot = ReviewFocusProtocol.prepare(Trends.analysisInput(report), request.focus)
        val selected = selection.get()
        val model = selected.modelId?.let { models[it] }
        val attempts = mutableListOf<ReviewAttempt>()
        fun response(reason: String, interpretations: List<ReviewInterpretation> = emptyList()) = ReviewInterpretationResponse(
            ReviewFocusProtocol.PROFILE, if (interpretations.isEmpty()) "unavailable" else "available", reason, model?.option,
            selected.version, snapshot.facts.evidenceReportSha256, snapshot.packetSha256, interpretations, attempts.toList())
        if (!selected.enabled) return response("interpretation_disabled")
        if (model == null) return response("model_not_selected")
        if (request.modelId != selected.modelId || request.selectionVersion != selected.version) return response("model_selection_changed")
        if (request.evidenceReportSha256 != snapshot.facts.evidenceReportSha256 || request.oldest != report.current.oldest ||
            request.newest != report.current.newest || request.sport != report.selectedSport) return response("evidence_changed")
        if (snapshot.facts.focuses.isEmpty()) return response("insufficient_input")
        if (ReviewFocusProtocol.json.encodeToString(snapshot.packet).toByteArray(Charsets.UTF_8).size > 6000) return response("input_budget_exceeded")
        if (runtimeGuard == null) return response("runtime_guard_unavailable")
        if (!operation.tryLock()) return response("analysis_busy")
        try {
            suspend fun check(): String? {
                currentCoroutineContext().ensureActive()
                if (!stillCurrent()) return "review_invalidated"
                if (selection.get() != selected) return "model_selection_changed"
                if (!ReviewFocusProtocol.unchanged(snapshot)) return "evidence_changed"
                val healthy = try { runtimeGuard.invoke() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
                if (!healthy) return "runtime_guard_failed"
                if (Trends.analysisInput(freshReport()).evidenceReportSha256 != snapshot.facts.evidenceReportSha256) return "evidence_changed"
                if (selection.get() != selected) return "model_selection_changed"
                return null
            }
            return try {
                supervisorScope {
                    val worker = async(start = CoroutineStart.LAZY) {
                        val remainingJob = policy.jobTimeoutMillis - (nanoTime() - start) / 1_000_000
                        if (remainingJob <= 0) return@async response("job_timeout")
                        withTimeout(remainingJob) job@ {
                            var feedback: String? = null
                            var lastReason = "unusable_model_output"
                            for (number in 1..2) {
                                val attemptStart = nanoTime()
                                fun failAttempt(reason: String, state: String = "failed"): ReviewInterpretationResponse {
                                    if (attempts.lastOrNull()?.number == number) attempts[attempts.lastIndex] = ReviewAttempt(number, state, reason)
                                    return response(reason)
                                }
                                val outcome = try {
                                    withTimeout(policy.callTimeoutMillis) attempt@ {
                                        check()?.let { return@attempt failAttempt(it, "invalidated") }
                                        if ((nanoTime() - start) / 1_000_000 >= policy.jobTimeoutMillis) return@attempt failAttempt("job_timeout")
                                        val copy = ReviewFocusProtocol.json.decodeFromString<ReviewFocusPacket>(ReviewFocusProtocol.json.encodeToString(snapshot.packet))
                                        // Settling/checks may consume correction headroom. Check again at the actual send boundary.
                                        val remaining = policy.jobTimeoutMillis - (nanoTime() - start) / 1_000_000
                                        if (number == 2 && remaining < policy.callTimeoutMillis) return@attempt response(lastReason)
                                        if ((nanoTime() - attemptStart) / 1_000_000 >= policy.callTimeoutMillis) return@attempt failAttempt("attempt_timeout")
                                        attempts += ReviewAttempt(number, "started", null)
                                        val raw = model.generate(copy, feedback)
                                        check()?.let { return@attempt failAttempt(it, "invalidated") }
                                        val validation = ReviewFocusProtocol.validate(raw, snapshot)
                                        check()?.let { return@attempt failAttempt(it, "invalidated") }
                                        // Also bound non-suspending validation/serialization before any acceptance is published.
                                        if ((nanoTime() - start) / 1_000_000 >= policy.jobTimeoutMillis) return@attempt failAttempt("job_timeout")
                                        if ((nanoTime() - attemptStart) / 1_000_000 >= policy.callTimeoutMillis) return@attempt failAttempt("attempt_timeout")
                                        attempts[attempts.lastIndex] = ReviewAttempt(number, if (validation.reason == null) "accepted" else "rejected", validation.reason)
                                        response(validation.reason ?: "validated_focus_selection", validation.interpretations)
                                    }
                                } catch (_: TimeoutCancellationException) {
                                    currentCoroutineContext().ensureActive() // Parent job/caller expiry is not a corrective semantic failure.
                                    failAttempt("attempt_timeout")
                                } catch (cancelled: CancellationException) { throw cancelled }
                                  catch (_: Exception) { failAttempt("model_unavailable") }
                                if (outcome.status == "available") return@job outcome
                                lastReason = outcome.reason
                                feedback = outcome.reason
                                val remaining = policy.jobTimeoutMillis - (nanoTime() - start) / 1_000_000
                                if (number != 1 || !selected.correction || !policy.allowCorrectiveAttempt || feedback !in ReviewFocusProtocol.correctionReasons || remaining < policy.callTimeoutMillis)
                                    return@job outcome
                            }
                            response("unusable_model_output")
                        }
                    }
                    running.set(worker)
                    try {
                        if (selection.get() != selected) worker.cancel()
                        worker.await()
                    } finally { running.compareAndSet(worker, null) }
                }
            } catch (_: TimeoutCancellationException) { response("job_timeout") }
              catch (cancelled: CancellationException) {
                  if (selection.get() != selected) response("model_selection_changed") else throw cancelled
              } catch (_: Exception) { response("model_unavailable") }
        } finally { operation.unlock() }
    }

    fun current(result: ReviewInterpretationResponse): Boolean = selection.get().let {
        it.enabled && it.modelId == result.model?.id && it.version == result.selectionVersion
    }
    override fun close() { running.get()?.cancel(); closeModels() }

    companion object {
        fun fromEnvironment(operation: Mutex): ReviewInterpretationService = OllamaReviewFocusModel.serviceFromEnvironment(operation)
    }
}
