#!/usr/bin/env python3
"""Prevent publication to an existing public GHCR package; never print tokens."""

import argparse
import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class PrivacyCheckError(ValueError):
    """Messages contain only fixed diagnostics and HTTP status, never payloads."""


def check(allow_missing=False):
    token = os.environ.get("GH_TOKEN")
    if not token:
        raise PrivacyCheckError("Read-only package metadata token is not configured.")
    request = Request(
        "https://api.github.com/users/blondacz/packages/container/gtrainer",
        headers={"Authorization": f"Bearer {token}",
                 "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28"},
    )
    try:
        with urlopen(request, timeout=20) as response:
            package = json.load(response)
    except HTTPError as error:
        scopes = {scope.strip() for scope in error.headers.get("X-OAuth-Scopes", "").split(",")}
        if error.code == 404 and allow_missing and "read:packages" in scopes:
            # GitHub creates newly published container packages as private.
            return "absent; initial private package creation permitted"
        raise PrivacyCheckError(f"Cannot verify registry privacy (HTTP {error.code}).") from None
    if package.get("visibility") != "private":
        raise PrivacyCheckError("Registry package is not private; publication refused.")
    return "private"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-missing", action="store_true")
    args = parser.parse_args()
    try:
        print("GHCR visibility:", check(args.allow_missing))
        return 0
    except PrivacyCheckError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError, URLError):
        print("Registry privacy verification failed; no credentials or response bodies displayed.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
