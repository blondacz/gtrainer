package com.gtrainer

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DevelopmentReviewNodeCommandTest {
    @Test fun `bounded successful command reads bytes and deterministically closes and reaps`() = runBlocking {
        val process = FakeProcess("synthetic-proof".toByteArray())
        val result = BoundedDevelopmentNodeCommandRunner { process }.read(listOf("synthetic-command"), 1500, 4096)
        assertContentEquals("synthetic-proof".toByteArray(), result)
        assertTrue(process.closed)
        assertTrue(process.destroyed)
        assertTrue(process.reaped)
    }

    @Test fun `oversized output and nonzero exit never produce successful command bytes`() = runBlocking {
        for (process in listOf(FakeProcess(ByteArray(4097)), FakeProcess("synthetic-private-marker".toByteArray(), code = 17))) {
            assertNull(BoundedDevelopmentNodeCommandRunner { process }.read(listOf("synthetic-command"), 1500, 4096))
            assertTrue(process.destroyed)
            assertTrue(process.closed)
            assertTrue(process.reaped)
        }
    }

    @Test fun `active deadline and cancellation stop and reap stalled command`() = runBlocking {
        val deadline = FakeProcess(ByteArray(0), alive = true)
        assertNull(BoundedDevelopmentNodeCommandRunner { deadline }.read(listOf("synthetic-command"), 1050, 4096))
        assertTrue(deadline.destroyed)
        assertTrue(deadline.reaped)
        val cancelled = FakeProcess(ByteArray(0), alive = true)
        assertNull(withTimeoutOrNull(20) {
            BoundedDevelopmentNodeCommandRunner { cancelled }.read(listOf("synthetic-command"), 15000, 4096)
        })
        assertTrue(cancelled.destroyed)
        assertTrue(cancelled.reaped)
    }

    private class FakeProcess(bytes: ByteArray, private val code: Int = 0, private var alive: Boolean = false) : Process() {
        var closed = false
        var destroyed = false
        var reaped = false
        private val stream = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        override fun getInputStream(): InputStream = stream
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int { reaped = true; return code }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { reaped = true; return true }
        override fun isAlive(): Boolean = alive
        override fun exitValue(): Int = code
        override fun destroy() { destroyed = true; alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
