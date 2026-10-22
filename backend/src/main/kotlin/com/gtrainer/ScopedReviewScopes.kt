package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

@Serializable
data class ScopedDateRange(val oldest: String, val newest: String) {
    fun range(): TrendRange {
        val a = parseDate(oldest); val b = parseDate(newest)
        require(a <= b && ChronoUnit.DAYS.between(a, b) in 0L..365L) { "Invalid date range" }
        return TrendRange(a, b)
    }
    override fun toString() = "ScopedDateRange([REDACTED])"
}

@Serializable
data class ScopedReviewRequest(
    val schemaVersion: Int = 1, val mode: String = "period", val intent: String = "review",
    val scope: String = "rolling", val anchorDate: String? = null, val days: Int? = null,
    val oldest: String? = null, val newest: String? = null, val activityIds: List<String> = emptyList(),
    val comparison: ScopedDateRange? = null, val wellnessPolicy: String? = null, val sport: String? = null,
    val focus: String = "daily_combined",
) { override fun toString() = "ScopedReviewRequest([REDACTED])" }

@Serializable
data class ScopedComparisonWindows(
    val policy: String, val comparableSelected: ScopedDateRange? = null, val comparablePrevious: ScopedDateRange? = null,
    val priorCalendarFull: ScopedDateRange? = null, val comparisonFull: ScopedDateRange? = null,
    val unmatchedSelectedDates: List<String> = emptyList(), val unmatchedPreviousDates: List<String> = emptyList(),
    val priorCalendarRemainderDates: List<String> = emptyList(), val unequalDuration: Boolean = false,
    val overlapping: Boolean = false, val reason: String? = null,
) { override fun toString() = "ScopedComparisonWindows([REDACTED])" }

@Serializable
data class ResolvedReviewScope(
    val request: ScopedReviewRequest, val asOf: String, val timeZone: String, val anchorDate: String,
    val wellnessPolicy: String, val status: String, val reasons: List<String>, val evidenceRange: ScopedDateRange?,
    val fullCalendarRange: ScopedDateRange?, val activityIds: List<String>, val activityDates: List<String>,
    val soFar: Boolean, val comparison: ScopedComparisonWindows,
    val dateBasis: String = "Activities use preserved source-local dates; wellness uses source dates; schedule timezone anchors periods, not imported timestamps.",
) { override fun toString() = "ResolvedReviewScope([REDACTED])" }

object ReviewScopeResolver {
    fun legacy(scope: ReviewScope, date: LocalDate, today: LocalDate): Pair<LocalDate, LocalDate> = when (scope.kind) {
        "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6).coerceAtMost(today) }
        "rolling" -> date.minusDays(scope.days.toLong() - 1) to date
        else -> date to date
    }

    fun resolve(request: ScopedReviewRequest, asOf: Instant, timeZone: String,
                activities: Map<String, ActivityRecord> = emptyMap(), verifiedNightSourceDate: String? = null): ResolvedReviewScope {
        require(request.schemaVersion == 1 && request.mode in setOf("period", "activities"))
        require(request.intent in setOf("review", "compare"))
        require(request.focus in FactualReviews.supportedFocuses)
        val zone = ZoneId.of(timeZone); val today = asOf.atZone(zone).toLocalDate()
        require(today.year in 1..9999)
        val policy = request.wellnessPolicy ?: if (request.mode == "period") "assessment" else "off"
        require(policy in setOf("off", "display_only", "assessment"))
        val selectedIds = request.activityIds
        require(selectedIds.size in 0..1000 && selectedIds.distinct().size == selectedIds.size && selectedIds.all { it.matches(Regex("[0-9a-f]{64}")) })
        require(request.intent != "compare" || (request.mode == "activities" && selectedIds.size >= 2 || request.mode == "period"))
        require(request.intent != "review" || request.mode != "activities" || selectedIds.isNotEmpty())
        var status = "available"; val reasons = mutableListOf<String>()
        var evidence: ScopedDateRange? = null; var full: ScopedDateRange? = null; var soFar = false
        var anchor = request.anchorDate?.let(::parseDate) ?: today
        val activityDates = mutableListOf<String>()
        if (request.mode == "activities") {
            require(request.scope == "activities" && request.anchorDate == null && request.days == null && request.oldest == null && request.newest == null && request.comparison == null && request.sport == null)
            val records = selectedIds.map { activities[it] ?: throw IllegalArgumentException("Unknown activity") }
            records.forEach { record ->
                val date = parseDate(record.startLocal.take(10)); activityDates += date.toString()
                val instant = record.startInstant?.let { Instant.parse(it) }
                if (instant != null && instant > asOf) { status = "needs_confirmation"; reasons += "activity_time_needs_confirmation" }
                if (date > today && (instant == null || instant > asOf)) { status = "needs_confirmation"; reasons += "activity_time_needs_confirmation" }
                if (date > today && instant != null && instant <= asOf) reasons += "activity_source_date_ahead_of_schedule_date"
            }
            val sortedDates = activityDates.map(::parseDate)
            if (sortedDates.isNotEmpty()) require(ChronoUnit.DAYS.between(sortedDates.min(), sortedDates.max()) <= 365) { "Activity span exceeds 366 days" }
            anchor = sortedDates.maxOrNull() ?: today
        } else {
            require(selectedIds.isEmpty())
            require(request.sport == null || request.sport.matches(Regex("[A-Za-z0-9 _-]{1,64}")))
            if (request.scope == "custom") {
                require(request.oldest != null && request.newest != null && request.anchorDate == null && request.days == null)
                val r = ScopedDateRange(request.oldest, request.newest).range(); require(r.newest <= today)
                evidence = ScopedDateRange(r.oldest.toString(), r.newest.toString()); anchor = r.newest; soFar = r.newest == today
            } else {
                require(request.oldest == null && request.newest == null)
                if (request.scope == "rolling") {
                    val n = request.days ?: throw IllegalArgumentException("Rolling days required"); require(n in setOf(7, 30, 365)); require(anchor <= today)
                    val start = anchor.minusDays((n - 1).toLong()); require(start.year >= 1)
                    val end = anchor.coerceAtMost(today); evidence = ScopedDateRange(start.toString(), end.toString()); soFar = end == today
                } else if (request.scope == "night") {
                    require(request.days == null && anchor <= today)
                    if (verifiedNightSourceDate == null) { status = "needs_confirmation"; reasons += "night_mapping_unverified" }
                    // The caller must verify this selected night's source-date association.
                    // A wake-date source can differ from the selected night's anchor; do not infer a shift here.
                    else { val d = parseDate(verifiedNightSourceDate); require(d <= today); evidence = ScopedDateRange(d.toString(), d.toString()); full = evidence }
                } else {
                    require(request.days == null)
                    val bounds = calendar(request.scope, anchor); require(anchor <= today)
                    require(bounds.first.year in 1..9999 && bounds.second.year in 1..9999) { "Calendar window exceeds supported years" }
                    val end = bounds.second.coerceAtMost(today); require(bounds.first <= end)
                    evidence = ScopedDateRange(bounds.first.toString(), end.toString())
                    if (request.scope in setOf("calendar_week", "calendar_month", "calendar_year")) full = ScopedDateRange(bounds.first.toString(), bounds.second.toString())
                    soFar = end == today
                }
            }
        }
        if (request.comparison != null) { val r = request.comparison.range(); require(request.mode == "period" && r.newest <= today) }
        val cmp = comparison(request, evidence)
        return ResolvedReviewScope(request, asOf.toString(), timeZone, anchor.toString(), policy, status, reasons.distinct(), evidence, full,
            selectedIds, activityDates, soFar, cmp)
    }

