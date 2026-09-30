package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val PASSWORD_ITERATIONS = 600_000
private const val SESSION_LIMIT = 32
private val encoder = Base64.getUrlEncoder().withoutPadding()
private val decoder = Base64.getUrlDecoder()

/** No personal identifier or health values are part of an authentication session. */
data class UserSession(val token: String, val csrfToken: String, val expiresAt: Instant)

class PasswordVerifier private constructor(
    private val iterations: Int,
    private val salt: ByteArray,
    private val expected: ByteArray,
) {
    fun matches(password: String): Boolean {
        if (password.length !in 1..256) return false
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            MessageDigest.isEqual(actual, expected)
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        fun fromEncoded(value: String): PasswordVerifier {
            try {
                val fields = value.trim().split('$')
                require(fields.size == 4 && fields[0] == "pbkdf2-sha256")
                val iterations = fields[1].toInt()
                require(iterations in PASSWORD_ITERATIONS..1_000_000)
                val salt = decoder.decode(fields[2])
                val expected = decoder.decode(fields[3])
                require(salt.size == 16 && expected.size == 32)
                return PasswordVerifier(iterations, salt, expected)
            } catch (_: IllegalArgumentException) {
                // Never put the original verifier, file contents, or password in an exception.
                throw IllegalArgumentException("Invalid authentication verifier configuration")
            }
        }
    }
}

class SingleUserAuth(
    private val verifier: PasswordVerifier?,
    val allowedOrigin: String = "https://localhost",
    val secureCookie: Boolean = true,
    val intervalsConfigured: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
    private val sessionLifetime: Duration = Duration.ofHours(8),
) {
    private val random = SecureRandom()
    private val sessions = linkedMapOf<String, UserSession>()
    private var windowStart = Instant.EPOCH
    private var attempts = 0

    init {
        require(sessionLifetime > Duration.ZERO)
        require(allowedOrigin.matches(Regex("https://[a-zA-Z0-9.:-]+")) ||
            (!secureCookie && allowedOrigin == "http://127.0.0.1:8080")) {
            "Authentication requires HTTPS or the explicit loopback SSH-tunnel origin"
        }
    }

    val configured: Boolean get() = verifier != null

    /** Serialize expensive password work and bound attempts globally, not by spoofable headers. */
    @Synchronized
    fun login(password: String): UserSession? {
        val now = clock.instant()
        if (now >= windowStart.plus(Duration.ofMinutes(10))) {
            windowStart = now
            attempts = 0
        }
        if (attempts >= 10) return null
        attempts++
        if (verifier?.matches(password) != true) return null
        sessions.entries.removeIf { now >= it.value.expiresAt }
        while (sessions.size >= SESSION_LIMIT) sessions.remove(sessions.keys.first())
        val session = UserSession(randomToken(), randomToken(), now.plus(sessionLifetime))
        sessions[session.token] = session
        return session
    }

    @Synchronized
    fun session(token: String?): UserSession? {
        if (token == null || token.length != 43) return null
        val session = sessions[token] ?: return null
        if (clock.instant() >= session.expiresAt) {
            sessions.remove(token)
            return null
        }
        return session
    }

    @Synchronized
    fun logout(token: String) { sessions.remove(token) }

    fun validCsrf(session: UserSession, token: String?): Boolean = token != null &&
        MessageDigest.isEqual(session.csrfToken.toByteArray(), token.toByteArray())

    private fun randomToken(): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))

    companion object {
        fun fromEnvironment(): SingleUserAuth {
            val verifierPath = System.getenv("GTRAINER_PASSWORD_VERIFIER_FILE")
            val verifier = verifierPath?.let {
                try {
                    val file = Path.of(it)
                    require(Files.size(file) <= 256)
                    PasswordVerifier.fromEncoded(Files.readString(file))
                } catch (_: Exception) {
                    throw IllegalArgumentException("Cannot read authentication verifier configuration")
                }
            }
            val origin = System.getenv("GTRAINER_PUBLIC_ORIGIN") ?: "https://localhost"
            val tunnel = System.getenv("GTRAINER_SSH_TUNNEL_ONLY") == "true"
            val intervalsPath = System.getenv("GTRAINER_INTERVALS_KEY_FILE")
            val intervalsConfigured = intervalsPath?.let {
                try {
                    val file = Path.of(it)
                    require(Files.size(file) in 1..256)
                    Files.readString(file).trim().isNotEmpty()
                } catch (_: Exception) {
                    throw IllegalArgumentException("Cannot read source credential configuration")
                }
            } ?: false
            return SingleUserAuth(verifier, origin, !tunnel, intervalsConfigured)
        }
    }
}
