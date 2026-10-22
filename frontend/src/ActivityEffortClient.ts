import {arrayFrom,object,sameValue} from './ContextIntakeClient'
import {effortScaleFrom,type EffortScale,type PlanRef} from './PlanClient'
import {activityFrom,type Activity} from './PlanActivity'
import {realDate} from './ContextFields'
import {utc} from './ScopedReviewTypes'

export type EffortReport={reportId:string;revision:number;previousRevision:number|null;activityId:string;activityRevision:number|null;activityRecordSha256:string;scale:EffortScale;value:number;observedOn:string;authorAttribution:string;recordedBy:'authenticated_user';recordedUtc:string;withdrawn:boolean;detached:boolean;detachmentGeneration:number}
export type EffortHistory={activityId:string;scale:EffortScale;currentActivity:Activity|null;reports:EffortReport[]}
export type EffortReceipt={report:EffortReport;replayed:boolean}
const invalid=():never=>{throw Error('Invalid effort report response')}
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const hash=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const integer=(v:unknown,min:number,max:number):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=min&&v<=max
const text=(v:unknown,max:number):v is string=>typeof v==='string'&&v.trim().length>0&&v.length<=max
export function effortReportFrom(v:unknown):EffortReport {
 if(new TextEncoder().encode(JSON.stringify(v)).length>2*1024*1024)return invalid()
  const r=object(v),scale=effortScaleFrom(r.scale)
  if(typeof r.value!=='number'||!Number.isFinite((r.value-scale.minimum)/scale.step))return invalid()
 if(!uuid(r.reportId)||!integer(r.revision,1,1000)||!(r.previousRevision===null&&r.revision===1||integer(r.previousRevision,1,999)&&r.previousRevision===r.revision-1)||!hash(r.activityId)||!(r.activityRevision===null||integer(r.activityRevision,1,Number.MAX_SAFE_INTEGER))||!hash(r.activityRecordSha256)||typeof r.value!=='number'||!Number.isFinite(r.value)||r.value<scale.minimum||r.value>scale.maximum||Math.abs((r.value-scale.minimum)/scale.step-Math.round((r.value-scale.minimum)/scale.step))>=1e-8||typeof r.observedOn!=='string'||!realDate(r.observedOn)||!text(r.authorAttribution,160)||r.recordedBy!=='authenticated_user'||!utc(r.recordedUtc)||typeof r.withdrawn!=='boolean'||typeof r.detached!=='boolean'||!integer(r.detachmentGeneration,0,Number.MAX_SAFE_INTEGER))return invalid()
 return {...r,scale} as EffortReport
}
export function effortHistoryFrom(v:unknown):EffortHistory {
 if(new TextEncoder().encode(JSON.stringify(v)).length>2*1024*1024)return invalid()
 const h=object(v),scale=effortScaleFrom(h.scale)
 if(!hash(h.activityId)||!Array.isArray(h.reports)||h.reports.length>1000)return invalid()
 const currentActivity=h.currentActivity===null?null:activityFrom(h.currentActivity),reports=arrayFrom(h.reports,effortReportFrom)
  if(currentActivity&&currentActivity.activityId!==h.activityId||reports.length>0&&reports[0].revision!==1||reports.some((r,i)=>r.activityId!==h.activityId||!sameValue(r.scale,scale)||i>0&&(r.reportId!==reports[0].reportId||r.revision!==reports[i-1].revision+1||r.previousRevision!==reports[i-1].revision)))return invalid()
 return {activityId:h.activityId,scale,currentActivity,reports}
}
export function effortReceiptFrom(v:unknown):EffortReceipt {const r=object(v);if(typeof r.replayed!=='boolean')return invalid();return {report:effortReportFrom(r.report),replayed:r.replayed}}
export type EffortOrigin={plan:PlanRef;sessionId:string;expectedSelectionGeneration:number;expectedDigest:string;asOf:string;query:{oldest:string;newest:string;timeZone:string}}
