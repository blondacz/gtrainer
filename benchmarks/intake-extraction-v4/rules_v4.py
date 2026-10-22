"""Pre-inference extractive conformance contract; never a response repairer."""
import json


def source_cases(artifacts):
    return [json.loads(c["exactPrompt"]["userPacketJson"]) for c in artifacts["cases"]]


def excerpts(text):
    return list(dict.fromkeys([text, text.removesuffix(".")]))


def context(text, observed, start=None, end=None, start_mode="unknown", end_mode="unknown", category="restriction"):
    return {"kind": ["context"], "sourceExcerpt": excerpts(text), "commitment": [None],
            "context.category": [category], "context.observedOn": [observed],
            "context.applicableFrom": [start], "context.applicableUntil": [end],
            "context.applicableFromMode": [start_mode], "context.applicableUntilMode": [end_mode],
            "context.content": excerpts(text),
            "unresolvedFields": [[field for field, mode in (("applicableFrom", start_mode), ("applicableUntil", end_mode)) if mode == "unknown"]]}


def times(value):
    return [None] if value is None else [value, value + ":00"]


def commitment(text, labels, start, end, zone="UTC", recurrence="one_off", interval=None, until=None):
    fields = {"kind": ["commitment"], "sourceExcerpt": excerpts(text), "context": [None],
              "commitment.label": labels, "commitment.kind": ["commitment"],
              "commitment.startsLocal": times(start), "commitment.endsLocal": times(end),
              "commitment.timeZone": [zone], "unresolvedFields": [[]]}
    if recurrence is None:
        fields["commitment.recurrence"] = [None]
        fields["unresolvedFields"] = [["startsLocal", "endsLocal", "timeZone", "recurrence"]]
    else:
        fields.update({"commitment.recurrence.frequency": [recurrence],
                       "commitment.recurrence.interval": [interval],
                       "commitment.recurrence.endMode": ["bounded" if until else "open"],
                       "commitment.recurrence.untilDate": [until]})
    return fields


def definition(artifacts):
    packets = source_cases(artifacts)
    text = [p["intake"]["content"] for p in packets]
    first, second = text[2].split(". ", 1)
    travel, training = text[5].split(". ", 1)
    corrected = text[6].split(". Ignore", 1)[0]
    rows = [
        ([context(text[0], "2026-06-01", end_mode="open")], "applicability_start",
         ["When did the no-running restriction begin?", "When does the restriction start?"]),
        ([context(text[1], "2026-10-17", start="2026-10-01", start_mode="specified", category="symptom")], "illness_end",
         ["When did the illness end, if it has ended?", "What is the illness applicability end date, if known?"]),
        ([context(first + ".", "2026-10-01"), context(second, "2026-10-06")], "conflict_and_applicability",
         ["Which guidance currently applies, and when does each reported restriction apply?",
          "Please clarify the conflicting guidance and its applicability dates."]),
        ([commitment(text[3], ["Meeting", "Fixed meeting"], "2026-10-19T09:00", "2026-10-19T10:00")], None, []),
        ([commitment(text[4], ["Duty"], "2026-10-19T07:00", "2026-10-19T08:00", recurrence="weekly", interval=2, until="2026-11-30")], None, []),
        ([commitment(travel + ".", ["Travel"], "2026-10-18T09:00", "2026-10-18T10:00"),
          commitment(training, ["Training on Friday evening might repeat", training], None, None, zone=None, recurrence=None)], "training_unknowns",
         ["What are the training date, start and end times, timezone, and recurrence details?",
          "Please clarify the Friday training date, times, timezone, and whether and how it repeats."]),
        ([commitment(corrected + ".", ["Meeting", "Corrected meeting"], "2026-10-20T14:00", "2026-10-20T15:00")], None, []),
        ([context(text[7], "2026-10-17", category="symptom")], "wellness_dates_and_methods",
         ["When did the sleep issue and HRV readings occur, what methods were used, and when do they apply?",
          "When did the poor sleep and HRV readings occur, and what measurement methods were used?"])]
    return {"version": "intake-conformance-oracle-v4", "evaluationMethod": "deterministic_extractive",
            "humanScoringRequired": False, "independentHuman": False, "productionAdmission": "closed",
            "cases": [{"caseId": c["caseId"], "intakeId": p["intake"]["intakeId"],
                       "intakeRevision": p["intake"]["revision"], "suggestions": suggestions,
                       "questionRoles": [] if role is None else [{"id": role, "texts": questions}]}
                      for c, p, (suggestions, role, questions) in zip(artifacts["cases"], packets, rows)]}
