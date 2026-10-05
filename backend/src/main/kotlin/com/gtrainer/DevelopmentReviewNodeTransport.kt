package com.gtrainer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Operator-only configuration; never decoded from an API request or persisted run. */
internal class DevelopmentNodeSshConfiguration(val nodeName: String, private val host: String, private val user: String,
    private val port: Int, private val identityFile: Path, private val knownHostsFile: Path) {
    init {
        DevelopmentNodeBoundary(nodeName, "00000000-0000-0000-0000-000000000001", "a".repeat(64))
        require(host.matches(Regex("[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?")))
        require(user.matches(Regex("[a-z_][a-z0-9_-]{0,31}")))
        require(port in 1..65535)
        listOf(identityFile, knownHostsFile).forEach {
            require(it.isAbsolute && it.toString().matches(Regex("/[a-zA-Z0-9_./-]+")))
        }
    }

    fun command(request: DevelopmentNodeProofRequest): List<String> {
        return commandPrefix(request.boundary.nodeName) + request.arguments
    }

    fun command(request: DevelopmentNodeHealthRequest): List<String> = commandPrefix(request.nodeName) + request.arguments

    private fun commandPrefix(expectedNodeName: String): List<String> {
        require(expectedNodeName == nodeName)
        return listOf("/usr/bin/ssh", "-F", "/dev/null", "-T", "-p", port.toString(), "-l", user,
            "-i", identityFile.toString(), "-o", "UserKnownHostsFile=$knownHostsFile", "-o", "GlobalKnownHostsFile=/dev/null",
            "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no", "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
            "-o", "IdentityAgent=none", "-o", "ForwardAgent=no", "-o", "ClearAllForwardings=yes",
            "-o", "ConnectionAttempts=1", "-o", "ConnectTimeout=5", "-o", "ControlMaster=no", "-o", "ControlPath=none",
            "--", host)
    }

    fun verifyFiles() {
        val owner = identityFile.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        listOf(identityFile, knownHostsFile).forEach {
            require(Files.isRegularFile(it, NOFOLLOW_LINKS) && Files.getOwner(it, NOFOLLOW_LINKS) == owner)
            require(Files.getPosixFilePermissions(it, NOFOLLOW_LINKS).all { permission -> permission.name.startsWith("OWNER_") })
        }
    }

    override fun toString() = "DevelopmentNodeSshConfiguration([REDACTED])"
}

internal fun interface DevelopmentNodeCommandRunner {
    suspend fun read(command: List<String>, timeoutMillis: Long, maximumBytes: Int): ByteArray?
}

/** The only real invocation is fixed read-only SSH verification; default launcher never constructs this. */
internal class DevelopmentNodeSshTransport(private val config: DevelopmentNodeSshConfiguration,
    private val runner: DevelopmentNodeCommandRunner = BoundedDevelopmentNodeCommandRunner()) : DevelopmentNodeProofTransport {
    override suspend fun read(request: DevelopmentNodeProofRequest): ByteArray? {
        config.verifyFiles()
        return runner.read(config.command(request), request.timeoutMillis, request.maximumBytes)
    }
}

/** Active command deadline, not an idle housekeeping timer. Never inherits SSH agent/private app environment. */
internal class BoundedDevelopmentNodeCommandRunner(private val startProcess: (List<String>) -> Process = ::startNodeCommand) : DevelopmentNodeCommandRunner {
    override suspend fun read(command: List<String>, timeoutMillis: Long, maximumBytes: Int): ByteArray? = runInterruptible(Dispatchers.IO) {
        require(timeoutMillis in 1_001..15_000 && maximumBytes in 1..4096)
        val deadline = System.nanoTime() + (timeoutMillis - 1_000) * 1_000_000
        val process = startProcess(command)
        try {
            process.outputStream.close()
            process.inputStream.use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (System.nanoTime() < deadline) {
                    if (!drain(stream, result, buffer, deadline, maximumBytes)) return@runInterruptible null
                    if (!process.isAlive) {
                        // Drain remaining already-buffered bytes after exit without waiting on descendant-held pipes.
                        if (!drain(stream, result, buffer, deadline, maximumBytes)) return@runInterruptible null
                        return@runInterruptible if (process.exitValue() == 0) result.toByteArray() else null
                    }
                    Thread.sleep(10)
                }
                null
            }
        } finally {
            process.destroyForcibly()
            // Preserve coroutine interruption while still making a bounded reap attempt for this read-only command.
            val interrupted = Thread.interrupted()
            try { process.waitFor(1_000, TimeUnit.MILLISECONDS) }
            finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }

    private fun drain(stream: InputStream, result: ByteArrayOutputStream, buffer: ByteArray,
        deadline: Long, maximumBytes: Int): Boolean {
        while (stream.available() > 0) {
            if (System.nanoTime() >= deadline) return false
            val read = stream.read(buffer, 0, minOf(buffer.size, stream.available()))
            if (read < 0) break
            result.write(buffer, 0, read)
            if (result.size() > maximumBytes) return false
        }
        return true
    }
}

private fun startNodeCommand(command: List<String>): Process {
    val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
    builder.environment().clear()
    builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "LANG" to "C.UTF-8"))
    return builder.start()
}
