package com.gtrainer

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class PlanService(private val repository: PlanRepository, private val clock: Clock = Clock.systemUTC()) {
    fun list()=repository.plans()
    fun get(id: String, revision: Int?=null)=repository.plan(id,revision)
    fun create(request: AuthoredPlanRequest)=repository.createPlan(request,clock.instant())
    fun correct(id: String, request: PlanCorrection)=repository.correctPlan(id,request,clock.instant())
    fun select(request: PlanSelectionRequest)=repository.selectPlan(request)

    fun amendmentCandidates(): PlanAmendmentInspection {
        val source=repository as? PlanAmendmentRepository ?: throw PlanNotFound()
        val capture=source.capturePlanAmendment(clock.instant())
        val report=PlanAmendmentPolicy.enumerate(capture.input,capture.at)
        return source.finishPlanAmendment(capture,report,clock)
    }

    fun amendmentSelection(snapshotId: String): AmendmentSelectionInspection =
        PlanAmendmentPreparation(repository as? HistoryStore ?: throw PlanNotFound(),clock).prepare(snapshotId).inspection()

    fun validateAmendmentSelection(request: AmendmentValidationRequest): CheckedAmendmentResult =
        PlanAmendmentPreparation(repository as? HistoryStore ?: throw PlanNotFound(),clock).validate(request)

    fun generateAmendmentProposal(request: AmendmentValidationRequest): PlanAmendmentProposalInspection =
        PlanAmendmentProposalService(repository as? HistoryStore ?: throw PlanNotFound(),clock).generate(request)

    fun amendmentProposal(id: String): PlanAmendmentProposalInspection =
        PlanAmendmentProposalService(repository as? HistoryStore ?: throw PlanNotFound(),clock).inspect(id)

    fun amendmentProposals(offset: Int,limit: Int): PlanAmendmentProposalIndex =
        (repository as? HistoryStore ?: throw PlanNotFound()).amendmentProposalIndex(offset,limit)

    fun rejectAmendmentProposal(id: String,request: PlanAmendmentProposalRejection): PlanAmendmentProposalInspection =
        PlanAmendmentProposalService(repository as? HistoryStore ?: throw PlanNotFound(),clock).reject(id,request)

    fun approveAmendmentProposal(id: String,request: PlanAmendmentApprovalRequest): PlanAmendmentApprovalReceipt =
        PlanAmendmentApprovalService(repository as? HistoryStore ?: throw PlanNotFound(),clock).approve(id,request)

    fun signalRules()=(repository as? PersonalSignalRepository ?: throw PlanNotFound()).signalRules()
    fun workloadRules()=(repository as? ExtraWorkloadRepository ?: throw PlanNotFound()).workloadRules()
    fun saveWorkloadRule(request: ExtraWorkloadRuleRequest)=(repository as? ExtraWorkloadRepository ?: throw PlanNotFound()).saveWorkloadRule(request,clock.instant())
    fun inspectWorkloadRule(id: String): ExtraWorkloadReport {
        val source=repository as? ExtraWorkloadRepository ?: throw PlanNotFound()
        val captured=source.captureExtraWorkload(id,clock.instant())
        val report=ExtraWorkloadRules.evaluate(captured.rule,captured.at,PlanReconciler.core(captured.at,captured.reconciliation),captured.reconciliation.records)
        return source.finishExtraWorkload(captured,report)
    }
    fun saveSignalRule(request: PersonalSignalRuleRequest)=(repository as? PersonalSignalRepository ?: throw PlanNotFound()).saveSignalRule(request,clock.instant())
    fun inspectSignalRule(id: String,query: PersonalSignalQuery): PersonalSignalReport {
        val source=repository as? PersonalSignalRepository ?: throw PlanNotFound()
        val capture=source.capturePersonalSignal(id,query,clock.instant())
        val report=PersonalSignalRules.evaluate(capture.rule,query,capture.at,capture.samples).copy(sourceStatus=capture.sourceStatus)
        return source.finishPersonalSignal(capture,report)
    }

    fun effortHistory(activityId: String,scaleId: String)=(repository as? ActivityEffortRepository ?: throw PlanNotFound()).effortHistory(activityId,scaleId)

    fun saveEffortReport(request: ActivityEffortRequest): ActivityEffortReceipt {
        val source=repository as? ActivityEffortRepository ?: throw PlanNotFound()
        source.effortReceipt(request)?.let { return it }
        if(request.action=="withdraw") return source.saveEffortReport(request,null,clock)
        val reads=repository as? PlanReconciliationRepository ?: throw PlanNotFound()
        val cutoff=request.origin?.asOf?.let(java.time.Instant::parse)?:clock.instant()
        if(cutoff>clock.instant()) throw PlanConflict()
        val capture=reads.capturePlanReconciliation(cutoff)
        request.origin?.let { origin ->
            val view=PlanReconciler.reconcile(origin.query,cutoff,capture)
            val finding=view.sessions.singleOrNull { it.sessionId==origin.sessionId } ?: throw PlanConflict()
            if(view.digest!=origin.expectedDigest || capture.selection.selected!=origin.plan || capture.selection.generation!=origin.expectedSelectionGeneration ||
                finding.state!="matched" || finding.activityId!=request.activityId || capture.plan?.definition?.sessions?.single { it.sessionId==origin.sessionId }
                    ?.targets?.none { it.effortScale?.scaleId==request.scaleId }!=false) throw PlanConflict()
        }
        return source.saveEffortReport(request,capture,clock)
    }

    fun reconciliation(query: PlanReconciliationQuery): PlanReconciliationView {
        val source=repository as? PlanReconciliationRepository ?: throw PlanNotFound()
        val at=clock.instant()
        val capture=source.capturePlanReconciliation(at)
        val view=PlanReconciler.reconcile(query,at,capture)
        return source.finishPlanReconciliation(capture,view)
    }

    fun confirmReconciliation(request: PlanReconciliationConfirmationRequest): PlanReconciliationReceipt {
        val source=repository as? PlanReconciliationRepository ?: throw PlanNotFound()
        source.reconciliationReceipt(request)?.let { return it }
        val cutoff=java.time.Instant.parse(request.asOf)
        if(cutoff>clock.instant()) throw PlanConflict()
        val capture=source.capturePlanReconciliation(cutoff)
        val view=PlanReconciler.reconcile(request.query,cutoff,capture)
        if(view.digest!=request.expectedDigest || view.sessions.none { it.sessionId==request.sessionId }) throw PlanConflict()
        val activity=request.activityId?.let { id -> capture.activities.singleOrNull { it.activityId==id } ?: throw PlanConflict() }
        validatePlanConfirmation(request,capture,activity,clock.instant())
        return source.confirmPlanReconciliation(request,capture,activity,clock)
    }

    fun calendar(oldest: String, newest: String, timeZone: String): PlanCalendar {
        val from=LocalDate.parse(oldest); val until=LocalDate.parse(newest)
        require(from.toString()==oldest && until.toString()==newest && ChronoUnit.DAYS.between(from,until) in 0..365)
        require(timeZone=="UTC" || timeZone in ZoneId.getAvailableZoneIds())
        val zone=ZoneId.of(timeZone)
        val start=from.atStartOfDay(zone).toInstant(); val end=until.plusDays(1).atStartOfDay(zone).toInstant()
        val at=clock.instant()
        val view=repository.plans() // One owner read; selection and authoritative plan revisions cannot tear.
        val selected=view.plans.singleOrNull { it.reference==view.selection.selected }
        val entries=selected?.definition?.sessions.orEmpty().filter { session ->
            session.schedule.startInstant()<end && session.schedule.endInstant()>start
        }.map { session ->
            val policy=selected?.definition?.policy
            val eligible=policy!=null && !session.omitted && at<amendmentStartCutoff(session,policy) &&
                (session.permissions.move!=null || session.permissions.reduceDuration!=null || session.permissions.omit) &&
                policy.horizon(at).let { (first,last) ->
                    val policyZone=ZoneId.of(policy.adaptationTimeZone)
                    session.schedule.startInstant()>=first.atStartOfDay(policyZone).toInstant() &&
                        session.schedule.endInstant()<=last.plusDays(1).atStartOfDay(policyZone).toInstant()
                }
            PlanCalendarEntry(selected!!.reference,session.sessionId,schedule=session.schedule,sport=session.sport,
                objective=session.objective,omitted=session.omitted,amendmentEligible=eligible)
        }.sortedWith(compareBy({ it.schedule.startInstant() },{ it.sessionId }))
        return PlanCalendar(at.toString(),oldest,newest,timeZone,view.selection,entries)
    }
}
