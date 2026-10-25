# Persistent Mac owner UAT

## Current delivery status

`integrated-local-model-uat` is under implementation. Explicit policy admission and
the normal-app manual backend and descriptive UI paths are implemented and tested
with fake owned transport. No real runtime adapter or persistent Mac service is
installed/accepted yet. The normal application's default remains closed.
Do not use the old synthetic pilot, development launcher or a shared Ollama endpoint
as a personal-data runtime. No installation, import or inference is authorized by
this document.

## Explicit experimental policy

The owner confirmed a narrow manual local-UAT exception on 2026-10-23. This does not
qualify a model. Ordinary, automatic and hosted model admission remain closed.

The private installed `owner-local-uat-v1` policy binds owner acknowledgement and its
UTC time to the application artifact, exact local provider/model/resource identity,
runtime/model/resource-policy hashes, and each allowed profile's prompt/schema
hashes. Browser input cannot choose an endpoint, model artifact or resource setting.
The installer must supply current trusted identities. Installation wiring and actual
runtime acceptance are subsequent tasks, not a default inferred from file presence.

Each profile accepts a bounded unique sorted `promptSha256s` set. The descriptive
set comes from the shared code-owned instruction renderer for the four mode/intent
variants, not from observed browser packets. Set or contract drift requires renewed
acceptance. The old singular prompt-hash shape is rejected; no stored acceptance is
automatically widened. Existing frozen prompt bytes and profile validators are retained.

## Manual backend execution

With a trusted runtime/factory explicitly supplied to the normal app, authenticated
owner actions use the following routes. The standard app currently supplies none;
these paths are tested offline without granting a real runtime permission.

- `POST /api/review-profiles/owner-uat/rearm` names the exact `policySha256`.
- `POST /api/review-profiles/owner-uat/disarm` requires confirmation
  `Disarm local model`; it revokes publication before cancelling owned jobs.
- `POST /api/review-executions/{id}/request` names `policySha256` and `packetSha256`
  from its exact preview. A 202 receipt follows durable reservation, not generation.
- `GET /api/review-executions/{id}/status` reads persisted execution/claim state,
  attempts and deadline without observing or changing a runtime. A concurrent source
  change can temporarily report `UNVERIFIED` / `inspection_conflict`; reading again
  never restarts execution or renews its budget.
- `POST /api/review-executions/{id}/cancel` names the exact `packetSha256` and revokes
  publication before cancellation/owned cleanup. Deleting an execution also cancels
  its owned job before removal; unresolved stopping remains in the ownership ledger.
- Existing execution inspection returns only whole validated persisted output. Its
  service-owned `manualRequestAdmission` reports permission to submit a guarded request,
  not physical runtime acceptance. `executionAllowed` remains distinct and closed.
  Inspection performs no runtime probes; POST rechecks policy, identity and ownership.
- `GET /api/review-executions?profile=scoped-descriptive-v1` lists up to 25 stored
  outcomes per page, optionally filtered by `snapshotId`, with bounded offset/limit.
  Opening a result rechecks current source binding. Listing does not claim currentness.
- `POST /api/review-executions/{id}/feedback` requires `expectedOutputSha256` and an
  attributed rating/correction. Only descriptive results with retained whole validated
  published output qualify, including explicitly stale historical output. Source/output
  identity is checked atomically; no output is rewritten and no model is called.

All mutations retain normal authentication, Origin and CSRF checks and strict bounded
request decoding. Browser requests cannot provide runtime settings. Once an execution
has a durable claim, duplicate requests and restart return its recorded state, never
another send. One owned domain cannot overlap work, and unknown stopping blocks it.
Per-job work receives at most 1140 seconds of the proposed 1200-second deadline,
leaving a 60-second cleanup reserve. The independent sleep-aware supervisor and actual
resource/stop observer are still required before real runtime acceptance.

