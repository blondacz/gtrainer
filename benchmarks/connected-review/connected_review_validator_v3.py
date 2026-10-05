"""Python parity guard for InterpretationContractV1.decodeDraft hard checks."""
from datetime import date
from decimal import Decimal, InvalidOperation
import json
import math
import re


PROFILE = "connected-review-v1"
EVIDENCE_FIELDS = {
    "schemaVersion", "evidenceReportSha256", "evaluatedOnUtc", "selectedSport", "facts",
    "comparisons", "sourceStatus", "unavailable", "limitations",
}
FACT_FIELDS = {
    "evidenceId", "period", "oldest", "newest", "sport", "metric", "label", "unit",
    "aggregation", "value", "sampleCount", "observedDays", "periodDays", "firstObservedDate",
    "lastObservedDate", "sources", "metricOrigins", "recordOrigins", "unknownMetricOriginCount", "flags",
}
CONTEXT_FIELDS = {
    "contextId", "revision", "category", "sourceCategory", "authorAttribution", "enteredBy",
    "observedOn", "applicableFrom", "applicableUntil", "sport", "activityId", "reviewId", "content",
    "retired", "restrictionKind", "restrictionValue", "restrictionUnit",
}
SOURCES = {"user_report", "clinician_guidance", "coach_guidance", "review_feedback"}
CATEGORIES = {"restriction", "symptom", "goal", "preference", "feedback", "note"}
UNCERTAINTY = {"low", "moderate", "high", "unknown"}
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
ISO_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
NUMBER = re.compile(r"(?<![A-Za-z])\d+(?:[.,]\d+)?")
PROHIBITED = [
    re.compile(r"\b(diagnos(?:e|ed|es|ing|is)|medical diagnosis)\b", re.I),
    re.compile(r"\b(prescrib(?:e|es|ed|ing)|prescription|workout plan|training plan)\b", re.I),
    re.compile(r"\b(?:you|athlete) should (?:run|ride|train|exercise|work out|do)\b", re.I),
    re.compile(r"\b(sport[- ]safety clearance|medical clearance|cleared to (?:run|ride|train|exercise)|safe to (?:run|ride|train|exercise))\b", re.I),
    re.compile(r"\b(caus(?:e|es|ed|ing|al)|resulted in|due to|proves? that)\b", re.I),
    re.compile(r"\b(clinician verified|verified by (?:a )?clinician|coach verified|confirmed by (?:a )?clinician)\b", re.I),
]
CLINICIAN = re.compile(r"\b(clinician|doctor|physician|medical provider)\b", re.I)
COACH = re.compile(r"\b(coach|trainer)\b", re.I)
USER_ATTRIBUTION = re.compile(r"\b(user[- ]reported|reported by (?:the )?user|you entered|your report)\b", re.I)
POSITIVE_PERMISSION = re.compile(r"\b(allowed|permitted|safe|resume|cleared|can do)\b", re.I)
NEGATIVE_PERMISSION = re.compile(r"\b(blocked|prohibited|forbidden|must avoid|cannot do|not allowed)\b", re.I)
DURATION = re.compile(r"\b([0-9]+(?:\.[0-9]+)?)\s*(?:min|mins|minute|minutes)\b", re.I)


def _date(value):
    if not isinstance(value, str) or not ISO_DATE.fullmatch(value):
        raise ValueError("invalid date")
    return date.fromisoformat(value)


def _numbers(value):
    return {Decimal(item.replace(",", ".")).normalize() for item in NUMBER.findall(value)}


