package com.gtrainer

/** Fixed transport reasons are safe to persist; provider bodies and exception causes are not. */
internal enum class ConnectedReviewProviderFailureCode(val wireCode: String) {
    OWNERSHIP_REFUSED("runtime_ownership_unavailable"),
    BINDING_MISMATCH("runtime_binding_mismatch"),
    RESPONSE_TOO_LARGE("response_budget_exceeded"),
    PROTOCOL_OR_RUNTIME("provider_protocol_invalid"),
}

internal open class ConnectedReviewProviderFailure(val code: ConnectedReviewProviderFailureCode) :
    Exception("Connected-review provider failure: ${code.wireCode}")
