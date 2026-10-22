"""Private acquisition-to-app admission. Hashes are checked against registered ownership."""
import argparse
import json
from pathlib import Path
import sys

import pilot_profile as p
import revision_pilot as revision
import campaign_v4 as c
import supervisor_v4 as supervisor
import lifecycle_v4 as life


def read(path):
    c.private.private_directory(path.parent)
    return c.private.private_read(path)


def assemble(directory):
    directory = c.private.private_directory(directory)
    p.need(directory == directory.resolve())
    p.need(directory.is_relative_to(supervisor.BASE) and not directory.is_relative_to(p.ROOT))
    with c.Campaign(directory) as campaign:
        p.need(campaign.scope["profile"] == p.PROFILE and len(campaign.receipts) == 1)
        mode = campaign.scope["executionMode"]
        grant = c.private.read_json(directory / "user-authority.json")
        p.need(grant["version"] == p.PROFILE and grant["executionMode"] == mode and grant["limits"] == p.LIMITS)
        p.need(grant["toolingSha256"] == campaign.scope["toolingSha256"] == c.tooling(p.PROFILE))
        p.need(grant["campaignDirectory"] == str(directory))
        consumer = c.private.read_json(directory / "grant-consumer.json")
        original = Path(consumer["authorityFile"])
        p.need(original.is_absolute() and original == original.resolve() and original.is_relative_to(supervisor.BASE))
        p.need(read(original) == read(directory / "user-authority.json"))
        p.need(consumer["authoritySha256"] == p.e.digest(read(original)))
        consumed = c.private.read_json(original.with_name(original.name + ".consumed.json"))
        p.need(consumed == {"authoritySha256": consumer["authoritySha256"], "campaignDirectory": str(directory)})
        terminal = c.private.read_json(directory / "terminal.json")
        report = campaign.receipts[0][1]
        p.need(terminal["status"] in ("passed", "failed") and terminal["batchCount"] == 1)
        p.need(report["classification"] in ("passed", "quality_failure") and report["probeVerified"] is True)
        p.need(report["stopVerified"] is True and report["errorCategory"] is None and report["deadlineBreach"] is False)
        p.need(report["profile"] == p.PROFILE and [row["caseId"] for row in report["cases"]] == ["u02"])
        registration = c.private.read_json(directory / "supervisor-01.json")
        supervision = c.private.private_directory(Path(registration["directory"]))
        p.need(supervision == supervision.resolve() and supervision.is_relative_to(supervisor.BASE))
        job = c.private.read_json(supervision / "job.json")
        outcome = c.private.read_json(supervision / "outcome.json")
        cleanup = c.private.read_json(supervision / "cleanup-result.json")
        binding = c.private.read_json(supervision / "binding.json")
        p.need(job["mode"] == ("live" if mode == "live" else "pilot_fake"))
        p.need(job["profile"] == p.PROFILE and job["campaign"] == str(directory))
        p.need(job["campaignSha256"] == p.e.digest(read(directory / "campaign.json")))
        p.need(outcome["reason"] == "normal" and outcome["cleanup"]["verified"] is True and outcome["workerExitCode"] == 0)
        p.need(cleanup["verified"] is True)
        p.need(binding["claim"] == c.private.read_json(directory / "claim-01.json"))
        batch = directory / "batch-01"
        p.need(binding["batch"] == str(batch) and binding["run"] == c.private.read_json(batch / "run.json"))
        p.need(binding["run"]["executionMode"] == mode and binding["run"]["restoreOriginalService"] is False)
        source = c.private.read_json(batch / "revision.json")
        counts = c.private.read_json(batch / "context-evidence.json")
        requests = revision.check_revision(source, counts)
        p.need(source["contextEvidenceMode"] == ("live_vocab_only" if mode == "live" else "synthetic_fake"))
        if mode == "live":
            p.need((directory / "counter.launched.json").exists() and (directory / "counter-result.json").exists())
            counter_intent = c.private.read_json(directory / "counter.launched.json")
            counter_result = c.private.read_json(directory / "counter-result.json")
            p.need(counter_intent["helperSha256"] == counter_result["helperSha256"] == source["counterToolSha256"])
            p.need(counter_intent["inputSha256"] == counts["inputSha256"])
            p.need(grant["counter"] == p.COUNTER and counter_intent["modelPath"] == p.COUNTER["modelPath"])
            p.need(counter_intent["libraryPath"] == "/run/k3s/containerd/io.containerd.runtime.v2.task/k8s.io/" + counter_intent["containerId"] + "/rootfs/usr/lib/ollama/libllama.so.0.4.1")
            p.need(counter_result["stdoutSha256"] == p.e.digest(p.e.compact({key: value for key, value in counts.items() if key != "evidenceMode"})))
            p.need(counter_result["exitCode"] == 0 and campaign.scope["startMonotonic"] <= counter_result["finishedMonotonic"] <= campaign.scope["startMonotonic"] + 120)
            p.need(outcome["cleanup"].get("deploymentLeftPaused") is True)
            ownership = life.records(supervision, "ownership")
            p.need(ownership and all(row["runSha256"] == p.e.digest(p.e.compact(binding["run"])) for row in ownership))
            # The supervisor checks owned descendants and cgroup/CRI proof before this result.
            p.need(cleanup["verified"] is True and cleanup.get("deploymentLeftPaused") is True)
            p.need((supervision / "cleanup-stop-evidence.json").exists())
            events = [p.e.decode(line) for line in read(batch / "events.jsonl").splitlines()]
            stopped = [event for event in events if event.get("type") == "owned_pod_stopped"]
            stopped += [event for event in c.private.read_json(supervision / "cleanup-stop-evidence.json")["events"]
                        if event.get("type") == "owned_pod_stopped"]
            stopped_by_uid = {event["identity"]["uid"]: event for event in stopped}
            p.need(len(stopped_by_uid) == 2 and all(event.get("cgroupEmpty") is True and
                event.get("runningCriIdentityAbsent") is True for event in stopped_by_uid.values()))
        launches = sorted(path.name for path in batch.glob("*.launched.json"))
        p.need(launches == ["probe.launched.json", "u02.launched.json"])
        row = report["cases"][0]
        p.need(row["outcome"] == "response" and isinstance(row["raw"], str) and len(row["raw"].encode()) <= 12288)
        stages = row["stages"]
        p.need(all(stages[key] == "accepted" for key in ("wire", "binding", "semantic")))
        draft_json = stages["assembledDraft"]
        draft = p.e.decode(draft_json.encode())
        p.need(any(item["kind"] == "commitment" and item["unresolvedFields"] for item in draft["suggestions"]))
        packet_json = revision.prompt_cases(source["systemInstructions"])[0]["exactPrompt"]["userPacketJson"]
        packet_sha = p.e.digest(packet_json.encode())
        p.need(source["packetSha256"]["u02"] == packet_sha and draft["intakeId"] == p.e.decode(packet_json.encode())["intake"]["intakeId"])
        paths = [directory / name for name in ("campaign.json", "scope-approval.json", "user-authority.json",
            "grant-consumer.json", "claim-01.json", "receipt-01.json", "terminal.json", "supervisor-01.json")]
        paths += [original, original.with_name(original.name + ".consumed.json")]
        paths += [batch / name for name in ("run.json", "approval.json", "revision.json", "context-evidence.json",
            "probe.intent.json", "probe.launched.json", "probe-result.json", "u02.intent.json", "u02.launched.json",
            "u02.result.json", "report.json", "events.jsonl")]
        paths += [supervision / name for name in ("job.json", "ready.json", "binding.json", "cleanup-result.json", "outcome.json")]
        if mode == "live":
            paths += [directory / "counter.preparation.json", directory / "counter.launched.json", directory / "counter-result.json", supervision / "cleanup-stop-evidence.json"]
            paths += sorted(supervision.glob("ownership-*.json"))
        chain = [{"path": str(path), "sha256": p.e.digest(read(path))} for path in paths]
        p.need(all(p.e.digest(read(Path(row["path"]))) == row["sha256"] for row in chain))
        return {"profile": p.PROFILE, "evidenceMode": mode, "acquisitionId": binding["run"]["runId"],
            "grantSha256": consumer["authoritySha256"], "campaignSha256": p.e.digest(read(directory / "campaign.json")),
            "packetJson": packet_json, "packetSha256": packet_sha, "requestSha256": source["requestSha256"]["u02"],
            "raw": row["raw"], "rawSha256": p.e.digest(row["raw"].encode()),
            "draftJson": draft_json, "draftSha256": p.e.digest(draft_json.encode()),
            "caseComparison": row["oracle"], "cleanupVerified": True, "restoreOriginalService": False,
            "productionAdmission": "closed", "chain": chain}