    private fun comparison(req: ScopedReviewRequest, selected: ScopedDateRange?): ScopedComparisonWindows {
        if (req.mode == "activities") return ScopedComparisonWindows(if (req.intent == "compare") "selected_sessions" else "none")
        val sr = selected?.range() ?: return ScopedComparisonWindows("unavailable", reason="selected_range_unavailable")
        val explicit = req.comparison?.range()
        if (explicit != null) return ScopedComparisonWindows("explicit", selected, ScopedDateRange(explicit.oldest.toString(), explicit.newest.toString()),
            comparisonFull=ScopedDateRange(explicit.oldest.toString(), explicit.newest.toString()), unequalDuration=sr.days != explicit.days,
            overlapping=sr.oldest <= explicit.newest && explicit.oldest <= sr.newest)
        if (req.scope.startsWith("calendar_")) {
            if (sr.oldest == LocalDate.MIN || sr.oldest.year == 1 && sr.oldest.dayOfYear == 1)
                return ScopedComparisonWindows("unavailable", reason="comparison_unavailable")
            val prevEnd = sr.oldest.minusDays(1)
            val prevStart = when(req.scope) { "calendar_year" -> prevEnd.withDayOfYear(1); "calendar_month" -> prevEnd.withDayOfMonth(1); else -> prevEnd.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            if (prevStart.year !in 1..9999) return ScopedComparisonWindows("unavailable", reason="comparison_unavailable")
            val fullDays=ChronoUnit.DAYS.between(prevStart,prevEnd).toInt()+1; val n=minOf(sr.days,fullDays)
            val cEnd=sr.oldest.plusDays(n.toLong()-1); val pEnd=prevStart.plusDays(n.toLong()-1)
            return ScopedComparisonWindows("preceding_calendar", ScopedDateRange(sr.oldest.toString(),cEnd.toString()), ScopedDateRange(prevStart.toString(),pEnd.toString()),
                priorCalendarFull=ScopedDateRange(prevStart.toString(),prevEnd.toString()), unequalDuration=sr.days != fullDays,
                unmatchedSelectedDates=dates(cEnd.plusDays(1),sr.newest), priorCalendarRemainderDates=dates(pEnd.plusDays(1),prevEnd))
        }
        if (sr.oldest.year == 1 && sr.oldest.dayOfYear == 1) return ScopedComparisonWindows("unavailable",reason="comparison_unavailable")
        val end=sr.oldest.minusDays(1)
        val start=end.minusDays(sr.days.toLong()-1); if(start.year<1) return ScopedComparisonWindows("unavailable",reason="comparison_unavailable")
        val previous = ScopedDateRange(start.toString(),end.toString())
        return ScopedComparisonWindows("preceding_equal_length", selected, previous, comparisonFull=previous)
    }
    private fun dates(a:LocalDate,b:LocalDate)=if(a>b) emptyList() else generateSequence(a){it.plusDays(1)}.takeWhile{it<=b}.map{it.toString()}.toList()
    private fun calendar(scope:String,d:LocalDate):Pair<LocalDate,LocalDate> = when(scope) {
        "day" -> d to d
        "calendar_week" -> { val a=d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); a to a.plusDays(6) }
        "calendar_month" -> YearMonth.from(d).atDay(1) to YearMonth.from(d).atEndOfMonth()
        "calendar_year" -> LocalDate.of(d.year,1,1) to LocalDate.of(d.year,12,31)
        else -> throw IllegalArgumentException("Unknown scope")
    }
}

private fun parseDate(value:String): LocalDate {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "Invalid date" }
    return LocalDate.parse(value).also { require(it.year in 1..9999) }
}
