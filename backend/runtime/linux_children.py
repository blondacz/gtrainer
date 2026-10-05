"""Linux kernel process ownership primitives; never scan/signal unrelated processes."""

import ctypes
import os
from pathlib import Path
import signal
import sys
import time
from dataclasses import dataclass


class BoundaryFailure(RuntimeError):
    def __init__(self):
        super().__init__("owned_process_boundary_unavailable")


def require_linux():
    if sys.platform != "linux" or not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        raise BoundaryFailure()


def become_subreaper():
    require_linux()
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
        raise BoundaryFailure()
    value = ctypes.c_int()
    if libc.prctl(37, ctypes.byref(value), 0, 0, 0) != 0 or value.value != 1:
        raise BoundaryFailure()


def start_ticks(pid):
    # comm can contain spaces/parentheses; fields after its LAST ')' start at stat field 3.
    text = Path(f"/proc/{pid}/stat").read_text()
    return int(text[text.rfind(")") + 2:].split()[19])


def direct_children(pid):
    children = set()
    for task in Path(f"/proc/{pid}/task").iterdir():
        try:
            children.update(int(value) for value in (task / "children").read_text().split())
        except FileNotFoundError:
            continue  # A thread exited; the next bounded scan observes its reparented children.
    return children


@dataclass(frozen=True)
class ReapResult:
    confirmed: bool
    reaped: int
    escalated: bool
    elapsed_millis: int


class OwnedChildren:
    """One dedicated subreaper's descendants. ECHILD, not /proc emptiness, confirms exit."""

    MAX_DESCENDANTS = 512

    def __init__(self):
        become_subreaper()
        self.pid = os.getpid()
        self.reaped = 0

    def reap(self):
        while True:
            try:
                pid, _ = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                return True
            if pid == 0:
                return False
            self.reaped += 1

    def stop(self, total_seconds=60.0, term_seconds=5.0):
        if not 0 < total_seconds <= 60 or not 0 <= term_seconds <= total_seconds:
            raise BoundaryFailure()
        started = time.monotonic()
        deadline = started + total_seconds
        escalation_at = started + term_seconds
        escalated = False
        while True:
            if self.reap():
                return self._result(True, escalated, started)
            now = time.monotonic()
            if now >= deadline:
                return self._result(False, escalated, started)
            sig = signal.SIGKILL if now >= escalation_at else signal.SIGTERM
            escalated |= sig == signal.SIGKILL
            if not self._signal_descendants(sig):
                return self._result(False, escalated, started)
            time.sleep(min(0.01, max(0, deadline - time.monotonic())))

    def _result(self, confirmed, escalated, started):
        return ReapResult(confirmed, self.reaped, escalated, int((time.monotonic() - started) * 1000))

    def _descendants(self):
        pending = list(direct_children(self.pid))
        found = set()
        while pending:
            pid = pending.pop()
            if pid in found:
                continue
            if pid <= 1 or pid == self.pid or len(found) >= self.MAX_DESCENDANTS:
                raise BoundaryFailure()
            found.add(pid)
            try:
                pending.extend(direct_children(pid))
            except FileNotFoundError:
                continue
        return found

    def _signal_descendants(self, sig):
        try:
            for pid in self._descendants():
                self._signal_identity(pid, sig)
            return True
        except (OSError, ValueError, BoundaryFailure):
            return False

    def _signal_identity(self, pid, sig):
        descriptor = None
        try:
            before = start_ticks(pid)
            descriptor = os.pidfd_open(pid)
            if start_ticks(pid) != before or pid not in self._descendants():
                return  # PID reused between observations: never signal the replacement.
            signal.pidfd_send_signal(descriptor, sig)
        except ProcessLookupError:
            pass
        except FileNotFoundError:
            pass
        finally:
            if descriptor is not None:
                os.close(descriptor)


class SignalWakeup:
    """Kernel child/termination events wake select; no idle housekeeping polling timer."""

    def __enter__(self):
        self.read_fd, self.write_fd = os.pipe2(os.O_NONBLOCK | os.O_CLOEXEC)
        self.old_fd = signal.set_wakeup_fd(self.write_fd)
        self.old_handlers = {}
        self.shutdown = False
        for sig in (signal.SIGCHLD, signal.SIGTERM, signal.SIGINT):
            self.old_handlers[sig] = signal.getsignal(sig)
            signal.signal(sig, self._received)
        return self

    def _received(self, sig, _frame):
        if sig != signal.SIGCHLD:
            self.shutdown = True

    def drain(self):
        try:
            while os.read(self.read_fd, 4096):
                pass
        except BlockingIOError:
            pass

    def __exit__(self, *_):
        signal.set_wakeup_fd(self.old_fd)
        for sig, handler in self.old_handlers.items():
            signal.signal(sig, handler)
        os.close(self.read_fd)
        os.close(self.write_fd)
