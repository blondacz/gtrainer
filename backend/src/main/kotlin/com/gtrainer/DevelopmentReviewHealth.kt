package com.gtrainer

import kotlinx.coroutines.*
import java.util.Collections

/** Timestamps use the worker's monotonic clock BEFORE each bounded read, never a remote wall clock. */
internal data class DevelopmentHealthMeasurement<T>(val value: T, val startedAtNanos: Long)
internal data class DevelopmentRuntimeHealth(val epoch: Long, val generation: Long, val boundary: DevelopmentNodeBoundary,
    val binding: DevelopmentReviewModelBinding, val ready: Boolean, val unexpectedRestarts: Int)
internal data class DevelopmentHostResources(val availableBytes: Long, val applianceMemoryLimitBytes: Long)
internal data class DevelopmentAppContainer(val podId: String, val containerName: String, val imageIdentity: String,
    val ready: Boolean, val restartCount: Int)

/** Snapshot copies its collection; callers cannot mutate the protected application's baseline. */
internal class DevelopmentProtectedAppState(containers: Collection<DevelopmentAppContainer>) {
    val containers: List<DevelopmentAppContainer> = Collections.unmodifiableList(containers.map { it.copy() }
        .sortedWith(compareBy({ it.podId }, { it.containerName })))
    fun ready(): Boolean = containers.isNotEmpty() && containers.size <= 16 && containers.all {
        it.podId.isNotBlank() && it.containerName.isNotBlank() && it.imageIdentity.isNotBlank() && it.ready && it.restartCount >= 0
    } && containers.map { it.podId to it.containerName }.distinct().size == containers.size
    fun matches(other: DevelopmentProtectedAppState): Boolean = containers == other.containers
    override fun toString() = "DevelopmentProtectedAppState([REDACTED])"
}

internal data class DevelopmentAppBaseline(val first: DevelopmentHealthMeasurement<DevelopmentProtectedAppState>,
    val second: DevelopmentHealthMeasurement<DevelopmentProtectedAppState>)
internal data class DevelopmentHealthReadings(val runtime: DevelopmentHealthMeasurement<DevelopmentRuntimeHealth>,
    val resources: DevelopmentHealthMeasurement<DevelopmentHostResources>,
    val application: DevelopmentHealthMeasurement<DevelopmentProtectedAppState>? = null,
    val initialBaseline: DevelopmentAppBaseline? = null)
internal data class DevelopmentHealthRequest(val token: OwnedOllamaRuntimeToken, val boundary: DevelopmentNodeBoundary,
    val establishAppBaseline: Boolean)

/** Collects read-only trusted measurements; null/unconfigured is always a refusal. No command text comes from a run. */
internal fun interface DevelopmentRuntimeHealthSource {
    suspend fun collect(request: DevelopmentHealthRequest): DevelopmentHealthReadings?
}

internal data class DevelopmentHealthPolicy(val applianceMemoryLimitBytes: Long, val protectApplication: Boolean) {
    init { require(applianceMemoryLimitBytes > 0) }
    companion object {
        const val HOST_HEADROOM_BYTES = 1024L * 1024 * 1024
        const val PI_APPLIANCE_BYTES = 5L * 1024 * 1024 * 1024
    }
}

internal enum class DevelopmentHealthRefusal(val reason: String) {
    UNAVAILABLE("runtime_health_unavailable"), RUNTIME("runtime_health_failed"), RESOURCES("resource_health_failed"),
}

internal data class DevelopmentHealthObservation(val oldestMeasurementNanos: Long, val completedAtNanos: Long,
    val refusal: DevelopmentHealthRefusal?)

