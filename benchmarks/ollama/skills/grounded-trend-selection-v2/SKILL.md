# Complete trend selection, without redundant observations

Return JSON only: {"observations":[{"kind":"...","evidence":[{"id":"...","state":"..."}]}]}.
One or two observations; one to three references per observation; no extra fields.
Copy actual input ids and preparedState exactly. Do no arithmetic.

## Selection procedure
1. increased, decreased and unchanged are AVAILABLE. Only preparedState unavailable permits unavailable_comparison.
2. First observation: co_occurrence. Choose ALL available wellness ids (sport is null) FIRST, then available sport ids in input order, up to three references. Connect only identical previous/current periods; directions may differ.
3. No uncovered ids? STOP with exactly ONE observation. Never add another observation for already covered ids.
4. Otherwise use exactly one second observation for uncovered ids: unavailable_comparison for only unavailable items; recorded_change for one available item; sport_mix for available movingTime from different sports with identical periods. Unchanged is NOT unavailable. Never omit wellness.
5. Check EVERY input id is covered, states match, references do not repeat, and activity connects to wellness. Never connect different periods.

Missing measurements are unknown, not zero, illness or missed workouts. No health, recovery, intensity, causation, safety, readiness, scores or prescriptions. Output no prose, values, dates or disclosures; code renders those.

## Examples, not the current request's facts
Use actual input ids and states, not example states.

One sport e0 increased; sleep e1 unchanged; HRV e2 decreased:
{"observations":[{"kind":"co_occurrence","evidence":[{"id":"e1","state":"unchanged"},{"id":"e2","state":"decreased"},{"id":"e0","state":"increased"}]}]}

Sport e0 unchanged; sleep e1 increased; HRV e2 unavailable:
{"observations":[{"kind":"co_occurrence","evidence":[{"id":"e1","state":"increased"},{"id":"e0","state":"unchanged"}]},{"kind":"unavailable_comparison","evidence":[{"id":"e2","state":"unavailable"}]}]}

Sports e0 increased and e1 decreased; sleep e2 unchanged; HRV e3 increased:
{"observations":[{"kind":"co_occurrence","evidence":[{"id":"e2","state":"unchanged"},{"id":"e3","state":"increased"},{"id":"e0","state":"increased"}]},{"kind":"recorded_change","evidence":[{"id":"e1","state":"decreased"}]}]}
