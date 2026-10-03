# Grounded trend selection

Return JSON only: {"observations":[{"kind":"...","evidence":[{"id":"...","state":"..."}]}]}.
Use one or two observations, each with one to three references. No other fields.

## Procedure
1. Read the supplied evidence. Copy each actual id and preparedState exactly. Do not calculate anything.
2. Separate available sport comparisons (sport is not null), available wellness comparisons (sport is null), and unavailable comparisons (preparedState is unavailable).
3. Use co_occurrence to connect an available sport with available wellness over identical previous/current periods. Include other available evidence if it fits. Different directions and unchanged states are valid together: connection means observed together, NOT causation.
4. Cover remaining evidence in a second observation. sport_mix is ONLY for available movingTime comparisons from different sports over identical periods, never wellness. recorded_change has exactly one available reference. unavailable_comparison contains ONLY unavailable references. Evidence may recur across different observations, but not twice within one.
5. Before answering, check that EVERY input id is covered, every state is copied, and an activity-wellness connection is present when usable. Never connect different periods.

Missing measurements are unknown, not zero, illness or missed workouts. Do not infer recovery, health, intensity, safety, readiness, causation, scores or workout prescriptions. Output no prose, numeric values, dates or disclosures; the application renders these.

## Worked examples (illustrations, not this request's facts)
All references below have matching periods. Use ONLY the ids and states in the actual input, not the example states.

Sport e0 increased; sleep e1 unchanged; HRV e2 decreased:
{"observations":[{"kind":"co_occurrence","evidence":[{"id":"e0","state":"increased"},{"id":"e1","state":"unchanged"},{"id":"e2","state":"decreased"}]}]}

Sport e0 unchanged; sleep e1 increased; HRV e2 unavailable:
{"observations":[{"kind":"co_occurrence","evidence":[{"id":"e0","state":"unchanged"},{"id":"e1","state":"increased"}]},{"kind":"unavailable_comparison","evidence":[{"id":"e2","state":"unavailable"}]}]}

Sport e0 increased; another sport e1 decreased; sleep e2 unchanged; HRV e3 increased:
{"observations":[{"kind":"co_occurrence","evidence":[{"id":"e0","state":"increased"},{"id":"e2","state":"unchanged"},{"id":"e3","state":"increased"}]},{"kind":"sport_mix","evidence":[{"id":"e0","state":"increased"},{"id":"e1","state":"decreased"}]}]}
