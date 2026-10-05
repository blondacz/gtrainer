#!/usr/bin/python3 -I
"""Narrow, read-only owning-node helper. Not installed/contacted by this change."""

import json
from pathlib import Path
import subprocess
import sys
import uuid

# Installation directory and every dependency must be root-owned/non-writable.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from bounded_node_read import MAX_READ_BYTES, COMMAND_SECONDS, read_command

PROFILE = "gtrainer-development-node-proof-v1"
MAX_INSPECT_BYTES = MAX_READ_BYTES


def inspect_container(container_id, process_factory=subprocess.Popen):
    # Fixed local CRI operation. No shell, endpoint override, workload exec or mutation.
    return read_command(["/usr/local/bin/k3s", "crictl", "--timeout", "10s", "inspect", container_id], process_factory)


def validate_reference(container_id, boot_id):
    if len(container_id) != 64 or any(c not in "0123456789abcdef" for c in container_id):
        raise ValueError("invalid_boundary_reference")
    if str(uuid.UUID(boot_id)) != boot_id:
        raise ValueError("invalid_boundary_reference")


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("node_proof_unavailable")
        result[key] = value
    return result


def verify(container_id, old_boot_id, boot_reader=None, runner=inspect_container):
    validate_reference(container_id, old_boot_id)
    boot_reader = boot_reader or (lambda: Path("/proc/sys/kernel/random/boot_id").read_text().strip())
    current_boot = boot_reader()
    if str(uuid.UUID(current_boot)) != current_boot:
        raise ValueError("node_proof_unavailable")
    base = {"profile": PROFILE, "containerId": container_id, "observedBootId": current_boot}
    if current_boot != old_boot_id:
        return {**base, "result": "HOST_REBOOTED"}
    raw = runner(container_id)
    if len(raw) > MAX_INSPECT_BYTES:
        raise ValueError("node_proof_unavailable")
    status = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_fields)["status"]
    labels = status.get("labels", {})
    correct_boundary = status.get("id") == container_id and labels.get("io.kubernetes.container.name") == "dedicated-worker-runtime" \
        and labels.get("io.kubernetes.pod.namespace") == "gtrainer-connected-review-development"
    finished = status.get("finishedAt", 0)
    finished_valid = type(finished) is int and finished > 0 or type(finished) is str and finished.isascii() and finished.isdecimal() and int(finished) > 0
    if correct_boundary and status.get("state") == "CONTAINER_EXITED" and finished_valid:
        return {**base, "result": "CONTAINER_EXITED"}
    return {**base, "result": "UNCONFIRMED"}


def main():
    try:
        if sys.platform != "linux" or len(sys.argv) != 4 or sys.argv[1] != "verify":
            raise ValueError("invalid_boundary_reference")
        payload = verify(sys.argv[2], sys.argv[3])
    except Exception:
        payload = {"profile": PROFILE, "result": "UNCONFIRMED"}
    print(json.dumps(payload, separators=(",", ":")))


if __name__ == "__main__":
    main()
