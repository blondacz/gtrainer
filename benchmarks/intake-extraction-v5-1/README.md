# V5.1 bounded wording comparison

This isolated offline policy corrects two wording sensitivities without rewriting
responses, changing the candidate or weakening strict validation:

- Commitment labels are compared using casefold and removal of exactly one leading
  `a ` or `an `. Internal words, punctuation and whitespace remain significant.
- A complete practice clarification can match a uniquely bound training or rehearsal
  role. This is derived from frozen expected activity/source/unknown predicates, not
  candidate labels or case IDs. Training and rehearsal do not become interchangeable.

The alias requires one eligible role, one unresolved commitment, the anchored
expected activity, all four typed null fields and exactly their four disclosures.
Another potentially practice-compatible activity blocks new alias expansion even
when that other activity is resolved. Unsupported contexts get no new alternatives;
pre-existing frozen alternatives remain unchanged. There is no fuzzy language model,
global noun replacement, repeated article stripping or arbitrary paraphrase acceptance.

All identity, exact-source, typed fact, uncertainty and count checks still apply.
Missing date/time/timezone/recurrence details in questions, invented facts, changed
activities, missing/duplicate disclosures and local rejection remain failures.

The new manifest binds policy/source/tests to the frozen v5 corpus and inherited
checker lineage. V5 actually delegates comparison to the v4.1 checker with its supplied
contract; v4.2's historical i06 alternative is not silently applied to new case IDs.

```sh
python3 -m unittest discover -s benchmarks/intake-extraction-v5-1 -v
```

Create a private external 0700 output directory, then compare the retained full run:

```sh
python3 benchmarks/intake-extraction-v5-1/oracle_v5_1.py \
  --source-report /private/path/campaign/batch-01/report.json \
  --output /private/path/comparison
```

Exclusive 0600 `comparison.json` retains all eight cases, original/revised outcomes,
source/raw/stage/packet hashes and explicit post-hoc/zero-inference metadata. It is
not an original-v5 pass, improved generation, fresh evaluation or independent
semantic assessment. Original reports/receipts and closed authority stay unchanged.
No live runner wiring, model operation, app change or production admission occurs.

## Verified comparison

The same eight retained responses score **7/8 under frozen v5** and **8/8 under
post-hoc v5.1**. Only u03 changes. All45 old/new oracle tests pass, including236
v5 omission/wrong-field mutations and targeted activity/date/detail/disclosure and
ambiguity negatives. Original report, receipt, terminal decision, frozen parent
files and accepted runtime sources remain unchanged. No model rerun was needed.

Private evidence: `intake-v51-wording-comparison-approved-v35_c_9w`, containing
`retained-eight-comparison/comparison.json` and explicit user-authority acceptance.
This demonstrates the declared comparison correction, not improved model output
or independent semantic validation.

The [guarded synthetic pilot proposal](PILOT-PROPOSAL.md) records the original
separate-approval boundary. The user subsequently approved its synthetic-only
implementation, recorded in OpenSpec tasks 5.1–5.3. The opt-in
[interaction harness](../../docs/data/synthetic-intake-pilot.md) uses handwritten
frozen witnesses and disposable storage, not model calls or personal data.
Production/runtime admission remains closed; this is not new extraction evidence.
