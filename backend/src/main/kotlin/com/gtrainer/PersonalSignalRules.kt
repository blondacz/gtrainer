package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Desired selection is not verification authority: only adapter-owned samples can be verified. */
@Serializable
data class PersonalSignalSelector(val field: String, val unit: String, val intermediary: String,
    val upstreamOrigin: String, val method: String, val basis: String) {
    init {
        require(field in setOf("sleepSecs","hrv","hrvSDNN"))
        require(unit==if(field=="sleepSecs") "seconds" else "ms")
        require(intermediary.matches(Regex("[a-zA-Z0-9_.-]{1,64}")))
        listOf(upstreamOrigin,method,basis).forEach { validateContextText(it,128,true);require(it!="unknown") }
    }
    internal fun desiredIdentity()=ScopedSignalIdentity(field,unit,intermediary,upstreamOrigin,method,basis,"verified")
    override fun toString()="PersonalSignalSelector([REDACTED])"
}

@Serializable
data class PersonalSignalBaseline(val trailingDays: Int,val minimumSamples: Int,val minimumCoverage: Double,val maximumAgeDays: Int) {
    init { require(trailingDays in 1..366 && minimumSamples in 1..trailingDays && minimumCoverage.isFinite() &&
        minimumCoverage in 0.0..1.0 && maximumAgeDays in 0..366) }
    internal fun on(date: String)=ScopedBaselineConfig(date,trailingDays,minimumSamples,minimumCoverage,maximumAgeDays)
}

@Serializable
data class PersonalSignalConfiguration(val timeZone: String,val selector: PersonalSignalSelector,
    val baseline: PersonalSignalBaseline,val direction: String,val boundaryType: String,
    val triggerMagnitude: Double,val resetMagnitude: Double,val triggerPersistenceDays: Int,
    val resetPersistenceDays: Int,val cooldownSeconds: Long) {
    init {
        require(timeZone=="UTC" || timeZone in ZoneId.getAvailableZoneIds())
        require(direction in setOf("low","high") && boundaryType in setOf("absolute_limit","absolute_deviation","percent_deviation"))
        require(triggerMagnitude.isFinite() && resetMagnitude.isFinite() && triggerMagnitude>=0 && resetMagnitude>=0)
        require(if(boundaryType=="absolute_limit") {
            if(direction=="low") resetMagnitude>triggerMagnitude else resetMagnitude<triggerMagnitude
        } else resetMagnitude<triggerMagnitude)
        require(triggerPersistenceDays in 1..366 && resetPersistenceDays in 1..366 && cooldownSeconds in 0..31_622_400)
    }
    override fun toString()="PersonalSignalConfiguration([REDACTED])"
}

@Serializable
data class PersonalSignalRule(val ruleId: String,val revision: Int,val enabled: Boolean,
    val configuration: PersonalSignalConfiguration?,val recordedUtc: String,val recordedBy: String="authenticated_user") {
    init { validateContextId(ruleId);require(revision in 1..1000 && (!enabled || configuration!=null));Instant.parse(recordedUtc);require(recordedBy=="authenticated_user") }
    override fun toString()="PersonalSignalRule([REDACTED])"
}

@Serializable
data class PersonalSignalRuleRequest(val operationId: String,val ruleId: String,val expectedRevision: Int,
    val enabled: Boolean=false,val configuration: PersonalSignalConfiguration?=null) {
    init { validateContextId(operationId);validateContextId(ruleId);require(expectedRevision in 0..999 && (!enabled || configuration!=null)) }
    override fun toString()="PersonalSignalRuleRequest([REDACTED])"
}
@Serializable
data class PersonalSignalRuleReceipt(val rule: PersonalSignalRule,val replayed: Boolean)

@Serializable
data class PersonalSignalQuery(val oldest: String,val newest: String) {
    init { ScopedDateRange(oldest,newest).range() }
}

/** Single shared support table avoids repeating candidates across overlapping baselines. */
@Serializable
data class PersonalSignalSupportDay(val date: String,val status: String,val value: Double?,val evidenceIds: List<String>,val candidateCount: Int)
{ override fun toString()="PersonalSignalSupportDay([REDACTED])" }

@Serializable
data class PersonalSignalDay(val date: String,val status: String,val reasons: List<String>,val value: Double?,
    val baselineValue: Double?,val baselineOldest: String,val baselineNewest: String,
    val baselineSelectedDays: Int,val baselineExpectedDays: Int,val triggerBoundary: Double?,val resetBoundary: Double?,
    val triggerCount: Int,val resetCount: Int) { override fun toString()="PersonalSignalDay([REDACTED])" }

@Serializable
data class PersonalSignalReport(val rule: PersonalSignalRule,val query: PersonalSignalQuery,val asOf: String,
    val status: String,val days: List<PersonalSignalDay>,val support: List<PersonalSignalSupportDay>,
    val sourceStatus: List<CategoryStatus> = emptyList(),
    val admission: String="not_integrated",val priorHistory: String="unknown_before_evaluated_horizon",
    val temporalBasis: String="source_date_window_using_records_available_at_preparation_not_point_in_time_replay",
    val upstreamFreshness: String="unknown") {
    override fun toString()="PersonalSignalReport([REDACTED])"
}

