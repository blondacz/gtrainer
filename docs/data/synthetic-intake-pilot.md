# Guarded synthetic intake interaction pilot

This opt-in development harness checks the inspection and confirmation boundary.
It does not run a model, demonstrate new extraction quality, qualify a production
profile or connect to an application database. Use only the bundled synthetic
notes. Do not enter personal data, including in source corrections or draft fields.

## Run locally

Prerequisites: the project's existing Java 25 toolchain, Node dependencies and
cached Gradle dependencies. These commands do not install or download dependencies.
From the repository root:

```sh
npm --prefix frontend run build:synthetic-intake-pilot
```

Create a dedicated temporary pilot password verifier. Do not reuse application
credentials. The password prompt is not echoed; only its salted verifier enters the
launcher environment:

```sh
export GTRAINER_SYNTHETIC_PILOT_PASSWORD_HASH="$(python3 - <<'PY'
import base64, getpass, hashlib, secrets
password = getpass.getpass('Dedicated synthetic pilot password: ')
assert 1 <= len(password) <= 256
salt = secrets.token_bytes(16)
encode = lambda value: base64.urlsafe_b64encode(value).decode().rstrip('=')
digest = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 600000, 32)
print('pbkdf2-sha256$600000$' + encode(salt) + '$' + encode(digest))
PY
)"
./gradlew --offline --no-daemon :backend:runSyntheticIntakePilot
```

Open **http://127.0.0.1:8082/** and sign in with that pilot password. The harness
binds only this loopback address and port. It refuses a missing verifier, a missing
separate UI build, launcher arguments or an occupied port. It never takes over an
existing listener. Do not expose or proxy it beyond loopback.

Each launch creates a new private temporary directory and synthetic SQLite store.
There is no existing-store or host override. Stop the owned launcher with Ctrl-C;
normal shutdown closes the store and removes only that generated directory. Then:

```sh
unset GTRAINER_SYNTHETIC_PILOT_PASSWORD_HASH
```

A forced kill or host crash can leave generated temporary files. Do not reset or
delete application databases to clean them up. Confirm ownership of a specific
`gtrainer-synthetic-intake-pilot-*` directory before any manual removal.

## One complete journey

1. Choose the bundled note beginning “A rehearsal on Tuesday evening is tentative”.
2. Inspect saved suggestions. Check the exact source excerpt and four unresolved
   fields. The draft is read-only, not staged or confirmed.
3. Click **Edit suggestion**. Change its label. Choose explicit local start/end
   dates, timezone and recurrence. For example, use synthetic dates
   `2026-11-10T18:00`–`2026-11-10T19:00`, `UTC`, and an explicitly chosen one-off or
   weekly rule. These are review inputs, not dates inferred from the original note.
4. Deliberately acknowledge each resolved unknown. Every acknowledgment is required
   before staging; invalid dates, reversed times and invalid recurrence still fail.
5. Click **Stage item for confirmation**. Inspect the visible pending JSON. No
   authoritative item exists yet. Remove or edit the draft if anything is wrong.
6. Click **Confirm selected items** or **Confirm this item**. Only this explicit
   action writes the selected synthetic items through the existing confirmation API.
7. Reload confirmed items or reload the page and select the source again. Confirmed
   items persist only within this disposable session. Reload never confirms drafts.

Source correction discards drafts and makes the original fixture unavailable.
It does not regenerate or repair a fixture. An uncertain confirmation response
requires reload and inspection, never an automatic retry. Unknown acknowledgment
is a UI review requirement, not a new server attestation protocol.

## Isolation and provenance

- Backend module and launcher exist only on the test classpath. Normal application
  assembly, provider selection, ingestion, schedulers and scoped execution are not
  constructed. The pilot bundle is separate from normal and development-review UIs.
- Eight pairs come from the frozen v5 corpus's **handwritten witnesses**, not retained
  acquired model responses. Startup verifies the pinned manifest and file hashes.
  Each available draft passes unchanged wire, source-binding and semantic validators.
- The fixture-only response declares `handwritten_frozen_witness` provenance and
  source/wire/manifest hashes. It has no execution identity or publication receipt.
  The normal saved-suggestion parser still requires its production envelope.
- Only exact current source identity, revision and content can retrieve a witness.
  Arbitrary new-source creation and unrelated APIs are unavailable. Corrections and
  explicit confirmation retain existing authentication, origin, CSRF, revision,
  atomicity and idempotency checks. The pilot uses its own session cookie name.
- Validation checks structure, binding and supported constraints; it does not prove
  universal factual truth. Review the displayed source and all proposed fields.

## Verification

Focused UI and real-store backend tests cover fixture provenance, strict negative
validation, unknown blocking, inert inspection/editing/staging, source correction,
explicit confirmation, replay conflicts, uncertain outcomes and isolation. The
approved implementation also requires a real-browser journey and owned-store count
snapshots before and after confirmation. See the active change's
[tasks 5.1–5.3](../../openspec/changes/automated-intake-unseen-evaluation-v5/tasks.md#5-approved-guarded-synthetic-interaction-pilot)
for the acceptance record.

Historical extraction results stay unchanged: prospective frozen v5 **7/8**;
separate post-hoc v5.1 comparison **8/8 on the same responses**. Fixture replay does
not override that original gate. Live extraction, personal data, production/runtime
admission, deployment and any new evaluation require separate bounded approval.
