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
    fun contextIntakes() = ContextIntakeService(store, clock)
    fun contextLifecycle() = ContextLifecycleService(store, clock)
    fun commitments() = CommitmentService(store, clock)
    fun contextDeletion() = ContextDeletionService(store, clock)
    fun plans() = PlanService(store, clock)
    internal fun scopedExecutions()=ScopedExecutionService(store,clock)

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
    suspend fun scopedTrends(request: ScopedReviewRequest): ScopedTrendReport = withContext(Dispatchers.IO) {
        store.scopedTrends(request, clock.instant())
    }
    suspend fun prepareEvidence(request: ScopedEvidenceRequest): ScopedEvidencePrepared = withContext(Dispatchers.IO) {
        var captured=store.captureScopedEvidence(request,clock.instant())
        if(request.purpose in setOf("adaptation","morning","evening") && captured.plan?.definition?.policy!=null && captured.read.scope.wellnessPolicy=="assessment") {
            val adaptation=PreparedAdaptationResult.prepare(store.captureAdaptationResult(null,clock.instant()))
            val result=store.publishAdaptationResult(adaptation,clock.instant())
            require(result.state=="PREPARED") { "Adaptation evidence preparation is blocked" }
            captured=store.captureScopedEvidence(request,clock.instant(),requireNotNull(result.packet?.adaptation).workKey)
        }
        val report=ScopedTrends.report(captured.read.scope,captured.read.history,captured.read.statuses)
        var prepared=prepareScopedEvidence(request,captured,report)
        if(request.purpose=="adaptation" && captured.plan?.definition?.policy!=null && captured.read.scope.wellnessPolicy!="assessment") {
            val blocked=(prepared.bounded.blockedReasons+"adaptation_requires_assessment_context").distinct()
            val manifest=prepared.manifest.copy(blockedReasons=blocked)
            prepared=prepared.copy(bounded=prepared.bounded.copy(packet=null,blockedReasons=blocked),packetJson=null,
                manifest=manifest,manifestJson=encodeScopedInspectionManifest(manifest))
        }
        ScopedEvidencePrepared(report,store.saveScopedEvidence(prepared,clock.instant()))
    }
    suspend fun inspectEvidence(id: String): ScopedEvidenceSnapshot = withContext(Dispatchers.IO) {
        val stored=store.storedScopedEvidence(id)
        var changes=stored.totalChanges
        var validUntil: java.time.Instant?=null
        var adaptationCapture: CapturedSignalAdaptation?=null
        val current=try {
            val captured=store.captureScopedEvidenceInspection(id,clock.instant())
            changes=captured.totalChanges
            validUntil=captured.validUntil
            adaptationCapture=captured.scoped?.adaptationReference?.current
            captured.currentDigest()==stored.bindingSha256
        } catch (_: IllegalArgumentException) { false }
          catch (_: TrendSizeLimit) { false }
          catch (_: CommitmentProjectionLimit) { false }
          catch (_: CommitmentTimeUncertain) { false }
        store.finishScopedEvidenceInspection(id,changes,current,validUntil,adaptationCapture)
    }
    suspend fun evidenceRecords(id: String,offset: Int,limit: Int): ScopedEvidenceRecords = withContext(Dispatchers.IO) {
        store.scopedEvidenceRecords(id,offset,limit)
    }
    suspend fun scopedReviewOptions(oldest: String?, newest: String?, offset: Int): ScopedReviewOptions = withContext(Dispatchers.IO) {
        store.scopedReviewOptions(clock.instant(), oldest, newest, offset)
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
