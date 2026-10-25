package com.gtrainer

import kotlinx.serialization.Serializable
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.math.BigInteger
import java.util.UUID
import java.util.Locale

@Serializable
internal data class OwnerUatClockSample(
    val bootId: String,
    val implementation: String,
    val numerator: Long,
    val denominator: Long,
    val ticks: Long,
) {
    init {
        require(runCatching { UUID.fromString(bootId).toString() == bootId }.getOrDefault(false))
        require(implementation in IMPLEMENTATIONS)
        require(numerator in 1..UINT32_MAX && denominator in 1..UINT32_MAX)
        require(ticks >= 0)
    }

    fun compatible(other: OwnerUatClockSample): Boolean =
        bootId == other.bootId && implementation == other.implementation &&
            numerator == other.numerator && denominator == other.denominator && other.ticks >= ticks

    fun elapsedNanosUntil(other: OwnerUatClockSample): Long? {
        if (!compatible(other)) return null
        return BigInteger.valueOf(other.ticks - ticks).multiply(BigInteger.valueOf(numerator))
            .divide(BigInteger.valueOf(denominator)).takeIf { it <= LONG_MAX }?.toLong()
    }

    fun afterMillis(millis: Long): OwnerUatClockSample {
        require(millis >= 0)
        val delta = BigInteger.valueOf(millis).multiply(MILLION).multiply(BigInteger.valueOf(denominator))
            .add(BigInteger.valueOf(numerator - 1)).divide(BigInteger.valueOf(numerator))
        val deadline = BigInteger.valueOf(ticks).add(delta)
        require(deadline <= LONG_MAX)
        return copy(ticks = deadline.toLong())
    }

    companion object {
        private val IMPLEMENTATIONS = setOf("darwin-mach-continuous-v1", "synthetic-continuous-v1")
        private val UINT32_MAX = 0xffff_ffffL
        private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
        private val MILLION = BigInteger.valueOf(1_000_000)
    }
}

internal fun interface OwnerUatContinuousClock { fun sample(): OwnerUatClockSample }

/** FFM declarations do not establish physical clock or sleep-semantics acceptance. */
internal class MacOwnerUatContinuousClock : OwnerUatContinuousClock {
    private var previous: OwnerUatClockSample? = null
    @Synchronized override fun sample(): OwnerUatClockSample {
        val next=Native.read()
        if(previous?.compatible(next)==false) throw IllegalStateException("Owner-UAT continuous clock unavailable")
        previous=next
        return next
    }

    private object Native {
        private const val FAILURE = "Owner-UAT continuous clock unavailable"
        private val linker: Linker by lazy {
            check(System.getProperty("os.name") == "Mac OS X" &&
                System.getProperty("os.arch") in setOf("x86_64", "amd64", "aarch64"))
            Linker.nativeLinker()
        }
        private val bootSymbol by lazy { linker.defaultLookup().find("sysctlbyname").orElseThrow() }
        private val tickSymbol by lazy { linker.defaultLookup().find("mach_continuous_time").orElseThrow() }
        private val timebaseSymbol by lazy { linker.defaultLookup().find("mach_timebase_info").orElseThrow() }

        fun read(): OwnerUatClockSample = try {
            val before = bootId()
            val arena = Arena.ofConfined()
            arena.use {
                val tb = it.allocate(8, 4)
                val timebase = linker.downcallHandle(timebaseSymbol,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                check(timebase.invokeWithArguments(tb) as Int == 0)
                val numerator = tb.get(ValueLayout.JAVA_INT, 0).toLong() and 0xffff_ffffL
                val denominator = tb.get(ValueLayout.JAVA_INT, 4).toLong() and 0xffff_ffffL
                check(numerator > 0 && denominator > 0)
                val ticks = linker.downcallHandle(tickSymbol, FunctionDescriptor.of(ValueLayout.JAVA_LONG))
                    .invokeWithArguments() as Long
                check(ticks >= 0 && before == bootId())
                OwnerUatClockSample(before, "darwin-mach-continuous-v1", numerator, denominator, ticks)
            }
        } catch (_: Throwable) { throw IllegalStateException(FAILURE) }

        private fun bootId(): String {
            Arena.ofConfined().use { arena ->
                val name = arena.allocateFrom("kern.bootsessionuuid")
                val buffer = arena.allocate(64, 1)
                val length = arena.allocate(ValueLayout.JAVA_LONG)
                length.set(ValueLayout.JAVA_LONG, 0, 64L)
                val handle = linker.downcallHandle(bootSymbol, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
                check(handle.invokeWithArguments(name, buffer, length, MemorySegment.NULL, 0L) as Int == 0)
                val size = length.get(ValueLayout.JAVA_LONG, 0)
                check(size == 37L)
                val bytes = ByteArray(size.toInt()) { buffer.get(ValueLayout.JAVA_BYTE, it.toLong()) }
                check(bytes.last() == 0.toByte())
                val text = bytes.dropLast(1).toByteArray().toString(Charsets.US_ASCII).lowercase(Locale.ROOT)
                check(text.all { it.code in 0x20..0x7e } && UUID.fromString(text).toString() == text)
                return text
            }
        }
    }
}
