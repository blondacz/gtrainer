package com.gtrainer

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

private const val SYNTHETIC_PASSWORD = "synthetic-test-password-not-a-real-credential"
private const val ORIGIN = "http://127.0.0.1:8080"

private fun syntheticVerifier(): PasswordVerifier {
    val salt = ByteArray(16) { it.toByte() }
    val spec = PBEKeySpec(SYNTHETIC_PASSWORD.toCharArray(), salt, 600_000, 256)
    val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    spec.clearPassword()
    val encoder = Base64.getUrlEncoder().withoutPadding()
    return PasswordVerifier.fromEncoded("pbkdf2-sha256\$600000\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(key)}")
}

class AuthenticationTest {
    private fun auth() = SingleUserAuth(syntheticVerifier(), ORIGIN, secureCookie = false)

    @Test
    fun `login session and logout use private cookies and no cache`() = testApplication {
        application { module(auth()) }
        val response = client.post("/api/login") {
            header(HttpHeaders.Origin, ORIGIN)
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$SYNTHETIC_PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertTrue(assertNotNull(response.headers["Content-Security-Policy"]).contains("frame-ancestors 'none'"))
        val cookie = assertNotNull(response.headers[HttpHeaders.SetCookie])
        assertTrue(cookie.contains("HttpOnly", ignoreCase = true))
        assertTrue(cookie.contains("SameSite=Strict", ignoreCase = true))
        assertFalse(cookie.contains(SYNTHETIC_PASSWORD))
        val csrf = Json.parseToJsonElement(response.bodyAsText()).jsonObject["csrfToken"]!!.jsonPrimitive.content
        val sessionCookie = cookie.substringBefore(';')
        assertEquals(HttpStatusCode.OK, client.get("/api/session") {
            header(HttpHeaders.Cookie, sessionCookie)
        }.status)
        assertEquals(HttpStatusCode.NotImplemented, client.get("/api/trends") {
            header(HttpHeaders.Cookie, sessionCookie)
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/logout") {
            header(HttpHeaders.Cookie, sessionCookie)
            header(HttpHeaders.Origin, ORIGIN)
        }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/logout") {
            header(HttpHeaders.Cookie, sessionCookie)
            header(HttpHeaders.Origin, ORIGIN)
            header("X-CSRF-Token", csrf)
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/session") {
            header(HttpHeaders.Cookie, sessionCookie)
        }.status)
    }

    @Test
    fun `missing wrong or fabricated credentials never authorize`() = testApplication {
        application { module(auth()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/session").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/session") {
            header(HttpHeaders.Cookie, "gtrainer_session=${"a".repeat(43)}")
        }.status)
        val response = client.post("/api/login") {
            header(HttpHeaders.Origin, ORIGIN)
            contentType(ContentType.Application.Json)
            setBody("""{"password":"synthetic-wrong-password"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("""{"error":"login_failed"}""", response.bodyAsText())
        assertNull(response.headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `cross origin and missing origin logins are rejected`() = testApplication {
        application { module(auth()) }
        for (origin in listOf(null, "https://attacker.example")) {
            val response = client.post("/api/login") {
                if (origin != null) header(HttpHeaders.Origin, origin)
                setBody("""{"password":"$SYNTHETIC_PASSWORD"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertFalse(response.bodyAsText().contains(SYNTHETIC_PASSWORD))
        }
    }

    @Test
    fun `invalid or oversized login body is rejected without echo`() = testApplication {
        application { module(auth()) }
        for ((body, expected) in listOf("synthetic-invalid-json" to HttpStatusCode.BadRequest,
                                      "x".repeat(5000) to HttpStatusCode.PayloadTooLarge)) {
            val response = client.post("/api/login") {
                header(HttpHeaders.Origin, ORIGIN)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(expected, response.status)
            assertFalse(response.bodyAsText().contains(body))
        }
    }

    @Test
    fun `unconfigured login fails closed and has no session`() = testApplication {
        application { module(SingleUserAuth(null, ORIGIN, secureCookie = false)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/login") {
            header(HttpHeaders.Origin, ORIGIN)
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/session").status)
    }

    @Test
    fun `expiry restart and login attempts are bounded`() {
        val clock = object : Clock() {
            var now: Instant = Instant.parse("2020-01-01T00:00:00Z")
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now
        }
        val verifier = syntheticVerifier()
        val auth = SingleUserAuth(verifier, ORIGIN, false, clock = clock, sessionLifetime = Duration.ofMinutes(1))
        val session = assertNotNull(auth.login(SYNTHETIC_PASSWORD))
        assertNotNull(auth.session(session.token))
        assertTrue(auth.validCsrf(session, session.csrfToken))
        assertFalse(auth.validCsrf(session, "synthetic-bad-csrf"))
        assertNull(SingleUserAuth(verifier, ORIGIN, false).session(session.token))
        repeat(9) { assertNull(auth.login("synthetic-wrong")) }
        assertNull(auth.login(SYNTHETIC_PASSWORD))
        clock.now = clock.now.plus(Duration.ofMinutes(11))
        assertNull(auth.session(session.token))
        assertNotNull(auth.login(SYNTHETIC_PASSWORD))
    }

    @Test
    fun `plaintext cookie exception is only for the exact SSH loopback origin`() {
        assertFailsWith<IllegalArgumentException> { SingleUserAuth(null, "http://192.0.2.1:8080", false) }
        assertFailsWith<IllegalArgumentException> { SingleUserAuth(null, ORIGIN, true) }
        assertFailsWith<IllegalArgumentException> { SingleUserAuth(null, "https://localhost", false) }
        assertFailsWith<IllegalArgumentException> { PasswordVerifier.fromEncoded("synthetic-invalid-verifier") }
        assertTrue(SingleUserAuth(null, "https://localhost", true).secureCookie)
    }

    @Test
    fun `password and session objects redact accidental stringification`() {
        assertFalse(LoginRequest(SYNTHETIC_PASSWORD).toString().contains(SYNTHETIC_PASSWORD))
        val session = UserSession("synthetic-session-token", "synthetic-csrf-token", Instant.EPOCH)
        assertFalse(session.toString().contains(session.token))
        assertFalse(session.toString().contains(session.csrfToken))
    }
}
