package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Instant

/** Installed metadata, never a browser-supplied endpoint or a usefulness qualification. */
@Serializable
internal data class OwnerUatProfileBinding(val profile: String, val promptSha256s: List<String>, val schemaSha256: String) {
    init {
        require(profile in ScopedProfileAdmission.profiles)
        require(promptSha256s.size in 1..4 && promptSha256s==promptSha256s.distinct().sorted())
        (promptSha256s + schemaSha256).forEach(::requireOwnerUatDigest)
    }
}

@Serializable
internal data class OwnerUatInstallationBinding(
    val applicationSha256: String,
    val resource: PersonalResourceIdentity,
    val runtimeSha256: String,
    val modelSha256: String,
    val resourcePolicySha256: String,
    val profiles: List<OwnerUatProfileBinding>,
) {
    init {
        listOf(applicationSha256, runtimeSha256, modelSha256, resourcePolicySha256).forEach(::requireOwnerUatDigest)
        require(resource.selection.providerKind == "local")
        require(profiles.size in 1..3 && profiles.map { it.profile }.distinct().size == profiles.size)
    }
}

/** A separately versioned policy. Existing ordinary/personal/development limits are untouched. */
@Serializable
internal data class OwnerUatJobLimits(
    val generationMillis: Long = 900_000,
    val totalMillis: Long = 1_200_000,
    val startupMillis: Long = 120_000,
    val validationMillis: Long = 30_000,
    val cleanupReserveMillis: Long = 60_000,
    val maxAttempts: Int = 1,
    val contextTokens: Int = 4096,
    val outputTokens: Int = 1536,
    val marginTokens: Int = 256,
)

@Serializable
internal data class OwnerUatAcceptedPolicy(
    val binding: OwnerUatInstallationBinding,
    val ownerAcknowledged: Boolean,
    val acceptedUtc: String,
    val schemaVersion: Int = 1,
    val policyId: String = "owner-local-uat-v1",
    val limits: OwnerUatJobLimits = OwnerUatJobLimits(),
) {
    init {
        require(schemaVersion == 1 && policyId == "owner-local-uat-v1")
        require(limits == OwnerUatJobLimits())
        Instant.parse(acceptedUtc)
    }
    override fun toString() = "OwnerUatAcceptedPolicy([REDACTED])"
}

@Serializable
data class OwnerUatPolicyStatus(val state: String, val policySha256: String? = null, val armed: Boolean = false)

/** Policy acceptance only. It cannot create a provider, observe/start/stop a runtime or claim qualification.
 * Production installation supplies compiled/current identities, not request data. A fresh instance is disarmed. */