Execution deletion preflights historical feedback dependencies before cancellation
and checks them again transactionally before removal. A conflict requires existing
Context deletion review; it never silently deletes shared feedback. The bounded
source-deletion graph traverses execution feedback across all revisions, including
retired/corrected-away links and downstream snapshots. New feedback invalidates an
older deletion preview; existing replacement/reconfirmation and rollback rules remain.

The reader accepts only a bounded regular owner-only policy file in an owner-only
directory. It rejects symlinks, unknown/duplicate JSON fields, coerced values,
unsupported profiles, hosted selections, future acknowledgement timestamps and
unrecognized limits. An absent, revoked, unreadable or changed policy fails closed.
Private policy data and parser exception details do not reach diagnostic responses.

Each app process starts disarmed. Explicit re-arming must name the exact currently
accepted policy digest; reading status never arms it. Set `ownerAcknowledged` false
in the operator-managed policy to durably revoke acceptance. A changed application,
runtime, model, prompt/schema or resource policy requires new acceptance. Re-arming
alone does not prove runtime health/ownership or enable a model call.

Observing missing, revoked, invalid or changed policy clears the process-local arming
latch. Restoring an old accepted file/binding cannot automatically re-arm it. This
fail-closed latch update does not create/stop a runtime or mutate a stored job.
Execution inspection additionally reports mismatched local/hosted selection or prompt
bindings; an accepted installed policy is not acceptance of every stored preview.

## Proposed per-job limits

The UAT policy allows one attempt, with no correction, automatic retry, provider
fallback or download. Proposed limits are 900 seconds generation, 1200 seconds total,
120 seconds startup, 30 seconds validation and a 60-second cleanup reserve within
the total. Context is 4096 tokens, with 1536 output tokens and a 256-token margin.
These are separate from unchanged PERSONAL (300/600 seconds) and DEVELOPMENT_LOCAL
(900/1840 seconds) policies. Real runtime controls must be implemented and accepted
before these UAT limits authorize execution.

Elapsed time includes sleep and cannot renew on browser reload or supervisor restart.
The host cannot perform cleanup while suspended or unavailable. Expired/unverifiable
work must be publication-fenced and capacity-blocked until authoritative stopping
on recovery; status must not claim a physical stop occurred at the missed deadline.

The application itself has no demo expiry and must not delete owner records on exit.
Mac sleep/logout/offline limitations, persistent installation, encrypted recovery,
three complete UI journeys and on-request monitoring remain implementation tasks.
Monitoring never implies continuous assistant supervision between requests.

## Descriptive context feasibility

Context is an internal memory/latency control, not a token charge for a local model
or a budget the owner should manage. The initial 4096-token setting is conservative,
not the cached model's inherent maximum. A separately validated and explicitly
accepted runtime binding may raise it without changing the memory/time/stop safeguards.
Never increase it automatically or retry a refused request under a different setting.

The subsequent authorized vocabulary-only feasibility check found that three of the
four minimal invented descriptive inputs exceed the accepted 2304-token input budget:
activity review 2230, activity comparison 3945, period review 2427, period comparison
5927. These use explicit wellness off and the unchanged normal preparation path.
The small activity-review fixture has only 74 input tokens of headroom. This is not
production context acceptance or general support for that mode. See task 3.1's
feasibility record for exact bindings, supervision and the outstanding representation
decision. No runtime or source service was started by that check.

The owner subsequently approved the versioned lossless format
`owner-uat-descriptive-wire-v1`. `OwnerUatDescriptiveWireV1` retains canonical saved
snapshots and original safety instructions/output validation, while placing repeated
object shapes and sufficiently repeated string values in explicit tables. A bounded
inverse must reproduce every original canonical byte. Original source identities,
timestamps, revisions, units, nulls and attributed text remain intact; model output
must use complete original references, not transport indexes. Wire/effective-input
hashes are distinct from canonical source binding. A changed codec requires a new
runtime/policy binding; it does not silently widen an accepted policy.

