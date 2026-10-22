package com.gtrainer

import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalMutationImportTest {
    private fun fixture(block: (HistoryStore, (Long) -> SignalMutationCoverage) -> Unit) {
        val directory = Files.createTempDirectory("signal-mutation-import-")
        val path = directory.resolve("history.sqlite")
        try {
            HistoryStore(path).use { history ->
                // Reads are deliberately performed on a second connection, never the owner DB.
                val capture: (Long) -> SignalMutationCoverage = { after ->
                    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use {
                        SignalMutationStore(it).capture(after,0)
                    }
                }
                block(history, capture)
            }
        } finally {
            Files.list(directory).use { paths -> paths.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(directory)
        }
    }

    private fun record(id: String, date: String, vararg values: Pair<String, Double>) = WellnessRecord(
        "garmin", id, date, setOf("garmin"), values.associate { (field, value) ->
            field to Measurement(value, when(field) { "sleepSecs" -> "seconds";"weight" -> "kg";else -> "ms" })
        }
    )

    private fun import(history: HistoryStore, vararg records: WellnessRecord) {
        history.completeWellness(ReadResult(ReadStatus.SUCCESS, records.toList()), Instant.parse("2025-02-01T00:00:00Z"))
    }

    @Test fun `imports record sorted signal field union and identical replay adds nothing`() = fixture { history, capture ->
        import(history, record("daily", "2025-01-01", "hrv" to 42.0, "sleepSecs" to 28000.0, "hrvSDNN" to 51.0))
        val first = capture(0)
        assertTrue(first.complete())
        assertEquals(listOf("hrv", "hrvSDNN", "sleepSecs"), first.mutations.single().fields)
        repeat(2) { import(history, record("daily", "2025-01-01", "hrv" to 42.0, "sleepSecs" to 28000.0, "hrvSDNN" to 51.0)) }
        assertEquals(first.through, capture(first.through).through)
        assertTrue(capture(first.through).complete())
    }

    @Test fun `whole record corrections including provenance and weight log signal ancestry`() = fixture { history, capture ->
        import(history, record("daily", "2025-01-01", "hrv" to 42.0))
        val initial = capture(0).through
        val corrected = record("daily", "2025-01-01", "hrv" to 42.0, "weight" to 70.0).copy(upstreamSources = setOf("garmin", "bridge"))
        import(history, corrected)
        val coverage = capture(initial)
        assertTrue(coverage.complete())
        assertEquals(listOf("hrv"), coverage.mutations.single().fields)
        assertEquals("2025-01-01", coverage.mutations.single().oldDate)
    }

    @Test fun `date moves and field removals retain both sides and unrelated weight does not`() = fixture { history, capture ->
        import(history, record("move", "2025-01-01", "hrv" to 42.0, "sleepSecs" to 28000.0))
        val beforeMove = capture(0).through
        import(history, record("move", "2025-01-02", "hrv" to 43.0))
        val moved = capture(beforeMove).mutations.single()
        assertEquals("2025-01-01", moved.oldDate)
        assertEquals("2025-01-02", moved.newDate)
        assertEquals(listOf("hrv", "sleepSecs"), moved.fields)
        val beforeWeight = capture(0).through
        import(history, record("weight-only", "2025-01-03", "weight" to 70.0))
        assertEquals(beforeWeight, capture(beforeWeight).through)
        assertTrue(capture(beforeWeight).complete())
    }

    @Test fun `removal preserves sequence high water and old checkpoint fails after reimport`() = fixture { history, capture ->
        import(history, record("daily", "2025-01-01", "hrv" to 42.0))
        val checkpoint = capture(0).through
        history.removeImports()
        val cleared = capture(checkpoint)
        assertFalse(cleared.complete())
        assertEquals(checkpoint, cleared.through)
        import(history, record("daily", "2025-01-01", "hrv" to 42.0))
        val reimported = capture(checkpoint)
        assertFalse(reimported.complete()) // A pre-removal epoch cannot be resumed by identical reimport.
        assertEquals(checkpoint + 1, reimported.through)
        assertEquals(1L,reimported.removalGeneration)
    }

    @Test fun `64 character source is accepted and invalid batch rolls back records and mutations`() = fixture { history, capture ->
        val source = "s".repeat(64)
        val longSource = record("long-source", "2025-01-01", "hrv" to 42.0).copy(source = source)
        import(history, longSource)
        val accepted = capture(0)
        assertTrue(accepted.complete())
        assertEquals(source, accepted.mutations.single().source)
        kotlin.test.assertFailsWith<IllegalArgumentException> { import(history,longSource.copy(source="s".repeat(65))) }

        val invalid = record("invalid", "not-a-date", "hrv" to 50.0)
        kotlin.test.assertFails { import(history, record("would-rollback", "2025-01-02", "hrv" to 43.0), invalid) }
        assertEquals(accepted.through, capture(accepted.through).through)
        assertTrue(capture(accepted.through).complete())
    }
}
