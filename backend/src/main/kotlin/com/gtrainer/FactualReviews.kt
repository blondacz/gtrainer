package com.gtrainer

import kotlinx.serialization.Serializable

@Serializable
data class ReviewPeriods(val previousOldest: String, val previousNewest: String, val currentOldest: String, val currentNewest: String) {
    override fun toString() = "ReviewPeriods([REDACTED])"
}

@Serializable
data class ReviewFact(val id: String, val state: String, val text: String, val comparison: MetricComparison,
                      val supportingMetrics: List<SummaryFact>) {
    override fun toString() = "ReviewFact([REDACTED])"
}

@Serializable
data class ReviewGroup(val id: String, val periods: ReviewPeriods, val coverage: String, val facts: List<ReviewFact>) {
    override fun toString() = "ReviewGroup([REDACTED])"
}

@Serializable
data class ReviewFocus(val kind: String, val groupId: String, val factIds: List<String>, val text: String) {
    override fun toString() = "ReviewFocus([REDACTED])"
}

@Serializable
data class FactualReview(val evidenceReportSha256: String, val requestedFocus: String, val groups: List<ReviewGroup>,
                         val focuses: List<ReviewFocus>, val reason: String, val unavailable: List<UnavailableMetric>,
                         val limitations: List<String>, val sourceStatus: List<CategoryStatus>,
                         val profile: String, val applicationGenerated: Boolean) {
    override fun toString() = "FactualReview([REDACTED])"
}

/** Complete app facts, not the smaller installed ModelPacket or the frozen synthetic model contract.
 * Focus selection is an explicit deterministic rule, never AI output or a model fallback.
 */
internal object FactualReviews {
    val supportedFocuses = setOf("daily_combined", "activity_balance", "wellness", "missing_wellness")

    fun prepare(input: AnalysisInput, requestedFocus: String = "daily_combined"): FactualReview {
        require(requestedFocus in supportedFocuses)
        require(input.evidenceReportSha256.matches(Regex("[a-f0-9]{64}")))
        require(input.facts.map { it.evidenceId }.distinct().size == input.facts.size)
        require(input.comparisons.map { it.sport to it.key }.distinct().size == input.comparisons.size)
        val supports = input.facts.associateBy { Triple(it.period, it.sport, it.metric) }
        require(supports.size == input.facts.size && supports.size == input.comparisons.size * 2)
        val facts = input.comparisons.mapIndexed { index, comparison ->
            val before = supports.getValue(Triple("previous", comparison.sport, comparison.key))
            val after = supports.getValue(Triple("current", comparison.sport, comparison.key))
            require(before.unit == comparison.unit && after.unit == comparison.unit && before.aggregation == after.aggregation)
            require(before.value == comparison.previousValue && after.value == comparison.currentValue)
            require(before.sampleCount == comparison.previousSamples && after.sampleCount == comparison.currentSamples)
            require(before.label == comparison.label && after.label == comparison.label)
            require(listOfNotNull(before.value, after.value).all { it.isFinite() && it >= 0 })
            val difference = if (before.value == null || after.value == null) null else after.value - before.value
            val percent = if (difference == null || before.value == 0.0) null else difference / before.value!! * 100
            require(comparison.absoluteChange == difference?.takeIf { it.isFinite() })
            require(comparison.percentChange == percent?.takeIf { it.isFinite() })
            val state = when {
                before.value == null || after.value == null || comparison.absoluteChange == null -> "unavailable"
                comparison.absoluteChange > 0 -> "increased"
                comparison.absoluteChange < 0 -> "decreased"
                else -> "unchanged"
            }
            val description = if (state == "unavailable") "Comparison unavailable; missing measurements are unknown, not zero." else
                "Observed arithmetic direction: $state; not a confident personal trend, cause or recovery conclusion."
            val text = "${comparison.sport ?: "Wellness"}: ${comparison.label} (${before.aggregation} of populated records). " +
                "Previous: ${before.value ?: "unavailable"} ${before.unit}; current: ${after.value ?: "unavailable"} ${after.unit}. $description"
            ReviewFact("r$index", state, text, comparison, listOf(before, after))
        }
        val grouped = facts.groupBy { fact ->
            val (before, after) = fact.supportingMetrics
            val periods = ReviewPeriods(before.oldest, before.newest, after.oldest, after.newest)
            val coverage = when {
                fact.state == "unavailable" -> "unavailable"
                before.sampleCount < 2 || after.sampleCount < 2 || "sparse_comparison" in fact.comparison.flags -> "sparse"
                else -> "available"
            }
            periods to coverage
        }.entries.mapIndexed { index, (key, items) -> ReviewGroup("g$index", key.first, key.second, items) }
        val selected = select(candidates(grouped), requestedFocus)
        return FactualReview(input.evidenceReportSha256, requestedFocus, grouped, selected,
            if (selected.isEmpty()) "no_supported_focus" else "supported_focus", input.unavailable,
            input.limitations + listOf(
                "Application-generated facts and rule-selected focus, not AI interpretation. Every supplied comparison is retained, including sparse and unavailable values.",
                "Focus rules do not classify readiness, diagnose illness, prescribe workouts or estimate unavailable Garmin scores.",
                "Only populated moving-time comparisons are used for combined or sport-balance focus. Sparse comparisons remain factual but are not connected as a pattern.",
            ), input.sourceStatus, "factual-review-v1", true)
    }

    internal fun candidates(groups: List<ReviewGroup>): List<ReviewFocus> = groups.flatMap { group ->
            val activity = group.facts.filter { it.comparison.sport != null && it.comparison.key == "movingTime" }
            val wellness = group.facts.filter { it.comparison.sport == null }
            buildList {
                fun focus(kind: String, selected: List<ReviewFact>, text: String) =
                    add(ReviewFocus(kind, group.id, selected.map { it.id }, text))
                if (group.coverage == "available") {
                    if (activity.isNotEmpty()) focus("activity_pattern", activity,
                        "Recorded moving-time comparisons describe imported activity, not effort or readiness.")
                    if (wellness.isNotEmpty()) focus("wellness_pattern", wellness,
                        "Observed wellness comparisons; no recovery, illness or readiness conclusion.")
                    if (activity.isNotEmpty() && wellness.isNotEmpty()) focus("cross_metric_pattern", activity + wellness,
                        "Recorded moving time and wellness comparisons cover the same periods; no cause is established.")
                    if (activity.map { it.comparison.sport }.distinct().size >= 2) focus("sport_mix", activity,
                        "Recorded moving time across sports is not interchangeable effort or training stimulus.")
                } else if (wellness.isNotEmpty()) focus("coverage_gap", wellness,
                    "Wellness comparisons have missing values or sparse records; unknown coverage does not establish illness.")
            }
        }

    internal fun select(candidates: List<ReviewFocus>, requestedFocus: String): List<ReviewFocus> {
        require(requestedFocus in supportedFocuses)
        val kinds = when (requestedFocus) {
            "daily_combined" -> setOf("cross_metric_pattern")
            "activity_balance" -> setOf("sport_mix")
            "missing_wellness" -> setOf("coverage_gap")
            else -> if (candidates.any { it.kind == "wellness_pattern" }) setOf("wellness_pattern") else setOf("coverage_gap")
        }
        return candidates.filter { it.kind in kinds }
    }
}
