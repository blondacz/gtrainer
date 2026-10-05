package com.gtrainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class DevelopmentReviewHealthReadersTest {
    private val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))
    private val config = DevelopmentReviewConfiguration("127.0.0.1", Path.of("/development/isolated.sqlite"),
        "ollama", "model-a", "Synthetic model", "model-a:synthetic", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1,
        DevelopmentReviewPolicyBinding(900_000, 1_840_000))
    private val token = OwnedOllamaRuntimeToken(1, 1, boundary.containerId, config.binding())
    private val request = DevelopmentHealthRequest(token, boundary, false)
    private val runtime = DevelopmentRuntimeHealth(1, 1, boundary, config.binding(), true, 0)

    @Test fun `owned-runtime observation reads version and tags only with fixed loopback and no generation`() = runBlocking {
        val paths = mutableListOf<String>()
        var statusReads = 0
        DevelopmentOllamaRuntimeHealthRead(HttpClient(MockEngine { http ->
            assertEquals("127.0.0.1", http.url.host)
            assertEquals(11434, http.url.port)
            assertEquals(HttpMethod.Get, http.method)
            paths += http.url.encodedPath
            respond(if (http.url.encodedPath == "/api/version") """{"version":"0.35.0"}""" else tags(),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), { statusReads++; runtime }, { true }).use { reader ->
            assertEquals(runtime, reader.read(request))
            assertEquals(listOf("/api/version", "/api/tags", "/api/ps"), paths)
            assertEquals(2, statusReads)
        }
    }

    @Test fun `model mismatch redirect unowned boundary and a root that exits during observation never permit execution`() = runBlocking {
        DevelopmentOllamaRuntimeHealthRead(HttpClient(MockEngine { http ->
            respond(if (http.url.encodedPath == "/api/version") """{"version":"0.35.0"}""" else tags("b".repeat(64)),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), { runtime }, { true }).use { assertFalse(assertNotNull(it.read(request)).ready) }
        var calls = 0
        DevelopmentOllamaRuntimeHealthRead(HttpClient(MockEngine {
            calls++
            respond("synthetic-private-marker", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://foreign.invalid/"))
        }), { runtime }, { true }).use {
            assertFailsWith<IllegalStateException> { it.read(request) }
            assertEquals(1, calls)
        }
        DevelopmentOllamaRuntimeHealthRead(HttpClient(MockEngine { fail("unowned runtime contacted") }), { runtime }).use {
            assertNull(it.read(request))
        }
        var statuses = 0
        DevelopmentOllamaRuntimeHealthRead(HttpClient(MockEngine { http ->
            respond(if (http.url.encodedPath == "/api/version") """{"version":"0.35.0"}""" else tags(),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), { statuses++; runtime.copy(ready = statuses == 1) }, { true }).use { assertFalse(assertNotNull(it.read(request)).ready) }
    }

    @Test fun `resource and application node reads use only their fixed validated operations`() = runBlocking {
        val directory = Files.createTempDirectory("health-reader-config")
        fun secure(name: String) = Files.createFile(directory.resolve(name),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        val ssh = DevelopmentNodeSshConfiguration(boundary.nodeName, "synthetic.invalid", "development_verifier", 22, secure("identity"), secure("known-hosts"))
        var resource = resourceEnvelope()
        var calls = 0
        val reader = DevelopmentNodeHealthReader(ssh) { command, timeout, bytes ->
            assertEquals(15_000, timeout); assertEquals(4096, bytes); calls++
            if (command.last() == "application") {
                assertEquals(listOf("sudo", "-n", "/usr/local/libexec/gtrainer-development-node-observer", "application"), command.takeLast(4))
                """{"profile":"gtrainer-development-node-health-v1","containers":[{"podId":"synthetic-pod","containerName":"app","imageIdentity":"synthetic-image","ready":true,"restartCount":3}]}""".toByteArray()
            } else {
                assertEquals(listOf("sudo", "-n", "/usr/local/libexec/gtrainer-development-node-observer", "observe", boundary.containerId, boundary.bootId), command.takeLast(6))
                resource.toByteArray()
            }
        }
        assertEquals(DevelopmentHostResources(DevelopmentHealthPolicy.HOST_HEADROOM_BYTES, DevelopmentHealthPolicy.PI_APPLIANCE_BYTES), reader.read(boundary))
        assertTrue(assertNotNull(reader.read()).ready())
        assertEquals(2, calls)
        resource = resourceEnvelope(attempt = 1)
        assertNull(reader.read(boundary))
        resource = resourceEnvelope(container = "b".repeat(64))
        assertNull(reader.read(boundary))
        resource = """{"profile":"gtrainer-development-node-health-v1","unavailable":true}"""
        assertFailsWith<kotlinx.serialization.SerializationException> { reader.read(boundary) }
    }

    @Test fun `unrelated model residency refuses without unloading or treating empty residency as stop proof`() = runBlocking {
        for (loaded in listOf("""{"models":[]}""", """{"models":[{"name":"unrelated-model","digest":"${"b".repeat(64)}"}]}""")) {
            val paths = mutableListOf<String>()
            DevelopmentOllamaRuntimeHealthRead(HttpClient(MockEngine { http ->
                paths += http.url.encodedPath
                assertEquals(HttpMethod.Get, http.method)
                val body = when (http.url.encodedPath) {
                    "/api/version" -> """{"version":"0.35.0"}"""
                    "/api/tags" -> tags()
                    else -> loaded
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }), { runtime }, { true }).use { reader ->
                assertEquals(loaded.contains("\"models\":[]"), assertNotNull(reader.read(request)).ready)
                assertEquals(listOf("/api/version", "/api/tags", "/api/ps"), paths)
            }
        }
    }

    private fun tags(digest: String = "a".repeat(64)) = """{"models":[{"name":"model-a:synthetic","digest":"$digest"}]}"""
    private fun resourceEnvelope(attempt: Int = 0, container: String = boundary.containerId) =
        """{"profile":"gtrainer-development-node-health-v1","containerId":"$container","bootId":"${boundary.bootId}","availableBytes":1073741824,"applianceMemoryLimitBytes":5368709120,"containerAttempt":$attempt}"""
}
