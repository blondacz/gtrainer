import {object} from './ContextIntakeClient'
import {realDate} from './ContextFields'
import {utc} from './ScopedReviewTypes'

export type Activity={activityId:string;revision:number|null;recordSha256:string;sport:string;startLocal:string;startInstant:string|null;timeContext:string}
export function activityFrom(v:unknown):Activity {
  const a=object(v)
  const hash=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
  const text=(v:unknown,max:number):v is string=>typeof v==='string'&&v.length>0&&v.length<=max
  const local=(v:unknown):v is string=>typeof v==='string'&&/^\d{4}-\d\d-\d\dT(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/.test(v)&&realDate(v.slice(0,10))
  if(!hash(a.activityId)||!(a.revision===null||typeof a.revision==='number'&&Number.isSafeInteger(a.revision)&&a.revision>0)||!hash(a.recordSha256)||
    !text(a.sport,80)||!local(a.startLocal)||!(a.startInstant===null||utc(a.startInstant))||!text(a.timeContext,80))throw Error('Invalid recording identity')
  return a as Activity
}
