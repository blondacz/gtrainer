"""One-case bounds and unchanged packet-aware Kotlin validation."""
import pilot_profile as p
import replay_v5 as parent


def cases_from(source, run):
    p.need(type(source) is dict and set(source) == {"version", "cases"})
    p.need(source["version"] == "intake-conformance-responses-pilot")
    p.need(len(source["cases"]) <= 1)
    result = []
    for row in source["cases"]:
        p.need(set(row) == {"caseId", "outcome", "raw", "resources"} and row["caseId"] == "u02")
        p.need(row["outcome"] in ("response", "empty", "timeout", "incomplete", "unrun"))
        p.need(row["raw"] is None or type(row["raw"]) is str and len(row["raw"].encode()) <= 12288)
        p.need(row["outcome"] != "response" or type(row["raw"]) is str and bool(row["raw"].strip()))
        p.need(row["outcome"] != "unrun" or row["raw"] is None)
        p.need(type(row["resources"]) is dict and set(row["resources"]) == parent.RESOURCES)
        for key, value in row["resources"].items():
            p.need(value is None or parent.private.bounded_number(value, 10**15))
            if key in ("peakRssBytes", "inputTokens", "outputTokens"):
                p.need(value is None or type(value) is int)
        if run["inputOrigin"] == "synthetic_fake":
            p.need(all(value is None for value in row["resources"].values()))
        result.append(row)
    return result


def kotlin_results(directory, requests, *, timeout_seconds=600):
    p.need(len(requests) == 1 and requests[0]["caseId"] == "u02")
    return parent.kotlin_results(directory, requests, timeout_seconds=timeout_seconds)
