"""Explicit one-evaluation entry point; no automatic retry or replacement root."""
import argparse
import os
from pathlib import Path

import suite_v5 as suite
import campaign_v4 as c
import run_mac_v4 as shared


def create(directory, authority_file):
    directory = c.private.private_directory(directory)
    authority_file = Path(authority_file)
    raw = c.private.private_read(authority_file)
    authority = c.e.decode(raw)
    c.need(type(authority) is dict and set(authority) == {
        "version", "recordedBy", "userInstruction", "applyInstruction", "campaignDirectory", "limits"})
    c.need(authority["version"] == "intake-conformance-user-authority-v5")
    c.need(authority["applyInstruction"] == "apply v5" and authority["campaignDirectory"] == str(directory))
    c.need(authority["limits"] == {"roots": 1, "batches": 1, "scoredLaunches": 8, "probes": 1,
         "campaignSeconds": 14400, "cleanupReserveSeconds": 240, "retryPermitted": False})
    c.need(c.private.text(authority["userInstruction"], 1536) and not any(directory.iterdir()))
    suite.verify()
    # Claim the single root before campaign creation. Interrupted creation remains
    # spent and fenced; no caller can silently reuse this permission elsewhere.
    c.durable(authority_file.with_name(authority_file.name + ".consumed.json"), {
        "authoritySha256": c.e.digest(raw), "campaignDirectory": str(directory)})
    with c.Campaign.create(directory, "live", authority["recordedBy"],
                           authority["userInstruction"] + " Separate instruction: apply v5.", profile=suite.PROFILE):
        return "created"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--create", action="store_true")
    actions.add_argument("--revision", type=Path)
    parser.add_argument("--authority-file", type=Path)
    parser.add_argument("--supervised-worker", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--wait", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        if args.create:
            c.need(args.authority_file is not None and args.supervised_worker is None)
            result = create(args.directory, args.authority_file)
        else:
            with c.Campaign(args.directory) as campaign:
                c.need(campaign.scope["profile"] == suite.PROFILE and campaign.scope.get("recovery") is None)
            args.finish = None
            result = shared.execute(args)
    except BaseException:
        print("V5 evaluation fenced; inspect private evidence. No retry or new root.")
        return 2
    print("V5 evaluation status: " + result + ". Production admission closed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
