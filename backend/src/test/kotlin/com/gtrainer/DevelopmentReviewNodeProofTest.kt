package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class DevelopmentReviewNodeProofTest {
    private val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))

    @Test fun `default unconfigured and mismatched-node verifiers never contact transport`() = runBlocking {
        assertNull(DevelopmentNodeTerminationVerifier().verify(boundary))
        assertNull(DevelopmentNodeTerminationVerifier("other-node") { fail("wrong node contacted") }.verify(boundary))
    }

    @Test fun `only exact-container or changed-boot evidence proves termination`() = runBlocking {
        val verifier = DevelopmentNodeTerminationVerifier(boundary.nodeName) { envelope().toByteArray() }
        val proof = assertNotNull(verifier.verify(boundary))
        assertEquals(boundary, proof.expected)
        assertEquals(DevelopmentNodeProofResult.CONTAINER_EXITED, proof.result)
        val reboot = assertNotNull(DevelopmentNodeTerminationVerifier(boundary.nodeName) {
            envelope("HOST_REBOOTED", "00000000-0000-0000-0000-000000000002").toByteArray()
        }.verify(boundary))
        assertEquals(DevelopmentNodeProofResult.HOST_REBOOTED, reboot.result)
    }

    @Test fun `missing malformed duplicate oversized foreign and contradictory evidence fail closed`() = runBlocking {
        val invalid = listOf("", "{}", "{", "synthetic-private-marker", "x".repeat(4097),
            envelope(container = "b".repeat(64)), envelope("UNCONFIRMED"), envelope("CONTAINER_RUNNING"),
            envelope("HOST_REBOOTED"), envelope(boot = "00000000-0000-0000-0000-000000000002"),
            envelope(boot = "malformed-boot"), envelope().replace("\"result\":", "\"result\":\"UNCONFIRMED\",\"result\":"),
            envelope().replace("gtrainer-development-node-proof-v1", "untrusted-proof-v1"),
            envelope().dropLast(1) + ",\"private\":\"synthetic-private-marker\"}")
        invalid.forEach { value ->
            assertNull(DevelopmentNodeTerminationVerifier(boundary.nodeName) { value.toByteArray() }.verify(boundary))
        }
        assertNull(DevelopmentNodeTerminationVerifier(boundary.nodeName) { byteArrayOf(0xff.toByte()) }.verify(boundary))
        assertNull(DevelopmentNodeTerminationVerifier(boundary.nodeName) { null }.verify(boundary))
        assertNull(DevelopmentNodeTerminationVerifier(boundary.nodeName) { throw IllegalStateException("synthetic-private-marker") }.verify(boundary))
    }

    @Test fun `coroutine cancellation propagates rather than manufacturing proof`() = runBlocking {
        assertFailsWith<CancellationException> {
            DevelopmentNodeTerminationVerifier(boundary.nodeName) { throw CancellationException("synthetic-marker") }.verify(boundary)
        }
        assertNull(withTimeoutOrNull(20) {
            DevelopmentNodeTerminationVerifier(boundary.nodeName) { awaitCancellation() }.verify(boundary)
        })
    }

    @Test fun `read-only operation has fixed arguments and hard bounds with no caller-controlled command`() {
        val request = DevelopmentNodeProofRequest(boundary)
        assertEquals(listOf("sudo", "-n", "/usr/local/libexec/gtrainer-development-node-verifier", "verify", boundary.containerId, boundary.bootId), request.arguments)
        assertEquals(15_000, request.timeoutMillis)
        assertEquals(4096, request.maximumBytes)
        listOf("-a", "x;touch marker", "synthetic-private-marker", "a".repeat(65)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { boundary.copy(containerId = invalid) }
        }
        assertFailsWith<IllegalArgumentException> { boundary.copy(nodeName = "-oProxyCommand=evil") }
        assertFailsWith<IllegalArgumentException> { boundary.copy(bootId = "malformed-boot") }
    }

    @Test fun `ssh configuration pins identity host keys and fixed operation without contacting node`() = runBlocking {
        val directory = Files.createTempDirectory("node-proof-config")
        val identity = secureFile(directory.resolve("identity"))
        val hosts = secureFile(directory.resolve("known-hosts"))
        val config = DevelopmentNodeSshConfiguration(boundary.nodeName, "synthetic.invalid", "development_verifier", 2222, identity, hosts)
        val request = DevelopmentNodeProofRequest(boundary)
        var commands = 0
        val transport = DevelopmentNodeSshTransport(config) { command, timeout, maximum ->
            commands++
            assertEquals(15_000, timeout)
            assertEquals(4096, maximum)
            assertEquals("/usr/bin/ssh", command.first())
            assertTrue(command.contains("StrictHostKeyChecking=yes"))
            assertTrue(command.contains("IdentityAgent=none"))
            assertTrue(command.contains("ClearAllForwardings=yes"))
            assertTrue(command.contains("ControlMaster=no"))
            assertEquals(listOf("--", "synthetic.invalid") + request.arguments, command.takeLast(8))
            assertEquals(1, command.count { it == boundary.containerId })
            envelope().toByteArray()
        }
        assertNotNull(DevelopmentNodeTerminationVerifier(boundary.nodeName, transport).verify(boundary))
        assertEquals(1, commands)
        assertEquals("DevelopmentNodeSshConfiguration([REDACTED])", config.toString())
        assertFailsWith<IllegalArgumentException> { config.command(request.copy(boundary = boundary.copy(nodeName = "other-node"))) }
        Files.setPosixFilePermissions(identity, PosixFilePermissions.fromString("rw-r--r--"))
        assertNull(DevelopmentNodeTerminationVerifier(boundary.nodeName, transport).verify(boundary))
        assertEquals(1, commands)
    }

    @Test fun `ssh arguments reject option injection and untrusted filesystem references`() {
        val identity = Path.of("/synthetic/identity")
        val hosts = Path.of("/synthetic/known-hosts")
        listOf("-oProxyCommand=bad", "host;touch marker", "host\nother").forEach { host ->
            assertFailsWith<IllegalArgumentException> { DevelopmentNodeSshConfiguration(boundary.nodeName, host, "verifier", 22, identity, hosts) }
        }
        assertFailsWith<IllegalArgumentException> { DevelopmentNodeSshConfiguration(boundary.nodeName, "synthetic.invalid", "root;evil", 22, identity, hosts) }
        assertFailsWith<IllegalArgumentException> { DevelopmentNodeSshConfiguration(boundary.nodeName, "synthetic.invalid", "verifier", 0, identity, hosts) }
        assertFailsWith<IllegalArgumentException> { DevelopmentNodeSshConfiguration(boundary.nodeName, "synthetic.invalid", "verifier", 22, Path.of("relative-key"), hosts) }
        assertFailsWith<IllegalArgumentException> { DevelopmentNodeSshConfiguration(boundary.nodeName, "synthetic.invalid", "verifier", 22, Path.of("/path with quotes/identity"), hosts) }
    }

    private fun secureFile(path: Path): Path = Files.createFile(path,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))

    private fun envelope(result: String = "CONTAINER_EXITED", boot: String = boundary.bootId, container: String = boundary.containerId) =
        """{"profile":"gtrainer-development-node-proof-v1","containerId":"$container","observedBootId":"$boot","result":"$result"}"""
}
