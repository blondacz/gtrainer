# Connected insights and athlete context

Connected insights are an experimental, private feature. The connected-review executor has no normal application provider wiring, and its qualification/health gate defaults closed. A configured adapter, a valid packet, hosted consent, or a passing schema check does **not** qualify a model or authorize inference. No model calls or live data operations are authorized by this document. A separate explicit approval and qualification decision are required before any evaluation run.

## Context and attribution

The authenticated dashboard's **Context** section stores private context in the existing SQLite database and encrypted backups. Topics are restriction, symptom, goal, preference, feedback, and note. Sources distinguish user reports, user-entered clinician guidance, user-entered coach guidance, and review feedback. The `author/source details` field records what the user says about the source; it is not independently verified. Imported measurements remain evidence, not context. Model interpretations remain review output, not user facts.

Each entry has an observed date, optional applicability dates, sport/activity/review scope, and stable ID/revision. Correcting creates a new revision; prior revisions are available as history and are not current. Retiring removes an entry from current retrieval but preserves its history. Deletion removes active copies and dependent snapshots, published output, references, and linked feedback. Upstream records and unrelated reviews/imports/queue state remain unchanged. Encrypted backups are not edited in place; retained encrypted backups may contain deleted data until normal retention expires them.

Structured restrictions are explicit user-entered blocked/allowed activity or sport, or a maximum duration in minutes. Free text is not parsed into restrictions. Applicable restrictions are mandatory packet context. Conflicting blocked/allowed assertions or differing overlapping duration limits are both shown with attribution and date and block connected review until the user resolves the conflict. Deterministic retrieval orders entries and bounds optional context; if mandatory context exceeds the configured hard packet limit, review is refused rather than dropping a restriction.

## Snapshot, provider, consent, and limits

Connected-review snapshots bind code-prepared evidence digest, exact context IDs/revisions, selected provider/model, contract version, coverage and sport. New request generations stale older output. Deleting referenced context purges dependent review snapshots/output from active storage. The **Connected insights** dashboard inspects snapshots by ID without making a model call; it separates code-generated facts, attributed reports/restrictions, model interpretations, uncertainty/questions, and their citations. A missing/purged snapshot is reported unavailable; factual views remain usable.

Every request must select its provider/model explicitly; there is no default, automatic hosted escalation, or fallback. The preview discloses the exact serialized prompt and packet digest. Hosted consent applies only to that snapshot, exact provider/model, and digest; packet/provider change, revocation, or process restart requires fresh approval. No hosted request is sent without matching consent. A provider must enforce/report its hosted cost ceiling before transfer. Consent is not qualification.

Approved code limits are five minutes per attempt, ten minutes total, at most two attempts (one correction only after a validation rejection), 32 KiB serialized prompt, and 12 KiB response. Hosted calls cap at $0.25 per attempt and $0.50 total; local calls have no monetary ceiling. A snapshot is atomically claimed by at most one executor; a failed/cancelled attempt terminally closes it, and a new request requires a new generation. Timeout, cancellation, provider/resource/health failure, consent failure, changed snapshot, and other non-validation failures do not retry. Connected reviews are manual-only and are not invoked by scheduler/queue paths. Current production qualification/health gate refuses execution by default.

The independent validator binds claims to packet evidence/context, dates, sport, attribution, restrictions, and prohibited-claim patterns. It cannot recognize every paraphrase; passing validation is not proof that prose is true or safe. Never present model interpretation as measurement, diagnosis, causal proof, clinician verification, sport-safety clearance, or training prescription. Factual dashboard values are code-owned and remain available when connected review is blocked or unavailable.

## Feedback and evaluation

Usefulness ratings/corrections are dated, attributed `review_feedback` context linked to an immutable snapshot. They are neither measurements nor model-training data. Review feedback requires the connected snapshot ID and an explicit rating or correction.

The frozen, synthetic evaluation definition is in `benchmarks/connected-review/`. It contains three matched groups (facts-only, context-present, context-absent), nine cases, a six-dimension independent rubric, an 80% aggregate threshold, and zero tolerance for listed critical failures. It includes no model outputs; validation only checks the frozen artifacts and makes no provider calls. Before any future inference evaluation, obtain separate explicit approval, review the qualification/runtime/consent gate, use identical synthetic packets for explicit candidates, retain first-pass/correction/failure outcomes with hashes/latency/resource/cost metadata in the approved private store, and obtain independent human review. Schema validity or model self-approval alone cannot qualify a candidate.

## Verification boundary

Frontend tests use synthetic API fixtures; backend/API/provider tests use synthetic records and fake transports. Backup tests validate isolated synthetic/fixture databases. Do not paste personal context, prompts, provider credentials, or outputs into source fixtures, logs, CI artifacts, or public benchmark records. Existing `benchmarks/ollama` captures are separate immutable baselines.
