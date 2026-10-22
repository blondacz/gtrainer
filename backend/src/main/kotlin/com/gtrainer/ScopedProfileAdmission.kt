package com.gtrainer

import kotlinx.serialization.Serializable

@Serializable
data class ScopedProfilePolicyLimits(val attemptTimeoutMillis: Long,val totalTimeoutMillis: Long,val maxAttempts: Int,
    val maxPromptBytes: Int,val maxResponseBytes: Int,val maxHostedCostPerAttemptUsd: String,val maxHostedCostTotalUsd: String)

@Serializable
data class ScopedProfileAdmissionStatus(val profile: String,val supported: Boolean,val selectedProviderModel: ConnectedReviewProviderSelection?,
    val qualificationState: String,val runtimeAdmissionState: String,val executionAllowed: Boolean,val blockingReasons: List<String>,
    val policyLimits: ScopedProfilePolicyLimits)

/** Pure inspection. No reconciliation, provider construction, health probes or inherited qualification. */
internal object ScopedProfileAdmission {
    val profiles=listOf(ScopedDescriptiveContractV1.PROFILE,ContextIntakeSuggestionContractV1.PROFILE,AmendmentSelectionContractV1.PROFILE)
    fun supportedExecutionContract(profile: String)=profile==InterpretationContractV1.PROFILE || profile in profiles
    fun inspect(profile: String): ScopedProfileAdmissionStatus {
        require(profile.matches(Regex("[a-zA-Z0-9_.-]{1,128}")))
        val supported=profile in profiles
        val limits=ConnectedReviewExecutionLimits()
        return ScopedProfileAdmissionStatus(profile,supported,null,if(supported) "unqualified" else "unsupported","closed_not_accepted",false,
            if(supported) listOf("profile_not_qualified","runtime_admission_closed","provider_model_not_selected") else listOf("unsupported_profile"),
            ScopedProfilePolicyLimits(limits.attemptTimeoutMillis,limits.totalTimeoutMillis,limits.maxAttempts,limits.maxPacketBytes,
                limits.maxResponseBytes,limits.maxHostedCostPerAttemptUsd.toPlainString(),limits.maxHostedCostTotalUsd.toPlainString()))
    }
    fun inspectAll()=profiles.map(::inspect)
}