def _valid_context(context):
    if set(context) != CONTEXT_FIELDS or not UUID.fullmatch(context["contextId"]):
        return False
    if not isinstance(context["revision"], int) or isinstance(context["revision"], bool) or context["revision"] <= 0:
        return False
    if context["retired"] is not False or context["category"] not in CATEGORIES or context["sourceCategory"] not in SOURCES:
        return False
    observed = _date(context["observedOn"])
    starts = _date(context["applicableFrom"]) if context["applicableFrom"] is not None else None
    ends = _date(context["applicableUntil"]) if context["applicableUntil"] is not None else None
    if starts is not None and ends is not None and starts > ends:
        return False
    if not isinstance(context["content"], str) or not context["content"].strip():
        return False
    if context["category"] != "restriction":
        return context["restrictionKind"] is None and context["restrictionValue"] is None and context["restrictionUnit"] is None
    kind, value, unit = context["restrictionKind"], context["restrictionValue"], context["restrictionUnit"]
    if kind in {"blocked_activity", "blocked_sport", "allowed_activity", "allowed_sport"}:
        return isinstance(value, str) and bool(value.strip()) and unit is None
    if kind == "maximum_duration":
        try:
            return Decimal(value) > 0 and unit == "minutes"
        except (InvalidOperation, TypeError):
            return False
    return False


def _packet_valid(packet):
    if not isinstance(packet, dict) or set(packet) != {"profile", "evidence", "context"} or packet["profile"] != PROFILE:
        return False
    evidence, contexts = packet["evidence"], packet["context"]
    if not isinstance(evidence, dict) or set(evidence) != EVIDENCE_FIELDS or not isinstance(contexts, list):
        return False
    if not re.fullmatch(r"[a-f0-9]{64}", evidence["evidenceReportSha256"]):
        return False
    facts = evidence["facts"]
    if not isinstance(facts, list) or not isinstance(evidence["selectedSport"], (str, type(None))):
        return False
    fact_ids = []
    for fact in facts:
        if not isinstance(fact, dict) or set(fact) != FACT_FIELDS:
            return False
        fact_ids.append(fact["evidenceId"])
        oldest, newest = _date(fact["oldest"]), _date(fact["newest"])
        if not isinstance(fact["evidenceId"], str) or not fact["evidenceId"].strip() or oldest > newest:
            return False
        for field in ("sampleCount", "observedDays", "periodDays", "unknownMetricOriginCount"):
            if not isinstance(fact[field], int) or isinstance(fact[field], bool):
                return False
        for field in ("firstObservedDate", "lastObservedDate"):
            if fact[field] is not None:
                _date(fact[field])
        if fact["value"] is not None and (
            isinstance(fact["value"], bool) or not isinstance(fact["value"], (int, float)) or
            not math.isfinite(float(fact["value"]))
        ):
            return False
    if len(set(fact_ids)) != len(fact_ids):
        return False
    if any(not isinstance(context, dict) or not _valid_context(context) for context in contexts):
        return False
    revision_ids = [(context["contextId"], context["revision"]) for context in contexts]
    if len(set(revision_ids)) != len(revision_ids):
        return False
    restrictions = [context for context in contexts if context["category"] == "restriction"]
    for index, left in enumerate(restrictions):
        for right in restrictions[index + 1:]:
            if left["sport"] != right["sport"] or left["activityId"] != right["activityId"] or left["reviewId"] != right["reviewId"]:
                continue
            left_start = _date(left["applicableFrom"]) if left["applicableFrom"] else None
            right_start = _date(right["applicableFrom"]) if right["applicableFrom"] else None
            left_end = _date(left["applicableUntil"]) if left["applicableUntil"] else None
            right_end = _date(right["applicableUntil"]) if right["applicableUntil"] else None
            if left_end and right_start and left_end < right_start or right_end and left_start and right_end < left_start:
                continue
            kinds = {left["restrictionKind"], right["restrictionKind"]}
            same = left["restrictionValue"].strip().lower() == right["restrictionValue"].strip().lower()
            blocked_allowed = kinds in ({"blocked_activity", "allowed_activity"}, {"blocked_sport", "allowed_sport"}) and same
            different_maximum = kinds == {"maximum_duration"} and Decimal(left["restrictionValue"]) != Decimal(right["restrictionValue"])
            if blocked_allowed or different_maximum:
                return False
    return True


