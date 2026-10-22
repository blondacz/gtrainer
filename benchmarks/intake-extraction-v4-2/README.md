# V4.2 contextual clarification policy

This isolated offline checker inherits v4.1 label-case equivalence and adds one
complete question alternative for i06's `training_unknowns` role:

> What are the practice date, start and end times, timezone, and recurrence details?

Here, "practice" is accepted as a contextual paraphrase of "training". The existing
predicates still require correct travel/training facts and all required unknowns.
Questions about travel, missing details, extra questions, changed source facts or
use of this alternative in another case still fail. There is no global synonym
replacement, fuzzy matching or arbitrary paraphrase acceptance.

The user explicitly authorized this policy after inspecting the output. This is
post-hoc development-set policy tuning, not improved generation or proof of general
reliability. The earlier description of this question as a definitively wrong
activity was too strong; possible demonstration wording carryover is not proven.

V4 and v4.1 files, manifests, raw responses, reports and receipts remain unchanged.
The new manifest binds this policy, implementation and tests to the parent manifest.
Comparison reports retain all three scores and the source report hash. They use all
eight responses from one acquisition, never mix runs, and declare zero new inference.
No prompt/model/runtime changes or new live execution are needed. Production
admission remains closed; no human assessment or fresh qualification is claimed.

```sh
python3 -m unittest discover -s benchmarks/intake-extraction-v4-2 -v
```

Create a private external 0700 output directory, then compare a complete report:

```sh
python3 benchmarks/intake-extraction-v4-2/oracle_v4_2.py \
  --source-report /private/path/campaign/batch-01/report.json \
  --output /private/path/comparison
```

The exclusive 0600 `comparison.json` is a separate secondary assessment, never a
replacement original report or receipt. This policy does not change live runner
wiring. A fresh acquisition would need separately scoped bounded authority.

## Verified outcome

The latest complete acquisition scores **7/8 under original v4, 7/8 under v4.1 and
8/8 under post-hoc v4.2**. All eight responses come from the same complete run. No
new inference or model rerun was needed; original scores remain unchanged.

All26 original/v4.1/v4.2 oracle tests pass, including234 missing/wrong-field
mutations with the new question alternative. Private evidence is retained under
`intake-v42-contextual-question-approved-ngnqywhr`, with the source-hash-linked
`latest-complete-comparison/comparison.json` and explicit user authorization.
