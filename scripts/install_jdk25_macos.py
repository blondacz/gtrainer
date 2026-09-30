#!/usr/bin/env python3
"""Install a checksum-verified Temurin 25 bundle for this Mac user."""

import argparse
import hashlib
import os
from pathlib import Path
import platform
import subprocess
import tarfile
import tempfile


VERSION = "25.0.4.1"
URL = ("https://github.com/adoptium/temurin25-binaries/releases/download/"
       "jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_mac_hotspot_25.0.4.1_1.tar.gz")
SHA256 = "e6229d9504f7922053ab31821b9e6bee8761daf7b026a3476d1a027563009880"
DESTINATION = Path.home() / "Library/Java/JavaVirtualMachines/temurin-25.0.4.1.jdk"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staging", required=True, type=Path)
    staging = parser.parse_args().staging
    if platform.system() != "Darwin" or platform.machine() != "x86_64":
        raise SystemExit("This verified artifact is for Intel macOS only.")
    if DESTINATION.exists():
        raise SystemExit("Destination already exists; refusing to overwrite a JDK.")
    staging.mkdir(parents=True, exist_ok=True)
    archive = staging / "temurin25.tar.gz"
    if not archive.exists():
        subprocess.run(["curl", "-fSL", "--connect-timeout", "15", "--max-time", "240",
                        "-o", str(archive), URL], check=True)
    digest = hashlib.sha256()
    with archive.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    if digest.hexdigest() != SHA256:
        raise SystemExit("JDK checksum mismatch; nothing installed.")
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise SystemExit("Unsafe archive member; nothing installed.")
    # A fresh directory avoids reusing or overwriting a previous extraction.
    extracted = Path(tempfile.mkdtemp(prefix="extracted-", dir=staging))
    subprocess.run(["tar", "-xzf", str(archive), "-C", str(extracted)], check=True)
    # Temurin's archive root may be jdk-<version> rather than ending in .jdk.
    bundles = [entry for entry in extracted.iterdir()
               if entry.is_dir() and (entry / "Contents/Home/bin/java").is_file()]
    if len(bundles) != 1:
        raise SystemExit("Unexpected JDK bundle structure; nothing installed.")
    subprocess.run([str(bundles[0] / "Contents/Home/bin/java"), "-version"], check=True)
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    os.rename(bundles[0], DESTINATION)
    print(f"Installed verified JDK at {DESTINATION}")
    print("Existing JDKs were preserved; no administrator password was requested.")


if __name__ == "__main__":
    main()
