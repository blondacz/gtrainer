package com.gtrainer

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

class ApplicationTest {
    @Test
    fun `packaged static shell is served without opening private routes`() = testApplication {
        application { module() }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "Public shell only")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/trends").status)
    }

    @Test
    fun `health endpoint contains only availability`() = testApplication {
        application { module() }
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
    }

    @Test
    fun `private paths fail closed without exposing data`() = testApplication {
        application { module() }
        for (path in listOf("/api", "/api/trends", "/api/events", "/api/models")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("""{"error":"authentication_required"}""", response.bodyAsText())
        }
    }

    @Test
    fun `private writes also fail closed`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/sync").status)
    }

    @Test
    fun `unknown non-api route does not return private content`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.NotFound, client.get("/unknown").status)
    }
}
