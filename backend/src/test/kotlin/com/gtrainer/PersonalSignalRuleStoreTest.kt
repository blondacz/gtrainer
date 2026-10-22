package com.gtrainer

import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalSignalRuleStoreTest {
    private fun connection()=DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
        db.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys=ON")
            s.execute("CREATE TABLE personal_signal_rule_revisions(rule_id TEXT NOT NULL,revision INTEGER NOT NULL,rule_json TEXT NOT NULL,PRIMARY KEY(rule_id,revision))")
            s.execute("CREATE TABLE personal_signal_rule_operations(operation_id TEXT PRIMARY KEY,request_json TEXT NOT NULL,rule_id TEXT NOT NULL,revision INTEGER NOT NULL,FOREIGN KEY(rule_id,revision) REFERENCES personal_signal_rule_revisions(rule_id,revision) ON DELETE CASCADE)")
        }
    }
    private fun request(op:String=UUID.randomUUID().toString(),id:String="11111111-1111-4111-8111-111111111111",expected:Int=0,enabled:Boolean=false)=PersonalSignalRuleRequest(op,id,expected,enabled,null)

    @Test fun `CAS replay exact conflict historical receipt reopen and rollback`() {
        connection().use { db ->
            val store=PersonalSignalRuleStore(db);db.autoCommit=false
            val input=request();val first=store.save(input,Instant.parse("2026-10-01T00:00:00Z"))
            assertFalse(first.replayed);assertEquals(first.rule,store.save(input,Instant.parse("2026-10-02T00:00:00Z")).rule)
            assertTrue(store.save(input,Instant.now()).replayed)
            assertFailsWith<PlanConflict> { store.save(input.copy(expectedRevision=1),Instant.now()) }
            val second=store.save(request(expected=1),Instant.parse("2026-10-03T00:00:00Z"))
            assertEquals(2,store.get(input.ruleId).revision);assertEquals(1,store.receipt(input)?.rule?.revision)
            assertFailsWith<PlanConflict> { store.save(request(expected=0),Instant.now()) }
            db.commit()
            assertEquals(second.rule,store.get(input.ruleId,2))
            store.save(request(expected=2),Instant.parse("2026-10-04T00:00:00Z"))
            db.rollback()
            assertEquals(2,store.list().single().revision)
        }
    }

    @Test fun `disabled rule may omit configuration and missing reads fail`() {
        connection().use { db ->
            val store=PersonalSignalRuleStore(db)
            val rule=store.save(request(),Instant.parse("2026-10-01T00:00:00Z")).rule
            assertFalse(rule.enabled);assertNull(rule.configuration)
            assertFailsWith<PlanNotFound> { store.get(UUID.randomUUID().toString()) }
        }
    }
}
