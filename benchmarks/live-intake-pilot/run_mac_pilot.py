"""Explicit one-root launcher. A durable owner, never the observing chat, runs work."""
import argparse
import copy
import os
from pathlib import Path
import subprocess
import re
import time
import uuid

import pilot_profile as p
import revision_pilot as revision
import campaign_v4 as c
import lifecycle_v4 as life
import supervisor_v4 as supervisor


def create(directory, authority_file, prompt_file):
    directory = c.private.private_directory(directory)
    authority_file = Path(authority_file)
    raw = c.private.private_read(authority_file)
    authority = p.e.decode(raw)
    p.need(set(authority) == {"version", "executionMode", "recordedBy", "userInstruction", "applyInstruction",
        "campaignDirectory", "limits", "toolingSha256", "counter"})
    p.need(authority["version"] == p.PROFILE and authority["executionMode"] in ("live", "synthetic_fake"))
    p.need(authority["applyInstruction"] == "apply guarded-live-intake-pilot")
    p.need(authority["campaignDirectory"] == str(directory) and authority["limits"] == p.LIMITS)
    p.need(authority["toolingSha256"] == c.tooling(p.PROFILE) and not any(directory.iterdir()))
    prompt = c.private.private_read(Path(prompt_file))
    p.need(p.e.digest(prompt) == p.parent.PROMPT_SHA256)
    p.need(c.private.text(authority["userInstruction"], 2048))
    if authority["executionMode"] == "live":
        p.need(authority["counter"] == p.COUNTER)
    else:
        p.need(authority["counter"] is None)
    # Exclusive consume survives interrupted creation; do not replace this grant/root.
    c.durable(authority_file.with_name(authority_file.name + ".consumed.json"), {
        "authoritySha256": p.e.digest(raw), "campaignDirectory": str(directory)})
    with c.Campaign.create(directory, authority["executionMode"], authority["recordedBy"],
                           authority["userInstruction"], profile=p.PROFILE):
        pass
    c.private.private_write(directory / "candidate.txt", prompt)
    c.private.private_write(directory / "user-authority.json", raw)
    c.durable(directory / "grant-consumer.json", {"authorityFile": str(authority_file.absolute()),
        "authoritySha256": p.e.digest(raw)})
    return "created"


def fake_counts(instructions):
    source = revision.counter_input(instructions)
    return {"version": "intake-conformance-token-evidence-pilot", "tokenizer": revision.TOKENIZER,
        "inputSha256": p.e.digest(p.e.compact(source)), "tensorWeightsLoaded": False,
        "inferenceContextCreated": False, "generationCalled": False, "evidenceMode": "synthetic_fake",
        "cases": [{"caseId": row["caseId"], "tokens": 100, "tokensSha256": "1" * 64,
            "renderedSha256": p.e.digest(p.e.v3.render(row["exactPrompt"])),
            "renderedUtf8Bytes": len(p.e.v3.render(row["exactPrompt"]))} for row in source["cases"]]}


def counter_command(container_id, helper):
    """Resolve only the already pinned running image; never install a library."""
    p.need(type(container_id) is str and re.fullmatch("[0-9a-f]{64}", container_id) is not None)
    library = "/run/k3s/containerd/io.containerd.runtime.v2.task/k8s.io/" + container_id + "/rootfs/usr/lib/ollama"
    return ["limactl", "shell", "--workdir=/", "gtrainer-models", "sudo", "-n", "env", "LD_LIBRARY_PATH=" + library,
        "python3", "-c", helper, "--library", library + "/libllama.so.0.4.1", "--model", p.COUNTER["modelPath"]]


