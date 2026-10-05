package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal

/** One call's confined mutable lifecycle; admission/catalogue resolution remains in the executor. */
internal class ConnectedReviewExecutionSession(
    private val repository: ConnectedReviewExecutionRepository,
    private val gate: ConnectedReviewExecutionGate,
    private val limits: ConnectedReviewExecutionLimits,
    private val nanos: () -> Long,
    private val snapshot: ConnectedReviewStatus,
    private val packet: InterpretationPacketV1,
    private val prompt: InterpretationPromptV1,
    private val provider: ConnectedReviewProvider,
    private val startedAt: Long,
    private val totalRemainingMillis: Long,
    private val costLimit: BigDecimal?,
    observer: ConnectedReviewAttemptObserver,
) {
    private val attempts = ConnectedReviewAttempts(observer)
    private var remainingCost = limits.maxHostedCostTotalUsd
    private var correctionReason: String? = null
    private var providerCallsStarted = 0
    private var claimed = false

    suspend fun execute(): ConnectedReviewExecutionResult = try {
        withTimeout(totalRemainingMillis) {
            for (number in 1..limits.maxAttempts) {
                prepare()?.let { return@withTimeout it }
                executeAttempt(number)?.let { return@withTimeout it }
            }
            result("REJECTED", "invalid_output")
        }
    } catch (_: TimeoutCancellationException) {
        if (claimed) fail("total_timeout") else result("UNAVAILABLE", "total_timeout")
    } catch (cancelled: CancellationException) {
        if (claimed || providerCallsStarted > 0) runCatching { repository.failConnectedReview(snapshot.snapshotId, "cancelled") }
        attempts.cancelled()
        throw cancelled
    }

    private suspend fun prepare(): ConnectedReviewExecutionResult? {
        val current = repository.connectedReviewStatus(snapshot.snapshotId)
        if (current.state != (if (claimed) "RUNNING" else "IN_FLIGHT") || current.packetDigest != snapshot.packetDigest)
            return result("STALE", "snapshot_changed")
        gateRefusal(current)?.let { return if (claimed) fail(it, "REFUSED") else result("REFUSED", it) }
        if (provider.hosted && minOf(costLimit!!, remainingCost) <= BigDecimal.ZERO)
            return if (claimed) fail("hosted_cost_budget_exhausted", "REFUSED") else result("REFUSED", "hosted_cost_budget_exhausted")
        if (!claimed) {
            val claim = repository.claimConnectedReview(snapshot.snapshotId)
            if (claim?.state != "RUNNING" || claim.packetDigest != snapshot.packetDigest)
                return result("REFUSED", "snapshot_not_in_flight")
            claimed = true
        }
        return null
    }

    private suspend fun executeAttempt(number: Int): ConnectedReviewExecutionResult? {
        val attemptStart = nanos()
        val remaining = remainingMillis()
        if (development() && remaining <= 0) return fail("total_timeout")
        val attemptLimit = if (provider.hosted) minOf(costLimit!!, remainingCost) else null
        val timeoutReason = if (remaining <= limits.attemptTimeoutMillis) "total_timeout" else "attempt_timeout"
        val received = receive(number, remaining, timeoutReason, attemptLimit)
        if (received is Received.Terminal) return received.result
        val reply = (received as Received.Reply).value
        replyFailure(reply, attemptStart, attemptLimit)?.let {
            attempts += ConnectedReviewAttemptResult(number, "failed", it, if (it == "total_timeout" || it == "invalid_local_cost_binding") null else reply.costUsd?.toPlainString())
            return fail(it)
        }
        if (provider.hosted) remainingCost -= reply.costUsd!!
        currentForPublication(number, reply)?.let { return it }
        return validateAndPublish(number, reply)
    }

    private sealed interface Received {
        data class Reply(val value: ConnectedReviewProviderReply) : Received
        data class Terminal(val result: ConnectedReviewExecutionResult) : Received
    }

    private suspend fun receive(number: Int, remaining: Long, timeoutReason: String, attemptLimit: BigDecimal?): Received = try {
        Received.Reply(withTimeout(minOf(limits.attemptTimeoutMillis, remaining.coerceAtLeast(1))) {
            attempts.starting(number, correctionReason != null)
            providerCallsStarted++
            provider.generate(prompt, correctionReason, limits.maxResponseBytes, attemptLimit)
        })
    } catch (_: TimeoutCancellationException) {
        attemptFailure(number, timeoutReason)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ConnectedReviewProviderFailure) {
        attemptFailure(number, failure.code.wireCode)
    } catch (_: Exception) {
        attemptFailure(number, "provider_unavailable")
    }

    private fun attemptFailure(number: Int, reason: String): Received.Terminal {
        attempts += ConnectedReviewAttemptResult(number, "failed", reason, null)
        return Received.Terminal(fail(reason))
    }

    private fun replyFailure(reply: ConnectedReviewProviderReply, attemptStart: Long, attemptLimit: BigDecimal?): String? {
        if (development() && remainingMillis() <= 0) return "total_timeout"
        if (nanos() - attemptStart >= limits.attemptTimeoutMillis * 1_000_000L) return "attempt_timeout"
        if (reply.response.toByteArray(Charsets.UTF_8).size > limits.maxResponseBytes) return "response_budget_exceeded"
        val cost = reply.costUsd
        if (provider.hosted && (cost == null || cost.signum() < 0 || cost > attemptLimit!! || cost > remainingCost)) return "hosted_cost_budget_exceeded"
        if (!provider.hosted && cost != null) return "invalid_local_cost_binding"
        return null
    }

    private suspend fun currentForPublication(number: Int, reply: ConnectedReviewProviderReply): ConnectedReviewExecutionResult? {
        val latest = try { repository.connectedReviewStatus(snapshot.snapshotId) }
        catch (_: ConnectedReviewNotFound) {
            attempts += ConnectedReviewAttemptResult(number, "invalidated", "context_deleted", reply.costUsd?.toPlainString())
            return result("STALE", "context_deleted")
        }
        if (latest.state != "RUNNING" || latest.packetDigest != snapshot.packetDigest) {
            attempts += ConnectedReviewAttemptResult(number, "invalidated", "snapshot_changed", reply.costUsd?.toPlainString())
            return result("STALE", "snapshot_changed")
        }
        gateRefusal(latest)?.let {
            attempts += ConnectedReviewAttemptResult(number, "invalidated", it, reply.costUsd?.toPlainString())
            return fail(it, "REFUSED")
        }
        return null
    }

    private fun validateAndPublish(number: Int, reply: ConnectedReviewProviderReply): ConnectedReviewExecutionResult? {
        val validated = try { InterpretationContractV1.decodeDraft(reply.response, packet) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // Rejected text never enters correction prompts or diagnostics.
        if (validated == null) {
            attempts += ConnectedReviewAttemptResult(number, "rejected", "invalid_output", reply.costUsd?.toPlainString())
            if (number == 1) { correctionReason = "invalid_output"; return null }
            return fail("invalid_output", "REJECTED")
        }
        val output = InterpretationContractV1.encodeDraft(validated)
        if (development() && remainingMillis() <= 0) return fail("total_timeout")
        val published = try {
            repository.publishConnectedReview(snapshot.snapshotId, ValidatedConnectedReviewOutput(output), snapshot.evidenceDigest,
                snapshot.context, snapshot.provider, snapshot.model, snapshot.contractVersion)
        } catch (_: ConnectedReviewNotFound) {
            attempts += ConnectedReviewAttemptResult(number, "invalidated", "context_deleted", reply.costUsd?.toPlainString())
            return result("STALE", "context_deleted")
        }
        attempts += ConnectedReviewAttemptResult(number, if (published.state == "PUBLISHED") "accepted" else "invalidated", null, reply.costUsd?.toPlainString())
        return if (published.state == "PUBLISHED") result("PUBLISHED", "validated", validated)
        else result("STALE", published.staleReason ?: "snapshot_changed")
    }

    private suspend fun gateRefusal(current: ConnectedReviewStatus): String? = try {
        gate.refusalReason(current, provider)?.let { if (it in GATE_REASONS) it else "safety_gate_unavailable" }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { "safety_gate_unavailable" }

    private fun fail(reason: String, state: String = "UNAVAILABLE"): ConnectedReviewExecutionResult {
        val final = try { repository.failConnectedReview(snapshot.snapshotId, reason) }
        catch (_: ConnectedReviewNotFound) { return result("STALE", "context_deleted") }
        return if (final.state == "STALE") result("STALE", final.staleReason ?: "snapshot_changed") else result(state, reason)
    }
    private fun result(state: String, reason: String, review: InterpretationDraftV1? = null) =
        ConnectedReviewExecutionResult(snapshot.snapshotId, state, reason, snapshot.packetDigest, attempts.toList(), review)
    private fun remainingMillis() = limits.totalTimeoutMillis - (nanos() - startedAt) / 1_000_000L
    private fun development() = limits.policy == ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL

    private companion object {
        val GATE_REASONS = setOf("qualification_gate_disabled", "provider_not_qualified", "runtime_health_failed",
            "runtime_health_unavailable", "resource_health_failed", "consent_required", "consent_revoked", "snapshot_changed",
            "provider_changed", "packet_changed", "safety_gate_unavailable")
    }
}
