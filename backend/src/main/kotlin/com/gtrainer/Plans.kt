package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private fun planId(value: String) {
    validateContextId(value)
}

private fun text(value: String, max: Int) {
    require(
        value.isNotBlank() && value == value.trim() && value.length <= max &&
            value.none { it.isISOControl() }
    )
}

private fun zone(value: String): ZoneId {
    require(value == "UTC" || value in ZoneId.getAvailableZoneIds())
    return ZoneId.of(value)
}

private fun date(value: String): LocalDate {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
    return LocalDate.parse(
        value,
        DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)
    )
}
private val localFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT)
private fun local(value: String): LocalDateTime {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}").matches(value))
    return LocalDateTime.parse(value, localFormat)
}
private fun offset(value: String?, z: ZoneId, t: LocalDateTime): ZoneOffset {
    val valid = z.rules.getValidOffsets(t)
    require(valid.isNotEmpty())
    if (value != null) return ZoneOffset.of(value).also { require(it in valid) }
    require(valid.size == 1)
    return valid.single()
}

@Serializable
data class PlanRef(val planId: String, val revision: Int) {
    init {
        planId(planId)
        require(revision in 1..1000)
    }
}
@Serializable
data class PlanAuthor(val authorKind: String, val authorAttribution: String) {
    init {
        require(authorKind in setOf("user", "coach"))
        text(authorAttribution, 160)
    }
    override fun toString() = "PlanAuthor([REDACTED])"
}
@Serializable
data class EffortScale(
    val scaleId: String,
    val name: String,
    val minimum: Double,
    val maximum: Double,
    val step: Double,
    val higherMeansMoreEffort: Boolean
) {
    init {
        planId(scaleId)
        text(name, 160)
        require(
            minimum.isFinite() && maximum.isFinite() && (maximum - minimum).isFinite() &&
                step.isFinite() && maximum > minimum && step > 0 &&
                step <= maximum - minimum && higherMeansMoreEffort
        )
    }
    override fun toString() = "EffortScale([REDACTED])"
}
@Serializable
data class PlannedTarget(
    val metric: String,
    val unit: String,
    val basis: String,
    val minimum: Double,
    val maximum: Double,
    val effortScale: EffortScale? = null
) {
    init {
        require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum)
        val r = when {
            metric == "duration" && unit == "seconds" && basis in setOf("moving", "elapsed") -> 1.0..2678400.0
            metric == "distance" && unit == "metres" && basis == "session_total" -> 0.0..10000000.0
            metric == "average_heart_rate" && unit == "beats/minute" && basis == "session_average" -> 0.0..300.0
            metric == "intervals_training_load" && unit == "Intervals.icu load" && basis == "intervals_session_load" -> 0.0..10000.0
            metric == "reported_effort" && unit == "scale_points" && basis == "reported" -> {
                val s = requireNotNull(effortScale); require(minimum >= s.minimum && maximum <= s.maximum)
                for (v in listOf(minimum, maximum)) {
                    val n = (v - s.minimum) / s.step
                    require(kotlin.math.abs(n - kotlin.math.round(n)) < 1e-8)
                }
                s.minimum..s.maximum
            }
            else -> throw IllegalArgumentException("Unsupported planned target")
        }
        if (metric != "reported_effort") require(effortScale == null)
        require(minimum >= r.start && maximum <= r.endInclusive)
        if (metric == "duration") require(minimum % 1.0 == 0.0 && maximum % 1.0 == 0.0)
    }
    override fun toString() = "PlannedTarget([REDACTED])"
}
@Serializable
data class PlanSchedule(
    val kind: String,
    val date: String? = null,
    val startsLocal: String? = null,
    val endsLocal: String? = null,
    val timeZone: String,
    val startOffset: String? = null,
    val endOffset: String? = null
) {
    init {
        val z = zone(timeZone)
        when (kind) {
            "date_only" -> {
                val localDate=date(requireNotNull(date))
                require(startsLocal == null && endsLocal == null && startOffset == null && endOffset == null)
                require(localDate.atStartOfDay(z).toLocalDate()==localDate && startInstant()<endInstant())
            }
            "timed" -> {
                require(date == null)
                val s = local(requireNotNull(startsLocal))
                val e = local(requireNotNull(endsLocal))
                require(e > s && Duration.between(s, e) <= Duration.ofDays(366))
                offset(startOffset, z, s)
                offset(endOffset, z, e)
                require(startInstant() < endInstant())
            }
            else -> throw IllegalArgumentException("Unsupported schedule kind")
        }
    }
    fun startInstant(): Instant = if (kind == "date_only") {
        date(date!!).atStartOfDay(zone(timeZone)).toInstant()
    } else {
        local(startsLocal!!)
            .atOffset(offset(startOffset, zone(timeZone), local(startsLocal!!)))
            .toInstant()
    }

    fun endInstant(): Instant = if (kind == "date_only") {
        date(date!!).plusDays(1).atStartOfDay(zone(timeZone)).toInstant()
    } else {
        local(endsLocal!!)
            .atOffset(offset(endOffset, zone(timeZone), local(endsLocal!!)))
            .toInstant()
    }

    fun firstDate(): LocalDate = if (kind == "date_only") date(date!!) else local(startsLocal!!).toLocalDate()
    fun lastDate(): LocalDate = if (kind == "date_only") date(date!!) else local(endsLocal!!).minusNanos(1).toLocalDate()
    override fun toString() = "PlanSchedule([REDACTED])"
}
@Serializable
data class PlanMoveWindow(val earliestStart: String, val latestStart: String) { override fun toString() = "PlanMoveWindow([REDACTED])" }
@Serializable
data class DurationReductionBounds(val basis: String, val minimumSeconds: Long, val maximumSeconds: Long) {
    init { require(basis in setOf("moving","elapsed") && minimumSeconds>0 && maximumSeconds<=2678400 && minimumSeconds<=maximumSeconds) }
}
@Serializable data class AmendmentPermissions(val move: PlanMoveWindow? = null, val reduceDuration: DurationReductionBounds? = null, val omit: Boolean = false)
@Serializable
data class PlannedSession(val sessionId: String, val author: PlanAuthor, val sport: String, val objective: String, val schedule: PlanSchedule, val targets: List<PlannedTarget> = emptyList(), val notes: String? = null, val permissions: AmendmentPermissions = AmendmentPermissions(), val omitted: Boolean = false) {
    init {
        planId(sessionId); text(sport,80); text(objective,160)
        notes?.let { require(it.isNotBlank() && it==it.trim() && it.length<=4000 && it.none { character -> character.isISOControl() && character!='\n' && character!='\t' }) }
        require(targets.size<=6 && targets.map { listOf(it.metric,it.unit,it.basis,it.effortScale?.scaleId) }.distinct().size==targets.size)
        permissions.reduceDuration?.let { p -> val t=targets.singleOrNull { it.metric=="duration" && it.basis==p.basis } ?: throw IllegalArgumentException("Duration target required"); require(p.minimumSeconds.toDouble()<=t.minimum && p.maximumSeconds.toDouble()>=t.maximum) }
        permissions.move?.let { w ->
            val d=schedule.kind=="date_only"; val a=if(d) date(w.earliestStart).atStartOfDay() else local(w.earliestStart); val b=if(d) date(w.latestStart).atStartOfDay() else local(w.latestStart)
            require(b>=a && Duration.between(a,b)<=Duration.ofDays(366))
        }
    }
    override fun toString() = "PlannedSession([REDACTED])"
}
@Serializable
data class PlanPolicy(val adaptationTimeZone: String, val horizonDays: Int, val importGraceSeconds: Long) {
    init { zone(adaptationTimeZone); require(horizonDays in 1..366 && importGraceSeconds in 0..31622400) }
    fun horizon(at: Instant): Pair<LocalDate,LocalDate> { val d=at.atZone(zone(adaptationTimeZone)).toLocalDate(); return d to d.plusDays(horizonDays.toLong()-1) }
}
@Serializable
data class AuthoredPlanRequest(val title: String, val author: PlanAuthor, val sessions: List<PlannedSession>, val policy: PlanPolicy? = null) {
    init { text(title,160); require(sessions.size<=1000 && sessions.map { it.sessionId }.distinct().size==sessions.size) }
    override fun toString() = "AuthoredPlanRequest([REDACTED])"
}
@Serializable data class AuthoredPlan(val reference: PlanRef, val previousRevision: Int?, val definition: AuthoredPlanRequest, val recordedBy: String, val recordedUtc: String) { override fun toString() = "AuthoredPlan([REDACTED])" }
@Serializable data class PlanSelection(val generation: Long, val selected: PlanRef?)
@Serializable data class PlansView(val plans: List<AuthoredPlan>, val selection: PlanSelection)
@Serializable data class PlanCorrection(val expectedRevision: Int, val expectedSelectionGeneration: Long, val definition: AuthoredPlanRequest) { init { require(expectedRevision in 1..999 && expectedSelectionGeneration>=0) } }
@Serializable data class PlanSelectionRequest(val expectedGeneration: Long, val selected: PlanRef?) { init { require(expectedGeneration>=0) } }
fun amendmentStartCutoff(session: PlannedSession, policy: PlanPolicy): Instant = if(session.schedule.kind=="timed") session.schedule.startInstant() else minOf(session.schedule.startInstant(),date(session.schedule.date!!).atStartOfDay(zone(policy.adaptationTimeZone)).toInstant())
fun unmatchedAfter(session: PlannedSession, policy: PlanPolicy): Instant = session.schedule.endInstant().plusSeconds(policy.importGraceSeconds)
@Serializable data class PlanCalendarEntry(val plan: PlanRef, val sessionId: String, val kind: String = "authored_session", val schedule: PlanSchedule, val sport: String, val objective: String, val omitted: Boolean, val amendmentEligible: Boolean) {
    override fun toString()="PlanCalendarEntry([REDACTED])"
}
@Serializable data class PlanCalendar(val asOf: String, val from: String, val until: String, val timeZone: String, val selection: PlanSelection, val items: List<PlanCalendarEntry>,
    val limitation: String = "authored_sessions_not_completed_activities_or_commitments;eligibility_is_not_constraint_validation_or_medical_clearance")
interface PlanRepository {
    fun plans(): PlansView
    fun plan(id: String, revision: Int? = null): AuthoredPlan
    fun createPlan(request: AuthoredPlanRequest, at: Instant): AuthoredPlan
    fun correctPlan(id: String, request: PlanCorrection, at: Instant): AuthoredPlan
    fun selectPlan(request: PlanSelectionRequest): PlanSelection
}
class PlanConflict : IllegalStateException("Plan or selection changed")
class PlanNotFound : IllegalStateException("Plan not found")
