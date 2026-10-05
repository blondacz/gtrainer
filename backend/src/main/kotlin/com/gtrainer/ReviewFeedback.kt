package com.gtrainer

import kotlinx.serialization.Serializable

private val allowedReviewRatings = setOf("useful", "not_useful")

@Serializable
data class ReviewFeedbackRequest(
    val rating: String? = null,
    val correction: String? = null,
) {
    init {
        require(rating == null || rating in allowedReviewRatings) { "Invalid usefulness rating" }
        correction?.let { validateContextText(it, 4000, required = true) }
        require(rating != null || correction != null) { "A rating or correction is required" }
    }

    override fun toString() = "ReviewFeedbackRequest([REDACTED])"
}
