#!/usr/bin/python3 -I
"""Fixed read-only host/container/app samples. Not installed or contacted by tests."""

import json
from pathlib import Path
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bounded_node_read import MAX_READ_BYTES, read_command
from node_termination_verifier import inspect_container, unique_fields, validate_reference

PROFILE = "gtrainer-development-node-health-v1"
APP_COMMAND = ["/usr/local/bin/k3s", "kubectl", "--request-timeout=10s", "-n", "gtrainer",
    "get", "pods", "-l", "app.kubernetes.io/name=gtrainer", "-o", "json"]


def parse(raw):
    if len(raw) > MAX_READ_BYTES:
        raise ValueError("node_health_unavailable")
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_fields)


def host_available(text):
    rows = [line.split() for line in text.splitlines() if line.startswith("MemAvailable:")]
    if len(rows) != 1 or len(rows[0]) != 3 or rows[0][2] != "kB" or not rows[0][1].isascii() or not rows[0][1].isdecimal():
        raise ValueError("node_health_unavailable")
    value = int(rows[0][1]) * 1024
    if not 0 <= value < 2 ** 63:
        raise ValueError("node_health_unavailable")
    return value


def observe(container_id, boot_id, boot_reader=None, memory_reader=None, inspector=inspect_container):
    validate_reference(container_id, boot_id)
    boot_reader = boot_reader or (lambda: Path("/proc/sys/kernel/random/boot_id").read_text().strip())
    memory_reader = memory_reader or (lambda: Path("/proc/meminfo").read_text())
    boot = boot_reader()
    if boot != boot_id or str(uuid.UUID(boot)) != boot:
        raise ValueError("node_health_unavailable")
    available = host_available(memory_reader())
    data = parse(inspector(container_id))
    status = data["status"]
    labels = status["labels"]
    if status["id"] != container_id or status["state"] != "CONTAINER_RUNNING" \
        or labels.get("io.kubernetes.container.name") != "dedicated-worker-runtime" \
        or labels.get("io.kubernetes.pod.namespace") != "gtrainer-connected-review-development":
        raise ValueError("node_health_unavailable")
    attempt = status["metadata"]["attempt"]
    limit = data["info"]["runtimeSpec"]["linux"]["resources"]["memory"]["limit"]
    if type(attempt) is not int or attempt < 0 or type(limit) is not int or not 0 < limit < 2 ** 63 or boot_reader() != boot:
        raise ValueError("node_health_unavailable")
    return {"profile": PROFILE, "containerId": container_id, "bootId": boot, "availableBytes": available,
        "applianceMemoryLimitBytes": limit, "containerAttempt": attempt}


def bounded_identity(value, maximum):
    if not isinstance(value, str) or not 1 <= len(value) <= maximum or any(ord(c) < 32 for c in value):
        raise ValueError("node_health_unavailable")
    return value


def application(runner=read_command):
    data = parse(runner(list(APP_COMMAND)))
    containers = []
    for pod in data["items"]:
        if pod["metadata"]["namespace"] != "gtrainer" or pod["metadata"].get("labels", {}).get("app.kubernetes.io/name") != "gtrainer" \
            or pod["metadata"].get("deletionTimestamp") is not None:
            raise ValueError("node_health_unavailable")
        if pod["status"].get("phase") != "Running" or not any(condition.get("type") == "Ready" and condition.get("status") == "True"
            for condition in pod["status"].get("conditions", [])):
            raise ValueError("node_health_unavailable")
        statuses = pod["status"]["containerStatuses"]
        if not statuses or len(containers) + len(statuses) > 16:
            raise ValueError("node_health_unavailable")
        for container in statuses:
            if type(container["ready"]) is not bool or type(container["restartCount"]) is not int or container["restartCount"] < 0:
                raise ValueError("node_health_unavailable")
            containers.append({"podId": bounded_identity(pod["metadata"]["uid"], 128),
                "containerName": bounded_identity(container["name"], 128), "imageIdentity": bounded_identity(container["imageID"], 256),
                "ready": container["ready"], "restartCount": container["restartCount"]})
    if not containers:
        raise ValueError("node_health_unavailable")
    return {"profile": PROFILE, "containers": containers}


def main():
    try:
        if sys.platform != "linux":
            raise ValueError("node_health_unavailable")
        if len(sys.argv) == 4 and sys.argv[1] == "observe":
            payload = observe(sys.argv[2], sys.argv[3])
        elif len(sys.argv) == 2 and sys.argv[1] == "application":
            payload = application()
        else:
            raise ValueError("node_health_unavailable")
    except Exception:
        payload = {"profile": PROFILE, "unavailable": True}
    print(json.dumps(payload, separators=(",", ":")))


if __name__ == "__main__":
    main()