/** One run's observer. Only explicit opportunities start observations; there is no idle timer. */
internal class DevelopmentReviewHealthObserver(private val token: OwnedOllamaRuntimeToken,
    private val boundary: DevelopmentNodeBoundary, private val policy: DevelopmentHealthPolicy,
    parentScope: CoroutineScope, private val source: DevelopmentRuntimeHealthSource = DevelopmentRuntimeHealthSource { null },
    private val nanos: () -> Long = System::nanoTime, private val observationTimeoutMillis: Long = 60_000) : AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private var inFlight: Deferred<DevelopmentHealthObservation>? = null
    private var lastStarted: Long? = null
    private val origin = nanos()
    private var latest: DevelopmentHealthObservation? = null
    private var baseline: DevelopmentProtectedAppState? = null
    private var closed = false

    init {
        require(observationTimeoutMillis in 1..60_000)
        require(token.epoch > 0 && token.runtimeGeneration > 0 && token.containerIdentity == boundary.containerId)
    }

    suspend fun opportunity(): DevelopmentHealthObservation = flight().await()

    @Synchronized private fun flight(): Deferred<DevelopmentHealthObservation> {
        check(!closed)
        inFlight?.takeUnless { it.isCompleted }?.let { return it }
        val now = nanos()
        val previous = latest
        if (previous != null && lastStarted?.let { now - it < OPPORTUNITY_NANOS } == true)
            return CompletableDeferred(previous)
        lastStarted = now
        return scope.async {
            val result = observe()
            synchronized(this@DevelopmentReviewHealthObserver) { latest = result }
            result
        }.also { inFlight = it }
    }

    private suspend fun observe(): DevelopmentHealthObservation {
        val readStarted = nanos()
        val needsBaseline = policy.protectApplication && baseline == null
        val readings = try {
            withTimeoutOrNull(observationTimeoutMillis) {
                source.collect(DevelopmentHealthRequest(token, boundary, needsBaseline))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val completed = nanos()
        if (readings == null || completed - readStarted > MAX_OBSERVATION_NANOS)
            return DevelopmentHealthObservation(readStarted, completed, DevelopmentHealthRefusal.UNAVAILABLE)
        val timestamps = listOfNotNull(readings.runtime.startedAtNanos, readings.resources.startedAtNanos,
            if (policy.protectApplication) readings.application?.startedAtNanos else null,
            if (needsBaseline) readings.initialBaseline?.first?.startedAtNanos else null)
        val oldest = timestamps.minOrNull() ?: readStarted
        if (timestamps.any { it < readStarted || it > completed } || completed - oldest > FRESHNESS_NANOS)
            return DevelopmentHealthObservation(oldest, completed, DevelopmentHealthRefusal.UNAVAILABLE)
        return DevelopmentHealthObservation(oldest, completed, validate(readings, readStarted, completed))
    }

    private fun validate(readings: DevelopmentHealthReadings, readStarted: Long, completed: Long): DevelopmentHealthRefusal? {
        val runtime = readings.runtime.value
        if (runtime.epoch != token.epoch || runtime.generation != token.runtimeGeneration || runtime.boundary != boundary ||
            runtime.binding != token.desiredBinding || !runtime.ready || runtime.unexpectedRestarts != 0) return DevelopmentHealthRefusal.RUNTIME
        val resources = readings.resources.value
        if (resources.availableBytes < DevelopmentHealthPolicy.HOST_HEADROOM_BYTES ||
            resources.applianceMemoryLimitBytes != policy.applianceMemoryLimitBytes) return DevelopmentHealthRefusal.RESOURCES
        if (!policy.protectApplication) return null
        val current = readings.application?.value ?: return DevelopmentHealthRefusal.UNAVAILABLE
        val initial = readings.initialBaseline
        if (baseline == null) {
            if (initial == null || initial.first.startedAtNanos < readStarted || initial.second.startedAtNanos > completed ||
                initial.second.startedAtNanos - initial.first.startedAtNanos < APP_BASELINE_NANOS ||
                !initial.first.value.ready() || !initial.first.value.matches(initial.second.value)) return DevelopmentHealthRefusal.RESOURCES
        }
        val expected = baseline ?: initial!!.first.value
        if (!current.ready() || !expected.matches(current) ||
            initial?.let { !expected.matches(it.first.value) || !expected.matches(it.second.value) } == true) return DevelopmentHealthRefusal.RESOURCES
        baseline = expected // Immutable original baseline; never reset to launder a new restart or replacement pod.
        return null
    }

    @Synchronized fun refusal(): DevelopmentHealthRefusal? {
        if (closed) return DevelopmentHealthRefusal.UNAVAILABLE
        val sample = latest ?: return DevelopmentHealthRefusal.UNAVAILABLE
        if (sample.refusal != null) return sample.refusal
        return if (nanos() - sample.oldestMeasurementNanos > FRESHNESS_NANOS) DevelopmentHealthRefusal.UNAVAILABLE else null
    }

    /** Run only while inference/startup is active. Independent of a hung collection job. */
    suspend fun watchdog(onFailure: suspend (DevelopmentHealthRefusal) -> Unit) {
        while (currentCoroutineContext().isActive) {
            val failure = synchronized(this) {
                val sample = latest
                when {
                    closed -> DevelopmentHealthRefusal.UNAVAILABLE
                    sample?.refusal != null -> sample.refusal
                    nanos() - (sample?.oldestMeasurementNanos ?: origin) > FRESHNESS_NANOS -> DevelopmentHealthRefusal.UNAVAILABLE
                    else -> null
                }
            }
            if (failure != null) { onFailure(failure); return }
            delay(WATCHDOG_INTERVAL_MILLIS)
        }
    }

    @Synchronized override fun close() { closed = true; job.cancel() }

    companion object {
        const val OPPORTUNITY_NANOS = 5_000_000_000L
        const val MAX_OBSERVATION_NANOS = 60_000_000_000L
        const val FRESHNESS_NANOS = 70_000_000_000L
        const val APP_BASELINE_NANOS = 10_000_000_000L
        const val WATCHDOG_INTERVAL_MILLIS = 1_000L
    }
}
