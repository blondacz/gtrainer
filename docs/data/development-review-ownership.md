# Durable ownership and old-container recovery

The ownership/verifier and elected worker have synthetic/mock acceptance, not live
deployment or real-runtime recovery acceptance. The launcher still defaults to no
configured worker and cannot send inference in that state. Node transport defaults to
unconfigured/refused; no node access, sudo setup, verifier installation or SSH
credential discovery is performed by the implementation/tests.

## Election and durable fencing

The isolated SQLite store fixes its lifetime lock beside its database; callers
cannot choose another lock directory. Acquisition requires owner-only regular
storage/lock files, rejects symlinks and competing process/JVM owners, and never
truncates or chmods an existing file. The kernel file lock has no expiry/lease.
Acquiring it atomically advances a persisted epoch and pins the owning node name.
A later attempt on another node is refused even if the lock is available.

Claim persists an opaque run ID, epoch, increasing runtime generation, pinned
node/boot/container identities, original expiry and `STARTING` safety record in
the same short transaction as the queue transition. A monotonic claim origin and
TTL-clamped immutable policy accompany the claim before any runtime request.
The safety ledger has no payload foreign key: deleting an expired packet cannot
delete its unresolved execution boundary. Starting/publication require the exact
current identity; publication also rechecks the task terminal transition, binding,
immutable expiry and monotonic budget. Successful output does not release the
execution block. Exact normal-run stop receipts/cancellation are wired through the
elected worker; see `development-review-worker.md` for the event-driven phases.

On a new epoch, previously claimed `RUNNING` outcomes become `FAILED` with fixed
`worker_interrupted`; their execution remains `UNKNOWN`. Already terminal outcomes
are preserved while unfinished execution remains uncertain. Unclaimed runs retain
their queue order/binding/TTL and are not replayed or implicitly submitted. Late
old-epoch starts/publication/failure callbacks cannot alter a new owner's work.
Closing the file lock itself proves nothing about runtime termination. The earlier
epoch-zero repository seam remains for synthetic compatibility tests only; after
ownership has been activated, its unfenced claim/publication/failure writes refuse
to change the ledger. No HTTP endpoint exposes the seam.

Legacy test-only claims with no recorded boundary are conservatively captured as
unbound uncertainty, not assigned the replacement container's identity. There is
no unsafe "assume stopped" escape hatch. Use new isolated storage for a separately
approved appliance; never repoint to a personal/existing runtime database.

## Read-only proof from the owning node

`DevelopmentNodeTerminationVerifier` accepts only an operator-pinned node and a
strict, bounded, duplicate-field-free result. The optional SSH transport uses a
separate explicit identity and owner-only known-hosts file, strict host-key checking,
no inherited SSH config/agent/multiplexing/forwarding, no retries and no shell on
the local client. Host/user/port/paths and recorded references are validated; the
browser cannot provide a command, path, host or endpoint. SSH receives only:

```text
sudo -n /usr/local/libexec/gtrainer-development-node-verifier verify <64 lowercase hex container ID> <canonical UUID old boot ID>
```

The separately approved, root-owned helper is
`backend/runtime/node_termination_verifier.py` with its root-owned
`bounded_node_read.py` dependency (isolated Python invocation). It
allows this one operation and reads Linux boot identity. If unchanged, it executes
only the fixed local read-only command:

```text
/usr/local/bin/k3s crictl --timeout 10s inspect <recorded container ID>
```

The helper bounds stdout at 64 KiB, discards stderr and returns only its fixed
profile, exact container ID, observed boot ID and result. Exact CRI container ID,
dedicated container name/namespace, `CONTAINER_EXITED` and positive finish time are
required. A changed boot identity also proves the old node execution ended.
Absent/malformed/oversized data, a missing old container, running state, foreign
workload, mismatched identities and unreachable nodes are **not** positive proof.
Neither pod deletion, replacement readiness, replica count, PVC availability nor
HTTP/model status establishes termination. No delete/exec/kill/reschedule operation
is available through this verifier.

One transport attempt is bounded to 15 seconds including local command cleanup;
one reconciliation pass is bounded to 60 seconds. No database monitor/transaction
spans node observation. Missing proof keeps new execution blocked while authenticated
inspection/submission can continue within capacity. The worker will invoke recovery
only at startup or a durable event/explicit maintenance opportunity, not an idle
housekeeping timer. Reconciliation marks only the matching old safety row stopped;
it never changes an interrupted outcome back to queued or successful.

Future deployment requires explicit provisioning of the new pinned-node namespace,
local storage, restricted verifier account/key and root-owned helper. Grant only
this validated helper, never generalized passwordless sudo/cluster administration;
verify the K3s binary/runtime layout and actual CRI semantics in the separately
approved smoke test. Disabled appliance examples neither install it nor supply
credentials. Mock evidence proves implementation contracts, not live-host trust.
