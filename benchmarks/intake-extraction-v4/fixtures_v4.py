"""Handwritten synthetic test witnesses; not model outputs or live prompt examples."""
import json


def context(text, observed, start=None, end=None, start_mode="unknown", end_mode="unknown", category="restriction"):
    return {"kind": "context", "sourceExcerpt": text,
            "context": {"category": category, "observedOn": observed, "applicableFrom": start,
                        "applicableUntil": end, "applicableFromMode": start_mode,
                        "applicableUntilMode": end_mode, "content": text},
            "commitment": None, "unresolvedFields": [f for f, mode in
                (("applicableFrom", start_mode), ("applicableUntil", end_mode)) if mode == "unknown"]}


def commitment(text, label, start, end, zone="UTC", frequency="one_off", interval=None, until=None):
    recurrence = None if frequency is None else {"frequency": frequency, "interval": interval,
                 "endMode": "bounded" if until else "open", "untilDate": until}
    return {"kind": "commitment", "sourceExcerpt": text, "context": None,
            "commitment": {"label": label, "kind": "commitment", "startsLocal": start, "endsLocal": end,
                           "timeZone": zone, "recurrence": recurrence},
            "unresolvedFields": ["startsLocal", "endsLocal", "timeZone", "recurrence"] if frequency is None else []}


def vectors(artifacts):
    t = [json.loads(c["exactPrompt"]["userPacketJson"])["intake"]["content"] for c in artifacts["cases"]]
    split1, split2 = t[2].split(". ", 1)
    travel, training = t[5].split(". ", 1)
    suggestions = [
        [context(t[0], "2026-06-01", end_mode="open")],
        [context(t[1], "2026-10-17", start="2026-10-01", start_mode="specified", category="symptom")],
        [context(split1 + ".", "2026-10-01"), context(split2, "2026-10-06")],
        [commitment(t[3], "Meeting", "2026-10-19T09:00", "2026-10-19T10:00")],
        [commitment(t[4], "Duty", "2026-10-19T07:00", "2026-10-19T08:00", frequency="weekly", interval=2, until="2026-11-30")],
        [commitment(travel + ".", "Travel", "2026-10-18T09:00", "2026-10-18T10:00"),
         commitment(training, "Training on Friday evening might repeat", None, None, zone=None, frequency=None)],
        [commitment(t[6].split(". Ignore", 1)[0] + ".", "Meeting", "2026-10-20T14:00", "2026-10-20T15:00")],
        [context(t[7], "2026-10-17", category="symptom")]]
    questions = ["When did the no-running restriction begin?", "When did the illness end, if it has ended?",
                 "Which guidance currently applies, and when does each reported restriction apply?", None, None,
                 "What are the training date, start and end times, timezone, and recurrence details?", None,
                 "When did the sleep issue and HRV readings occur, what methods were used, and when do they apply?"]
    return {c["caseId"]: {"suggestions": s, "questions": [] if q is None else [{"text": q}]}
            for c, s, q in zip(artifacts["cases"], suggestions, questions)}
