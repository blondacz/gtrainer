"""Explicit primary-session entry point. Never creates a replacement campaign."""
import argparse
import os
from pathlib import Path

import campaign_v4 as c


def execute(args):
    if args.revision and args.supervised_worker is None:
        import supervisor_v4
        return supervisor_v4.run(args.directory, args.revision, wait=args.wait)
    if args.create:
        authority = c.private.read_json(args.authority_file)
        c.need(type(authority) is dict and set(authority) == {"version", "recordedBy", "userInstruction", "applyInstruction"})
        c.need(authority["version"] == "intake-conformance-user-authority-v4" and authority["applyInstruction"] == "apply")
        c.need(c.private.text(authority["userInstruction"], 1536))
        with c.Campaign.create(args.directory, "live", authority["recordedBy"],
                               authority["userInstruction"] + " Separate instruction: apply."):
            return "created"
    with c.Campaign(args.directory) as campaign:
        if args.finish:
            campaign.finish(args.finish)
            return "stopped"
        directory = c.private.private_directory(args.revision)
        prepared = c.private.read_json(directory / "revision.json")
        evidence = c.private.read_json(directory / "context-evidence.json")
        requests = campaign.suite.revision.check_revision(prepared, evidence)
        c.need(c.private.private_read(directory / "requests.json") == c.e.compact(requests))
        c.need(c.private.private_read(directory / "token-input.json") == c.e.compact(campaign.suite.revision.counter_input(prepared["systemInstructions"])))
        import lifecycle_v4
        lifecycle = lifecycle_v4.Worker(args.supervised_worker, campaign.directory)
        return campaign.run_batch(prepared, evidence, lifecycle=lifecycle)["classification"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--create", action="store_true")
    action.add_argument("--revision", type=Path)
    action.add_argument("--finish", choices=("no_defensible_revision", "budget_exhausted"))
    parser.add_argument("--authority-file", type=Path)
    parser.add_argument("--supervised-worker", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--wait", action="store_true", help="Observe completion; observer loss does not cancel the job")
    args = parser.parse_args()
    if args.create and args.authority_file is None: parser.error("--create requires --authority-file")
    if args.supervised_worker is not None and not args.revision: parser.error("worker requires revision")
    old = os.umask(0o077)
    try: status = execute(args)
    except BaseException:
        print("V4 campaign fenced; inspect private evidence. No automatic retry or new campaign.")
        return 2
    finally: os.umask(old)
    print("V4 campaign status: " + status + ". Production admission closed.")
    return 0


if __name__ == "__main__": raise SystemExit(main())
