package com.gtrainer

internal object DevelopmentReviewRuntimeSql {
    val CREATE = """
        CREATE TABLE IF NOT EXISTS development_runtime_bindings(
            run_id TEXT PRIMARY KEY, owner_epoch INTEGER NOT NULL, generation INTEGER NOT NULL,
            container_id TEXT NOT NULL, keeper_pid INTEGER NOT NULL, keeper_ticks INTEGER NOT NULL, receipt TEXT
        )
    """.trimIndent()
    const val INSERT = "INSERT INTO development_runtime_bindings VALUES(?,?,?,?,?,?,NULL)"
    const val MATCH = "SELECT receipt FROM development_runtime_bindings WHERE run_id=? AND owner_epoch=? AND generation=? AND container_id=? AND keeper_pid=? AND keeper_ticks=?"
    const val HANDLE = "SELECT keeper_pid,keeper_ticks FROM development_runtime_bindings WHERE run_id=? AND owner_epoch=? AND generation=? AND container_id=?"
    val STOPPING = """
        UPDATE development_execution_safety SET state='UNKNOWN'
        WHERE run_id=? AND owner_epoch=? AND generation=? AND container_id=? AND state<>'STOPPED_CONFIRMED'
    """.trimIndent()
    const val TASK_STOPPING = "UPDATE tasks SET execution_state='STOPPING',state=CASE WHEN state='RUNNING' THEN 'FAILED' ELSE state END,stale_reason=CASE WHEN state='RUNNING' THEN 'execution_failed' ELSE stale_reason END WHERE id=?"
    const val TASK_UNKNOWN = "UPDATE tasks SET execution_state='UNKNOWN' WHERE id=? AND execution_state<>'STOPPED_CONFIRMED'"
    const val CONFIRM = "UPDATE development_execution_safety SET state='STOPPED_CONFIRMED' WHERE run_id=? AND owner_epoch=? AND generation=? AND container_id=?"
    const val RECEIPT = "UPDATE development_runtime_bindings SET receipt=? WHERE run_id=?"
    const val CANCEL = "UPDATE tasks SET state='CANCELLED',stale_reason='cancelled',execution_state=CASE WHEN execution_state='NOT_STARTED' THEN 'NOT_STARTED' ELSE 'STOPPING' END WHERE id=? AND state IN ('QUEUED','RUNNING')"
}
