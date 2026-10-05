package com.gtrainer

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data class PrivateBoundaryFixture(val value: String)

class PrivateHttpSecurityTest {
    @Test fun `shared headers preserve existing privacy policy without service wiring`() = testApplication {
        application { boundaryModule() }
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        val policy = response.headers["Content-Security-Policy"].orEmpty()
        assertTrue(policy.contains("connect-src 'self'"))
        assertTrue(policy.contains("frame-ancestors 'none'"))
    }

    @Test fun `strict bounded requests reject duplicates and unknown fields safely`() = testApplication {
        application { boundaryModule() }
        for (body in listOf(
            """{"value":"synthetic-private-marker","value":"other"}""",
            """{"value":"synthetic-private-marker","unknown":true}""",
            """{"value":"synthetic-private-marker""",
        )) {
            val response = client.post("/boundary") { contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertFalse(response.bodyAsText().contains("synthetic-private-marker"))
            assertEquals("DENY", response.headers["X-Frame-Options"])
        }
    }

    @Test fun `oversized and invalid UTF8 requests have content-free errors`() = testApplication {
        application { boundaryModule() }
        val oversized = client.post("/boundary") {
            contentType(ContentType.Application.Json)
            setBody("""{"value":"${"x".repeat(200)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        val invalid = client.post("/boundary") {
            contentType(ContentType.Application.Json)
            setBody(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("{\"error\":\"invalid_request\"}", invalid.bodyAsText())
    }

    @Test fun `valid request retains wire response`() = testApplication {
        application { boundaryModule() }
        val response = client.post("/boundary") {
            contentType(ContentType.Application.Json)
            setBody("""{"value":"synthetic"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"value\":\"synthetic\"}", response.bodyAsText())
    }

    private fun Application.boundaryModule() {
        installPrivateHttpSecurity()
        install(ContentNegotiation) { json() }
        routing {
            get("/healthz") { call.respond(HealthResponse("ok")) }
            post("/boundary") {
                try { call.respond(call.privateJson<PrivateBoundaryFixture>(128)) }
                catch (error: PrivateRequestError) { call.respond(error.status, ApiError("invalid_request")) }
            }
        }
    }
}
