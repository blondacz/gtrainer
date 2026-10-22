package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class ScopedActivityChoice(val activityId: String, val sport: String, val startLocal: String,
                                val startInstant: String?, val timeContext: String) {
    override fun toString() = "ScopedActivityChoice([REDACTED])"
}

@Serializable
data class ScopedReviewOptions(val schemaVersion: Int = 1, val timeZone: String?, val configurationVersion: Long,
                               val asOf: String, val today: String?, val activities: List<ScopedActivityChoice>,
                               val nextOffset: Int?, val browseRange: ScopedDateRange?) {
    override fun toString() = "ScopedReviewOptions([REDACTED])"
}

/** Called only while HistoryStore owns its connection/monitor. No independent transactions or source access. */
internal object ScopedHistoryReads {
    private const val SAVE_IDENTITY = "INSERT INTO scoped_activity_identities(identity_sha256,source,source_id) VALUES (?,?,?) ON CONFLICT(source,source_id) DO NOTHING"

    /** Only changes to these exact records are inspected; unchanged imports keep their original receipt. */
    fun provenance(connection: Connection, history: HistoryResponse): List<ScopedImportProvenance> {
        require(history.activities.size.toLong() + history.wellness.size <= 50_000)
        val identities = history.activities.map { Triple("activities", it.source, it.sourceRecordId) } +
            history.wellness.map { Triple("wellness", it.source, it.sourceRecordId) }
        return identities.distinct().groupBy { it.first }.flatMap { (category, records) ->
            records.chunked(400).flatMap { batch ->
                val byHash = batch.associateBy { scopedActivityId(it.second, it.third) }
                val receipts = mutableMapOf<String, Pair<Long, String>>()
                connection.prepareStatement("""
                    SELECT identity_sha256,revision,received_utc FROM review_import_changes
                    WHERE revision IN (
                        SELECT max(revision) FROM review_import_changes
                        WHERE category=? AND identity_sha256 IN (${batch.joinToString(",") { "?" }})
                        GROUP BY identity_sha256
                    )
                """.trimIndent()).use { statement ->
                    statement.setString(1, category)
                    byHash.keys.forEachIndexed { index, hash -> statement.setString(index + 2, hash) }
                    statement.executeQuery().use { rows ->
                        while (rows.next()) receipts[rows.getString(1)] = rows.getLong(2) to rows.getString(3)
                    }
                }
                batch.map { (_, source, id) ->
                    val receipt = receipts[scopedActivityId(source, id)]
                    ScopedImportProvenance(category, source, id, receipt?.first, receipt?.second)
                }
            }
        }.sortedWith(compareBy({ it.category }, { it.source }, { it.sourceRecordId }))
    }

    fun saveIdentity(connection: Connection, source: String, id: String) {
        connection.prepareStatement(SAVE_IDENTITY).use { saveIdentity(it, source, id) }
    }