class OwnerUatAdmission internal constructor(
    private val policyFile: Path?,
    private val currentBinding: () -> OwnerUatInstallationBinding?,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var armedDigest: String? = null
    private data class Inspection(val state: String, val policy: OwnerUatAcceptedPolicy? = null, val digest: String? = null)

    @Synchronized
    internal fun arm(expectedPolicySha256: String): Boolean {
        val current = observePolicy()
        if (current.state != "accepted" || current.digest != expectedPolicySha256) return false
        armedDigest = current.digest
        return true
    }

    @Synchronized
    internal fun disarm() { armedDigest = null }

    /** Lock order matches consent publication: policy monitor before HistoryStore/JDBC. */
    @Synchronized
    internal fun publishIfPermitted(binding: ScopedExecutionBinding,publish: ()->ConnectedReviewStatus): ConnectedReviewStatus? =
        if(policyRefusal(binding,observePolicy())==null) publish() else null

    @Synchronized
    internal fun inspect(profile: String): ScopedProfileAdmissionStatus = status(profile, observePolicy())

    @Synchronized
    internal fun inspectExecution(binding: ScopedExecutionBinding): ScopedProfileAdmissionStatus {
        val current = observePolicy()
        val status = status(binding.profile, current)
        return status.copy(selectedProviderModel = binding.providerSelection,
            blockingReasons = (status.blockingReasons + listOfNotNull(policyRefusal(binding, current))).distinct())
    }

    private fun status(profile: String, current: Inspection): ScopedProfileAdmissionStatus {
        val base = ScopedProfileAdmission.inspect(profile)
        val accepted = current.state == "accepted"
        val permitted = current.policy?.binding?.profiles?.any { it.profile == profile } == true
        val armed = accepted && armedDigest != null && armedDigest == current.digest
        val reasons = when {
            !base.supported -> listOf("unsupported_profile")
            !accepted -> listOf("owner_uat_${current.state}", "runtime_admission_closed")
            !permitted -> listOf("owner_uat_profile_not_accepted", "runtime_admission_closed")
            !armed -> listOf("owner_uat_rearm_required", "runtime_admission_closed")
            else -> listOf("runtime_admission_closed")
        }
        return base.copy(
            selectedProviderModel = current.policy?.binding?.resource?.selection,
            executionMode = "owner_local_uat",
            ownerUat = OwnerUatPolicyStatus(current.state, current.digest, armed),
            blockingReasons = reasons,
            // Installed policy is not runtime acceptance. Actual owned execution is connected separately.
            executionAllowed = false,
            policyLimits = if (accepted) base.policyLimits.copy(attemptTimeoutMillis = 900_000,
                totalTimeoutMillis = 1_200_000, maxAttempts = 1,
                maxHostedCostPerAttemptUsd = "0", maxHostedCostTotalUsd = "0") else base.policyLimits,
        )
    }

    /** Separate from qualification. Call again before sending/publishing, not only at preparation. */
    @Synchronized
    internal fun policyRefusal(binding: ScopedExecutionBinding): String? = policyRefusal(binding, observePolicy())

    @Synchronized
    internal fun runtimeRefusal(resource: PersonalResourceIdentity): String? {
        val current=observePolicy()
        if(current.state!="accepted") return "owner_uat_${current.state}"
        if(armedDigest==null || armedDigest!=current.digest) return "owner_uat_rearm_required"
        return if(current.policy?.binding?.resource==resource) null else "owner_uat_runtime_changed"
    }

    @Synchronized
    internal fun supervisionRegistration(origin: OwnerUatClockSample): OwnerUatJobRegistration? {
        val current=observePolicy()
        if(current.state!="accepted" || armedDigest==null || armedDigest!=current.digest) return null
        return OwnerUatJobRegistration(origin,requireNotNull(current.digest),requireNotNull(current.policy).binding.runtimeSha256,
            OwnerUatProcessIdentity.capture())
    }

    private fun policyRefusal(binding: ScopedExecutionBinding, current: Inspection): String? {
        if (current.state != "accepted") return "owner_uat_${current.state}"
        if (armedDigest == null || armedDigest != current.digest) return "owner_uat_rearm_required"
        val installed = requireNotNull(current.policy).binding
        if (binding.providerSelection.providerKind != "local") return "owner_uat_local_only"
        if (binding.providerSelection != installed.resource.selection) return "provider_selection_mismatch"
        val contract = installed.profiles.singleOrNull { it.profile == binding.profile }
            ?: return "owner_uat_profile_not_accepted"
        if (AnalysisClaims.hash(binding.exactPrompt.systemInstructions) !in contract.promptSha256s)
            return "owner_uat_prompt_changed"
        return null
    }

    /** Observed invalidation only clears this process's arming latch; no runtime or stored claim is mutated. */
    private fun observePolicy(): Inspection {
        val current = readPolicy()
        if (current.state != "accepted" || current.digest != armedDigest) armedDigest = null
        return current
    }

    private fun readPolicy(): Inspection {
        val path = policyFile ?: return Inspection("policy_missing")
        return try {
            if (!Files.exists(path, NOFOLLOW_LINKS)) return Inspection("policy_missing")
            val owner = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            require(Files.isDirectory(path.parent, NOFOLLOW_LINKS))
            for (file in listOf(path.parent, path)) {
                require(Files.getOwner(file, NOFOLLOW_LINKS) == owner)
                require(Files.getPosixFilePermissions(file, NOFOLLOW_LINKS).all { it.name.startsWith("OWNER_") })
            }
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.isRegularFile && before.size() in 1..32_768)
            val bytes = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { it.readNBytes(32_769) }
            require(bytes.size in 1..32_768)
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.fileKey() == after.fileKey() && before.lastModifiedTime() == after.lastModifiedTime() && before.size() == after.size())
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            // Duplicate keys and unknown fields are rejected, as in the existing profile contracts.
            val policy = StrictProfileDraftJson.decode<OwnerUatAcceptedPolicy>(text)
            if (!policy.ownerAcknowledged) return Inspection("policy_revoked")
            if (Instant.parse(policy.acceptedUtc) > clock.instant()) return Inspection("policy_invalid")
            val installed = currentBinding() ?: return Inspection("installation_unavailable")
            if (policy.binding != installed) return Inspection("binding_changed")
            Inspection("accepted", policy, AnalysisClaims.hash(ScopedEvidencePreparation.json.encodeToString(policy)))
        } catch (_: Exception) {
            Inspection("policy_invalid")
        }
    }
}

private fun requireOwnerUatDigest(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }

internal val OWNER_UAT_REFUSAL_REASONS=setOf("owner_uat_policy_missing","owner_uat_policy_revoked",
    "owner_uat_policy_invalid","owner_uat_installation_unavailable","owner_uat_binding_changed",
    "owner_uat_rearm_required","owner_uat_local_only","owner_uat_profile_not_accepted","owner_uat_prompt_changed","owner_uat_runtime_changed")