data class CapturedPersonalSignal(val rule: PersonalSignalRule,val query: PersonalSignalQuery,val at: Instant,
    val samples: List<ScopedSignalSample>,val sourceStatus: List<CategoryStatus>,val sourceRevision: Long,
    val totalChanges: Long,val stateGeneration: Long,val state: PersonalSignalState?,val ownerId: String,val dataVersion: Long,
    val rawRecordCount: Int = samples.size) {
    init { require(rawRecordCount in samples.size..50_000) }
    override fun toString()="CapturedPersonalSignal([REDACTED])"
}

interface PersonalSignalRepository {
    fun signalRules(): List<PersonalSignalRule>
    fun saveSignalRule(request: PersonalSignalRuleRequest,at: Instant): PersonalSignalRuleReceipt
    fun capturePersonalSignal(id: String,query: PersonalSignalQuery,at: Instant): CapturedPersonalSignal
    fun finishPersonalSignal(capture: CapturedPersonalSignal,report: PersonalSignalReport): PersonalSignalReport
}

internal object PersonalSignalRules {
    fun evaluate(rule: PersonalSignalRule,query: PersonalSignalQuery,at: Instant,samples: List<ScopedSignalSample>): PersonalSignalReport {
        require(samples.size<=50_000)
        val config=rule.configuration
        if(config==null || !rule.enabled) return PersonalSignalReport(rule,query,at.toString(),"disabled",emptyList(),emptyList())
        val oldest=LocalDate.parse(query.oldest);val newest=LocalDate.parse(query.newest)
        require(newest<=at.atZone(ZoneId.of(config.timeZone)).toLocalDate())
        val first=oldest.minusDays(config.baseline.trailingDays.toLong());require(first.year in 1..9999)
        samples.forEach { sample ->
            validateContextDate(sample.date);require(LocalDate.parse(sample.date) in first..newest)
            require(sample.value.isFinite() && sample.value>=0 && sample.evidenceId.isNotBlank() && (sample.revision==null || sample.revision>0))
            sample.receivedUtc?.let { require(Instant.parse(it)<=at) }
        }
        val identity=config.selector.desiredIdentity()
        val indexed=samples.groupBy { it.date }
        val support=(0..java.time.temporal.ChronoUnit.DAYS.between(first,newest).toInt()).map { offset ->
            val date=first.plusDays(offset.toLong()).toString()
            val day=ScopedWellnessEvidence.selectDay(identity,date,indexed[date].orEmpty())
            PersonalSignalSupportDay(date,day.status,day.value,day.evidenceIds,day.candidateCount)
        }
        val byDate=support.associateBy { it.date }
        var triggers=0;var resets=0
        val days=(0..java.time.temporal.ChronoUnit.DAYS.between(oldest,newest).toInt()).map { offset ->
            val date=oldest.plusDays(offset.toLong());val day=byDate.getValue(date.toString())
            val baseline=ScopedWellnessEvidence.baseline(identity,config.baseline.on(date.toString()),samples)
            val reasons=buildList {
                if(day.status!="unique_compatible") add("evaluation_${day.status}")
                addAll(baseline.reasons)
                if(config.boundaryType=="percent_deviation" && baseline.value==0.0) add("zero_percentage_baseline")
            }.toMutableList()
            fun boundary(magnitude: Double): Double? {
                val value=when(config.boundaryType) {
                    "absolute_limit" -> magnitude
                    "absolute_deviation" -> baseline.value?.let { it+(if(config.direction=="low") -magnitude else magnitude) }
                    else -> baseline.value?.takeIf { it!=0.0 }?.let { it+(if(config.direction=="low") -1 else 1)*it*(magnitude/100.0) }
                }
                return value?.takeIf { it.isFinite() }
            }
            val trigger=boundary(config.triggerMagnitude);val reset=boundary(config.resetMagnitude)
            if(reasons.isEmpty() && (trigger==null || reset==null || if(config.direction=="low") trigger>=reset else trigger<=reset)) reasons.add("nonfinite_or_collapsed_boundary")
            val eligible=reasons.isEmpty() && day.value!=null
            val triggered=eligible && (if(config.direction=="low") day.value<=trigger!! else day.value>=trigger!!)
            val resetSatisfied=eligible && (if(config.direction=="low") day.value>=reset!! else day.value<=reset!!)
            triggers=if(triggered) triggers+1 else 0;resets=if(resetSatisfied) resets+1 else 0
            val status=when {
                !eligible -> "unevaluable"
                triggers>=config.triggerPersistenceDays -> "trigger"
                resets>=config.resetPersistenceDays -> "reset"
                triggered || resetSatisfied -> "below_persistence"
                else -> "hysteresis_band"
            }
            PersonalSignalDay(date.toString(),status,reasons,day.value,baseline.value,date.minusDays(config.baseline.trailingDays.toLong()).toString(),
                date.minusDays(1).toString(),baseline.selectedSamples,baseline.expectedDays,trigger,reset,triggers,resets)
        }
        return PersonalSignalReport(rule,query,at.toString(),if(days.any { it.status!="unevaluable" }) "evaluated" else "unevaluable",days,support)
    }
}