    fun timezoneConfirmed(connection: Connection): Boolean = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT timezone_confirmed FROM scoped_review_configuration WHERE singleton=1").use { rows ->
            check(rows.next()); val confirmed = rows.getInt(1); check(confirmed in 0..1 && !rows.next()); confirmed == 1
        }
    }

    private fun saveIdentity(statement: java.sql.PreparedStatement, source: String, id: String) {
        statement.setString(1, scopedActivityId(source, id)); statement.setString(2, source); statement.setString(3, id)
        statement.executeUpdate()
    }

    fun selected(connection: Connection, ids: List<String>): Map<String, ActivityRecord> {
        require(ids.size in 1..1000 && ids.distinct().size == ids.size && ids.all { Regex("[a-f0-9]{64}").matches(it) })
        val records = linkedMapOf<String, ActivityRecord>()
        // Keep under even old SQLite variable limits. Read only explicitly selected payloads through the hash index.
        for (batch in ids.chunked(400)) {
            val placeholders = batch.joinToString(",") { "?" }
            connection.prepareStatement("SELECT i.identity_sha256,a.record_json FROM scoped_activity_identities i JOIN activities a ON a.source=i.source AND a.source_id=i.source_id WHERE i.identity_sha256 IN ($placeholders)").use { statement ->
                batch.forEachIndexed { index, id -> statement.setString(index + 1, id) }
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val id = rows.getString(1)
                        val record = Json.decodeFromString<ActivityRecord>(rows.getString(2))
                        check(scopedActivityId(record) == id) { "Activity identity persistence failed" }
                        records[id] = record
                    }
                }
            }
        }
        require(records.keys.containsAll(ids)) { "Unknown selected activity" }
        return records
    }

    fun options(connection: Connection, state: ReviewSchedulerState, asOf: Instant,
                oldest: String?, newest: String?, offset: Int): ScopedReviewOptions {
        require(offset in 0..(Int.MAX_VALUE - 201))
        require((oldest == null) == (newest == null))
        val requestedRange = if (oldest != null && newest != null) ScopedDateRange(oldest, newest).range() else null
        // A deletion/invalidation can also increment the scheduler version; only an explicit save confirms its timezone.
        if (!timezoneConfirmed(connection)) return ScopedReviewOptions(timeZone = null, configurationVersion = state.version,
            asOf = asOf.toString(), today = null, activities = emptyList(), nextOffset = null, browseRange = null)
        val zone = state.configuration.timeZone
        val today = asOf.atZone(ZoneId.of(zone)).toLocalDate()
        val range = requestedRange ?:
            TrendRange(today.minusDays(28).coerceAtLeast(LocalDate.of(1, 1, 1)), today.plusDays(1).coerceAtMost(LocalDate.of(9999, 12, 31)))
        require(range.newest <= today.plusDays(1))
        val activities = connection.prepareStatement("SELECT i.identity_sha256,a.record_json FROM activities a JOIN scoped_activity_identities i ON i.source=a.source AND i.source_id=a.source_id WHERE a.observed_date BETWEEN ? AND ? ORDER BY a.observed_date,a.source,a.source_id LIMIT 201 OFFSET ?").use { statement ->
            statement.setString(1, range.oldest.toString()); statement.setString(2, range.newest.toString()); statement.setInt(3, offset)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    val record = Json.decodeFromString<ActivityRecord>(rows.getString(2))
                    add(ScopedActivityChoice(rows.getString(1), record.sport, record.startLocal, record.startInstant, record.timeContext))
                }
            } }
        }
        return ScopedReviewOptions(timeZone = zone, configurationVersion = state.version, asOf = asOf.toString(), today = today.toString(),
            activities = activities.take(200), nextOffset = if (activities.size > 200) offset + 200 else null,
            browseRange = ScopedDateRange(range.oldest.toString(), range.newest.toString()))
    }

    /** Union reads exclude intervening dates for widely separated explicit comparisons. */
    fun periodHistory(connection: Connection, ranges: List<ScopedDateRange>, includeWellness: Boolean,
        includeActivities: Boolean = true, maximumRecords: Int = 50_000): HistoryResponse {
        require(maximumRecords in 0..50_000)
        val ordered = ranges.map { it.range() }.sortedBy { it.oldest }
        val merged = mutableListOf<Pair<LocalDate, LocalDate>>()
        for (range in ordered) {
            val prior = merged.lastOrNull()
            if (prior != null && range.oldest.toEpochDay() <= prior.second.toEpochDay() + 1)
                merged[merged.lastIndex] = prior.first to maxOf(prior.second, range.newest)
            else merged += range.oldest to range.newest
        }
        var remaining = maximumRecords
        fun rows(table: String): List<String> = buildList {
            for ((oldest, newest) in merged) {
                connection.prepareStatement("SELECT record_json FROM $table WHERE observed_date BETWEEN ? AND ? ORDER BY observed_date,source,source_id LIMIT ?").use { statement ->
                    statement.setString(1, oldest.toString()); statement.setString(2, newest.toString()); statement.setInt(3, remaining + 1)
                    statement.executeQuery().use { rows -> while (rows.next()) {
                        if (remaining == 0) throw TrendSizeLimit()
                        add(rows.getString(1)); remaining--
                    } }
                }
            }
        }
        val activities = if(includeActivities) rows("activities").map { Json.decodeFromString<ActivityRecord>(it) } else emptyList()
        val wellness = if (includeWellness) rows("wellness").map { Json.decodeFromString<WellnessRecord>(it) } else emptyList()
        return HistoryResponse(activities, wellness)
    }

    fun selectedWellness(connection: Connection, dates: List<String>, maximum: Int): List<WellnessRecord> = buildList {
        for (batch in dates.distinct().sorted().chunked(400)) {
            connection.prepareStatement("SELECT record_json FROM wellness WHERE observed_date IN (${batch.joinToString(",") { "?" }}) ORDER BY observed_date,source,source_id LIMIT ?").use { statement ->
                batch.forEachIndexed { index, date -> statement.setString(index + 1, date) }
                statement.setInt(batch.size + 1, maximum - size + 1)
                statement.executeQuery().use { rows -> while (rows.next()) {
                    if (size == maximum) throw TrendSizeLimit()
                    add(Json.decodeFromString<WellnessRecord>(rows.getString(1)))
                } }
            }
        }
    }
}