Offline parity and a guarded fake journey cover all four mode/intent variants. They
do not prove tokenizer savings or that a real model understands the representation.
The opt-in test exporter accepts `--args='<new-private-root> --compact'` to prepare
invented inputs for a future separately approved vocabulary check. Export alone
contacts no source, provider or runtime. The prior counting grant is spent; no new
native count, resource experiment or generation has been authorized. Production
provider/control integration remains unfinished and closed by default.

## Owned Ollama request transport

`OwnerUatOllamaProvider` and its factory now implement the descriptive HTTP request
path against the reserved loopback VM forwarding at `127.0.0.1:11435`. This endpoint
is never supplied by a browser. Factory construction is inert; creating a per-claim
provider requires matching installed application/runtime/model/resource/profile
metadata and an authoritative owned-start token. An accessible Ollama endpoint,
matching `/api/tags`, or an injected client does not establish ownership.

The compiled runtime binding pins the cached image, Ollama version, model manifest/
blob, template, tokenizer library, compact wire/decoding contract, native response
schema and generation options. The exact serialized request and rendered effective
input receive separate hashes. The original durable claim's consent digest must
match the canonical source prompt before any context counting or HTTP request.
Output uses the original strict descriptive contract; native schema constraints do
not prove source validity or usefulness.

Before any HTTP, the provider requires a trusted control observation matching this
exact claim/incarnation, image/assets and resource policy, plus request-specific
runtime-matched vocabulary evidence within the accepted context. Ownership is checked
again around every request and before returning output. HTTP version/tag/digest checks
also run before and after generation. Returned `prompt_eval_count` must equal the
admitted count; truncated/incomplete/thinking/tool output is not accepted as a draft.
All requests/responses are bounded. There is one generation attempt, no correction,
HTTP retry, redirect, model fallback or implicit context enlargement. Fixed failure
codes exclude private bodies and exception causes. Closing a client never proves a
runtime stopped; admission still requires authoritative cleanup even if close fails.

The factory still refuses a larger context under the current accepted-policy version
and requires a supervision-bound claim. Preparing/testing a larger binding is not
acceptance. Production per-request native counting and physical VM/container stop
observation remain unfinished; no real production control is supplied by default.
Fake owned-control/HTTP tests exercise the normal manual claim/executor/publication
path, not real-model or physical-stop acceptance.

## Independent job supervision

`OwnerUatSupervisorMain` is a separate JVM entry point with a private Unix socket and
an exclusive OS file lock. Installation under launchd is still task 6.1, not something
normal requests or status reads bootstrap. Its narrow JDBC connection validates the
existing store without constructing `HistoryStore` or rotating the app's owner epoch.
The `owner_uat_job_supervision` companion table shares the existing ownership ledger
and publication transaction; it is not a second queue or a separate permission store.

The application captures a boot-bound continuous origin before external preflight.
The source Mac clock uses lazy JDK FFM calls to `mach_continuous_time`, its timebase
and the boot UUID, with overflow/backwards/identity checks. Tests use injected clocks:
FFM compilation does not establish hardware or real host-sleep qualification. Startup
has at most 120 seconds from the original origin; generation has at most 900 seconds;
validation/publication has at most 30 seconds. Phases retain the original 1140-second
work and 1200-second total cutoffs. Repeated messages never renew phase deadlines.
Cleanup receives at most the remaining total budget or 60 seconds; recovery may still
stop owned work after expiry, but never resume generation or renew publication rights.

A durable supervisor responsibility receipt precedes request acknowledgment and
START_INTENT. A launched worker waits behind an activation gate until its exact PID/
start identity is committed; EOF before that gate permits no computation. Consumed
launches never redispatch. An unknown launched identity remains occupied, not assumed
absent. App death aborts outstanding work and retains independent cleanup responsibility;
browser loss alone does not. Supervisor recovery fences old records before listening.
Core revocation and companion tombstones commit together. Publication checks boot,
phase, unchanged deadlines, tombstones and supervisor identity inside the same SQLite
transaction, including a final precommit check. Failed checks roll back result and
permission writes together. Stop observations distinguish verification after recovery
from any unproven claim that termination happened at the deadline.