def validate(raw, packet):
    """Return acceptance parity with Kotlin whole-draft checks for this contract."""
    try:
        if not isinstance(raw, str) or len(raw.encode("utf-8")) > 24576 or not _packet_valid(packet):
            return False
        draft = json.loads(raw)
        if not isinstance(draft, dict) or set(draft) != {"profile", "interpretations", "questions"}:
            return False
        if draft["profile"] != PROFILE:
            return False
        interpretations, questions = draft["interpretations"], draft["questions"]
        if not isinstance(interpretations, list) or not isinstance(questions, list) or len(interpretations) > 8 or len(questions) > 8:
            return False
        evidence = packet["evidence"]
        facts_by_id = {fact["evidenceId"]: fact for fact in evidence["facts"]}
        contexts_by_ref = {(item["contextId"], item["revision"]): item for item in packet["context"]}
        for claim in interpretations + questions:
            if not isinstance(claim, dict) or set(claim) != {"text", "sources", "scope", "uncertainty"}:
                return False
            text, sources, scope = claim["text"], claim["sources"], claim["scope"]
            if not isinstance(text, str) or not text.strip() or len(text) > 1000 or any(pattern.search(text) for pattern in PROHIBITED):
                return False
            if not isinstance(sources, dict) or set(sources) != {"evidenceIds", "context"}:
                return False
            evidence_ids, context_refs = sources["evidenceIds"], sources["context"]
            if not isinstance(evidence_ids, list) or not isinstance(context_refs, list) or not 1 <= len(evidence_ids) + len(context_refs) <= 12:
                return False
            if len(set(evidence_ids)) != len(evidence_ids) or any(evidence_id not in facts_by_id for evidence_id in evidence_ids):
                return False
            for ref in context_refs:
                if not isinstance(ref, dict) or set(ref) != {"contextId", "revision"}:
                    return False
            refs = [(ref["contextId"], ref["revision"]) for ref in context_refs]
            if len(set(refs)) != len(refs) or any(ref not in contexts_by_ref or ref[1] <= 0 for ref in refs):
                return False
            cited_facts = [facts_by_id[evidence_id] for evidence_id in evidence_ids]
            cited_contexts = [contexts_by_ref[ref] for ref in refs]
            if CLINICIAN.search(text) and (not any(c["sourceCategory"] == "clinician_guidance" for c in cited_contexts) or not USER_ATTRIBUTION.search(text)):
                return False
            if COACH.search(text) and (not any(c["sourceCategory"] == "coach_guidance" for c in cited_contexts) or not USER_ATTRIBUTION.search(text)):
                return False
            if not isinstance(scope, dict) or set(scope) != {"from", "until", "sport"}:
                return False
            start, end = _date(scope["from"]), _date(scope["until"])
            if start > end:
                return False
            starts = [_date(fact["oldest"]) for fact in cited_facts]
            starts += [_date(c["applicableFrom"]) for c in cited_contexts if c["applicableFrom"]]
            ends = [_date(fact["newest"]) for fact in cited_facts]
            ends += [_date(c["applicableUntil"]) for c in cited_contexts if c["applicableUntil"]]
            if starts and start < max(starts) or ends and end > min(ends):
                return False
            if any(not start <= _date(context["observedOn"]) <= end for context in cited_contexts):
                return False
            sports = [fact["sport"] for fact in cited_facts if fact["sport"] is not None]
            sports += [context["sport"] for context in cited_contexts if context["sport"] is not None]
            sports = list(dict.fromkeys(sports)) or ([evidence["selectedSport"]] if evidence["selectedSport"] is not None else [])
            if len(sports) > 1:
                return False
            if sports and scope["sport"] != sports[0]:
                return False
            if not sports and scope["sport"] is not None and (not isinstance(scope["sport"], str) or not 1 <= len(scope["sport"]) <= 80):
                return False
            source_numbers = set()
            for fact in cited_facts:
                values = [fact["oldest"], fact["newest"], fact["value"], fact["sampleCount"], fact["observedDays"],
                          fact["periodDays"], fact["firstObservedDate"], fact["lastObservedDate"]]
                source_numbers.update(_numbers(" ".join(str(value) for value in values if value is not None)))
            for context in cited_contexts:
                values = [context["content"], context["observedOn"], context["applicableFrom"],
                          context["applicableUntil"], context["restrictionValue"]]
                source_numbers.update(_numbers(" ".join(str(value) for value in values if value is not None)))
            if not _numbers(text) <= source_numbers:
                return False
            for context in cited_contexts:
                target = (context["restrictionValue"] or "").strip()
                if context["category"] == "restriction":
                    kind = context["restrictionKind"]
                    mentions_target = bool(target) and target.lower() in text.lower()
                    if mentions_target and kind in {"blocked_activity", "blocked_sport"} and POSITIVE_PERMISSION.search(text):
                        return False
                    if mentions_target and kind in {"allowed_activity", "allowed_sport"} and NEGATIVE_PERMISSION.search(text):
                        return False
                    if kind == "maximum_duration":
                        maximum = Decimal(context["restrictionValue"])
                        if any(Decimal(match) > maximum for match in DURATION.findall(text)):
                            return False
            if claim["uncertainty"] not in UNCERTAINTY:
                return False
        return True
    except (KeyError, TypeError, ValueError, InvalidOperation, OverflowError, json.JSONDecodeError):
        return False


