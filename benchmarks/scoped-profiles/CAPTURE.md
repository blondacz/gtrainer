# Private offline capture and review packets

`capture.py` extends the frozen scoped-profile evaluation without modifying its
protocols or legacy benchmark tools. It imports existing synthetic responses,
validates them with the Kotlin production contracts and combined amendment policy,
and writes private capture and independent-human review packets. There is no model
adapter, network request, provider discovery, runtime enrollment or inference path.
This work does not supply the separately approved candidate acquisition in task 9.4.

## Private workspace and approval

Use a new external directory with mode `0700`. It must be outside the repository,
with no symlinked parent. All input files must be regular, single-link files with
mode `0600`. Keep these files and all generated evidence outside Git, terminal logs
and CI artifacts. Never use actual personal data in these synthetic evaluations.

Supply three separate files:

1. `run.json`: exact candidate/model/settings/resource identity and frozen profile.
2. `approval.json`: a separately recorded approval for **offline local replay only**,
   bound to the SHA-256 of compact UTF-8 `run.json` content (not its formatting).
3. `replay.json`: the existing raw first/correction attempts and resource records.

The tool does not create an approval. A matching JSON record is not authentication
or proof of human authority. A local replay approval never authorizes collecting
new model responses, transferring data, qualifying a profile or applying a plan.
Actual acquisition requires separate explicit permission before any model call.

The run record has exactly these fields:

```json
{
  "version": "scoped-private-run-v1",
  "runId": "00000000-0000-4000-8000-000000000001",
  "executionMode": "offline_replay",
  "inputOrigin": "externally_recorded",
  "profile": "context-intake-suggestion-v1",
  "caseIds": ["i01", "i02", "i03", "i04", "i05", "i06", "i07", "i08"],
  "manifestSha256": "<SHA-256 of exact manifest-v1.json bytes>",
  "model": {
    "candidate": "<explicit candidate>",
    "provider": "<recorded provider>",
    "modelVersionSha256": "<immutable model/version SHA-256>"
  },
  "settings": {"temperature": 0, "seed": 123},
  "resourceLimits": {
    "attemptTimeoutSeconds": 60,
    "caseTimeoutSeconds": 120,
    "maxResponseBytes": 12288,
    "memoryLimitBytes": 5368709120,
    "maxCostUsd": 1
  }
}
```

Every case of exactly one explicit profile must appear in its frozen order.
`inputOrigin` is either `externally_recorded` or `synthetic_fake`; neither implies
inference approval. Include all generation settings in the bounded settings object.
Do not substitute a new candidate or settings after approval.

`approval.json` has exactly these fields; timestamps must use UTC `Z` and a validity
window of at most 24 hours:

```json
{
  "version": "scoped-capture-approval-v1",
  "runSha256": "<SHA-256 of protocol.compact(run)>" ,
  "purpose": "synthetic_offline_replay_only",
  "allowCorrection": false,
  "recordedBy": "<approval record owner>",
  "recordedAt": "2026-10-18T00:00:00Z",
  "expiresUtc": "2026-10-18T01:00:00Z"
}
```

`replay.json` has `version: "scoped-private-replay-v1"` and a `cases` array. Each
case has `caseId` and `attempts`. Each attempt has exactly `attempt` (1 or 2),
`outcome` (`response`, `timeout`, `empty`, `incomplete` or `unrun`), `raw` (the exact
string, or null for unavailable output), and `resources`. Resource fields are
`elapsedMs`, `peakRssBytes`, `inputTokens`, `outputTokens` and `costUsd`, each a
nonnegative finite number or null when unavailable. Never invent unavailable
measurements. All raw strings, including partial failed responses, remain private.

Missing cases become explicit `unrun` cases. A recorded correction requires explicit
replay permission. Only a rejected or empty first response makes that correction
eligible as the final. Orphan, redundant and post-timeout corrections are retained
but cannot replace the first outcome. Nothing retries or resumes model execution.

## Run and inspect locally

With the installed JDK 25 selected and dependencies already cached:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) \
  python3 benchmarks/scoped-profiles/capture.py --directory <private-directory>
```

The command checks the frozen manifest and separate approval before reading raw
responses or invoking the validator. It launches only an offline Gradle test bridge,
not the application. Bridge requests/results and Gradle diagnostics remain private.
The Kotlin bridge regenerates exact frozen artifacts, validates all three profile
contracts and independently rejects combined amendment collisions. It never
confirms a suggestion, publishes a review or applies a plan. Batches support both
attempts for each case without enlarging the production model-envelope limit.

Successful replay writes new `capture.json` and `human-review.json` with mode `0600`.
Existing evidence is never overwritten. Validator failure may leave private staging
files for inspection; start a new directory after investigation, not an automatic
retry. Captures retain run/approval/tooling/prompt/raw hashes, settings, all attempts,
validation results, imported resources and every final outcome. Imported resource
claims are not independently measured or verified. Recorded deadline/memory/cost
overruns cannot count as accepted finals. Missing measurements remain missing.

Human-review JSON includes exact frozen inputs, expectations, rubric, attempts and
blank scores. It is data, not executable HTML. It does not pre-fill usefulness or
human critical-failure findings from schema validity. The frozen arithmetic helper
can evaluate separately supplied human scores, but cannot authenticate their author
or open runtime admission. Qualification stays incomplete and production admission
stays closed for every replay, including successful synthetic fake runs.

## Acceptance checks and limitations

```sh
python3 -m unittest discover -s benchmarks/scoped-profiles -p 'test_*.py'
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --offline --no-daemon \
  --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :backend:test --tests 'com.gtrainer.ScopedCaptureValidationTest'
```

Fake callback tests exercise approval-before-callback, all profiles and denominators,
first/correction outcomes, failed/empty/unrun cases, resource overrun accounting,
private files, refusal/tamper paths and runtime-generated sensitive markers. A
separate actual Kotlin bridge replay checks private file exchange and real
validation. Fake callbacks are test-only substitutions; they are not production
validation or model-safety evidence. No replay is live timeout/cancellation proof.
Hash pinning detects changes; it is not protection against replacing the entire
repository or forging local approval records. Keep independent approval, human
scoring, inference qualification, personal runtime acceptance and exact personal
amendment approval separate.
