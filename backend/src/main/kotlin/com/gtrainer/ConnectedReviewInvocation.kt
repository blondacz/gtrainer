package com.gtrainer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal sealed interface ConnectedReviewValidation {
    data class Accepted(val output: ValidatedConnectedReviewOutput,val legacyReview: InterpretationDraftV1? = null): ConnectedReviewValidation
    data object Rejected: ConnectedReviewValidation
}

/** Assembly and whole-response validation only: no provider, retry, lifecycle or admission access. */
class ConnectedReviewInvocation internal constructor(
    val profile: String, val exactPrompt: InterpretationPromptV1, val exactCorrectionPrompt: InterpretationPromptV1?,
    val consentDigest: String, private val validator: (String)->ConnectedReviewValidation,
) {
    internal fun validate(raw: String): ConnectedReviewValidation = validator(raw)
    override fun toString()="ConnectedReviewInvocation([REDACTED])"

    companion object {
        internal fun legacy(packet: InterpretationPacketV1): ConnectedReviewInvocation = ConnectedReviewInvocation(
            InterpretationContractV1.PROFILE,InterpretationContractV1.prompt(packet),null,InterpretationContractV1.promptSha256(packet),{ raw ->
                decode {
                    val draft=InterpretationContractV1.decodeDraft(raw,packet)
                    ConnectedReviewValidation.Accepted(ValidatedConnectedReviewOutput(InterpretationContractV1.encodeDraft(draft)),draft)
                }
            })

        internal fun descriptive(packet: ScopedDescriptivePacketV1): ConnectedReviewInvocation {
            val frozen=json.decodeFromString<ScopedDescriptivePacketV1>(json.encodeToString(packet))
            return profile(ScopedDescriptiveContractV1.PROFILE,ScopedDescriptiveContractV1.prompt(frozen)) { raw ->
                ScopedDescriptiveContractV1.encodeDraft(ScopedDescriptiveContractV1.decodeDraft(raw,frozen))
            }
        }
        internal fun intake(packet: ContextIntakeSuggestionPacketV1): ConnectedReviewInvocation = profile(
            ContextIntakeSuggestionContractV1.PROFILE,ContextIntakeSuggestionContractV1.prompt(packet)) { raw ->
            ContextIntakeSuggestionContractV1.encodeDraft(ContextIntakeSuggestionContractV1.decodeDraft(raw,packet))
        }
        internal fun amendment(packet: AmendmentSelectionPacketV1): ConnectedReviewInvocation {
            val frozen=json.decodeFromString<AmendmentSelectionPacketV1>(json.encodeToString(packet))
            return profile(AmendmentSelectionContractV1.PROFILE,AmendmentSelectionContractV1.prompt(frozen)) { raw ->
                AmendmentSelectionContractV1.encodeDraft(AmendmentSelectionContractV1.decodeDraft(raw,frozen))
            }
        }

        private val json=Json { encodeDefaults=true }
        private const val CORRECTION="The previous draft failed whole-response validation. Return one corrected draft using only the unchanged supplied packet and contract. Do not infer rejected content or add new facts."
        @Serializable private data class PromptBinding(val profile: String,val first: InterpretationPromptV1,val correction: InterpretationPromptV1)
        private fun profile(profile: String,prompt: InterpretationPromptV1,validator: (String)->String): ConnectedReviewInvocation {
            val correction=prompt.copy(systemInstructions=prompt.systemInstructions+"\n"+CORRECTION)
            require(json.encodeToString(correction).toByteArray(Charsets.UTF_8).size<=32*1024) { "Input budget exceeded" }
            val digest=AnalysisClaims.hash(json.encodeToString(PromptBinding(profile,prompt,correction)))
            return ConnectedReviewInvocation(profile,prompt,correction,digest,{ raw ->
                decode { ConnectedReviewValidation.Accepted(ValidatedConnectedReviewOutput(validator(raw))) }
            })
        }
        private fun decode(validate: ()->ConnectedReviewValidation): ConnectedReviewValidation = try { validate() }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(_: SerializationException) { ConnectedReviewValidation.Rejected }
        catch(_: IllegalArgumentException) { ConnectedReviewValidation.Rejected }
        catch(_: java.time.DateTimeException) { ConnectedReviewValidation.Rejected }
    }
}
