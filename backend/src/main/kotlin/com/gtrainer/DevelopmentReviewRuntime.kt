package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

internal data class DevelopmentRuntimeHandle(val claim: DevelopmentOwnedClaim, val keeperPid: Long, val keeperStartTicks: Long) {
    init { require(keeperPid > 1 && keeperStartTicks >= 0) }
}

/** Constructible only by validating a response from the private supervisor transport. */
internal class DevelopmentRuntimeStopReceipt private constructor(val handle: DevelopmentRuntimeHandle,
    val reaped: Int, val escalated: Boolean, val elapsedMillis: Long) {
    fun safeJson(): String = buildJsonObject {
        put("identity", identity(handle.claim)); put("keeper_pid", handle.keeperPid); put("keeper_start_ticks", handle.keeperStartTicks)
        put("confirmed", true); put("mechanism", MECHANISM); put("reaped", reaped); put("escalated", escalated); put("elapsed_millis", elapsedMillis)
    }.toString()

    companion object {
        const val MECHANISM = "linux_subreaper_waitpid_echild"
        fun decode(handle: DevelopmentRuntimeHandle, data: ByteArray): DevelopmentRuntimeStopReceipt? {
            if (data.size > 4096) return null
            val value = StrictModelJson.parse(data.decodeToString(throwOnInvalidSequence = true)).jsonObject
            if (value["ok"]?.let { it != JsonPrimitive(true) } == true || value["identity"] != identity(handle.claim) ||
                value["keeper_pid"] != JsonPrimitive(handle.keeperPid) || value["keeper_start_ticks"] != JsonPrimitive(handle.keeperStartTicks) ||
                value["confirmed"] != JsonPrimitive(true) || value["mechanism"] != JsonPrimitive(MECHANISM)) return null
            val allowed = setOf("ok", "identity", "keeper_pid", "keeper_start_ticks", "confirmed", "mechanism", "reaped", "escalated", "elapsed_millis")
            if (!allowed.containsAll(value.keys)) return null
            if (listOf("reaped", "elapsed_millis", "escalated").any { value[it]?.jsonPrimitive?.isString != false }) return null
            val reaped = value["reaped"]?.jsonPrimitive?.int ?: return null
            val elapsed = value["elapsed_millis"]?.jsonPrimitive?.long ?: return null
            val escalated = value["escalated"]?.jsonPrimitive?.boolean ?: return null
            if (reaped !in 0..1_000_000 || elapsed !in 0..60_000) return null
            return DevelopmentRuntimeStopReceipt(handle, reaped, escalated, elapsed)
        }
    }
}

internal fun identity(claim: DevelopmentOwnedClaim) = buildJsonObject {
    put("run_id", claim.runId); put("epoch", claim.owner.number); put("generation", claim.generation); put("container_id", claim.boundary.containerId)
}

internal interface DevelopmentRuntimeSupervisor {
    suspend fun start(claim: DevelopmentOwnedClaim): DevelopmentRuntimeHandle?
    suspend fun running(handle: DevelopmentRuntimeHandle): Boolean
    suspend fun lookup(claim: DevelopmentOwnedClaim): DevelopmentRuntimeHandle? = null
    suspend fun stop(handle: DevelopmentRuntimeHandle, budgetMillis: Long): DevelopmentRuntimeStopReceipt?
}

/** Always used after execution, including success. Cancellation acceptance is a separate short transaction. */
internal class DevelopmentRuntimeCleanup(private val store: DevelopmentReviewStore, private val supervisor: DevelopmentRuntimeSupervisor,
    private val nanos: () -> Long = System::nanoTime) {
    suspend fun stop(handle: DevelopmentRuntimeHandle, cancelRequest: suspend () -> Unit = {}): Boolean = withContext(NonCancellable) {
        val deadline = nanos() + 60_000_000_000L
        try {
            runCatching { store.beginOwnedStop(handle) }
            // Storage failure/stale epoch may fence persistence, but never skips its owned physical stop.
            try { withTimeoutOrNull(500) { cancelRequest() } } catch (_: Exception) { }
            // Request-close failure is never a reason to skip owned TERM/KILL/reap.
            val remaining = (deadline - nanos()) / 1_000_000 - 2_100 // Reserve bounded SQLite receipt persistence.
            if (remaining <= 0) return@withContext store.unknownOwnedStop(handle)
            val receipt = withTimeoutOrNull(minOf(57_000, remaining)) { supervisor.stop(handle, minOf(57_000, remaining)) }
            if (receipt != null && receipt.handle == handle && nanos() < deadline && store.confirmOwnedStop(receipt)) true
            else store.unknownOwnedStop(handle)
        } catch (_: CancellationException) { store.unknownOwnedStop(handle) }
        catch (_: Exception) { runCatching { store.unknownOwnedStop(handle) }; false }
    }
}
