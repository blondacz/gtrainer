"""Mac OS-wide monotonic clock, independent of Python 3.9 process origins.

There is deliberately no fallback to Python's process-relative monotonic clock,
wall time, another clock ID, or a newly installed interpreter.
"""
import subprocess
import sys
import time
import uuid

VERSION = "mac-os-wide-monotonic-v1"
NAME = "time.clock_gettime_ns(CLOCK_MONOTONIC)"


def need(value):
    if not value: raise ValueError("Native campaign clock unavailable")


def monotonic():
    need(sys.platform == "darwin")
    try:
        value = time.clock_gettime_ns(time.CLOCK_MONOTONIC)
    except (AttributeError, OSError, ValueError):
        raise ValueError("Native campaign clock unavailable") from None
    need(type(value) is int and 0 <= value <= 2**63 - 1)
    return value / 1_000_000_000


def identity():
    # Verify the actual clock API, not just the presence of its constant.
    monotonic()
    result = subprocess.run(["sysctl", "-n", "kern.bootsessionuuid"], capture_output=True, timeout=5, check=True)
    boot = result.stdout.decode("ascii").strip().lower()
    need(str(uuid.UUID(boot)) == boot)
    return {"version": VERSION, "clock": NAME, "bootSessionUuid": boot}


def valid_identity(value):
    return (type(value) is dict and set(value) == {"version", "clock", "bootSessionUuid"}
            and value["version"] == VERSION and value["clock"] == NAME
            and type(value["bootSessionUuid"]) is str and 0 < len(value["bootSessionUuid"]) <= 256)
