"""Bounded read-only command IO shared by the narrowly validated node entrypoints."""

import os
import selectors
import subprocess
import time

MAX_READ_BYTES = 64 * 1024
COMMAND_SECONDS = 15


def read_command(command, process_factory=subprocess.Popen):
    deadline = time.monotonic() + COMMAND_SECONDS - 1  # Reserve one second for kill/reap within the total budget.
    process = process_factory(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        env={"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8"}, close_fds=True)
    data = bytearray()
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not selector.select(remaining):
                    raise ValueError("node_read_unavailable")
                block = os.read(process.stdout.fileno(), min(4096, MAX_READ_BYTES + 1 - len(data)))
                if not block:
                    break
                data.extend(block)
                if len(data) > MAX_READ_BYTES:
                    raise ValueError("node_read_unavailable")
        if process.wait(timeout=max(0.001, deadline - time.monotonic())) != 0:
            raise ValueError("node_read_unavailable")
        return bytes(data)
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=1)
