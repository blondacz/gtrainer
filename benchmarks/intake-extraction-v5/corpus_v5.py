"""Prospective synthetic inputs and witnesses, authored before candidate inference.

These witnesses are offline tests only, never candidate prompt demonstrations.
"""
import copy
import uuid

import fixtures_v4 as witness
import rules_v4 as rules

TEXTS = [
    "I have an appointment booked for 2026-11-03, 11:15 to 12:00 UTC.",
    "Travel on 2026-11-04, 08:00 to 09:00 UTC. Training on Saturday afternoon may repeat; times and timezone are unknown.",
    "A rehearsal on Tuesday evening is tentative; its date, times, timezone and repetition are undecided.",
    "🧭 Correction: meeting on 2026-11-05 is 16:30 to 17:15 UTC, not 15:30 to 16:15 UTC.",
    "My shift is set for 2026-11-06, starting 06:45 and finishing 08:15 UTC.",
    "Clinician report on 2026-10-12: avoid cycling. Coach report on 2026-10-16: cycling permitted. Applicability dates are unknown.",
    "Knee pain began on 2026-10-09 and is still present; no recovery date has been reported.",
    "Duty on 2026-11-07 from 13:00 to 14:00 UTC, repeating every 3 weeks until 2026-12-19.",
]
QUESTIONS = {
    "u02": ("training_unknowns", [
        "What are the training date, start and end times, timezone, and recurrence details?",
        "What are the practice date, start and end times, timezone, and recurrence details?",
        "Please clarify the Saturday training date, times, timezone, and whether and how it repeats."]),
    "u03": ("rehearsal_unknowns", [
        "What are the rehearsal date, start and end times, timezone, and recurrence details?",
        "Please clarify the Tuesday rehearsal date, times, timezone, and whether and how it repeats."]),
    "u06": ("conflict_and_applicability", [
        "Please clarify the conflicting guidance and its applicability dates.",
        "Which guidance currently applies, and when does each reported restriction apply?"]),
    "u07": ("symptom_end", [
        "When did the knee pain end, if it has ended?",
        "When did the pain end, if it has ended?",
        "What is the knee pain applicability end date, if known?"]),
}


def packet(case_id, text):
    return {"profile": "context-intake-suggestion-v1", "intake": {
        "intakeId": str(uuid.uuid5(uuid.NAMESPACE_URL, "gtrainer:intake-v5:" + case_id)),
        "revision": 2 if case_id == "u04" else 1, "content": text,
        "sourceCategory": "user_report", "authorAttribution": "Synthetic athlete",
        "enteredBy": "authenticated_user", "observedOn": "2026-10-21",
        "recordedUtc": "2026-10-21T12:00:00Z"}, "dateResolution": None}


def suggestions():
    travel, training = TEXTS[1].split(". ", 1)
    clinician, coach = TEXTS[5].split(". ", 1)
    return [
        [witness.commitment(TEXTS[0], "Appointment", "2026-11-03T11:15", "2026-11-03T12:00")],
        [witness.commitment(travel + ".", "Travel", "2026-11-04T08:00", "2026-11-04T09:00"),
         witness.commitment(training, training, None, None, zone=None, frequency=None)],
        [witness.commitment(TEXTS[2], TEXTS[2], None, None, zone=None, frequency=None)],
        [witness.commitment(TEXTS[3], "Meeting", "2026-11-05T16:30", "2026-11-05T17:15")],
        [witness.commitment(TEXTS[4], "Shift", "2026-11-06T06:45", "2026-11-06T08:15")],
        [witness.context(clinician + ".", "2026-10-12"), witness.context(coach, "2026-10-16")],
        [witness.context(TEXTS[6], "2026-10-21", start="2026-10-09", start_mode="specified", category="symptom")],
        [witness.commitment(TEXTS[7], "Duty", "2026-11-07T13:00", "2026-11-07T14:00",
                            frequency="weekly", interval=3, until="2026-12-19")],
    ]


def predicates():
    travel, training = TEXTS[1].split(". ", 1)
    clinician, coach = TEXTS[5].split(". ", 1)
    return [
        [rules.commitment(TEXTS[0], ["Appointment"], "2026-11-03T11:15", "2026-11-03T12:00")],
        [rules.commitment(travel + ".", ["Travel"], "2026-11-04T08:00", "2026-11-04T09:00"),
         rules.commitment(training, [training, "Training on Saturday afternoon may repeat"], None, None, zone=None, recurrence=None)],
        [rules.commitment(TEXTS[2], [TEXTS[2], "A rehearsal on Tuesday evening is tentative"], None, None, zone=None, recurrence=None)],
        [rules.commitment(TEXTS[3], ["Meeting", "Corrected meeting"], "2026-11-05T16:30", "2026-11-05T17:15")],
        [rules.commitment(TEXTS[4], ["Shift"], "2026-11-06T06:45", "2026-11-06T08:15")],
        [rules.context(clinician + ".", "2026-10-12"), rules.context(coach, "2026-10-16")],
        [rules.context(TEXTS[6], "2026-10-21", start="2026-10-09", start_mode="specified", category="symptom")],
        [rules.commitment(TEXTS[7], ["Duty"], "2026-11-07T13:00", "2026-11-07T14:00",
                          recurrence="weekly", interval=3, until="2026-12-19")],
    ]


def documents(e, profile, ids):
    packets = [packet(case_id, text) for case_id, text in zip(ids, TEXTS)]
    inputs = {"version": "intake-conformance-inputs-v5", "profile": profile,
              "schema": copy.deepcopy(e.verify()[1]["schema"]),
              "cases": [{"caseId": case_id, "userPacketJson": e.compact(p).decode()}
                        for case_id, p in zip(ids, packets)]}
    contract = {"version": "intake-conformance-oracle-v5", "evaluationMethod": "deterministic_extractive",
                "independentHuman": False, "productionAdmission": "closed", "cases": []}
    fixtures = {}
    for case_id, p, actual, expected in zip(ids, packets, suggestions(), predicates()):
        role, texts = QUESTIONS.get(case_id, (None, []))
        contract["cases"].append({"caseId": case_id, "intakeId": p["intake"]["intakeId"],
            "intakeRevision": p["intake"]["revision"], "suggestions": expected,
            "questionRoles": [] if role is None else [{"id": role, "texts": texts}]})
        fixtures[case_id] = {"suggestions": actual, "questions": [] if not texts else [{"text": texts[0]}]}
    return inputs, contract, fixtures
