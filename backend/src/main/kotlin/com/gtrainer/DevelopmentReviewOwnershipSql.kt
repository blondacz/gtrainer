package com.gtrainer

/** Non-payload ownership records deliberately have no FK to expiring task payload. */
internal object DevelopmentReviewOwnershipSql {
    val CREATE_OWNER = """
        CREATE TABLE IF NOT EXISTS development_worker_owner(
            singleton INTEGER PRIMARY KEY CHECK(singleton=1), epoch INTEGER NOT NULL,
            generation INTEGER NOT NULL, node_name TEXT
        )
    """.trimIndent()
    val CREATE_SAFETY = """
        CREATE TABLE IF NOT EXISTS development_execution_safety(
            run_id TEXT PRIMARY KEY, owner_epoch INTEGER NOT NULL, generation INTEGER NOT NULL,
            node_name TEXT, boot_id TEXT, container_id TEXT, expires_utc TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('STARTING','EXECUTING','UNKNOWN','STOPPED_CONFIRMED'))
        )
    """.trimIndent()
    const val INSERT_OWNER = "INSERT OR IGNORE INTO development_worker_owner VALUES(1,0,0,NULL)"
    val INITIALIZE = listOf(CREATE_OWNER, CREATE_SAFETY, INSERT_OWNER)
    const val OWNER = "SELECT epoch,generation,node_name FROM development_worker_owner WHERE singleton=1"
    const val ADVANCE_EPOCH = "UPDATE development_worker_owner SET epoch=?,node_name=? WHERE singleton=1 AND epoch=?"
    const val ADVANCE_GENERATION = "UPDATE development_worker_owner SET generation=? WHERE singleton=1 AND epoch=?"
    val CAPTURE_UNBOUND = """
        INSERT OR IGNORE INTO development_execution_safety(run_id,owner_epoch,generation,expires_utc,state)
        SELECT id,0,0,expires_utc,'UNKNOWN' FROM tasks
        WHERE execution_state IN ('STARTING','EXECUTING','STOPPING','UNKNOWN')
    """.trimIndent()
    const val MARK_UNCERTAIN = "UPDATE development_execution_safety SET state='UNKNOWN' WHERE state<>'STOPPED_CONFIRMED'"
    const val INTERRUPT = "UPDATE tasks SET state='FAILED',stale_reason='worker_interrupted' WHERE state='RUNNING'"
    const val TASKS_UNKNOWN = "UPDATE tasks SET execution_state='UNKNOWN' WHERE execution_state IN ('STARTING','EXECUTING','STOPPING','UNKNOWN')"
    const val PENDING = "SELECT * FROM development_execution_safety WHERE state<>'STOPPED_CONFIRMED' ORDER BY run_id"
    const val BLOCKED = "SELECT EXISTS(SELECT 1 FROM development_execution_safety WHERE state<>'STOPPED_CONFIRMED')"
    const val INSERT_CLAIM = "INSERT INTO development_execution_safety VALUES(?,?,?,?,?,?,?,'STARTING')"
    val MATCHING = """
        SELECT state FROM development_execution_safety
        WHERE run_id=? AND owner_epoch=? AND generation=? AND node_name=? AND boot_id=? AND container_id=?
    """.trimIndent()
    val STARTED = """
        UPDATE development_execution_safety SET state='EXECUTING'
        WHERE run_id=? AND owner_epoch=? AND generation=? AND node_name=? AND boot_id=? AND container_id=? AND state='STARTING'
    """.trimIndent()
    const val TASK_EXECUTING = "UPDATE tasks SET execution_state='EXECUTING' WHERE id=? AND state='RUNNING'"
    val RECONCILE = """
        UPDATE development_execution_safety SET state='STOPPED_CONFIRMED'
        WHERE run_id=? AND owner_epoch=? AND generation=? AND node_name=? AND boot_id=? AND container_id=? AND state='UNKNOWN'
    """.trimIndent()
    const val TASK_STOPPED = "UPDATE tasks SET execution_state='STOPPED_CONFIRMED' WHERE id=?"
}
