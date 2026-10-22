import { arrayFrom, object } from './ContextIntakeClient'
import { realDate } from './ContextFields'
import { utc as validUtc, dateCount } from './ScopedReviewTypes'

export type PlanRef = { planId: string; revision: number }
export type PlanAuthor = { authorKind: 'user' | 'coach'; authorAttribution: string }
export type EffortScale = { scaleId: string; name: string; minimum: number; maximum: number; step: number; higherMeansMoreEffort: boolean }
export type PlannedTarget = { metric: 'duration' | 'distance' | 'average_heart_rate' | 'intervals_training_load' | 'reported_effort';
  unit: string; basis: string; minimum: number; maximum: number; effortScale: EffortScale | null }
export type PlanSchedule = { kind: 'date_only' | 'timed'; date: string | null; startsLocal: string | null; endsLocal: string | null;
  timeZone: string; startOffset: string | null; endOffset: string | null }
export type AmendmentPermissions = { move: { earliestStart: string; latestStart: string } | null;
  reduceDuration: { basis: 'moving' | 'elapsed'; minimumSeconds: number; maximumSeconds: number } | null; omit: boolean }
export type PlannedSession = { sessionId: string; author: PlanAuthor; sport: string; objective: string; schedule: PlanSchedule;
  targets: PlannedTarget[]; notes: string | null; permissions: AmendmentPermissions; omitted: boolean }
export type PlanPolicy = { adaptationTimeZone: string; horizonDays: number; importGraceSeconds: number }
export type AuthoredPlanRequest = { title: string; author: PlanAuthor; sessions: PlannedSession[]; policy: PlanPolicy | null }
export type AuthoredPlan = { reference: PlanRef; previousRevision: number | null; definition: AuthoredPlanRequest; recordedBy: string; recordedUtc: string }
export type PlanSelection = { generation: number; selected: PlanRef | null }
export type PlansView = { plans: AuthoredPlan[]; selection: PlanSelection }
export type PlanCalendarEntry = { plan: PlanRef; sessionId: string; kind: 'authored_session'; schedule: PlanSchedule; sport: string;
  objective: string; omitted: boolean; amendmentEligible: boolean }
export type PlanCalendar = { asOf: string; from: string; until: string; timeZone: string; selection: PlanSelection; items: PlanCalendarEntry[]; limitation: string }

const invalid = (): never => { throw new Error('Invalid private plan response') }
export const planUuid = (value: unknown): value is string => typeof value==='string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown, min: number, max: number): value is number => typeof value==='number' && Number.isSafeInteger(value) && value>=min && value<=max
const text = (value: unknown, max: number): value is string => typeof value==='string' && value.trim().length>0 && value.length<=max
const zone = (value: unknown): value is string => {
  if(typeof value!=='string' || value.length>80) return false
  try { new Intl.DateTimeFormat('en',{timeZone:value}).format(); return true } catch { return false }
}
const local = (value: unknown): value is string => typeof value==='string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value) &&
  realDate(value.slice(0,10)) && value.slice(11,13)<='23' && value.slice(14)<='59'
const offset = (value: unknown) => value===null || value==='Z' || typeof value==='string' && /^[+-]\d{2}:\d{2}$/.test(value) &&
  Number(value.slice(1,3))<=18 && Number(value.slice(4))<=59 && (Number(value.slice(1,3))<18 || value.slice(4)==='00')

