package com.gtrainer

import java.sql.Connection

internal data class ScopedRoutingRevisions(val source: Long,val planReason: Long)

/** SQL only. HistoryStore owns every transaction and synchronization boundary. */
internal class ScopedQueueStore(private val connection: Connection) {
    fun seed() { connection.createStatement().use { it.executeUpdate("INSERT INTO scoped_queue_metadata(singleton,source_revision,plan_reason_revision) VALUES(1,0,0)") } }
    fun revisions(): ScopedRoutingRevisions = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT source_revision,plan_reason_revision FROM scoped_queue_metadata WHERE singleton=1").use { rows ->
            check(rows.next());ScopedRoutingRevisions(rows.getLong(1),rows.getLong(2))
        }
    }
    fun sourceChanged()=advance("source_revision")
    fun planReasonChanged()=advance("plan_reason_revision")
    private fun advance(column: String) {
        check(column in setOf("source_revision","plan_reason_revision"))
        connection.createStatement().use { check(it.executeUpdate("UPDATE scoped_queue_metadata SET $column=$column+1 WHERE singleton=1 AND $column<9223372036854775807")==1) }
    }
    fun ownSnapshot(snapshotId: String,jobId: String) {
        validateContextId(snapshotId);validateContextId(jobId)
        connection.prepareStatement("INSERT INTO scoped_queue_snapshot_ownership(snapshot_id,job_id) VALUES(?,?)").use {
            it.setString(1,snapshotId);it.setString(2,jobId);it.executeUpdate()
        }
    }
    /** Do not sweep manual snapshots, current queue outcomes or sources still referenced by executions. */
    fun pruneOwnedSnapshots(retainedSnapshotIds: Set<String>) {
        require(retainedSnapshotIds.size<=257);retainedSnapshotIds.forEach(::validateContextId)
        val candidates=connection.createStatement().use { statement ->
            statement.executeQuery("SELECT o.snapshot_id FROM scoped_queue_snapshot_ownership o WHERE NOT EXISTS(SELECT 1 FROM scoped_execution_bindings b WHERE b.source_snapshot_id=o.snapshot_id) AND NOT EXISTS(SELECT 1 FROM adaptation_results a WHERE a.snapshot_id=o.snapshot_id) LIMIT 1001").use { rows ->
                buildList { while(rows.next()) { require(size<1000);add(rows.getString(1)) } }
            }
        }
        candidates.filter { it !in retainedSnapshotIds }.forEach { id ->
            connection.prepareStatement("DELETE FROM scoped_evidence_snapshots WHERE snapshot_id=?").use { it.setString(1,id);it.executeUpdate() }
        }
    }
}
