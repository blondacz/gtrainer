package com.gtrainer

import kotlinx.coroutines.delay

internal fun interface DevelopmentOwnedRuntimeHealthRead {
    suspend fun read(request: DevelopmentHealthRequest): DevelopmentRuntimeHealth?
}
internal fun interface DevelopmentHostResourceRead {
    suspend fun read(boundary: DevelopmentNodeBoundary): DevelopmentHostResources?
}
internal fun interface DevelopmentProtectedApplicationRead {
    suspend fun read(): DevelopmentProtectedAppState?
}

/** Sequential fixed-purpose reads share the enclosing observer's ONE total deadline. */
internal class DevelopmentReadOnlyHealthSource(private val runtime: DevelopmentOwnedRuntimeHealthRead,
    private val resources: DevelopmentHostResourceRead,
    private val application: DevelopmentProtectedApplicationRead? = null,
    private val nanos: () -> Long = System::nanoTime,
    private val baselinePause: suspend () -> Unit = { delay(10_000) }) : DevelopmentRuntimeHealthSource {
    override suspend fun collect(request: DevelopmentHealthRequest): DevelopmentHealthReadings? {
        val baseline = if (request.establishAppBaseline) establishBaseline() ?: return null else null
        val app = if (application != null) {
            if (baseline != null) baseline.second else measurement { application.read() } ?: return null
        } else null
        val observedRuntime = measurement { runtime.read(request) } ?: return null
        val observedResources = measurement { resources.read(request.boundary) } ?: return null
        return DevelopmentHealthReadings(observedRuntime, observedResources, app, baseline)
    }

    private suspend fun establishBaseline(): DevelopmentAppBaseline? {
        val reader = application ?: return null
        val first = measurement { reader.read() } ?: return null
        baselinePause() // Only the active initial Pi observation waits; never an idle/startup retention timer.
        val second = measurement { reader.read() } ?: return null
        return DevelopmentAppBaseline(first, second)
    }

    private suspend fun <T> measurement(read: suspend () -> T?): DevelopmentHealthMeasurement<T>? {
        val started = nanos()
        return read()?.let { DevelopmentHealthMeasurement(it, started) }
    }
}
