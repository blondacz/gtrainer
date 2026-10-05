package com.gtrainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.serialization.encodeToString
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DevelopmentReviewStoreTest {
    private val config = DevelopmentReviewConfiguration("127.0.0.1", java.nio.file.Path.of("/var/tmp/dev-review.db"),
        "ollama", "qwen3", "test", "qwen3:8b", "a".repeat(64), "0.6.0", 2048, 1536, 3, 23, 0.2,
        DevelopmentReviewPolicyBinding(900_000, 1_840_000))

    @Test fun curatedFixturesMatchSourceAndPreviewSurvivesRestartWithoutAliases() {
        val bytes = requireNotNull(javaClass.getResourceAsStream(DevelopmentReviewFixtures.RESOURCE)).use { it.readBytes() }
        val bundled = DevelopmentReviewFixtures.load(bytes)
        val source = Files.readAllBytes(java.nio.file.Path.of("../benchmarks/connected-review/cases-v2.json"))
        val parser = Json { ignoreUnknownKeys = true }
        val sourceCases = parser.parseToJsonElement(source.toString(Charsets.UTF_8)).jsonObject.getValue("cases").jsonArray
        assertEquals(9, bundled.size)
        sourceCases.forEach { item ->
            val obj=item.jsonObject; assertEquals(obj.getValue("packet"), parser.parseToJsonElement(bundled.getValue(obj.getValue("id").jsonPrimitive.content).packetJson))
        }
        val directory = Files.createTempDirectory("dev-review")
        val path = directory.resolve("isolated.sqlite")
        val first = DevelopmentReviewStore(path)
        val original = first.createPreview("matched-run-recovery-context-present", config)
        val runId = first.submit(original.id).id
        assertEquals(original.packet.evidence.evidenceReportSha256, first.connectedReviewStatus(runId).evidenceDigest)
        (original.packet.context as? MutableList<AthleteContext>)?.clear()
        first.close()
        val second = DevelopmentReviewStore(path)
        val restored = assertNotNull(second.preview(original.id))
        assertNotEquals(0, restored.packet.context.size)
        assertEquals(original.promptDigest, restored.promptDigest)
        val model = Json.decodeFromString<DevelopmentReviewModelBinding>(restored.configurationJson)
        assertEquals(config.binding(), model)
        assertEquals(config.executionPolicy, restored.policy)
        assertFailsWith<IllegalArgumentException> { second.createPreview("not-a-fixture", config) }
        repeat(31) { second.createPreview("matched-run-recovery-facts-only", config) }
        assertFailsWith<IllegalStateException> { second.createPreview("matched-run-recovery-facts-only", config) }
        second.close()
    }

    @Test fun unrelatedDatabaseIsNotModified() {
        val parent=Files.createTempDirectory("unrelated-db")
        val db=parent.resolve("other.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$db").use { c -> c.createStatement().use { it.execute("CREATE TABLE keep_me(value TEXT)"); it.execute("PRAGMA application_id=41") } }
        val bytes=Files.readAllBytes(db); val permissions=Files.getPosixFilePermissions(parent)
        assertFailsWith<IllegalStateException> { DevelopmentReviewStore(db) }
        assertEquals(bytes.toList(), Files.readAllBytes(db).toList())
        assertEquals(permissions, Files.getPosixFilePermissions(parent))
    }

    @Test fun unrelatedWalDatabaseAndAuxiliariesAreNotModified() {
        val parent = Files.createTempDirectory("unrelated-wal-db")
        val path = parent.resolve("other.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("CREATE TABLE private_fixture(value TEXT)")
                it.execute("INSERT INTO private_fixture VALUES('synthetic-sensitive-marker')")
            }
            val before = Files.list(parent).use { paths -> paths.toList().associateWith { Files.readAllBytes(it).toList() } }
            assertFailsWith<DevelopmentReviewStorageFailure> { DevelopmentReviewStore(path) }
            val after = Files.list(parent).use { paths -> paths.toList().associateWith { Files.readAllBytes(it).toList() } }
            assertEquals(before, after)
        }
    }

    @Test fun ownerOnlyStorageAndEscapedFileNamesAreEnforcedWithoutChmodOfUnrelatedParents() {
        val parent = Files.createTempDirectory("permissions-dev-db")
        val path = parent.resolve("isolated space?#.sqlite")
        DevelopmentReviewStore(path).use { store ->
            assertEquals(0, store.previews().size)
            assertTrue(Files.getPosixFilePermissions(path).all { it.name.startsWith("OWNER_") })
        }
        val insecure = Files.createTempDirectory("insecure-dev-parent")
        val permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(insecure, permissions)
        assertFailsWith<DevelopmentReviewStorageFailure> { DevelopmentReviewStore(insecure.resolve("unused.sqlite")) }
        assertEquals(permissions, Files.getPosixFilePermissions(insecure))
        assertFalse(Files.exists(insecure.resolve("unused.sqlite")))
        val link = parent.resolve("link.sqlite")
        Files.createSymbolicLink(link, path)
        assertFailsWith<DevelopmentReviewStorageFailure> { DevelopmentReviewStore(link) }
    }

    @Test fun repositoryTransitionsAreDurableTerminalAndExpiryHidesPayload() {
        val directory=Files.createTempDirectory("dev-review-state")
        val path=directory.resolve("state.sqlite")
        var now=java.time.Instant.parse("2026-01-01T00:00:00Z")
        val clock=object:java.time.Clock() {
            override fun getZone()=java.time.ZoneOffset.UTC
            override fun withZone(zone:java.time.ZoneId)=this
            override fun instant()=now
        }
        val store=DevelopmentReviewStore(path,clock)
        val preview=store.createPreview("matched-run-recovery-facts-only",config)
        val run = store.submit(preview.id)
        val claimed=assertNotNull(store.claimConnectedReview(run.id))
        assertEquals("RUNNING",claimed.state)
        val packet=store.connectedReviewPacket(run.id)
        val digest=claimed.evidenceDigest
        val refs=packet.context.map { ContextRevisionRefV1(it.contextId,it.revision) }
        val draft=InterpretationDraftV1("connected-review-v1", emptyList(), emptyList())
        val published=store.publishConnectedReview(run.id,ValidatedConnectedReviewOutput(InterpretationContractV1.encodeDraft(draft)),digest,refs,claimed.provider,claimed.model,claimed.contractVersion)
        assertEquals("PUBLISHED",published.state)
        assertEquals("PUBLISHED",store.publishConnectedReview(run.id,ValidatedConnectedReviewOutput("replacement"),digest,refs,claimed.provider,claimed.model,claimed.contractVersion).state)
        assertEquals("PUBLISHED",store.failConnectedReview(run.id,"cancelled").state)
        store.close()
        val reopened=DevelopmentReviewStore(path,clock)
        assertEquals("PUBLISHED",reopened.connectedReviewStatus(run.id).state)
        now=now.plusSeconds(24*60*60)
        assertEquals("PUBLISHED",reopened.connectedReviewStatus(run.id).state)
        assertEquals(false,reopened.connectedReviewInspection(run.id).packetAvailable)
        assertEquals(null,reopened.connectedReviewInspection(run.id).review)
        assertEquals(null,reopened.preview(preview.id))
        reopened.close()
    }

    @Test fun allNineCompiledPacketsAreExactAndDeepCopiesAreIndependent() {
        val source = Json.parseToJsonElement(Files.readString(java.nio.file.Path.of("../benchmarks/connected-review/cases-v2.json")))
            .jsonObject.getValue("cases").jsonArray
        val path = Files.createTempDirectory("all-nine-fixtures").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            source.forEach { item ->
                val fixture = item.jsonObject
                val expected = Json.decodeFromString<InterpretationPacketV1>(fixture.getValue("packet").toString())
                val preview = store.createPreview(fixture.getValue("id").jsonPrimitive.content, config)
                val run = store.submit(preview.id)
                assertEquals(expected, preview.packet)
                assertEquals(InterpretationContractV1.promptSha256(expected), preview.promptDigest)
                preview.packet.evidence.facts.forEach { fact ->
                    (fact.sources as? MutableList<String>)?.clear()
                }
                (preview.packet.evidence.facts as? MutableList<SummaryFact>)?.clear()
                (preview.packet.context as? MutableList<AthleteContext>)?.clear()
                assertEquals(expected, store.connectedReviewPacket(run.id))
                assertEquals(InterpretationContractV1.prompt(expected), store.connectedReviewInspection(run.id).exactPrompt)
            }
        }
        val bytes = Files.readAllBytes(java.nio.file.Path.of("../benchmarks/connected-review/cases-v2.json"))
        bytes[0] = ' '.code.toByte()
        assertFailsWith<IllegalArgumentException> { DevelopmentReviewFixtures.load(bytes) }
    }

    @Test fun concurrentConnectionsCannotDoubleClaimOrOverwriteTerminalOutcome() {
        val path = Files.createTempDirectory("concurrent-dev-store").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { first ->
            DevelopmentReviewStore(path).use { second ->
                val id = first.submit(first.createPreview("matched-run-recovery-facts-only", config).id).id
                val pool = Executors.newFixedThreadPool(2)
                val start = CountDownLatch(1)
                try {
                    val claims = listOf(first, second).map { store -> pool.submit(Callable { start.await(); store.claimConnectedReview(id) }) }
                    start.countDown()
                    assertEquals(1, claims.count { it.get() != null })
                    val snapshot = first.connectedReviewStatus(id)
                    val output = ValidatedConnectedReviewOutput(InterpretationContractV1.encodeDraft(
                        InterpretationDraftV1(InterpretationContractV1.PROFILE, emptyList(), emptyList())))
                    val race = CountDownLatch(1)
                    val publish = pool.submit(Callable {
                        race.await()
                        first.publishConnectedReview(id, output, snapshot.evidenceDigest, snapshot.context,
                            snapshot.provider, snapshot.model, snapshot.contractVersion)
                    })
                    val fail = pool.submit(Callable { race.await(); second.failConnectedReview(id, "cancelled") })
                    race.countDown()
                    publish.get(); fail.get()
                    val final = first.connectedReviewStatus(id)
                    assertTrue(final.state in setOf("PUBLISHED", "CANCELLED"))
                    if (final.state == "CANCELLED") assertNull(final.publishedOutput)
                    assertEquals(final, second.connectedReviewStatus(id))
                    assertNull(second.claimConnectedReview(id))
                } finally { pool.shutdownNow() }
            }
        }
    }

    @Test fun expiryPreventsClaimAndLatePublicationWithoutDeletingRowsOrReleasingCapacity() {
        val path = Files.createTempDirectory("expiry-dev-store").resolve("isolated.sqlite")
        var now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        DevelopmentReviewStore(path, clock).use { store ->
            val id = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id).id
            val running = assertNotNull(store.claimConnectedReview(id))
            val queued = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id).id
            repeat(30) { store.createPreview("matched-run-recovery-facts-only", config) }
            now = now.plusSeconds(86400)
            assertNull(store.claimConnectedReview(queued))
            val late = store.publishConnectedReview(id, ValidatedConnectedReviewOutput("late-marker"),
                running.evidenceDigest, running.context, running.provider, running.model, running.contractVersion)
            assertEquals("EXPIRED", late.state)
            assertNull(late.publishedOutput)
            assertFalse(store.connectedReviewInspection(id).packetAvailable)
            assertEquals(InterpretationPromptV1("", ""), store.connectedReviewInspection(id).exactPrompt)
            assertFailsWith<DevelopmentReviewCapacityReached> { store.createPreview("matched-run-recovery-facts-only", config) }
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM previews").use { rows -> rows.next(); assertEquals(32, rows.getInt(1)) }
            }
        }
    }

    @Test fun adversarialFieldsStayDataAndStorageFailuresExposeNoSqlOrSubmittedReason() {
        val path = Files.createTempDirectory("adversarial-dev-store").resolve("isolated.sqlite")
        val store = DevelopmentReviewStore(path)
        val malicious = "x'); DROP TABLE previews; -- private-marker"
        val preview = store.createPreview("matched-run-recovery-facts-only", config.copy(label = malicious))
        val run = store.submit(preview.id)
        assertEquals(malicious, Json.decodeFromString<DevelopmentReviewModelBinding>(preview.configurationJson).label)
        assertNull(store.preview(malicious))
        assertEquals("execution_failed", store.failConnectedReview(run.id, "private_marker").staleReason)
        assertEquals(1, store.previews().size)
        store.close()
        val error = assertFailsWith<DevelopmentReviewStorageFailure> { store.previews() }
        assertEquals("development_storage_unavailable", error.message)
        assertNull(error.cause)
    }
}
