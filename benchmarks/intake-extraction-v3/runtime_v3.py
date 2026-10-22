"""Frozen request/context seam; no authority to operate a runtime."""
import copy
import time

import experiment as e
import replay
import mac_runtime as mac
import observed_mmap_runtime as observed


RUNNER_POLICY = observed.RUNNER_POLICY


def payload(prompt):
    _, artifacts = e.verify()
    e.need(any(e.compact(prompt) == e.compact(c["exactPrompt"]) for c in artifacts["cases"]))
    request = mac.MacRuntime.payload(prompt)
    request["format"] = copy.deepcopy(artifacts["schema"])
    e.need(len(e.compact(request)) <= 65536)
    return request


def bindings():
    _, artifacts = e.verify()
    proof = e.decode((e.HERE / "context-proof-v3.json").read_bytes())
    e.check_context(proof, artifacts)
    return {"manifestSha256": e.digest((e.HERE / "manifest-v3.json").read_bytes()),
            "contextProofSha256": e.digest((e.HERE / "context-proof-v3.json").read_bytes()),
            "schemaSha256": proof["schemaSha256"], "templateSha256": proof["templateSha256"],
            "tokenizerLibrarySha256": proof["tokenizerLibrarySha256"],
            "probeRequestSha256": e.digest(e.compact(probe_payload())),
            "requestSha256": {c["caseId"]: e.digest(e.compact(payload(c["exactPrompt"]))) for c in artifacts["cases"]},
            "renderedSha256": {c["caseId"]: c["renderedSha256"] for c in proof["cases"]}}


def probe_payload():
    return mac.MacRuntime.payload({"systemInstructions": "Return JSON only.",
        "userPacketJson": "Return an object with an integers array containing every integer from 1 through 100000."})


def with_mmap(request):
    """The sole approved provider-request delta; never mutate frozen originals."""
    e.need("use_mmap" not in request["options"])
    result = copy.deepcopy(request)
    result["options"]["use_mmap"] = True
    return result


def mmap_payload(prompt):
    return with_mmap(payload(prompt))


def request_variant():
    """Bind new wire bytes while inheriting only identical rendered context."""
    baseline = bindings()
    _, artifacts = e.verify()
    effective = {}
    for case in artifacts["cases"]:
        original = payload(case["exactPrompt"])
        changed = mmap_payload(case["exactPrompt"])
        stripped = copy.deepcopy(changed)
        e.need(stripped["options"].pop("use_mmap") is True)
        e.need(e.compact(stripped) == e.compact(original))
        e.need(len(e.compact(changed)) <= 65536)
        effective[case["caseId"]] = e.digest(e.compact(changed))
    return {"version": "v3-request-mmap-1", "requestOptionsDelta": {"use_mmap": True},
            "frozenContextProofSha256": baseline["contextProofSha256"],
            "renderedSha256": baseline["renderedSha256"],
            "frozenRequestSha256": baseline["requestSha256"], "requestSha256": effective,
            "frozenProbeRequestSha256": baseline["probeRequestSha256"],
            "probeRequestSha256": e.digest(e.compact(with_mmap(probe_payload())))}


def context(run, show=None):
    _, artifacts = e.verify()
    e.need(run.get("profile") == e.PROFILE)
    model = replay.FAKE_MODEL if run.get("executionMode") == "synthetic_fake" else replay.MODEL
    for key, value in (("experiment", bindings()), ("model", model),
                       ("settings", replay.settings(artifacts)), ("runtime", mac.IDENTITY),
                       ("runnerPolicy", RUNNER_POLICY), ("requestVariant", request_variant())):
        e.need(e.compact(run.get(key)) == e.compact(value))
    if show is not None:
        e.need(type(show) is dict and e.digest(show.get("template", "").encode()) == e.TEMPLATE)
        e.need(not show.get("system") and show.get("model_info", {}).get("tokenizer.ggml.model") == "gpt2")
        # Only this audited branch is supported. No tools/history/hidden system.
        e.need(not show.get("messages"))
    return e.decode((e.HERE / "context-proof-v3.json").read_bytes())


class Runtime(observed.ObservedMmapRuntime):
    """V3-only explicit loader variant; shared adapter/defaults remain unchanged."""
    def __init__(self):
        super().__init__(request_builder=mmap_payload, context_validator=context)

    @staticmethod
    def payload(prompt):
        # Shared probe construction dispatches here; scored requests use the hook.
        return with_mmap(mac.MacRuntime.payload(prompt))

def make_runtime(run):
    e.need(run.get("executionMode") == "live")
    context(run)
    return Runtime()