export function planRefFrom(value: unknown): PlanRef {
  const ref=object(value)
  if(!planUuid(ref.planId) || !integer(ref.revision,1,1000)) return invalid()
  return {planId:ref.planId,revision:ref.revision}
}
export function planSelectionFrom(value: unknown): PlanSelection {
  const item=object(value)
  if(!integer(item.generation,0,Number.MAX_SAFE_INTEGER)) return invalid()
  return {generation:item.generation,selected:item.selected===null ? null : planRefFrom(item.selected)}
}
function authorFrom(value: unknown): PlanAuthor {
  const item=object(value)
  if(!['user','coach'].includes(String(item.authorKind)) || !text(item.authorAttribution,160)) return invalid()
  return item as PlanAuthor
}
export function planScheduleFrom(value: unknown): PlanSchedule {
  const item=object(value)
  if(!zone(item.timeZone) || !offset(item.startOffset) || !offset(item.endOffset)) return invalid()
  if(item.kind==='date_only') {
    if(typeof item.date!=='string' || !realDate(item.date) || item.startsLocal!==null || item.endsLocal!==null || item.startOffset!==null || item.endOffset!==null) return invalid()
  } else if(item.kind==='timed') {
    if(item.date!==null || !local(item.startsLocal) || !local(item.endsLocal) || item.endsLocal<=item.startsLocal) return invalid()
  } else return invalid()
  return item as PlanSchedule
}
export function effortScaleFrom(value: unknown): EffortScale {
  const s=object(value)
  if(!planUuid(s.scaleId)||!text(s.name,160)||typeof s.minimum!=='number'||!Number.isFinite(s.minimum)||
    typeof s.maximum!=='number'||!Number.isFinite(s.maximum)||!Number.isFinite(s.maximum-s.minimum)||s.maximum<=s.minimum||typeof s.step!=='number'||
    !Number.isFinite(s.step)||s.step<=0||s.step>s.maximum-s.minimum||s.higherMeansMoreEffort!==true)return invalid()
  return s as EffortScale
}
export function plannedTargetFrom(value: unknown): PlannedTarget {
  const item=object(value)
  if(typeof item.minimum!=='number' || !Number.isFinite(item.minimum) || typeof item.maximum!=='number' || !Number.isFinite(item.maximum) || item.minimum>item.maximum) return invalid()
  const keys: Record<string,string[]> = {duration:['seconds','moving','elapsed'],distance:['metres','session_total'],
    average_heart_rate:['beats/minute','session_average'],intervals_training_load:['Intervals.icu load','intervals_session_load'],reported_effort:['scale_points','reported']}
  const allowed=typeof item.metric==='string'?keys[item.metric]:undefined
  if(!allowed || item.unit!==allowed[0] || typeof item.basis!=='string' || !allowed.slice(1).includes(item.basis)) return invalid()
  if(item.metric==='reported_effort') {
    const scale=effortScaleFrom(item.effortScale)
    if(item.minimum<scale.minimum || item.maximum>scale.maximum ||
      [item.minimum,item.maximum].some(v=>{const n=(v-scale.minimum)/scale.step;return !Number.isFinite(n)||Math.abs(n-Math.round(n))>=1e-8})) return invalid()
  } else if(item.effortScale!==null) return invalid()
  const bounds:Record<string,[number,number]>={duration:[1,2678400],distance:[0,10000000],average_heart_rate:[0,300],intervals_training_load:[0,10000]}
  const bound=bounds[item.metric as string]
  if(bound&&(item.minimum<bound[0]||item.maximum>bound[1])||item.metric==='duration'&&(!Number.isInteger(item.minimum)||!Number.isInteger(item.maximum)))return invalid()
  return item as PlannedTarget
}
export function plannedSessionFrom(value: unknown): PlannedSession {
  const item=object(value), permissions=object(item.permissions)
  if(!planUuid(item.sessionId) || !text(item.sport,80) || !text(item.objective,160) || !(item.notes===null || text(item.notes,4000)) ||
    typeof item.omitted!=='boolean' || typeof permissions.omit!=='boolean') return invalid()
  const schedule=planScheduleFrom(item.schedule), author=authorFrom(item.author), targets=arrayFrom(item.targets,plannedTargetFrom)
  if(targets.length>6) return invalid()
  if(permissions.move!==null) {
    const move=object(permissions.move), valid=schedule.kind==='date_only' ? (v:unknown)=>typeof v==='string' && realDate(v) : local
    if(!valid(move.earliestStart) || !valid(move.latestStart) || String(move.earliestStart)>String(move.latestStart)) return invalid()
  }
  if(permissions.reduceDuration!==null) {
    const reduction=object(permissions.reduceDuration)
    if(!['moving','elapsed'].includes(String(reduction.basis)) || !integer(reduction.minimumSeconds,1,2678400) ||
      !integer(reduction.maximumSeconds,reduction.minimumSeconds,2678400)) return invalid()
  }
  return {...item,schedule,author,targets} as PlannedSession
}
export function authoredPlanFrom(value: unknown): AuthoredPlan {
  const item=object(value), definition=object(item.definition), reference=planRefFrom(item.reference)
  if(!text(definition.title,160) || item.recordedBy!=='authenticated_user' || !validUtc(item.recordedUtc) ||
    !(reference.revision===1 ? item.previousRevision===null : item.previousRevision===reference.revision-1)) return invalid()
  if(definition.policy!==null) {
    const policy=object(definition.policy)
    if(!zone(policy.adaptationTimeZone) || !integer(policy.horizonDays,1,366) || !integer(policy.importGraceSeconds,0,31622400)) return invalid()
  }
  const sessions=arrayFrom(definition.sessions,plannedSessionFrom)
  if(new Set(sessions.map(item=>item.sessionId)).size!==sessions.length) return invalid()
  return {...item,reference,definition:{...definition,author:authorFrom(definition.author),sessions}} as AuthoredPlan
}
export function plansViewFrom(value: unknown): PlansView {
  const item=object(value), plans=arrayFrom(item.plans,authoredPlanFrom), selection=planSelectionFrom(item.selection)
  if(plans.length>100 || new Set(plans.map(plan=>plan.reference.planId)).size!==plans.length ||
    selection.selected && !plans.some(plan=>plan.reference.planId===selection.selected?.planId && plan.reference.revision===selection.selected.revision)) return invalid()
  return {plans,selection}
}
export function planCalendarFrom(value: unknown): PlanCalendar {
  const item=object(value), selection=planSelectionFrom(item.selection)
  if(!validUtc(item.asOf) || typeof item.from!=='string' || !realDate(item.from) ||
    typeof item.until!=='string' || !realDate(item.until) || dateCount(item.from,item.until)<1 || dateCount(item.from,item.until)>366 || !zone(item.timeZone) || typeof item.limitation!=='string') return invalid()
  const items=arrayFrom(item.items,value=>{
    const row=object(value), plan=planRefFrom(row.plan), schedule=planScheduleFrom(row.schedule)
    if(!planUuid(row.sessionId) || row.kind!=='authored_session' || !text(row.sport,80) || !text(row.objective,160) ||
      typeof row.omitted!=='boolean' || typeof row.amendmentEligible!=='boolean' || !selection.selected ||
      plan.planId!==selection.selected.planId || plan.revision!==selection.selected.revision) return invalid()
    return {...row,plan,schedule} as PlanCalendarEntry
  })
  if(new Set(items.map(row=>row.sessionId)).size!==items.length) return invalid()
  return {...item,selection,items} as PlanCalendar
}
