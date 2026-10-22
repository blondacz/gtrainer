# V4.1 label-case comparison policy

This isolated evaluator treats commitment-label capitalization as formatting:
`meeting`, `Meeting` and `MEETING` are equivalent. It applies Unicode casefold only
to comparison copies of `commitment.label` and its allowed alternatives. It does
not trim whitespace, remove punctuation, substitute synonyms or rewrite responses.

All original v4 facts, source excerpts, identity, dates, timezones, recurrence,
uncertainty, suggestion counts and clarification-question checks remain unchanged.
Kotlin wire/binding/semantic acceptance is still required. A wrong activity such as
"practice" instead of "training" still fails.

The original v4 checker, frozen contract, active acquisition and historical outcomes
remain untouched. `manifest.json` binds this new policy, its implementation/tests
and the parent v4 source/contract/manifest. A comparison against previously acquired
responses is explicitly post-hoc, not a fresh experiment or retroactive v4 pass.

Run offline tests:

```sh
python3 -m unittest discover -s benchmarks/intake-extraction-v4-1 -v
```

To compare a complete private v4 report without inference, create a private external
0700 output directory, then run:

```sh
python3 benchmarks/intake-extraction-v4-1/oracle_v4_1.py \
  --source-report /private/path/campaign/batch-01/report.json \
  --output /private/path/comparison
```

The exclusive 0600 `comparison.json` preserves the original report hash, both case
results, the new manifest hash and explicit post-hoc/no-inference flags. It never
overwrites a report or receipt. Production admission remains closed. Live execution
wiring is not changed by this comparison-only policy.

Initial verification:16 original/new oracle tests passed, including all234 existing
missing/wrong-field mutations. On the previously complete eight-case run, original
v4 remains6/8; the separately labeled post-hoc v4.1 result is7/8. The lowercase
label no longer fails, but the wrong activity noun in the clarification still does.
This is a changed evaluation policy, not improved model output.

The subsequent complete targeted run scores7/8 under both original v4 and post-hoc
v4.1. Its label already passes v4; only the wrong activity question remains. The
secondary comparison uses the same eight acquired responses with zero additional
inference, not a mixture of results from different runs.
