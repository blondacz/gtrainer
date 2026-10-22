"""Deterministic typed/extractive predicates, not human or model scoring."""
import itertools

import experiment_v4 as e

MISSING = object()


def same(a, b):
    if type(a) is not type(b): return False
    if isinstance(b, dict): return set(a) == set(b) and all(same(a[k], b[k]) for k in b)
    if isinstance(b, list): return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    return a == b


def at(value, path):
    for key in path.split("."):
        if not isinstance(value, dict) or key not in value: return MISSING
        value = value[key]
    return value


def field_matches(actual, path, alternatives):
    if path == "unresolvedFields":
        return (type(actual) is list and all(type(v) is str for v in actual)
                and len(actual) == len(set(actual)) and any(set(actual) == set(option) for option in alternatives))
    return any(same(actual, option) for option in alternatives)


def check_draft(case_id, draft, contract):
    rules = next((c for c in contract["cases"] if c["caseId"] == case_id), None)
    e.need(rules is not None)
    errors = []
    if not isinstance(draft, dict): return ["draft_shape"]
    for field, value in (("profile", "context-intake-suggestion-v1"), ("intakeId", rules["intakeId"]), ("intakeRevision", rules["intakeRevision"])):
        if not same(draft.get(field), value): errors.append("identity_" + field)
    expected = rules["suggestions"]
    actual = draft.get("suggestions")
    if type(actual) is not list or len(actual) != len(expected):
        errors.append("suggestion_count")
    else:
        candidates = []
        for ordering in itertools.permutations(actual):
            mismatches = [f"suggestion_{i}.{field}" for i, (row, fields) in enumerate(zip(ordering, expected))
                          for field, alternatives in fields.items() if not field_matches(at(row, field), field, alternatives)]
            candidates.append(mismatches)
        errors.extend(min(candidates, key=lambda items: (len(items), items)))
    questions = draft.get("questions")
    roles = rules["questionRoles"]
    if type(questions) is not list or len(questions) != len(roles):
        errors.append("question_roles")
    else:
        expected_texts = [role["texts"] for role in roles]
        texts = [q.get("text") if isinstance(q, dict) and set(q) == {"text"} else None for q in questions]
        if not any(all(type(text) is str and text in allowed for text, allowed in zip(order, expected_texts))
                   for order in itertools.permutations(texts)):
            errors.append("question_roles")
    return sorted(set(errors))


def evaluate(case_id, stages, contract=None):
    if contract is None: _, _, contract, _ = e.verify()
    if (type(stages) is not dict or any(stages.get(stage) != "accepted" for stage in ("wire", "binding", "semantic"))):
        return {"status": "not_run", "reasons": ["local_validation_not_accepted"]}
    assembled = stages.get("assembledDraft")
    if type(assembled) is not str or not 0 < len(assembled.encode()) <= 12288:
        return {"status": "failed", "reasons": ["assembled_draft_invalid"]}
    try: draft = e.decode(assembled.encode())
    except (ValueError, UnicodeError): return {"status": "failed", "reasons": ["assembled_draft_invalid"]}
    errors = check_draft(case_id, draft, contract)
    return {"status": "failed" if errors else "passed", "reasons": errors}