def validate_detailed(raw, packet):
    """Return stable error categories while leaving hard acceptance unchanged."""
    if not isinstance(raw, str) or len(raw.encode("utf-8")) > 24576:
        return ["response_budget_exceeded"]
    if not _packet_valid(packet):
        return ["invalid_packet"]
    try:
        draft = json.loads(raw)
    except (ValueError, TypeError):
        return ["malformed_json"]
    if not isinstance(draft, dict) or set(draft) != {"profile", "interpretations", "questions"}:
        return ["draft_shape_invalid"]
    if draft.get("profile") != PROFILE:
        return ["draft_profile_invalid"]
    interpretations, questions = draft.get("interpretations"), draft.get("questions")
    if not isinstance(interpretations, list) or not isinstance(questions, list):
        return ["claim_lists_invalid"]
    if len(interpretations) > 8 or len(questions) > 8:
        return ["claim_count_exceeded"]

    evidence = packet["evidence"]
    facts = {fact["evidenceId"]: fact for fact in evidence["facts"]}
    contexts = {(context["contextId"], context["revision"]): context for context in packet["context"]}
    errors = set()
    for claim in interpretations + questions:
        if not isinstance(claim, dict) or set(claim) != {"text", "sources", "scope", "uncertainty"}:
            errors.add("claim_shape_invalid")
            continue
        text, sources, scope = claim["text"], claim["sources"], claim["scope"]
        if not isinstance(text, str) or not text.strip() or len(text) > 1000:
            errors.add("claim_text_invalid")
            continue
        if any(pattern.search(text) for pattern in PROHIBITED):
            errors.add("prohibited_claim")
        if not isinstance(sources, dict) or set(sources) != {"evidenceIds", "context"}:
            errors.add("sources_shape_invalid")
            continue
        evidence_ids, context_refs = sources["evidenceIds"], sources["context"]
        if not isinstance(evidence_ids, list) or not isinstance(context_refs, list) or not 1 <= len(evidence_ids) + len(context_refs) <= 12:
            errors.add("source_count_invalid")
            continue
        if len(set(evidence_ids)) != len(evidence_ids):
            errors.add("duplicate_evidence_reference")
        cited_facts = []
        for evidence_id in evidence_ids:
            if evidence_id not in facts:
                errors.add("unsupported_evidence_reference")
            else:
                cited_facts.append(facts[evidence_id])
        cited_contexts = []
        refs = []
        for ref in context_refs:
            if not isinstance(ref, dict) or set(ref) != {"contextId", "revision"}:
                errors.add("context_reference_shape_invalid")
                continue
            key = (ref["contextId"], ref["revision"])
            refs.append(key)
            if key not in contexts or not isinstance(key[1], int) or key[1] <= 0:
                errors.add("unsupported_context_reference")
            else:
                cited_contexts.append(contexts[key])
        if len(set(refs)) != len(refs):
            errors.add("duplicate_context_reference")
        if CLINICIAN.search(text) and (not any(c["sourceCategory"] == "clinician_guidance" for c in cited_contexts) or not USER_ATTRIBUTION.search(text)):
            errors.add("clinician_attribution_invalid")
        if COACH.search(text) and (not any(c["sourceCategory"] == "coach_guidance" for c in cited_contexts) or not USER_ATTRIBUTION.search(text)):
            errors.add("coach_attribution_invalid")
        if not isinstance(scope, dict) or set(scope) != {"from", "until", "sport"}:
            errors.add("scope_shape_invalid")
            continue
        try:
            start, end = _date(scope["from"]), _date(scope["until"])
        except (ValueError, TypeError):
            errors.add("scope_date_invalid")
            start = end = None
        if start is not None and end is not None:
            if start > end:
                errors.add("scope_date_order_invalid")
            starts = [_date(f["oldest"]) for f in cited_facts]
            starts += [_date(c["applicableFrom"]) for c in cited_contexts if c["applicableFrom"]]
            ends = [_date(f["newest"]) for f in cited_facts]
            ends += [_date(c["applicableUntil"]) for c in cited_contexts if c["applicableUntil"]]
            if starts and start < max(starts) or ends and end > min(ends):
                errors.add("scope_outside_cited_period")
            if any(not start <= _date(c["observedOn"]) <= end for c in cited_contexts):
                errors.add("scope_omits_context_observation")
        sports = [f["sport"] for f in cited_facts if f["sport"] is not None]
        sports += [c["sport"] for c in cited_contexts if c["sport"] is not None]
        sports = list(dict.fromkeys(sports)) or ([evidence["selectedSport"]] if evidence["selectedSport"] is not None else [])
        if len(sports) > 1 or (sports and scope["sport"] != sports[0]):
            errors.add("scope_sport_mismatch")
        elif not sports and scope["sport"] is not None and (not isinstance(scope["sport"], str) or not 1 <= len(scope["sport"]) <= 80):
            errors.add("scope_sport_invalid")
        source_numbers = set()
        for fact in cited_facts:
            values = [fact["oldest"], fact["newest"], fact["value"], fact["sampleCount"], fact["observedDays"], fact["periodDays"], fact["firstObservedDate"], fact["lastObservedDate"]]
            source_numbers.update(_numbers(" ".join(str(value) for value in values if value is not None)))
        for context in cited_contexts:
            values = [context["content"], context["observedOn"], context["applicableFrom"], context["applicableUntil"], context["restrictionValue"]]
            source_numbers.update(_numbers(" ".join(str(value) for value in values if value is not None)))
        if not _numbers(text) <= source_numbers:
            errors.add("unsupported_numeric_claim")
        for context in cited_contexts:
            target = (context["restrictionValue"] or "").strip()
            if context["category"] != "restriction":
                continue
            mentions_target = bool(target) and target.lower() in text.lower()
            kind = context["restrictionKind"]
            if mentions_target and kind in {"blocked_activity", "blocked_sport"} and POSITIVE_PERMISSION.search(text):
                errors.add("restriction_contradiction")
            if mentions_target and kind in {"allowed_activity", "allowed_sport"} and NEGATIVE_PERMISSION.search(text):
                errors.add("restriction_contradiction")
            if kind == "maximum_duration" and any(Decimal(value) > Decimal(context["restrictionValue"]) for value in DURATION.findall(text)):
                errors.add("restriction_duration_exceeded")
        if claim["uncertainty"] not in UNCERTAINTY:
            errors.add("uncertainty_invalid")
    if not errors and not validate(raw, packet):
        errors.add("contract_validation_unclassified")
    return sorted(errors)
