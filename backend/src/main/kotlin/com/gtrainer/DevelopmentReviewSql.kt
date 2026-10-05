package com.gtrainer

/** Schema and named statements belong to this repository, never to routes or worker logic. */
internal object DevelopmentReviewSql {
    const val APPLICATION_ID = 1196576849
    const val SCHEMA = "gtrainer-development-review-v3"
    const val READ_APP_ID = "PRAGMA application_id"
    const val CREATE_APP_ID = "PRAGMA application_id=1196576849"
    const val JOURNAL_MODE = "PRAGMA journal_mode=DELETE"
    const val FOREIGN_KEYS = "PRAGMA foreign_keys=ON"
    const val CREATE_META = "CREATE TABLE development_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)"
    const val MARKER = "INSERT INTO development_meta(key,value) VALUES('schema','gtrainer-development-review-v3')"
    const val MARKER_QUERY = "SELECT value FROM development_meta WHERE key='schema'"
    val CREATE_PREVIEWS = """
        CREATE TABLE previews(
            id TEXT PRIMARY KEY, case_id TEXT NOT NULL,
            packet TEXT NOT NULL, prompt TEXT NOT NULL,
            provider TEXT NOT NULL, model TEXT NOT NULL, binding TEXT NOT NULL, policy TEXT NOT NULL,
            slot_key TEXT NOT NULL, input_revision INTEGER NOT NULL, input_digest TEXT NOT NULL,
            prompt_digest TEXT NOT NULL, evidence_digest TEXT NOT NULL, context TEXT NOT NULL,
            created_utc TEXT NOT NULL, expires_utc TEXT NOT NULL
        )
    """.trimIndent()
    val CREATE_TASKS = """
        CREATE TABLE tasks(
            queue_order INTEGER PRIMARY KEY AUTOINCREMENT,
            id TEXT NOT NULL UNIQUE, preview_id TEXT NOT NULL UNIQUE REFERENCES previews(id),
            created_utc TEXT NOT NULL, expires_utc TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('QUEUED','RUNNING','SUCCESSFUL','FAILED','CANCELLED','SUPERSEDED')),
            execution_state TEXT NOT NULL DEFAULT 'NOT_STARTED',
            replacement_id TEXT, stale_reason TEXT, output TEXT
        )
    """.trimIndent()
    val CREATE_EVENTS = """
        CREATE TABLE events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            kind TEXT NOT NULL, created_utc TEXT NOT NULL, task_id TEXT
        )
    """.trimIndent()
    val CREATE_SCHEMA = listOf(CREATE_META, CREATE_PREVIEWS, CREATE_TASKS, CREATE_EVENTS, MARKER, CREATE_APP_ID)
    const val COUNT = "SELECT COUNT(*) FROM previews"
    val INSERT = """
        INSERT INTO previews(id,case_id,packet,prompt,provider,model,binding,policy,slot_key,input_revision,input_digest,
            prompt_digest,evidence_digest,context,created_utc,expires_utc)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
    """.trimIndent()
    val INSERT_TASK = """
        INSERT INTO tasks(id,preview_id,created_utc,expires_utc,state)
        VALUES(?,?,?,?,'QUEUED')
    """.trimIndent()
    const val INSERT_EVENT = "INSERT INTO events(kind,created_utc,task_id) VALUES(?,?,?)"
    private const val COLUMNS = "id,case_id,packet,prompt,provider,model,binding,policy,prompt_digest," +
        "evidence_digest,context,slot_key,input_revision,input_digest,created_utc,expires_utc"
    const val SELECT = "SELECT $COLUMNS FROM previews WHERE id=?"
    const val SELECT_ALL = "SELECT $COLUMNS FROM previews ORDER BY created_utc,id"
    val CLAIM = """
        UPDATE tasks SET state='RUNNING',execution_state='STARTING'
        WHERE id=? AND state='QUEUED' AND julianday(expires_utc)>julianday(?)
            AND NOT EXISTS(SELECT 1 FROM tasks WHERE execution_state IN ('STARTING','EXECUTING','STOPPING','UNKNOWN'))
    """.trimIndent()
    val PUBLISH = """
        UPDATE tasks SET state='SUCCESSFUL',output=?
        WHERE id=? AND state='RUNNING' AND julianday(expires_utc)>julianday(?)
    """.trimIndent()
    const val FAIL = "UPDATE tasks SET state=?,stale_reason=? WHERE id=? AND state IN ('QUEUED','RUNNING')"
    const val RUN = "SELECT * FROM tasks WHERE id=?"
    const val RUN_FOR_PREVIEW = "SELECT * FROM tasks WHERE preview_id=?"
    const val RUNS = "SELECT * FROM tasks ORDER BY queue_order"
    val SUPERSEDE = """
        UPDATE tasks SET state='SUPERSEDED',stale_reason='queued_input_superseded',replacement_id=?
        WHERE state='QUEUED' AND id<>? AND preview_id IN (
            SELECT id FROM previews WHERE slot_key=? AND input_revision<? AND input_digest<>?
        )
    """.trimIndent()
    val REPLACED = "SELECT id FROM tasks WHERE replacement_id=? AND state='SUPERSEDED'"
    val RUNNING_PREDECESSORS = """
        SELECT tasks.id FROM tasks JOIN previews ON previews.id=tasks.preview_id
        WHERE tasks.state='RUNNING' AND previews.slot_key=? AND previews.input_revision<? AND previews.input_digest<>?
    """.trimIndent()
    val HAS_NEWER_INPUT = """
        SELECT EXISTS(
            SELECT 1 FROM tasks JOIN previews ON previews.id=tasks.preview_id
            WHERE previews.slot_key=? AND previews.input_revision>? AND previews.input_digest<>?
        )
    """.trimIndent()
}
