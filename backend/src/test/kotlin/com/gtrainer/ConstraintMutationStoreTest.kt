package com.gtrainer

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstraintMutationStoreTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        c.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        HistorySchema.createIfEmpty(c)
        c.createStatement().use { it.execute("INSERT INTO constraint_observation_metadata VALUES(1,0,0,1,1)") }
    }
    private fun ref(kind: String = "context", id: String = UUID.randomUUID().toString(), rev: Int = 1, present: Boolean = true) = ConstraintSubjectRef(kind,id,rev,present)

    @Test fun `requires manifest seed and caller owns rollback`() = db().use { c ->
        val store = ConstraintMutationStore(c)
        assertEquals(ConstraintMutationFrontier(0,0,1),store.frontier())
        c.autoCommit=false
        val a=ref()
        store.append(listOf(a))
        c.rollback()
        assertEquals(ConstraintMutationFrontier(0,0,1),store.frontier())
        assertTrue(store.inventory().isEmpty())
    }

    @Test fun `repeated opaque refs preserve every transition including ABA`() = db().use { c ->
        val store=ConstraintMutationStore(c);val id=UUID.randomUUID().toString()
        val a=ref(id=id);val b=ref(id=id,present=false)
        store.append(listOf(a));store.append(listOf(b));store.append(listOf(a))
        val captured=store.capture(0,0)
        assertTrue(captured.complete());assertEquals(listOf(1L,2L,3L),captured.records.map { it.sequence })
        assertEquals(listOf(true,false,true),captured.records.map { it.refs.single().present })
        assertEquals(a,store.inventory().single())
    }

    @Test fun `revision rewinds and invalid DTOs refuse without mutation`() = db().use { c ->
        val store=ConstraintMutationStore(c);val id=UUID.randomUUID().toString()
        store.append(listOf(ref(id=id,rev=2)));val before=store.frontier()
        assertFailsWith<IllegalArgumentException> { store.append(listOf(ref(id=id,rev=1))) }
        assertFailsWith<IllegalArgumentException> { ConstraintSubjectRef("other",id,1,true) }
        assertFailsWith<IllegalArgumentException> { ConstraintSubjectRef("context","not-uuid",1,true) }
        assertFailsWith<IllegalArgumentException> { ConstraintSubjectRef("intake",id,1001,true) }
        assertEquals(before,store.frontier())
    }

    @Test fun `capture rejects future frontier gaps and deletion ABA even without new rows`() = db().use { c ->
        val store=ConstraintMutationStore(c)
        assertFalse(store.capture(1,0).complete())
        assertTrue(store.capture(0,0).complete())
        store.append(listOf(ref()))
        c.createStatement().use { it.executeUpdate("DELETE FROM constraint_observation_mutations") }
        assertFalse(store.capture(0,0).complete())
        store.invalidateForDeletion()
        assertFalse(store.capture(1,0).complete())
        assertFalse(store.capture(0,1).complete())
        assertTrue(store.capture(1,1).complete())
    }

    @Test fun `missing singleton seed is refused rather than repaired`() = db().use { c ->
        c.createStatement().use { it.executeUpdate("DELETE FROM constraint_observation_metadata WHERE singleton=1") }
        assertFailsWith<IllegalStateException> { ConstraintMutationStore(c).frontier() }
        assertEquals(0,c.createStatement().use { it.executeQuery("SELECT count(*) FROM constraint_observation_metadata").use { r -> r.next();r.getInt(1) } })
    }

    @Test fun `deletion purges receipts but preserves tombstoned inventory and sequence`() = db().use { c ->
        val store=ConstraintMutationStore(c);val subject=ref()
        store.append(listOf(subject));store.append(listOf(subject.copy(present=false)))
        val after=store.invalidateForDeletion()
        assertEquals(2L,after.sequence);assertEquals(1L,after.deletionGeneration);assertEquals(3L,after.retainedFromSequence)
        assertEquals(false,store.inventory().single().present)
        assertTrue(store.capture(2,1).complete());assertFalse(store.capture(2,0).complete())
    }

    @Test fun `empty append is inert duplicate subjects and over-cap transitions refuse`() = db().use { c ->
        val store=ConstraintMutationStore(c);val subject=ref()
        assertEquals(store.frontier(),store.append(emptyList()))
        assertFailsWith<IllegalArgumentException> { store.append(listOf(subject,subject)) }
        assertFailsWith<IllegalArgumentException> { store.append((0..3000).map { ref() }) }
        assertEquals(0L,store.frontier().sequence)
        c.autoCommit=false
        store.append(listOf(subject));c.commit()
        store.append(listOf(subject.copy(revision=2)));c.rollback()
        assertEquals(1L,store.frontier().sequence);assertEquals(listOf(subject),store.inventory())
    }

    @Test fun `pruning and deletion never move retained frontier backwards`() = db().use { c ->
        val store=ConstraintMutationStore(c)
        c.createStatement().use { it.execute("UPDATE constraint_observation_metadata SET mutation_sequence=50000") }
        val appended=store.append(listOf(ref()))
        assertEquals(2L,appended.retainedFromSequence)
        assertTrue(store.capture(50000,0).complete());assertFalse(store.capture(49999,0).complete())
        store.invalidateForDeletion()
        val after=store.append(listOf(ref()))
        assertEquals(50002L,after.retainedFromSequence)
        assertTrue(store.capture(50001,1).complete());assertFalse(store.capture(50000,1).complete())
    }

    @Test fun `capture bounds total references and refuses oversized rows before decoding`() = db().use { c ->
        val store=ConstraintMutationStore(c)
        val refs=(1..3000).map { ref() }.sortedWith(compareBy({it.kind},{it.id}))
        val encoded=ScopedEvidencePreparation.json.encodeToString(refs)
        c.prepareStatement("INSERT INTO constraint_observation_mutations VALUES(?,?)").use { s ->
            for(sequence in 1..17) { s.setInt(1,sequence);s.setString(2,encoded);s.addBatch() };s.executeBatch()
        }
        c.createStatement().use { it.execute("UPDATE constraint_observation_metadata SET mutation_sequence=17") }
        assertFailsWith<IllegalArgumentException> { store.capture(0,0) }
        assertTrue(store.capture(1,0).complete()) // 48,000 references, complete exact suffix.
        c.createStatement().use { it.execute("DELETE FROM constraint_observation_mutations") }
        c.prepareStatement("INSERT INTO constraint_observation_mutations VALUES(17,?)").use { s ->
            s.setString(1," ".repeat(512*1024)+"[]");s.executeUpdate()
        }
        assertFailsWith<IllegalArgumentException> { store.capture(16,0) }
    }

    @Test fun `terminal generation cannot block purge or create a continuity proof`() = db().use { c ->
        val store=ConstraintMutationStore(c);val subject=ref()
        store.append(listOf(subject))
        c.createStatement().use { it.execute("UPDATE constraint_observation_metadata SET deletion_generation=${Long.MAX_VALUE}") }
        val invalid=store.invalidateForDeletion()
        assertFalse(invalid.available);assertEquals(Long.MAX_VALUE,invalid.deletionGeneration);assertEquals(1L,invalid.sequence)
        assertFalse(store.capture(1,Long.MAX_VALUE).complete())
        store.append(listOf(subject.copy(present=false)))
        assertFalse(store.inventory().single().present)
        assertEquals(invalid,store.frontier())
        store.invalidateForDeletion()
        assertEquals(invalid,store.frontier())
    }
}
