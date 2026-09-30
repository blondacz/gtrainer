#!/usr/bin/env python3
"""One-time, identity-guarded removal of the explicitly approved CI package.

Not a general deletion tool: refuses newer packages or any unexpected versions.
The user's PAT is read-only. Deletion uses the publishing repository's temporary
GITHUB_TOKEN with package-admin access, never an expanded personal token.
"""

import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ENDPOINT = "https://api.github.com/users/blondacz/packages/container/gtrainer"
CREATED_AT = "2026-09-30T20:59:42Z"
APPROVED_TAG = "sha-0b49b8f0abd607c9c6c4dfdaa5260dbecf2d7818"


def verify_identity(package, versions):
    if (package.get("name") != "gtrainer" or package.get("package_type") != "container"
            or package.get("visibility") != "public"
            or package.get("created_at") != CREATED_AT
            or (package.get("repository") or {}).get("full_name") != "blondacz/gtrainer"
            or package.get("version_count") != 3 or len(versions) != 3):
        raise ValueError("Package identity changed; refusing deletion.")
    all_tags = []
    for version in versions:
        if not str(version.get("created_at", "")).startswith("2026-09-30T20:59:"):
            raise ValueError("Package versions changed; refusing deletion.")
        all_tags.extend(version.get("metadata", {}).get("container", {}).get("tags", []))
    if all_tags != [APPROVED_TAG]:
        raise ValueError("Package tags changed; refusing deletion.")


def request(url, token, method="GET"):
    return urlopen(Request(url, method=method, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }), timeout=20)


def main():
    read_token = os.environ.get("GHCR_READ_TOKEN")
    admin_token = os.environ.get("PACKAGE_ADMIN_TOKEN")
    if not read_token or not admin_token:
        print("Required scoped CI credentials are missing.", file=sys.stderr)
        return 1
    try:
        with request(ENDPOINT, read_token) as response:
            package = json.load(response)
        with request(ENDPOINT + "/versions?per_page=100", read_token) as response:
            versions = json.load(response)
        verify_identity(package, versions)
        with request(ENDPOINT, admin_token, "DELETE") as response:
            if response.status != 204:
                raise ValueError("Unexpected deletion result.")
        print("Removed only the identity-verified initial bootstrap package.")
        return 0
    except HTTPError as error:
        print(f"Bootstrap package operation rejected (HTTP {error.code}); response withheld.", file=sys.stderr)
    except (OSError, ValueError, URLError, TypeError, AttributeError):
        print("Bootstrap package operation refused or failed; credentials/payloads withheld.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
