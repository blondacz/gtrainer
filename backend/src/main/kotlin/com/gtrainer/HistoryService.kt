package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate

class SyncBusy : IllegalStateException("A private data operation is already running")

class HistoryService(private val store: HistoryStore, private val source: HistorySource,
                     private val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    private val operation = Mutex()
    fun athleteContexts() = AthleteContextService(store)

    suspend fun sync(range: ReadRange): List<CategoryStatus> = withContext(Dispatchers.IO) {
        if (!operation.tryLock()) throw SyncBusy()
        try {
            store.begin("activities", clock.instant())
            val activities = safeRead { source.activities(range) }
            store.completeActivities(activities, clock.instant())
            store.begin("wellness", clock.instant())
            val wellness = safeRead { source.wellness(range) }
            store.completeWellness(wellness, clock.instant())
            store.statuses(LocalDate.now(clock))
        } catch (cancelled: CancellationException) {
            store.interrupted("activities")
            store.interrupted("wellness")
            throw cancelled
        } catch (_: Exception) {
            store.interrupted("activities")
            store.interrupted("wellness")
            throw IllegalStateException("Private import operation failed; contents withheld")
        } finally { operation.unlock() }
    }

    private suspend fun <T> safeRead(read: suspend () -> ReadResult<T>): ReadResult<T> = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ReadResult(ReadStatus.TRANSPORT_ERROR)
    }

    suspend fun statuses(): List<CategoryStatus> = withContext(Dispatchers.IO) { store.statuses(LocalDate.now(clock)) }
    suspend fun history(oldest: LocalDate, newest: LocalDate): HistoryResponse = withContext(Dispatchers.IO) { store.history(oldest, newest) }
    suspend fun trends(range: TrendRange, sport: String?): TrendReport = withContext(Dispatchers.IO) {
        store.trends(range, sport, LocalDate.now(clock))
    }
    suspend fun removeImports() = withContext(Dispatchers.IO) {
        if (!operation.tryLock()) throw SyncBusy()
        try { store.removeImports() } finally { operation.unlock() }
    }

    internal fun reviewScheduler(reviews: ReviewInterpretationService): ReviewScheduler = ReviewScheduler(store, reviews, clock, durableQueue = true)
    internal fun reviewQueue(reviews: ReviewInterpretationService): ReviewQueueService = ReviewQueueService(store, reviews, clock)
    internal fun manualEvents(): ManualEventService = ManualEventService(store, clock)

    override fun close() {
        (source as? AutoCloseable)?.close()
        store.close()
    }

    companion object {
        fun fromEnvironment(): HistoryService? {
            val database = System.getenv("GTRAINER_DATABASE_FILE") ?: return null
            val keyFile = System.getenv("GTRAINER_INTERVALS_KEY_FILE")
            val source = if (keyFile != null) IntervalsSource.production(Path.of(keyFile)) else object : HistorySource {
                override val sourceId = "intervals.icu"
                override suspend fun activities(range: ReadRange): ReadResult<ActivityRecord> = ReadResult(ReadStatus.NOT_CONFIGURED)
                override suspend fun wellness(range: ReadRange): ReadResult<WellnessRecord> = ReadResult(ReadStatus.NOT_CONFIGURED)
            }
            return HistoryService(HistoryStore(Path.of(database)), source)
        }
    }
}
