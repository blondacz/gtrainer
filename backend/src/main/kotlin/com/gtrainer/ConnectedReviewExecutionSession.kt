package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.math.BigDecimal

/** One call's confined mutable lifecycle; admission/catalogue resolution remains in the executor. */
internal class ConnectedReviewExecutionSession(
    private val repository: ConnectedReviewExecutionRepository,
    private val gate: ConnectedReviewExecutionGate,
    private val limits: ConnectedReviewExecutionLimits,
    private val nanos: () -> Long,
    private val snapshot: ConnectedReviewStatus,
    private val invocation: ConnectedReviewInvocation,
    private val provider: ConnectedReviewProvider,
    private val startedAt: Long,
    private val totalRemainingMillis: Long,
    private val costLimit: BigDecimal?,
    observer: ConnectedReviewAttemptObserver,
    private val personalAttemptAuthority: (suspend (Int)->Boolean)?,
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
    } catch(_: ConnectedReviewBindingConflict) {
        if(claimed) fail("binding_conflict","STALE") else result("STALE","binding_conflict")
    } catch(_: ConnectedReviewNotFound) {
        result("STALE","context_deleted")
    } catch (cancelled: CancellationException) {
        if (claimed || providerCallsStarted > 0) runCatching { repository.failConnectedReview(snapshot.snapshotId, "cancelled") }
        attempts.cancelled()
        throw cancelled
    }

    private suspend fun prepare(): ConnectedReviewExecutionResult? {
        val current = repository.connectedReviewStatus(snapshot.snapshotId)
        if (current.state != (if (claimed) "RUNNING" else "IN_FLIGHT") || !sameBinding(current))
            return result("STALE", "snapshot_changed")
        gateRefusal(current)?.let { return if (claimed) fail(it, "REFUSED") else result("REFUSED", it) }
        if(invocation.profile!=snapshot.contractVersion || invocation.consentDigest!=snapshot.packetDigest)
            return if(claimed) fail("packet_changed","REFUSED") else result("REFUSED","packet_changed")
        if (provider.hosted && minOf(costLimit!!, remainingCost) <= BigDecimal.ZERO)
            return if (claimed) fail("hosted_cost_budget_exhausted", "REFUSED") else result("REFUSED", "hosted_cost_budget_exhausted")
        if (!claimed) {
            val claim = repository.claimConnectedReview(snapshot.snapshotId)
            if (claim?.state != "RUNNING" || !sameBinding(claim))
                return result("REFUSED", "snapshot_not_in_flight")
            claimed = true
        }
        return null
    }

    private suspend fun executeAttempt(number: Int): ConnectedReviewExecutionResult? {
        val attemptStart = nanos()
        val remaining = remainingMillis()
        if (owned() && remaining <= 0) return fail("total_timeout")
        val attemptLimit = if (provider.hosted) minOf(costLimit!!, remainingCost) else null
        val timeoutReason = if (remaining <= limits.attemptTimeoutMillis) "total_timeout" else "attempt_timeout"
        val received = receive(number, remaining, timeoutReason, attemptLimit,attemptStart)
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

    private suspend fun receive(number: Int, remaining: Long, timeoutReason: String, attemptLimit: BigDecimal?,attemptStart: Long): Received = try {
        withTimeout(minOf(limits.attemptTimeoutMillis, remaining.coerceAtLeast(1))) {
            val latest=repository.connectedReviewStatus(snapshot.snapshotId)
            if(latest.state!="RUNNING" || !sameBinding(latest)) return@withTimeout Received.Terminal(fail("snapshot_changed","STALE"))
            gateRefusal(latest)?.let { return@withTimeout Received.Terminal(fail(it,"REFUSED")) }
            currentCoroutineContext().ensureActive()
            if(remainingMillis()<=0) return@withTimeout attemptFailure(number,"total_timeout")
            if(nanos()-attemptStart>=limits.attemptTimeoutMillis*1_000_000L) return@withTimeout attemptFailure(number,"attempt_timeout")
            if(personalAttemptAuthority!=null && !personalAttemptAuthority.invoke(number))
                return@withTimeout attemptFailure(number,"runtime_admission_closed")
            // Observing/authorizing may suspend. Recheck all send bindings after it, not only at entry.
            val authorized=repository.connectedReviewStatus(snapshot.snapshotId)
            if(authorized.state!="RUNNING" || !sameBinding(authorized)) return@withTimeout Received.Terminal(fail("snapshot_changed","STALE"))
            gateRefusal(authorized)?.let { return@withTimeout Received.Terminal(fail(it,"REFUSED")) }
            val final=repository.connectedReviewStatus(snapshot.snapshotId)
            if(final.state!="RUNNING" || !sameBinding(final)) return@withTimeout Received.Terminal(fail("snapshot_changed","STALE"))
            currentCoroutineContext().ensureActive()
            if(remainingMillis()<=0) return@withTimeout attemptFailure(number,"total_timeout")
            if(nanos()-attemptStart>=limits.attemptTimeoutMillis*1_000_000L) return@withTimeout attemptFailure(number,"attempt_timeout")
            attempts.starting(number, correctionReason != null)
            providerCallsStarted++
            val correctionPrompt=invocation.exactCorrectionPrompt
            val prompt=if(correctionReason!=null && correctionPrompt!=null) correctionPrompt else invocation.exactPrompt
            Received.Reply(provider.generate(prompt,if(correctionPrompt==null) correctionReason else null,limits.maxResponseBytes,attemptLimit))
        }
    } catch (_: TimeoutCancellationException) {
        attemptFailure(number, timeoutReason)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ConnectedReviewProviderFailure) {
        attemptFailure(number, failure.code.wireCode)
    } catch(_: ConnectedReviewBindingConflict) {
        attempts += ConnectedReviewAttemptResult(number,"invalidated","binding_conflict",null)
        Received.Terminal(fail("binding_conflict","STALE"))
    } catch(_: ConnectedReviewNotFound) {
        attempts += ConnectedReviewAttemptResult(number,"invalidated","context_deleted",null)
        Received.Terminal(result("STALE","context_deleted"))
    } catch (_: Exception) {
        attemptFailure(number, "provider_unavailable")
    }

    private fun attemptFailure(number: Int, reason: String): Received.Terminal {
        attempts += ConnectedReviewAttemptResult(number, "failed", reason, null)
        return Received.Terminal(fail(reason))
    }

    private fun replyFailure(reply: ConnectedReviewProviderReply, attemptStart: Long, attemptLimit: BigDecimal?): String? {
        if (owned() && remainingMillis() <= 0) return "total_timeout"
        if (nanos() - attemptStart >= limits.attemptTimeoutMillis * 1_000_000L) return "attempt_timeout"
        if (reply.response.toByteArray(Charsets.UTF_8).size > limits.maxResponseBytes) return "response_budget_exceeded"
        val cost = reply.costUsd
        if (provider.hosted && (cost == null || cost.signum() < 0 || cost > attemptLimit!! || cost > remainingCost)) return "hosted_cost_budget_exceeded"
        if (!provider.hosted && cost != null) return "invalid_local_cost_binding"
        return null
    }

    private suspend fun currentForPublication(number: Int, reply: ConnectedReviewProviderReply): ConnectedReviewExecutionResult? {
        val latest = try { repository.connectedReviewStatus(snapshot.snapshotId) }
        catch(_: ConnectedReviewBindingConflict) {
            attempts += ConnectedReviewAttemptResult(number,"invalidated","binding_conflict",reply.costUsd?.toPlainString())
            return fail("binding_conflict","STALE")
        }
        catch (_: ConnectedReviewNotFound) {
            attempts += ConnectedReviewAttemptResult(number, "invalidated", "context_deleted", reply.costUsd?.toPlainString())
            return result("STALE", "context_deleted")
        }
        if (latest.state != "RUNNING" || !sameBinding(latest)) {
            attempts += ConnectedReviewAttemptResult(number, "invalidated", "snapshot_changed", reply.costUsd?.toPlainString())
            return result("STALE", "snapshot_changed")
        }
        gateRefusal(latest)?.let {
            attempts += ConnectedReviewAttemptResult(number, "invalidated", it, reply.costUsd?.toPlainString())
            return fail(it, "REFUSED")
        }
        return null
    }

    private suspend fun validateAndPublish(number: Int, reply: ConnectedReviewProviderReply): ConnectedReviewExecutionResult? {
        val validated = try { invocation.validate(reply.response) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return fail("validation_unavailable") } // Infrastructure failures never earn a correction.
        if (validated is ConnectedReviewValidation.Rejected) {
            attempts += ConnectedReviewAttemptResult(number, "rejected", "invalid_output", reply.costUsd?.toPlainString())
            if (number < limits.maxAttempts) { correctionReason = "invalid_output"; return null }
            return fail("invalid_output", "REJECTED")
        }
        val accepted=validated as ConnectedReviewValidation.Accepted
        if (owned() && remainingMillis() <= 0) return fail("total_timeout")
        val published = try {
            val publish=repository.prepareConnectedReviewPublication(snapshot.snapshotId, accepted.output, snapshot.evidenceDigest,
                snapshot.context, snapshot.provider, snapshot.model, snapshot.contractVersion)
            val latest=repository.connectedReviewStatus(snapshot.snapshotId)
            if(latest.state!="RUNNING" || !sameBinding(latest)) return fail("snapshot_changed","STALE")
            gateRefusal(latest)?.let { return fail(it,"REFUSED") }
            currentCoroutineContext().ensureActive()
            if(remainingMillis()<=0) return fail("total_timeout")
            publish()
        } catch(_: ConnectedReviewBindingConflict) {
            attempts += ConnectedReviewAttemptResult(number,"invalidated","binding_conflict",reply.costUsd?.toPlainString())
            return fail("binding_conflict","STALE")
        } catch (_: ConnectedReviewNotFound) {
            attempts += ConnectedReviewAttemptResult(number, "invalidated", "context_deleted", reply.costUsd?.toPlainString())
            return result("STALE", "context_deleted")
        }
        attempts += ConnectedReviewAttemptResult(number, if (published.state == "PUBLISHED") "accepted" else "invalidated", null, reply.costUsd?.toPlainString())
        return if (published.state == "PUBLISHED") result("PUBLISHED", "validated", accepted.legacyReview)
        else if(published.state=="FAILED" && published.staleReason=="consent_revoked") result("REFUSED","consent_revoked")
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
    private fun sameBinding(current: ConnectedReviewStatus)=current.requestId==snapshot.requestId && current.generation==snapshot.generation &&
        current.evidenceDigest==snapshot.evidenceDigest && current.packetDigest==snapshot.packetDigest && current.context==snapshot.context &&
        current.provider==snapshot.provider && current.model==snapshot.model && current.contractVersion==snapshot.contractVersion
    private fun owned() = limits.policy != ConnectedReviewExecutionPolicy.ORDINARY

    private companion object {
        val GATE_REASONS = setOf("qualification_gate_disabled", "provider_not_qualified", "runtime_health_failed",
            "runtime_health_unavailable", "resource_health_failed", "consent_required", "consent_revoked", "snapshot_changed",
            "provider_changed", "packet_changed", "safety_gate_unavailable","profile_not_qualified","runtime_admission_closed","unsupported_profile") + OWNER_UAT_REFUSAL_REASONS
    }
}
