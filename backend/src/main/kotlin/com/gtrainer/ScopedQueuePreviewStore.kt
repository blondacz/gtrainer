package com.gtrainer

import java.sql.Connection

/** Automatic-copy retention only. HistoryStore owns transactions; no GET calls this helper. */
internal class ScopedQueuePreviewStore(private val connection: Connection) {
    fun own(executionId: String,jobId: String) {
        validateContextId(executionId);validateContextId(jobId)
        connection.prepareStatement("INSERT INTO scoped_queue_preview_ownership(execution_id,job_id) VALUES(?,?)").use {
            it.setString(1,executionId);it.setString(2,jobId);it.executeUpdate()
        }
    }

    fun prune(retainedJobIds: Set<String>) {
        require(retainedJobIds.size<=281);retainedJobIds.forEach(::validateContextId)
        // Runtime rows deliberately survive execution deletion. Never delete a copy with any
        // actual attempt, or unresolved physical ownership (even before its first send).
        val candidates=connection.createStatement().use { statement ->
            statement.executeQuery("SELECT o.execution_id,o.job_id FROM scoped_queue_preview_ownership o " +
                "WHERE NOT EXISTS(SELECT 1 FROM personal_review_runtime_claims r WHERE r.execution_id=o.execution_id AND (r.phase<>'STOP_CONFIRMED' OR r.attempts>0)) LIMIT 1001").use { rows ->
                buildList { while(rows.next()) { check(size<1000);add(rows.getString(1) to rows.getString(2)) } }
            }
        }
        candidates.filter { it.second !in retainedJobIds }.forEach { (id,_) ->
            ScopedExecutionStore(connection).delete(id)
        }
    }
}
