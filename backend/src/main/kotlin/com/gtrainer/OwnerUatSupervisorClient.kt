package com.gtrainer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

@Serializable
internal data class OwnerUatSupervisorRequest(val op: String, val claim: PersonalReviewClaim, val phase: String? = null)
@Serializable
internal data class OwnerUatSupervisorReply(val ok: Boolean, val claimId: String, val epoch: String,
    val job: OwnerUatSupervisedJob? = null)

internal fun interface OwnerUatSupervisorTransport { suspend fun exchange(request: ByteArray): ByteArray? }

/** An IPC client never launches, repairs or bootstraps its supervisor. */
internal class OwnerUatSupervisorClient(val clock: OwnerUatContinuousClock, private val transport: OwnerUatSupervisorTransport) {
    suspend fun acknowledge(claim: PersonalReviewClaim): Boolean = request("register", claim)?.let {
        it.supervisorEpoch != null && it.permits(clock.sample())
    } == true
    suspend fun launch(claim: PersonalReviewClaim): OwnerUatProcessIdentity? = request("launch", claim)?.let {
        if (!it.tombstoned && it.launchConsumed && it.permits(clock.sample())) it.worker else null
    }
    suspend fun phase(claim: PersonalReviewClaim, phase: String): Boolean = request("phase", claim, phase)?.let {
        it.phase == phase && it.permits(clock.sample())
    } == true
    suspend fun status(claim: PersonalReviewClaim): OwnerUatSupervisedJob? = request("status", claim)
    suspend fun stop(claim: PersonalReviewClaim): PersonalStopEvidence? = request("stop", claim)?.takeIf {
        it.phase == "STOP_CONFIRMED" && it.tombstoned
    }?.let { PersonalStopEvidence(claim, java.time.Instant.now(), true, true) }

    private suspend fun request(op: String, claim: PersonalReviewClaim, phase: String? = null): OwnerUatSupervisedJob? {
        val payload = ScopedEvidencePreparation.json.encodeToString(OwnerUatSupervisorRequest(op, claim, phase)).toByteArray()
        val bytes = transport.exchange(payload) ?: return null
        require(bytes.size <= OwnerUatSocketIO.MAX_BYTES)
        val receipt = StrictProfileDraftJson.decode<OwnerUatSupervisorReply>(bytes.decodeToString(throwOnInvalidSequence = true))
        val job = receipt.job ?: return null
        return job.takeIf { receipt.ok && receipt.claimId == claim.claimId && it.claim == claim &&
            (it.supervisorEpoch == receipt.epoch || it.tombstoned) }
    }
}

internal class OwnerUatUnixSupervisorTransport(private val path: Path) : OwnerUatSupervisorTransport {
    override suspend fun exchange(request: ByteArray): ByteArray? = runInterruptible(Dispatchers.IO) {
        require(path.isAbsolute && path.normalize() == path && request.size <= OwnerUatSocketIO.MAX_BYTES)
        OwnerUatSocketIO.privateOwner(path.parent, directory = true)
        OwnerUatSocketIO.privateOwner(path, directory = false)
        val deadline = System.nanoTime() + 5_000_000_000L
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            Selector.open().use { selector ->
                channel.register(selector, SelectionKey.OP_CONNECT)
                if (!channel.connect(UnixDomainSocketAddress.of(path))) {
                    OwnerUatSocketIO.await(selector, deadline)
                    if (!channel.finishConnect()) return@runInterruptible null
                }
            }
            OwnerUatSocketIO.peer(channel)
            OwnerUatSocketIO.write(channel, request, deadline)
            OwnerUatSocketIO.read(channel, deadline)
        }
    }
}

internal object OwnerUatSocketIO {
    const val MAX_BYTES = 8192
    fun privateOwner(path: Path, directory: Boolean) {
        require(Files.exists(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        if (directory) require(Files.isDirectory(path, NOFOLLOW_LINKS))
        val owner = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        require(Files.getOwner(path, NOFOLLOW_LINKS) == owner)
        require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).all { it.name.startsWith("OWNER_") })
    }
    fun peer(channel: SocketChannel) {
        val peer = channel.getOption(jdk.net.ExtendedSocketOptions.SO_PEERCRED)
        require(peer.user().name == System.getProperty("user.name"))
    }
    fun await(selector: Selector, deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val remaining = deadline - System.nanoTime(); check(remaining > 0)
        selector.select(minOf(100L, (remaining / 1_000_000).coerceAtLeast(1)))
        selector.selectedKeys().clear()
    }
    fun write(channel: SocketChannel, bytes: ByteArray, deadline: Long) {
        require(bytes.size <= MAX_BYTES)
        Selector.open().use { selector ->
            channel.register(selector, SelectionKey.OP_WRITE)
            val output = ByteBuffer.wrap(bytes + byteArrayOf(10))
            while (output.hasRemaining()) { check(System.nanoTime() < deadline); if (channel.write(output) == 0) await(selector, deadline) }
        }
    }
    fun read(channel: SocketChannel, deadline: Long): ByteArray? {
        Selector.open().use { selector ->
            channel.register(selector, SelectionKey.OP_READ)
            val input = ByteBuffer.allocate(MAX_BYTES + 1)
            while (input.hasRemaining()) {
                check(System.nanoTime() < deadline)
                val read = channel.read(input)
                if (read < 0) return null
                val size = input.position()
                if (size > 0 && input.get(size - 1) == 10.toByte()) return input.array().copyOf(size - 1)
                if (read == 0) await(selector, deadline)
            }
            return null
        }
    }
}
