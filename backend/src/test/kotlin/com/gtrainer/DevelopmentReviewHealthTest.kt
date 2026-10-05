package com.gtrainer

import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DevelopmentReviewHealthTest {
    private val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))
    private val config = DevelopmentReviewConfiguration("127.0.0.1", Path.of("/development/isolated.sqlite"),
        "ollama", "model-a", "Synthetic model", "model-a:synthetic", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1,
        DevelopmentReviewPolicyBinding(900_000, 1_840_000))
    private val token = OwnedOllamaRuntimeToken(1, 1, boundary.containerId, config.binding())
    private val policy = DevelopmentHealthPolicy(DevelopmentHealthPolicy.PI_APPLIANCE_BYTES, false)

    @Test fun `unconfigured source and exceptions fail closed without propagating raw failures`() = runBlocking {
        DevelopmentReviewHealthObserver(token, boundary, policy, this).use { observer ->
            assertEquals(DevelopmentHealthRefusal.UNAVAILABLE, observer.refusal())
            assertEquals(DevelopmentHealthRefusal.UNAVAILABLE, observer.opportunity().refusal)
        }
        DevelopmentReviewHealthObserver(token, boundary, policy, this,
            DevelopmentRuntimeHealthSource { throw IllegalStateException("synthetic-private-marker") }).use { observer ->
            assertEquals(DevelopmentHealthRefusal.UNAVAILABLE, observer.opportunity().refusal)
        }
    }

    @Test fun `concurrent opportunities share one bounded read and no idle observations are scheduled`() = runBlocking {
        var now = 0L
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = DevelopmentRuntimeHealthSource { calls.incrementAndGet(); entered.complete(Unit); release.await(); readings(now) }
        DevelopmentReviewHealthObserver(token, boundary, policy, this, source, { now }).use { observer ->
            val first = async { observer.opportunity() }
            entered.await()
            val second = async { observer.opportunity() }
            yield()
            assertEquals(1, calls.get())
            release.complete(Unit)
            assertSame(first.await(), second.await())
            observer.opportunity()
            assertEquals(1, calls.get())
            now += 5_000_000_000
            observer.opportunity()
            assertEquals(2, calls.get())
            now += 100_000_000_000
            yield()
            assertEquals(2, calls.get()) // Advancing TTL/freshness does not wake an idle source.
            assertEquals(DevelopmentHealthRefusal.UNAVAILABLE, observer.refusal())
        }
    }

    @Test fun `whole observation has one sixty-second ceiling rather than one per constituent`() = runBlocking {
        var now = 0L
        DevelopmentReviewHealthObserver(token, boundary, policy, this, DevelopmentRuntimeHealthSource {
            val first = now
            now += 30_000_000_000
            val resources = now
            now += 30_000_000_001
            readings(first).copy(resources = DevelopmentHealthMeasurement(healthyResources(), resources))
        }, { now }).use { observer ->
            assertEquals(DevelopmentHealthRefusal.UNAVAILABLE, observer.opportunity().refusal)
        }
        DevelopmentReviewHealthObserver(token, boundary, policy, this, DevelopmentRuntimeHealthSource { awaitCancellation() },
            observationTimeoutMillis = 20).use { observer ->
            assertEquals(DevelopmentHealthRefusal.UNAVAILABLE, observer.opportunity().refusal)
        }
    }

    @Test fun `freshness uses oldest measurement not completion and rejects future or pre-observation data`() = runBlocking {
        var now = 0L
        DevelopmentReviewHealthObserver(token, boundary, policy, this, DevelopmentRuntimeHealthSource {
            val result = readings(now)
            now += 60_000_000_000
            result.copy(resources = DevelopmentHealthMeasurement(healthyResources(), now))
        }, { now }).use { observer ->
            assertNull(observer.opportunity().refusal)
            now = 70_000_000_000
            assertNull(observer.refusal())
            now++
            assertEquals(DevelopmentHealthRefusal.UNAVAILABLE, observer.refusal())
        }
        for (timestamp in listOf(-1L, 1L)) {
            DevelopmentReviewHealthObserver(token, boundary, policy, this,
                DevelopmentRuntimeHealthSource { readings(timestamp) }, { 0L }).use { observer ->
                assertEquals(DevelopmentHealthRefusal.UNAVAILABLE, observer.opportunity().refusal)
            }
        }
    }

    @Test fun `independent watchdog refuses stale evidence while collection remains hung`() = runBlocking {
        var now = 0L
        var hang = false
        val entered = CompletableDeferred<Unit>()
        DevelopmentReviewHealthObserver(token, boundary, policy, this, DevelopmentRuntimeHealthSource {
            if (hang) { entered.complete(Unit); awaitCancellation() }
            readings(now)
        }, { now }).use { observer ->
            assertNull(observer.opportunity().refusal)
            hang = true
            now = 5_000_000_000
            val collect = async { observer.opportunity() }
            entered.await()
            now = 70_000_000_001
            val failure = CompletableDeferred<DevelopmentHealthRefusal>()
            val watchdog = launch { observer.watchdog { failure.complete(it) } }
            assertEquals(DevelopmentHealthRefusal.UNAVAILABLE, withTimeout(1_000) { failure.await() })
            assertFalse(collect.isCompleted)
            watchdog.join()
            collect.cancel()
        }
    }

    @Test fun `runtime identity readiness settings digest and unexpected restarts must match exactly`() = runBlocking {
        val healthy = readings(0).runtime.value
        val invalid = listOf(healthy.copy(epoch = 2), healthy.copy(generation = 2), healthy.copy(ready = false),
            healthy.copy(unexpectedRestarts = 1), healthy.copy(boundary = boundary.copy(containerId = "b".repeat(64))),
            healthy.copy(binding = token.desiredBinding.copy(manifestDigest = "b".repeat(64))),
            healthy.copy(binding = token.desiredBinding.copy(ollamaVersion = "other")),
            healthy.copy(binding = token.desiredBinding.copy(threads = 2)))
        invalid.forEach { runtime ->
            DevelopmentReviewHealthObserver(token, boundary, policy, this,
                DevelopmentRuntimeHealthSource { readings(0).copy(runtime = DevelopmentHealthMeasurement(runtime, 0)) }, { 0L }).use { observer ->
                assertEquals(DevelopmentHealthRefusal.RUNTIME, observer.opportunity().refusal)
            }
        }
    }

    @Test fun `five GiB whole-appliance ceiling and one GiB host headroom are independently enforced`() = runBlocking {
        val valid = healthyResources()
        val cases = listOf(valid to null, valid.copy(availableBytes = valid.availableBytes - 1) to DevelopmentHealthRefusal.RESOURCES,
            valid.copy(applianceMemoryLimitBytes = DevelopmentHealthPolicy.HOST_HEADROOM_BYTES) to DevelopmentHealthRefusal.RESOURCES,
            valid.copy(applianceMemoryLimitBytes = 0) to DevelopmentHealthRefusal.RESOURCES)
        cases.forEach { (resources, expected) ->
            DevelopmentReviewHealthObserver(token, boundary, policy, this,
                DevelopmentRuntimeHealthSource { readings(0).copy(resources = DevelopmentHealthMeasurement(resources, 0)) }, { 0L }).use { observer ->
                assertEquals(expected, observer.opportunity().refusal)
            }
        }
    }

    @Test fun `Pi baseline permits historical restarts but rejects changes and cannot be reset`() = runBlocking {
        var now = 0L
        var restart = 3
        var requested = false
        val source = DevelopmentRuntimeHealthSource { request ->
            val first = now
            val original = app(restart)
            val baseline = if (request.establishAppBaseline) {
                requested = true
                now += 10_000_000_000
                DevelopmentAppBaseline(DevelopmentHealthMeasurement(original, first), DevelopmentHealthMeasurement(original, now))
            } else null
            readings(now).copy(application = DevelopmentHealthMeasurement(original, now), initialBaseline = baseline)
        }
        DevelopmentReviewHealthObserver(token, boundary, policy.copy(protectApplication = true), this, source, { now }).use { observer ->
            assertNull(observer.opportunity().refusal)
            assertTrue(requested)
            now += 5_000_000_000
            assertNull(observer.opportunity().refusal)
            restart++
            now += 5_000_000_000
            assertEquals(DevelopmentHealthRefusal.RESOURCES, observer.opportunity().refusal)
        }
    }

    @Test fun `missing short unstable not-ready and replacement Pi app baselines are refused`() = runBlocking {
        val first = app(3)
        val invalid = listOf<DevelopmentAppBaseline?>(null,
            DevelopmentAppBaseline(DevelopmentHealthMeasurement(first, 0), DevelopmentHealthMeasurement(first, 9_999_999_999)),
            DevelopmentAppBaseline(DevelopmentHealthMeasurement(first, 0), DevelopmentHealthMeasurement(app(4), 10_000_000_000)),
            DevelopmentAppBaseline(DevelopmentHealthMeasurement(app(3, false), 0), DevelopmentHealthMeasurement(app(3, false), 10_000_000_000)),
            DevelopmentAppBaseline(DevelopmentHealthMeasurement(first, 0), DevelopmentHealthMeasurement(
                DevelopmentProtectedAppState(listOf(DevelopmentAppContainer("replacement-pod", "app", "synthetic-image", true, 3))), 10_000_000_000)))
        for (baseline in invalid) {
            var now = 0L
            DevelopmentReviewHealthObserver(token, boundary, policy.copy(protectApplication = true), this, DevelopmentRuntimeHealthSource {
                now = 10_000_000_000
                readings(now).copy(application = DevelopmentHealthMeasurement(first, now), initialBaseline = baseline)
            }, { now }).use { observer -> assertEquals(DevelopmentHealthRefusal.RESOURCES, observer.opportunity().refusal) }
        }
    }

    @Test fun `protected application snapshot does not alias caller lists`() {
        val original = mutableListOf(DevelopmentAppContainer("synthetic-pod", "app", "synthetic-image", true, 2))
        val snapshot = DevelopmentProtectedAppState(original)
        original.clear()
        assertTrue(snapshot.ready())
        assertFailsWith<UnsupportedOperationException> { (snapshot.containers as MutableList).clear() }
        assertFalse(DevelopmentProtectedAppState(emptyList()).ready())
        assertEquals("DevelopmentProtectedAppState([REDACTED])", snapshot.toString())
    }

    private fun app(restarts: Int, ready: Boolean = true) = DevelopmentProtectedAppState(
        listOf(DevelopmentAppContainer("synthetic-pod", "app", "synthetic-image", ready, restarts)))
    private fun healthyResources() = DevelopmentHostResources(DevelopmentHealthPolicy.HOST_HEADROOM_BYTES, DevelopmentHealthPolicy.PI_APPLIANCE_BYTES)
    private fun readings(now: Long) = DevelopmentHealthReadings(DevelopmentHealthMeasurement(
        DevelopmentRuntimeHealth(token.epoch, token.runtimeGeneration, boundary, token.desiredBinding, true, 0), now),
        DevelopmentHealthMeasurement(healthyResources(), now))
}
