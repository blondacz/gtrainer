package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class ReviewTrigger(val id: String, val kind: String, val intervalMinutes: Int = 120,
                         val localTime: String? = null, val dayOfWeek: Int? = null,
                         val threshold: Int? = null, val period: String = "day", val settleSeconds: Int = 120) {
    init {
        require(id.matches(Regex("[a-z0-9-]{1,40}")))
        require(kind in setOf("changed_data", "daily_time", "weekly_time", "activity_arrival", "wellness_arrival", "sleep_arrival", "record_count", "daily_steps"))
        require(intervalMinutes in 1..10080 && settleSeconds in 0..3600 && period in setOf("day", "week"))
        require(localTime == null || (localTime.matches(Regex("[0-2][0-9]:[0-5][0-9]")) && LocalTime.parse(localTime).second == 0))
        require(dayOfWeek == null || dayOfWeek in 1..7)
        when (kind) {
            "daily_time" -> require(localTime != null && dayOfWeek == null && threshold == null)
            "weekly_time" -> require(localTime != null && dayOfWeek != null && threshold == null)
            "record_count", "daily_steps" -> require(threshold != null && threshold in 1..1_000_000 && localTime == null && dayOfWeek == null)
            else -> require(localTime == null && dayOfWeek == null && threshold == null)
        }
        require(kind != "daily_steps" || period == "day")
    }
}

@Serializable
data class ReviewScope(val kind: String = "day", val days: Int = 1, val sport: String? = null) {
    init {
        require(kind in setOf("day", "week", "rolling", "activity"))
        require(days in 1..90 && (sport == null || sport.matches(Regex("[a-zA-Z0-9 _-]{1,64}"))))
        require(kind != "day" || days == 1)
        require(kind != "week" || days == 7)
        require(kind != "activity" || days == 1)
    }
}

@Serializable
data class ReviewPreset(val id: String, val reviewType: String, val enabled: Boolean = false,
                        val scope: ReviewScope = ReviewScope(), val level: String = "interim",
                        val focus: String = "daily_combined", val budget: ReviewExecutionPolicy = ReviewExecutionPolicy(),
                        val triggers: List<ReviewTrigger> = emptyList()) {
    init {
        require(id.matches(Regex("[a-z0-9-]{1,40}")))
        require(reviewType in setOf("daily_combined", "after_activity", "weekly"))
        require(level in setOf("interim", "thorough") && focus in FactualReviews.supportedFocuses)
        require(triggers.size <= 8 && triggers.map { it.id }.distinct().size == triggers.size)
        // Activity identity must be supplied by a received activity, never invented by a time/count rule.
        require(scope.kind != "activity" || triggers.all { it.kind == "activity_arrival" })
    }
}

@Serializable
data class ReviewScheduleConfiguration(val enabled: Boolean = false, val paused: Boolean = false,
                                       val timeZone: String = "UTC", val presets: List<ReviewPreset> = defaultReviewPresets(),
                                       val queuePolicy: ReviewQueuePolicy = ReviewQueuePolicy()) {
    init {
        ZoneId.of(timeZone)
        require(timeZone.length <= 80 && presets.size in 1..12 && presets.map { it.id }.distinct().size == presets.size)
    }
}

fun defaultReviewPresets() = listOf(
    ReviewPreset("daily-combined", "daily_combined", triggers = listOf(ReviewTrigger("changed-data", "changed_data"), ReviewTrigger("sleep-arrival", "sleep_arrival"))),
    ReviewPreset("after-activity", "after_activity", scope = ReviewScope("activity"), triggers = listOf(ReviewTrigger("activity-arrival", "activity_arrival"))),
    ReviewPreset("weekly", "weekly", scope = ReviewScope("week", 7), level = "thorough"),
) // No fixed morning/evening time or automatic schedule is implied.

@Serializable
data class ImportedReviewChange(val revision: Long, val category: String, val observedDate: String,
                               val receivedUtc: String, val identitySha256: String, val sport: String?, val sleepChanged: Boolean,
                               val sleepAvailable: Boolean = false) {
    override fun toString() = "ImportedReviewChange([REDACTED])"
}

@Serializable
data class ReviewArrival(val observedDate: String, val activitySha256: String?, val eligibleUtc: String,
                         val identitySha256: String = "", val category: String = "", val revision: Long = 0) {
    override fun toString() = "ReviewArrival([REDACTED])"
}
@Serializable
data class ReviewTriggerMemory(val nextCheckUtc: String? = null, val lastOccurrence: String? = null,
                               val countPeriod: String? = null, val count: Int = 0, val thresholdPeriod: String? = null,
                               val arrivals: List<ReviewArrival> = emptyList(), val changed: Boolean = false,
                               val changedRevision: Long = 0, val countsByPeriod: Map<String, Int> = emptyMap()) {
    override fun toString() = "ReviewTriggerMemory([REDACTED])"
}
@Serializable
data class ReviewSchedulerState(val configuration: ReviewScheduleConfiguration = ReviewScheduleConfiguration(),
                              val version: Long = 0, val cursor: Long = 0, val modelVersion: Long? = null,
                              val modelId: String? = null, val armedUtc: String? = null,
                              val memories: Map<String, ReviewTriggerMemory> = emptyMap(),
                              val deferredChanges: List<ImportedReviewChange> = emptyList(),
                              val queue: ReviewQueueState = ReviewQueueState()) {
    override fun toString() = "ReviewSchedulerState([REDACTED])"
}

@Serializable
data class ReviewDue(val occurrenceId: String, val presetId: String, val configurationVersion: Long, val modelId: String, val selectionVersion: Long,
                     val reviewType: String, val scope: ReviewScope, val oldest: String, val newest: String,
                     val activitySha256: String?, val level: String, val focus: String, val budget: ReviewExecutionPolicy,
                     val reasons: List<String>, val dueUtc: String, val notBeforeUtc: String? = null, val timeZone: String = "UTC") {
    override fun toString() = "ReviewDue([REDACTED])"
}
@Serializable
data class ReviewTriggerStatus(val presetId: String, val triggerId: String, val kind: String, val reason: String,
                               val nextCheckUtc: String?, val nextReviewUtc: String?)
@Serializable
data class ReviewScheduleStatus(val configuration: ReviewScheduleConfiguration, val configurationVersion: Long,
                              val reason: String, val triggers: List<ReviewTriggerStatus>,
                              val due: List<ReviewDue>, val executionAvailable: Boolean = false) {
    override fun toString() = "ReviewScheduleStatus([REDACTED])"
}

@Serializable
data class ReviewScheduleUpdate(val expectedVersion: Long, val configuration: ReviewScheduleConfiguration) {
    init { require(expectedVersion >= 0) }
}
class ReviewScheduleConflict : IllegalStateException("Review configuration changed")
