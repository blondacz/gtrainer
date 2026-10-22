# Prospective synthetic intake evaluation v5

Eight new packets (`u01`–`u08`) exercise paraphrased appointments/shifts, travel with
uncertain training, tentative rehearsal, a Unicode correction, conflicting guidance,
symptom onset and bounded three-week recurrence. Exact content, witnesses and finite
allowed representations are frozen before candidate inference. Witnesses are offline
tests, never model demonstrations. This is not an independent blind holdout or a
claim that inputs were absent from model training.

The candidate prompt hash is fixed at
`34ed33a07b3e52eb0d61a6152b7dc2a7d714984ddbc5b9b01b732b7f333fbd7e`.
The provider schema and all five prior demonstrations remain unchanged. V4.2
semantics apply prospectively: labels receive casefold; contextual question
alternatives are finite and role-specific. Facts, uncertainty and excerpts remain
strict. Historical v4/v4.1/v4.2 manifests, responses and scores remain unchanged.

```sh
python3 -m unittest discover -s benchmarks/intake-extraction-v5 -p test_suite_v5.py -v
python3 benchmarks/intake-extraction-v5/suite_v5.py
```

Corpus tests use synthetic assembled witnesses, not candidate outputs. Twelve
offline Python checks pass. A separate detached actual Kotlin integration accepted
all eight fresh witnesses through strict local validation and the oracle, and
rejected four negative cases at their intended wire/binding/semantic stages.
Source fingerprint and owned compiler stopping were independently verified; no
acceptance gate was skipped. Evidence:
`intake-v5-prospective-apply-approved-q4htyfnl/primary-offline-kotlin-acceptance.json`.
The corpus manifest permits no model calls. No v5 inference had run at that offline
checkpoint; later execution is separately authorized below.

## Durable execution seam

The existing runner now has a closed choice between legacy v4 and fresh v5. Suite
identity controls packets, revisions, ordered IDs, validation, oracle, phase names,
worker entry point and report bindings. It accepts no plugin paths or arbitrary
packets. Legacy v4 keeps its three-batch policy; v5 is limited to one batch with
eight scored launches and one probe. V5 does not use the recovery coordinator.
The Mac profile allowlist explicitly includes v5; resource and ownership gates
are unchanged. Final detached regressions pass:29/29 v5,33/33 shared runtime and
123/123 legacy tests, with no skipped gates. The legacy suite includes three actual
Kotlin integrations; v5 also executes all eight fresh witnesses and four rejection
cases. Current source fingerprints, nested synthetic jobs and compiler stop proofs
were verified. Evidence: `primary-execution-acceptance.json` in the apply root.
Fresh vocabulary-only input counts are2276/2281/2257/2289/2273/2286/2268/2282,
probe47. Maximum2289+1536 output+256 margin=4081<=4096. Exact request/render/token
hashes are bound; frozen prompt/demos/schema/model are unchanged. Final pinned
identity/resource/storage/policy/normal-pressure and one Ready unloaded service
checks passed, with no existing owner/workload. Evidence:
`primary-context-runtime-admission.json`. No inference occurred during admission.

Detached full v5 offline checks, including synthetic durable execution and actual
Kotlin integration, can be launched with the exact private candidate prompt:

```sh
python3 benchmarks/intake-extraction-v4/durable_tests_v4.py --start v5 \
  --candidate-prompt /private/path/to/the/frozen/instructions.txt
```

The supplied prompt must match the frozen hash and is included in the test source
fingerprint. Fake token counts used by execution tests are synthetic fixtures,
never admission evidence. Live execution requires fresh vocabulary-only counts.

Planned live scope, only after all offline/durable/context/runtime gates: one root,
one batch, eight scored launches and one non-scored stop probe; one four-hour grant
with 240-second cleanup reserve. No retries, prompt/policy changes, extra root,
response repair, personal data, publication or app integration. Context4096 reserves
1536 output tokens and256 margin; every new rendered input must be at most2304
tokens. Overflow blocks inference without truncation. Production remains closed.

## Authorized fresh batch — completed with seven conformance passes

After the user's separate "apply v5" instruction, all offline/source/context/runtime
gates passed. One fresh campaign was launched at2026-10-21T15:29:18.388066Z, expiring
at2026-10-21T19:29:18.388066Z with the original cleanup reserve. Root:
`intake-v5-prospective-apply-approved-q4htyfnl/campaign`; independent launchd
supervisor `v4w-b0__rqqx`. Exact one-root authority consumption, readiness and actual
selected-v5 worker/claim/run/case/campaign bindings were verified. All eight candidate
outcomes remain pending at this checkpoint. No retry, prompt/policy revision or
replacement root is authorized, and no quality or integration-readiness claim is made.

Final result: all eight responses completed normally, with **8/8 strict local
accepts and 7/8 frozen prospective conformance passes** from one complete batch.
Only `u03` fails `question_roles` and `suggestion_0.commitment.label`. These are
frozen checker failures, not a human usefulness score or a diagnosis of their cause.
No rules, prompt, packet or response changed after acquisition began.

The run used eight scored launches and one stop probe, with no retry/revision or
deadline breach. Execution lasted4257.669191 seconds. All nine actual request hashes,
packet/raw/stage/predicate/report/receipt/event bindings and original source closure
were verified. Eight local compiler groups and both owned pods have authoritative
stop proofs. Independent checks verified exact original deployment restoration,
one Ready unloaded shared service and normal pressure. Only after durable outcomes
was the completed temporary supervisor registration removed.

Private evidence in the apply root:
`primary-final-prospective-model-verification.json` and
`independent-final-model-restoration.json`. The original decision remains
failed/quality_failure. The integration-pilot recommendation is blocked by the
prospective conformance failure; no application integration or production admission
follows. All historical policy scores and failed evidence remain unchanged. No
automatic retry, post-response policy expansion or replacement root is authorized.

### Separately authorized wording correction

After the completed run, the user explicitly authorized correcting harmless label
article variation and context-bound practice/rehearsal question wording. The isolated
`benchmarks/intake-extraction-v5-1/` policy re-scores the same retained outputs:
original frozen-v5 **7/8**, explicitly post-hoc-v5.1 **8/8**. Only u03 changes.
All45 old/new oracle tests pass, including236 missing/wrong-field mutations and
targeted activity/date/detail/disclosure/ambiguity negatives. Original prospective
policy/outcomes, reports/receipts and runtime sources remain unchanged. No rerun or
model improvement is claimed. Evidence `intake-v51-wording-comparison-approved-v35_c_9w`.
A synthetic interaction pilot is proposed separately, not implemented or admitted.
