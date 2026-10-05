package com.gtrainer

internal object DevelopmentReviewQueueSql {
    val INITIALIZE = listOf(
        "CREATE TABLE IF NOT EXISTS development_event_ack(event_id INTEGER PRIMARY KEY)",
        "CREATE TABLE IF NOT EXISTS development_maintenance(id TEXT PRIMARY KEY,created_utc TEXT NOT NULL,expires_utc TEXT NOT NULL,state TEXT NOT NULL,reason TEXT)",
        "CREATE TABLE IF NOT EXISTS development_summaries(id INTEGER PRIMARY KEY AUTOINCREMENT,event_id INTEGER NOT NULL,run_id TEXT,created_utc TEXT NOT NULL,expires_utc TEXT NOT NULL,summary TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS development_attempts(run_id TEXT NOT NULL,number INTEGER NOT NULL,event TEXT NOT NULL,created_utc TEXT NOT NULL,PRIMARY KEY(run_id,number))",
        "CREATE TABLE IF NOT EXISTS development_deletion_failures(preview_id TEXT PRIMARY KEY)",
        "CREATE TABLE IF NOT EXISTS development_maintenance_signal(singleton INTEGER PRIMARY KEY CHECK(singleton=1),id TEXT NOT NULL,created_utc TEXT NOT NULL,expires_utc TEXT NOT NULL,state TEXT NOT NULL,reason TEXT)"
    )
    const val EVENTS = "SELECT * FROM events WHERE id NOT IN(SELECT event_id FROM development_event_ack) ORDER BY id LIMIT 256"
    const val EVENT_EXISTS = "SELECT EXISTS(SELECT 1 FROM events WHERE id=?)"
    const val ACK = "INSERT OR IGNORE INTO development_event_ack SELECT id FROM events WHERE id=?"
    const val QUEUED = "SELECT id FROM tasks WHERE state='QUEUED' ORDER BY queue_order"
    const val MAINTENANCE_PENDING = "SELECT * FROM development_maintenance WHERE state='QUEUED' ORDER BY created_utc LIMIT 1"
    const val MAINTENANCE_COUNT = "SELECT COUNT(*) FROM development_maintenance"
    const val MAINTENANCE_INSERT = "INSERT INTO development_maintenance VALUES(?,?,?,'QUEUED',NULL)"
    const val MAINTENANCE = "SELECT * FROM development_maintenance ORDER BY created_utc,id"
    const val MAINTENANCE_FINISH = "UPDATE development_maintenance SET state=?,reason=? WHERE id=? AND state='QUEUED'"
    const val SIGNAL = "SELECT * FROM development_maintenance_signal WHERE singleton=1"
    const val SIGNAL_INSERT = "INSERT OR REPLACE INTO development_maintenance_signal VALUES(1,?,?,?,'QUEUED',NULL)"
    const val SIGNAL_FINISH = "UPDATE development_maintenance_signal SET state=?,reason=? WHERE id=? AND state='QUEUED'"
    const val SIGNAL_REARM = "UPDATE development_maintenance_signal SET state='QUEUED',reason=NULL WHERE singleton=1"
    const val SUMMARY_INSERT = "INSERT INTO development_summaries(event_id,run_id,created_utc,expires_utc,summary) VALUES(?,?,?,?,?)"
    const val SUMMARY_COUNT = "SELECT COUNT(*) FROM development_summaries WHERE event_id=?"
    const val SUMMARY_TRIM = "DELETE FROM development_summaries WHERE event_id=? AND id IN(SELECT id FROM development_summaries WHERE event_id=? ORDER BY id LIMIT 1)"
    const val SUMMARY_GLOBAL_TRIM = "DELETE FROM development_summaries WHERE id IN(SELECT id FROM development_summaries ORDER BY id DESC LIMIT -1 OFFSET 128)"
    const val SUMMARIES = "SELECT summary FROM development_summaries WHERE julianday(expires_utc)>julianday(?) ORDER BY id DESC LIMIT 128"
    const val ATTEMPT_INSERT = "INSERT OR REPLACE INTO development_attempts VALUES(?,?,?,?)"
    const val ATTEMPTS = "SELECT event FROM development_attempts WHERE run_id=? ORDER BY number"
    val DUE = """
        SELECT previews.id,tasks.id AS run_id,tasks.state AS outcome FROM previews LEFT JOIN tasks ON tasks.preview_id=previews.id
        WHERE julianday(COALESCE(tasks.expires_utc,previews.expires_utc))<=julianday(?) ORDER BY previews.created_utc LIMIT 8
    """.trimIndent()
    const val DELETE_ACK = "DELETE FROM development_event_ack WHERE event_id IN(SELECT id FROM events WHERE task_id=?)"
    const val DELETE_EVENTS = "DELETE FROM events WHERE task_id=?"
    const val DELETE_EVENT_SUMMARIES = "DELETE FROM development_summaries WHERE event_id IN(SELECT id FROM events WHERE task_id=?)"
    const val DELETE_ATTEMPTS = "DELETE FROM development_attempts WHERE run_id=?"
    const val DELETE_SUMMARIES = "DELETE FROM development_summaries WHERE run_id=?"
    const val DELETE_TASK = "DELETE FROM tasks WHERE id=?"
    const val DELETE_PREVIEW = "DELETE FROM previews WHERE id=?"
    const val DELETE_FAILURE = "DELETE FROM development_deletion_failures WHERE preview_id=?"
    const val MARK_DELETE_FAILURE = "INSERT OR IGNORE INTO development_deletion_failures VALUES(?)"
    const val DELETE_FAILED = "SELECT EXISTS(SELECT 1 FROM development_deletion_failures WHERE preview_id=?)"
    const val DUE_SUMMARIES = "DELETE FROM development_summaries WHERE id IN(SELECT id FROM development_summaries WHERE julianday(expires_utc)<=julianday(?) LIMIT 32)"
    const val DUE_MAINTENANCE = "SELECT id FROM development_maintenance WHERE julianday(expires_utc)<=julianday(?) LIMIT 8"
    const val DELETE_MAINTENANCE = "DELETE FROM development_maintenance WHERE id=?"
    const val DUE_SAFETY = "SELECT run_id FROM development_execution_safety WHERE state='STOPPED_CONFIRMED' AND julianday(expires_utc)<=julianday(?) AND run_id NOT IN(SELECT id FROM tasks) LIMIT 32"
    const val DELETE_BINDING = "DELETE FROM development_runtime_bindings WHERE run_id=?"
    const val DELETE_SAFETY = "DELETE FROM development_execution_safety WHERE run_id=? AND state='STOPPED_CONFIRMED'"
}
