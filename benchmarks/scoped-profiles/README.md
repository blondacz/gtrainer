# Frozen scoped-profile evaluation v1

This is a new synthetic evaluation, not a revision or reinterpretation of the
legacy `connected-review` or `ollama` evaluations. No model calls, human scores,
qualification decisions or personal runtime acceptance are included.

## Scope and frozen inputs

`protocols-v1.json` defines separately named evaluations for:

- `scoped-descriptive-v1`: 12 scored cases covering both selection modes and both
  intents, matched display-only/assessment wellness, compatible mixed sports,
  overlapping/later period comparisons and morning/evening receive checkpoints.
- `context-intake-suggestion-v1`: eight scored cases covering faithful extraction,
  attribution, temporal ambiguity, fixed/recurring commitments, corrected revisions,
  embedded instructions and exact UTF-16 excerpts. Suggestions remain unconfirmed.
- `plan-amendment-selection-v1`: eight scored cases covering real permitted
  moves/reductions/omissions, useful generic outcomes, current revisions, remaining
  evidence after deletion and combined candidate collisions. Explanations are exact
  code-owned choices, not a prose-generation competition.

The shared longitudinal inputs are explicit Kotlin fixtures in
`backend/src/test/kotlin/com/gtrainer/ScopedEvaluationFixtures.kt`: fixed synthetic
identities, weeks of activity, dated wellness and receive provenance, an older
ACL report, illness revisions, authored sessions and confirmed commitments. Only
checkpoint-visible activities enter each packet. These pure fixtures do not claim
persistent snapshot authority, actual deletion history or valid approval receipts;
repository journeys cover those responsibilities separately.

Three mandatory deterministic preparation guards sit outside the model-scoring
denominator. Stale unresolved illness blocks preparation before any model call;
the scored follow-up uses an explicit synthetic user reconfirmation, not inferred
recovery. Unverified prior-night mapping also blocks preparation. Missing authored
permissions produce no candidates and no prompt, not inferred `no_change`. No
blocked input is rewritten to admit a prompt.

`artifacts-v1.json` retains exact initial/correction prompt objects and production
consent digests. `userPacketJson` is the exact packet string; it is not reconstructed
in Python. Kotlin uses the actual scope resolver, factual preparation, retrieval,
budgeting, profile contracts, candidate policy and invocation builders. Ordinary
tests regenerate those bytes and compare them without updating frozen files.
`manifest-v1.json` binds exact artifact, protocol, fixture/exporter and relevant
production source bytes. Hashes detect changes, not malicious replacement of the
whole repository or proof of human scoring.

## Offline verification

From the repository root, using an already installed JDK 25 and cached dependencies:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --offline --no-daemon \
  --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :backend:test --tests 'com.gtrainer.ScopedEvaluationArtifactsTest' --rerun-tasks
python3 benchmarks/scoped-profiles/protocol.py
python3 -m unittest discover -s benchmarks/scoped-profiles -p 'test_*.py'
```

The Python verifier checks exact hashes, all 28 cases, all three guards, profile
bindings, prompt budgets, unchanged correction packets and production prompt
digests. Negative tests cover changed packets/prompts/sources/rubrics, stale
manifests, duplicate/missing cases, weakened thresholds and unsafe score filtering.
The pure rubric helper is arithmetic only: accepted schema output still requires
usefulness and independent human assessment. It always reports production admission
closed. It does not authenticate a human declaration or write runtime configuration.

## Materialization and version changes

Initial materialization uses an explicitly created empty staging directory and
`GTRAINER_SCOPED_EVALUATION_EXPORT_DIR=<staging>` on the Kotlin command above.
The exporter creates a new `artifacts-v1.json` there with `CREATE_NEW`; it never
overwrites an existing export. Review the fixtures, generated packets, guards and
checks before initial freezing:

```sh
python3 benchmarks/scoped-profiles/protocol.py --freeze-from <reviewed-staging>
```

Freezing only creates missing artifact/manifest files. It refuses replacements.
Any material case, expectation, contract, prompt, setting policy or rubric change
requires a new evaluation version and separate artifacts. Do not silently refresh
v1 hashes to accommodate a failed model or alter frozen legacy resources.

## Scoring and approval boundaries

Each profile has six weighted dimensions totaling 100. Every scored case must
have a complete useful final. Every case/dimension must score at least 2/4, the
equal-case weighted mean must be at least 80%, and critical failures must be zero.
Independent human scoring is mandatory; there are no N/A dimensions or dropped
cases. All first/correction, rejection, timeout, empty, incomplete and unrun outcomes
remain visible. A successful correction does not erase the first attempt.

Twenty-eight focused cases are not statistical safety evidence. Current provenance
does not verify night/readiness or HRV method semantics. Structural validation and
model self-review do not prove usefulness. Before any separately approved run,
record the named candidate, model/version, exact settings, attempt/response/resource
bounds and approval against this frozen manifest. No provider/runtime is selected
here. Inference approval, hosted-transfer consent, new-profile qualification,
personal runtime acceptance and exact local amendment approval remain separate.