def seal(directory):
    capture = assemble(directory)
    c.durable(directory / "capture.json", capture)
    return p.e.digest(read(directory / "capture.json"))


def verify(directory, expected, mode):
    p.need(mode in ("live", "synthetic_fake") and c.private.sha(expected))
    raw = read(directory / "capture.json")
    p.need(len(raw) <= 131072 and p.e.digest(raw) == expected)
    capture = p.e.decode(raw)
    p.need(capture["evidenceMode"] == mode and capture == assemble(directory))
    p.need(read(directory / "capture.json") == raw)
    # Only the bounded, verified payload crosses into the isolated JVM, never paths.
    return {key: capture[key] for key in ("profile", "evidenceMode", "acquisitionId", "packetJson", "packetSha256",
        "requestSha256", "raw", "rawSha256", "draftJson", "draftSha256")} | {"receiptSha256": expected}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--seal", action="store_true")
    parser.add_argument("--expected-sha256")
    parser.add_argument("--mode", choices=("live", "synthetic_fake"))
    args = parser.parse_args()
    try:
        if args.seal:
            print(seal(args.directory))
        else:
            sys.stdout.buffer.write(p.e.compact(verify(args.directory, args.expected_sha256, args.mode)))
    except BaseException:
        print("Live intake capture admission refused", file=sys.stderr)
        raise SystemExit(2)
