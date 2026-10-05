package com.gtrainer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal fun interface DevelopmentSupervisorTransport {
    suspend fun exchange(request: ByteArray, budgetMillis: Long): ByteArray?
}

internal class DevelopmentReviewSupervisorClient(private val transport: DevelopmentSupervisorTransport) : DevelopmentRuntimeSupervisor {
    override suspend fun start(claim: DevelopmentOwnedClaim): DevelopmentRuntimeHandle? {
        val response = request("start", claim, 5_000) ?: return null
        if (!matches(response, claim) || response["runtime_exited"] != JsonPrimitive(false)) return null
        return DevelopmentRuntimeHandle(claim, response.getValue("keeper_pid").jsonPrimitive.long,
            response.getValue("keeper_start_ticks").jsonPrimitive.long)
    }

    override suspend fun running(handle: DevelopmentRuntimeHandle): Boolean {
        val response = request("status", handle.claim, 5_000) ?: return false
        return matches(response, handle.claim) && response["keeper_pid"] == JsonPrimitive(handle.keeperPid) &&
            response["keeper_start_ticks"] == JsonPrimitive(handle.keeperStartTicks) && response["runtime_exited"] == JsonPrimitive(false)
    }

    override suspend fun lookup(claim: DevelopmentOwnedClaim): DevelopmentRuntimeHandle? {
        val response = request("status", claim, 5_000) ?: return null
        if (!matches(response, claim)) return null
        return DevelopmentRuntimeHandle(claim, response.getValue("keeper_pid").jsonPrimitive.long, response.getValue("keeper_start_ticks").jsonPrimitive.long)
    }

    override suspend fun stop(handle: DevelopmentRuntimeHandle, budgetMillis: Long): DevelopmentRuntimeStopReceipt? {
        require(budgetMillis in 1..57_000)
        val response = request("stop", handle.claim, budgetMillis, budgetMillis) ?: return null
        return DevelopmentRuntimeStopReceipt.decode(handle, response.toString().toByteArray())
    }

    private fun matches(value: JsonObject, claim: DevelopmentOwnedClaim) = value["ok"] == JsonPrimitive(true) && value["identity"] == identity(claim)
    private suspend fun request(op: String, claim: DevelopmentOwnedClaim, timeout: Long, stopBudget: Long? = null): JsonObject? {
        val payload = buildJsonObject {
            put("op", op); put("identity", identity(claim))
            if (stopBudget != null) {
                val seconds = (stopBudget - 100).coerceAtLeast(1) / 1000.0
                put("total_seconds", seconds); put("term_seconds", minOf(5.0, seconds))
            }
        }.toString().toByteArray()
        val bytes = transport.exchange(payload, timeout) ?: return null
        if (bytes.size > 4096) return null
        return StrictModelJson.parse(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
    }
}

/** No TCP endpoint. Only the dedicated Linux worker, directly parented by PID 1, may use this private socket. */
internal class DevelopmentUnixSupervisorTransport(private val path: Path) : DevelopmentSupervisorTransport {
    override suspend fun exchange(request: ByteArray, budgetMillis: Long): ByteArray? = runInterruptible(Dispatchers.IO) {
        require(System.getProperty("os.name") == "Linux" && ProcessHandle.current().parent().orElseThrow().pid() == 1L)
        require(path.isAbsolute && request.size < 4096 && budgetMillis in 1..60_000)
        require(Files.isDirectory(path.parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        val owner = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        listOf(path.parent, path).forEach {
            require(Files.getOwner(it, NOFOLLOW_LINKS) == owner && Files.getPosixFilePermissions(it, NOFOLLOW_LINKS).all { p -> p.name.startsWith("OWNER_") })
        }
        val deadline = System.nanoTime() + budgetMillis * 1_000_000
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            Selector.open().use { selector ->
                val key = channel.register(selector, SelectionKey.OP_CONNECT)
                if (!channel.connect(UnixDomainSocketAddress.of(path))) {
                    await(selector, deadline)
                    if (!channel.finishConnect()) return@runInterruptible null
                }
                require(channel.getOption(jdk.net.ExtendedSocketOptions.SO_PEERCRED).user() == owner)
                key.interestOps(SelectionKey.OP_WRITE)
                val outgoing = ByteBuffer.wrap(request + byteArrayOf(10))
                while (outgoing.hasRemaining()) {
                    check(System.nanoTime() < deadline)
                    if (channel.write(outgoing) == 0) await(selector, deadline)
                }
                key.interestOps(SelectionKey.OP_READ)
                val incoming = ByteBuffer.allocate(4097)
                while (incoming.hasRemaining()) {
                    check(System.nanoTime() < deadline)
                    val read = channel.read(incoming)
                    if (read < 0) return@runInterruptible null
                    val size = incoming.position()
                    if (size > 0 && incoming.get(size - 1) == 10.toByte()) return@runInterruptible incoming.array().copyOf(size - 1)
                    if (read == 0) await(selector, deadline)
                }
                null
            }
        }
    }

    private fun await(selector: Selector, deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val remaining = (deadline - System.nanoTime()) / 1_000_000
        check(remaining > 0)
        selector.select(minOf(100, remaining).coerceAtLeast(1))
        selector.selectedKeys().clear()
    }
}
