import {describe,expect,it} from 'vitest'
import {signalConfigurationFrom,signalRuleFrom,signalReportFrom,signalRulesFrom,signalDayFrom,signalSupportFrom,workloadConfigurationFrom,workloadReportFrom} from './AdaptationRulesClient'
import {signalRule,signalReport,workloadRule,workloadReport} from './adaptation.test-fixtures'

describe('AdaptationRulesClient strict parsers',()=>{
  it('accepts full daily support including the evaluation date and typed workload evidence',()=>{
    expect(signalReportFrom(signalReport()).support).toHaveLength(4)
    expect(signalRuleFrom(signalRule()).enabled).toBe(true)
    expect(workloadConfigurationFrom(workloadRule().configuration).metric).toBe('duration')
    expect(workloadReportFrom(workloadReport()).items[0].value).toBe(150)
    expect(signalRulesFrom([signalRule()])).toHaveLength(1)
    // One exact compatible sample can coexist with other, incompatible identities.
    expect(signalSupportFrom({...signalReport().support[0],candidateCount:2,evidenceIds:['wellness/hrv','wellness/hrvSDNN']}).status).toBe('unique_compatible')
  })
  it('accepts disabled unconfigured rules but refuses claimed evaluated output for them',()=>{
    const rule={...signalRule(),configuration:null,enabled:false},report={...signalReport(),rule,status:'disabled',days:[],support:[]}
    expect(signalReportFrom(report).status).toBe('disabled')
    expect(()=>signalReportFrom({...report,status:'evaluated'})).toThrow()
    expect(()=>signalRuleFrom({...rule,enabled:true})).toThrow()
  })
  it('rejects fake authority malformed rule dates and reset or metric mismatches',()=>{
    const c=signalRule().configuration!
    for(const patch of [{recordedBy:'fake_sender'},{recordedUtc:'2026-02-30T00:00:00Z'},{revision:1.5},{configuration:null}])expect(()=>signalRuleFrom({...signalRule(),...patch})).toThrow()
    expect(()=>signalConfigurationFrom({...c,resetMagnitude:20})).toThrow()
    expect(()=>signalConfigurationFrom({...c,direction:'high',triggerMagnitude:20,resetMagnitude:30})).toThrow()
    expect(()=>signalConfigurationFrom({...c,selector:{...c.selector,method:'unknown'}})).toThrow()
    expect(()=>signalConfigurationFrom({...c,selector:{...c.selector,field:'sleepSecs'}})).toThrow()
    expect(()=>signalConfigurationFrom({...c,selector:{...c.selector,intermediary:'invalid source'}})).toThrow()
    expect(()=>workloadConfigurationFrom({...workloadRule().configuration,unit:'metres'})).toThrow()
    expect(()=>workloadConfigurationFrom({...workloadRule().configuration,metric:'distance',basis:'elapsed'})).toThrow()
  })
  it('rejects invalid real dates duplicate identity string counters and nonfinite evidence',()=>{
    const report=signalReport(),day=report.days[0]
    expect(()=>signalRulesFrom([signalRule(),signalRule()])).toThrow()
    expect(()=>signalReportFrom({...report,query:{oldest:'2026-02-30',newest:'2026-03-01'}})).toThrow()
    expect(()=>signalReportFrom({...report,days:[day,day]})).toThrow()
    expect(()=>signalReportFrom({...report,support:report.support.slice(0,-1)})).toThrow()
    expect(()=>signalDayFrom({...day,triggerCount:'2'})).toThrow()
    expect(()=>signalDayFrom({...day,value:Infinity})).toThrow()
    expect(()=>signalDayFrom({...day,value:-1})).toThrow()
    expect(()=>signalSupportFrom({...report.support[0],candidateCount:'1'})).toThrow()
    expect(()=>signalSupportFrom({...report.support[0],status:'missing'})).toThrow()
    expect(()=>signalReportFrom({...report,filler:'x'.repeat(2_097_153)})).toThrow()
  })
  it('accepts explicitly unevaluable workload metrics and refuses malformed support identities',()=>{
    const report=workloadReport(),item=report.items[0]
    expect(workloadReportFrom({...report,status:'unevaluable',items:[{...item,status:'unevaluable',reason:'measurement_missing',value:null}],supportingActivityIds:[]}).items[0].reason).toBe('measurement_missing')
    expect(()=>workloadReportFrom({...report,items:[item,item]})).toThrow()
    expect(()=>workloadReportFrom({...report,supportingActivityIds:['a'.repeat(64)]})).toThrow()
    expect(()=>workloadReportFrom({...report,value:'150'})).toThrow()
  })
})
