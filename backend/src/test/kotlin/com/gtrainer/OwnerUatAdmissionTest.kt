package com.gtrainer

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class OwnerUatAdmissionTest {
    private val at = Instant.parse("2020-06-10T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val selection = ConnectedReviewProviderSelection("synthetic", "synthetic-model", "local")
    private val profile = ContextIntakeSuggestionContractV1.PROFILE
    private fun digest(value: String) = AnalysisClaims.hash(value)
    private fun installation(prompt: String = "synthetic instructions") = OwnerUatInstallationBinding(
        digest("synthetic app"), PersonalResourceIdentity("synthetic-domain", "synthetic-runtime", selection),
        digest("synthetic runtime"), digest("synthetic model"), digest("synthetic resources"),
        listOf(OwnerUatProfileBinding(profile, listOf(digest(prompt)), digest("synthetic schema"))),
    )
    private fun policy(binding: OwnerUatInstallationBinding) = OwnerUatAcceptedPolicy(binding, true, at.toString())
    private fun write(path: Path, policy: OwnerUatAcceptedPolicy) {
        Files.writeString(path, ScopedEvidencePreparation.json.encodeToString(policy))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }
    private fun fixture(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("synthetic-owner-uat-")
        try { block(dir) }
        finally { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun `missing policy stays closed and ordinary personal development limits are unchanged`() = fixture { dir ->
        for (path in listOf(null, dir.resolve("absent.json"))) {
            val gate = OwnerUatAdmission(path, { installation() }, clock)
            val status = gate.inspect(profile)
            assertEquals("policy_missing", status.ownerUat?.state)
            assertEquals("unqualified", status.qualificationState)
            assertFalse(status.executionAllowed)
            assertFalse(gate.arm(digest("unaccepted")))
        }
        assertTrue(ScopedProfileAdmission.inspectAll().all { it.ownerUat == null && !it.executionAllowed })
        val personal = ConnectedReviewExecutionLimits(policy = ConnectedReviewExecutionPolicy.PERSONAL)
        assertEquals(300_000L, personal.attemptTimeoutMillis)
        assertEquals(600_000L, personal.totalTimeoutMillis)
        assertEquals(2, personal.maxAttempts)
        assertEquals(900_000L, ConnectedReviewExecutionLimits.DEVELOPMENT_ATTEMPT_CAP_MILLIS)
        assertEquals(1_840_000L, ConnectedReviewExecutionLimits.DEVELOPMENT_TOTAL_CAP_MILLIS)
        assertFailsWith<IllegalArgumentException> { personal.copy(attemptTimeoutMillis = 900_000) }
        assertFailsWith<IllegalArgumentException> { personal.copy(maxAttempts = 1) }
    }

    @Test fun `accepted file and rearming never claim runtime acceptance or qualification`() = fixture { dir ->
        val file = dir.resolve("policy.json")
        val binding = installation()
        write(file, policy(binding))
        val gate = OwnerUatAdmission(file, { binding }, clock)
        val first = gate.inspect(profile)
        assertEquals("accepted", first.ownerUat?.state)
        assertEquals("owner_local_uat", first.executionMode)
        assertEquals(selection, first.selectedProviderModel)
        assertContains(first.blockingReasons, "owner_uat_rearm_required")
        assertEquals(900_000L, first.policyLimits.attemptTimeoutMillis)
        assertEquals(1_200_000L, first.policyLimits.totalTimeoutMillis)
        assertEquals(1, first.policyLimits.maxAttempts)
        assertEquals("0", first.policyLimits.maxHostedCostTotalUsd)
        val digest = assertNotNull(first.ownerUat?.policySha256)
        assertFalse(gate.arm(this.digest("wrong acknowledgement")))
        assertTrue(gate.arm(digest))
        val armed = gate.inspect(profile)
        assertEquals(true, armed.ownerUat?.armed)
        assertEquals("unqualified", armed.qualificationState)
        assertEquals("closed_not_accepted", armed.runtimeAdmissionState)
        assertEquals(listOf("runtime_admission_closed"), armed.blockingReasons)
        assertFalse(armed.executionAllowed)
        // Inspecting an accepted policy does not arm a new process or create a runtime.
        val restarted = OwnerUatAdmission(file, { binding }, clock)
        assertEquals(false, restarted.inspect(profile).ownerUat?.armed)
        gate.disarm()
        assertContains(gate.inspect(profile).blockingReasons, "owner_uat_rearm_required")
        assertContains(gate.inspect(ScopedDescriptiveContractV1.PROFILE).blockingReasons, "owner_uat_profile_not_accepted")
        assertEquals(listOf("unsupported_profile"), gate.inspect("unknown-profile-v1").blockingReasons)
    }

    @Test fun `every installed identity and contract drift requires new acceptance`() = fixture { dir ->
        val file = dir.resolve("policy.json")
        val accepted = installation()
        var current: OwnerUatInstallationBinding? = accepted
        write(file, policy(accepted))
        val gate = OwnerUatAdmission(file, { current }, clock)
        val acknowledgement = assertNotNull(gate.inspect(profile).ownerUat?.policySha256)
        assertTrue(gate.arm(acknowledgement))
        for (changed in listOf(
            accepted.copy(applicationSha256 = digest("changed app")),
            accepted.copy(resource = accepted.resource.copy(runtimeId = "changed-runtime")),
            accepted.copy(resource = accepted.resource.copy(selection = selection.copy(modelId = "changed-model"))),
            accepted.copy(runtimeSha256 = digest("changed runtime")),
            accepted.copy(modelSha256 = digest("changed model artifact")),
            accepted.copy(resourcePolicySha256 = digest("changed resource limits")),
            accepted.copy(profiles = listOf(accepted.profiles.single().copy(promptSha256s = listOf(digest("changed prompt"))))),
            accepted.copy(profiles = listOf(accepted.profiles.single().copy(schemaSha256 = digest("changed schema")))),
        )) {
            current = changed
            assertEquals("binding_changed", gate.inspect(profile).ownerUat?.state)
            assertFalse(gate.arm(acknowledgement))
            assertFalse(gate.inspect(profile).executionAllowed)
        }
        current = null
        assertEquals("installation_unavailable", gate.inspect(profile).ownerUat?.state)
        current = accepted
        write(file, policy(accepted).copy(ownerAcknowledged = false))
        assertEquals("policy_revoked", gate.inspect(profile).ownerUat?.state)
        assertFalse(gate.arm(acknowledgement))
        Files.delete(file)
        assertEquals("policy_missing", gate.inspect(profile).ownerUat?.state)
    }

    @Test fun `strict bounded private file refuses coercion duplicates new limits and hostile metadata`() = fixture { dir ->
        val file = dir.resolve("policy.json")
        val binding = installation()
        val raw = ScopedEvidencePreparation.json.encodeToString(policy(binding))
        val gate = OwnerUatAdmission(file, { binding }, clock)
        for (bad in listOf("", " ".repeat(32_769),
            raw.dropLast(1) + ",\"unknown\":true}",
            raw.dropLast(1) + ",\"ownerAcknowledged\":true}",
            raw.replace("\"ownerAcknowledged\":true", "\"ownerAcknowledged\":\"true\""),
            raw.replace("\"maxAttempts\":1", "\"maxAttempts\":2"),
            raw.replace("\"promptSha256s\":[\"${binding.profiles.single().promptSha256s.single()}\"]",
                "\"promptSha256\":\"${binding.profiles.single().promptSha256s.single()}\""),
            raw.replace("\"providerKind\":\"local\"", "\"providerKind\":\"hosted\""),
        )) {
            Files.writeString(file, bad)
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            assertEquals("policy_invalid", gate.inspect(profile).ownerUat?.state)
        }
        write(file, policy(binding).copy(acceptedUtc = at.plusSeconds(1).toString()))
        assertEquals("policy_invalid", gate.inspect(profile).ownerUat?.state)
        write(file, policy(binding))
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"))
        assertEquals("policy_invalid", gate.inspect(profile).ownerUat?.state)
        write(file, policy(binding))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-x---"))
        assertEquals("policy_invalid", gate.inspect(profile).ownerUat?.state)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        val link = dir.resolve("link.json")
        Files.createSymbolicLink(link, file)
        assertEquals("policy_invalid", OwnerUatAdmission(link, { binding }, clock).inspect(profile).ownerUat?.state)
        assertEquals("policy_invalid", OwnerUatAdmission(dir, { binding }, clock).inspect(profile).ownerUat?.state)
        val hashes=ScopedDescriptiveContractV1.promptVariantSha256s()
        assertFailsWith<IllegalArgumentException> { OwnerUatProfileBinding(ScopedDescriptiveContractV1.PROFILE,hashes.reversed(),digest("schema")) }
        assertFailsWith<IllegalArgumentException> { OwnerUatProfileBinding(ScopedDescriptiveContractV1.PROFILE,listOf(hashes.first(),hashes.first()),digest("schema")) }
    }

    @Test fun `restoring a previously invalidated policy or installation never restores arming`() = fixture { dir ->
        val file = dir.resolve("policy.json")
        val accepted = installation()
        var installed = accepted
        write(file, policy(accepted))
        val gate = OwnerUatAdmission(file, { installed }, clock)
        val acknowledgement = assertNotNull(gate.inspect(profile).ownerUat?.policySha256)
        val invalidations: List<() -> Unit> = listOf(
            { Files.delete(file) },
            { write(file, policy(accepted).copy(ownerAcknowledged = false)) },
            { Files.writeString(file, "invalid JSON") },
            { installed = accepted.copy(modelSha256 = digest("changed model")) },
        )
        for (invalidate in invalidations) {
            assertTrue(gate.arm(acknowledgement))
            invalidate()
            assertEquals(false, gate.inspect(profile).ownerUat?.armed)
            installed = accepted
            write(file, policy(accepted))
            val restored = gate.inspect(profile)
            assertEquals("accepted", restored.ownerUat?.state)
            assertEquals(false, restored.ownerUat?.armed)
            assertContains(restored.blockingReasons, "owner_uat_rearm_required")
        }
    }

    @Test fun `manual policy check binds local selection prompt and current file without becoming qualification`() = fixture { dir ->
        HistoryStore(dir.resolve("history.db")).use { store ->
            val note = store.createIntake(ContextIntakeRequest("Synthetic note", "user_report", "synthetic", "2020-06-01"), at)
            val execution = ScopedExecutionService(store, clock).prepare(profile,
                ScopedExecutionSource(intakeId = note.intakeId, intakeRevision = note.revision), selection).binding
            val originalState = store.captureScopedExecution(execution.executionId).stored.state
            val installed = installation(execution.exactPrompt.systemInstructions)
            val file = dir.resolve("policy.json")
            write(file, policy(installed))
            val gate = OwnerUatAdmission(file, { installed }, clock)
            assertEquals("owner_uat_rearm_required", gate.policyRefusal(execution))
            assertTrue(gate.arm(assertNotNull(gate.inspect(profile).ownerUat?.policySha256)))
            assertNull(gate.policyRefusal(execution))
            assertEquals("owner_uat_local_only", gate.policyRefusal(execution.copy(providerSelection = selection.copy(providerKind = "hosted"))))
            assertEquals("provider_selection_mismatch", gate.policyRefusal(execution.copy(providerSelection = selection.copy(modelId = "other"))))
            assertEquals("owner_uat_prompt_changed", gate.policyRefusal(execution.copy(
                exactPrompt = execution.exactPrompt.copy(systemInstructions = "changed instructions"))))
            val inspecting = ScopedExecutionService(store, clock, gate)
            assertEquals(true, inspecting.inspect(execution.executionId).admission.ownerUat?.armed)
            assertEquals(originalState, store.captureScopedExecution(execution.executionId).stored.state)
            for ((selected, reason) in listOf(selection.copy(providerKind = "hosted") to "owner_uat_local_only",
                selection.copy(modelId = "other") to "provider_selection_mismatch")) {
                val other = ScopedExecutionService(store, clock).prepare(profile,
                    ScopedExecutionSource(intakeId = note.intakeId, intakeRevision = note.revision), selected)
                val state = other.state
                val inspected = inspecting.inspect(other.binding.executionId)
                assertEquals(selected, inspected.admission.selectedProviderModel)
                assertContains(inspected.admission.blockingReasons, reason)
                assertFalse(inspected.admission.executionAllowed)
                assertEquals(state, inspected.state)
            }
            val promptChanged = execution.copy(exactPrompt = execution.exactPrompt.copy(systemInstructions = "changed instructions"))
            assertContains(gate.inspectExecution(promptChanged).blockingReasons, "owner_uat_prompt_changed")
            write(file, policy(installed).copy(ownerAcknowledged = false))
            assertEquals("owner_uat_policy_revoked", gate.policyRefusal(execution))
            assertFalse(gate.inspect(profile).executionAllowed)
            write(file, policy(installed))
            assertEquals("owner_uat_rearm_required", gate.policyRefusal(execution))
        }
    }

    @Test fun `normal authenticated admission inspection cannot arm the policy`() = fixture { dir ->
        val file = dir.resolve("policy.json")
        val binding = installation()
        write(file, policy(binding))
        val gate = OwnerUatAdmission(file, { binding }, clock)
        val origin = "http://127.0.0.1:8080"
        testApplication {
            application { module(SingleUserAuth(syntheticVerifier(), origin, false, true), history = null,
                reviews = ReviewInterpretationService(emptyList()), ownerUat = gate) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/review-profiles").status)
            val login = client.post("/api/login") {
                header(HttpHeaders.Origin, origin); setBody("""{"password":"$SYNTHETIC_PASSWORD"}""")
            }
            val cookie = assertNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
            repeat(2) {
                val response = client.get("/api/review-profiles?profile=$profile") { header(HttpHeaders.Cookie, cookie) }
                assertEquals(HttpStatusCode.OK, response.status)
                val status = Json.decodeFromString<List<ScopedProfileAdmissionStatus>>(response.bodyAsText()).single()
                assertEquals("accepted", status.ownerUat?.state)
                assertEquals(false, status.ownerUat?.armed)
                assertFalse(status.executionAllowed)
            }
            assertEquals(false, gate.inspect(profile).ownerUat?.armed)
        }
    }
}