Separate synthetic app, supervisor and worker JVM tests cover a full strict result,
duplicates, app death, supervisor death/restart, clock expiry/discontinuity, uncertain
launch and readonly status. These processes use invented data only and are independently
verified exited before generated stores are removed. Test worker/backend/app classes
are excluded from the main artifact. The production supervisor backend currently
refuses runtime acquisition: physical controls and the native vocabulary worker are
task 3.3 dependencies, not implemented by a fake PID receipt.

### Read-only node boundary verifier (not installed)

`backend/runtime/owner_uat_node_probe.py` implements the Linux-side descendant
observation needed by task 3.3. It validates a canonical claim/launch/boot/pod/container/
cgroup reference, requires the cgroup-v2 mount, scans the complete readable cgroup
tree for every matching pod boundary, and checks both `cgroup.procs` and recursive
`cgroup.events` population. Bounded CRI running-container and sandbox reads must
exclude every recorded container ID and the pod UID. A second cgroup scan and boot
check follow the CRI reads. Reboot, missing/ambiguous/malformed evidence, unreadable
trees, nonempty descendants or time/output limits produce `UNCONFIRMED`.

The affirmative result is deliberately **`BOUNDARY_EMPTY`, not `STOPPED`**. This
read-only helper cannot fence pending pod creation, delete a pod, stop the host
worker, establish safe workload isolation or authorize publication. A future trusted
supervisor/control adapter must combine the exact durable identity, revocation and
creation fence, UID-precondition deletion, host worker stopping and this node result.
The helper is not yet connected to the supervisor and has not been installed or
invoked on the VM. Never convert its result alone into released occupancy.

Recovery must retain the existing occupied/unknown slot until that complete
identity-bound proof exists. Do not delete the ownership record, reset the store,
adopt the paused original deployment or treat a vanished pod as a recovery shortcut.
Actual recovery mutations still require operational approval. VM/resource/pressure/
runner-mmap health observations and final publication health receipts remain
unimplemented; this helper does not complete task 3.3.

The companion table changes the exact expected schema manifest. An older store is
refused without epoch rotation, migration or reset. No existing personal/Pi store has
been upgraded or opened for this work. Installation/backup compatibility checks must
use the candidate's complete manifest, not assume that an equal numeric version is
sufficient. Never reset owner data to bypass a schema refusal.

## Isolated normal-app browser check

The test-classpath-only `:backend:runLocalDescriptiveUiCheck` task serves the normal
built frontend and normal authenticated routes with `DescriptiveUiFixture` invented
records and an in-process fake provider. It does not load environment credentials,
start Ollama/VM/pod processes, contact Intervals.icu or register development/pilot APIs.
It is not a persistent installation or an alternate way to admit personal inference.

Build the normal frontend first. Use only a new owner-private generated directory
named `synthetic-local-ui-*`, with the `synthetic-only` marker containing exactly
`synthetic-only-descriptive-ui-check-v1` and a final newline. Ensure port 8080 is free;
do not stop an unrelated listener. Then run the opt-in task with `--args='<root> first'`.
Sign in with the existing synthetic test password, select an invented activity, save
its snapshot and use the ordinary local-review controls. No source key is configured.

Create `stop-first` in that root to end the first phase. Verify its terminal record,
exact Java PID exit and listener absence before `--args='<same-root> restart'`. The
same test database and policy remain; the new policy gate starts disarmed and prior
claimed work is never replayed. Create `stop-restart` to end the second phase. The
20-minute per-phase fallback is **test supervision only**, not an application expiry.

After both exact owners/listeners are gone, verify outcome counts and remove only
the generated `data/` store, retaining content-free owner/terminal/acceptance records.
The fixture deliberately disables ordinary review scheduling/queue services: queue
inspection can return the normal unhandled501 response. That is not evidence of a
deployed queue defect or a console-error-free production release.