def prepare_counts(campaign, destination, worker):
    deadline = min(campaign.scope["startMonotonic"] + 120, campaign.scope["workCutoffMonotonic"])
    worker.phase("preflight", deadline)
    text = c.private.private_read(campaign.directory / "candidate.txt").decode()
    p.need(p.e.digest(text.encode()) == p.parent.PROMPT_SHA256)
    source = revision.counter_input(text)
    if campaign.scope["executionMode"] == "synthetic_fake":
        evidence = fake_counts(text)
    else:
        authority = c.private.read_json(campaign.directory / "user-authority.json")
        p.need(authority["toolingSha256"] == c.tooling(p.PROFILE))
        p.need(authority["counter"] == p.COUNTER)
        helper = (p.e.HERE / "count_v4.py").read_text()
        c.durable(campaign.directory / "counter.preparation.json", {"inputSha256": p.e.digest(p.e.compact(source)),
            "helperSha256": p.e.digest(helper.encode()), "deadlineMonotonic": deadline})
        inspection = c.v.mac.MacRuntime()
        inspection.deadline = campaign._runtime_deadline(deadline)
        pods = inspection.document(["get", "pods"])["items"]
        p.need(len(pods) == 1 and pods[0]["metadata"].get("labels", {}).get("app") == "ollama")
        pod = pods[0]
        p.need(pod["spec"]["containers"][0]["image"] == c.v.mac.IMAGE)
        statuses = pod["status"]["containerStatuses"]
        p.need(len(statuses) == 1 and statuses[0]["ready"] is True)
        container_id = statuses[0]["containerID"].removeprefix("containerd://")
        command = counter_command(container_id, helper)
        remaining = deadline - campaign._time()
        p.need(remaining > 0)
        c.durable(campaign.directory / "counter.launched.json", {"helperSha256": p.e.digest(helper.encode()),
            "inputSha256": p.e.digest(p.e.compact(source)), "deadlineMonotonic": deadline,
            "originalPodUid": pod["metadata"]["uid"], "containerId": container_id,
            "libraryPath": command[command.index("--library") + 1], "modelPath": p.COUNTER["modelPath"]})
        completed = subprocess.run(command,
            input=p.e.compact({"input": source, "tokenizer": revision.TOKENIZER}),
            capture_output=True, timeout=remaining)
        c.private.private_write(campaign.directory / "counter-stderr.log", completed.stderr)
        p.need(completed.returncode == 0 and 0 < len(completed.stdout) <= 131072 and campaign._time() < deadline)
        evidence = p.e.decode(completed.stdout)
        evidence["evidenceMode"] = "live_vocab_only"
        c.durable(campaign.directory / "counter-result.json", {"helperSha256": p.e.digest(helper.encode()),
            "stdoutSha256": p.e.digest(completed.stdout), "finishedMonotonic": campaign._time(), "exitCode": 0})
    prepared = revision.definition(str(uuid.uuid4()), text, evidence)
    destination.mkdir(mode=0o700)
    for name, value in (("revision.json", prepared), ("context-evidence.json", evidence),
                        ("requests.json", revision.expected_requests(text)), ("token-input.json", source)):
        c.durable(destination / name, value)
    return prepared, evidence


class FakeRuntime:
    synthetic_fake = True

    def __init__(self, run, guard, request_guard, prepared, worker):
        self.run, self.guard, self.request_guard = run, guard, request_guard
        self.prepared, self.worker = prepared, worker

    def preflight(self, run):
        self.guard(run)
        return {"syntheticFake": True, "modelCalls": 0}

    def cancellation_probe(self, run, emit, deadline):
        self.request_guard(run, self.prepared["requestSha256"]["probe"])
        return {"verified": True, "probeNonScored": True, "syntheticFake": True}

    def generate(self, prompt, run, deadline, emit):
        self.request_guard(run, self.prepared["requestSha256"]["u02"])
        # Only this conspicuously fake test transport may access the witness.
        raw = p.e.compact(p.verify()[3]["u02"]).decode()
        return {"outcome": "response", "raw": raw, "resources": {key: None for key in c.v.replay.RESOURCES}}

    def cancel(self, run, deadline, emit):
        return self.worker.cancel(run, deadline, emit)


def work(directory, destination, worker_directory):
    worker = life.Worker(worker_directory, directory)
    with c.Campaign(directory) as campaign:
        p.need(campaign.scope["profile"] == p.PROFILE)
        prepared, evidence = prepare_counts(campaign, destination, worker)
        factory = None
        if campaign.scope["executionMode"] == "synthetic_fake":
            factory = lambda run, guard, request_guard: FakeRuntime(run, guard, request_guard, prepared, worker)
        report = campaign.run_batch(prepared, evidence, lifecycle=worker, runtime_factory=factory)
        return report["classification"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--create", action="store_true")
    action.add_argument("--revision", type=Path)
    parser.add_argument("--authority-file", type=Path)
    parser.add_argument("--prompt-file", type=Path)
    parser.add_argument("--supervised-worker", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--wait", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        if args.create:
            result = create(args.directory, args.authority_file, args.prompt_file)
        elif args.supervised_worker is not None:
            result = work(args.directory, args.revision, args.supervised_worker)
        else:
            result = supervisor.run(args.directory, args.revision, wait=args.wait)
    except BaseException:
        print("Live intake pilot fenced; inspect private evidence. No retry or replacement root.")
        return 2
    print("Live intake pilot status: " + result + ". Production admission closed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
