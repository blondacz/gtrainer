# Connected review synthetic evaluation

This directory contains versioned synthetic inputs only. It is not part of the historical Ollama benchmark baseline. Schema validity and model self-review never qualify a candidate.

## Evaluation v1: frozen, exploratory only

`evaluation-v1.json` and `cases-v1.json` remain unchanged. Review found two protocol defects: score 4 means no inappropriate advice, but v1 reverse-scored that dimension; v1 also averaged dimensions without applying the weights in its policy. The v1 March context date also falls outside its evidence period. Do not score v1 outcomes or use them for qualification. Any already-started v1 Pi run remains exploratory; retain its private captures and do not compare its scores with v2.

## Evaluation v2

`evaluation-v2.json` fixes the rubric while retaining the exact v1 dimension weights and production contract version. Higher always means better. Each case/dimension receives 0–4; score each dimension as `score / 4`, apply the listed weight, then average weighted case scores as a percentage. Pass requires every case/dimension score to be at least 2, weighted score of at least 80%, and zero critical failures. Thus an inappropriate-advice score of 4 contributes its full weight; score 0 contributes none. Critical failures cannot be offset by averages.

`cases-v2.json` contains nine matched synthetic cases as complete `connected-review-v1` packets. All evidence counts, dates, origins, context types, revisions, and restrictions are explicit fixture values. The materializer serializes those fields only; it does not infer packet metadata. V2 moves the March context observation inside its evidence period. Evaluation-only identity, expected outcomes, and source-fixture digest details stay outside model packets. Python packet tests and Kotlin decoder tests check packet shape; shared validator fixtures compare the Python guard with `InterpretationContractV1.decodeDraft`.

## Pi execution boundary

All evaluation policies keep `modelCallsPermitted` false. An artifact alone never enables inference. The production connected-review gate stays closed. The user approved the Pi candidates and separately approved a GPT-5.6 Luna conversational reference using synthetic packets only.

After protocol review, an explicitly approved v2 run uses:

```sh
python3 benchmarks/connected-review/run_connected_review_v2_on_pi.py \
  --run --approve-synthetic-run \
  --models granite4.2:3b ministral-3:3b
```

The runner uses trusted `gtrainer_pi` SSH identity, a fresh owned K3s namespace per candidate, pinned Ollama 0.35.0, 3 CPU cores, and a 5 GiB model-container limit. It stages and verifies one pinned model digest, then denies and probes pod egress before inference. It never calls the app's model route, mounts its database, or reads live records. Each case has a 300-second attempt limit, 600-second total limit, and at most one correction after a correctable whole-draft rejection. Timeout, incomplete output, resource, runtime-health, and transport failures do not trigger correction or fallback. Stop after any candidate infrastructure/resource failure.

The runner checks outputs using a Python guard with shared acceptance/rejection fixtures against Kotlin hard validation. Human reviewers still score every first-pass, correction, rejection, and failure outcome. Automated checks do not score usefulness or qualify a model.

V2 comparison is complete for Granite 4.2 3B and Ministral 3 3B. All captures stay in owner-only private directories. Granite had invalid date scopes and unsupported context references. Ministral hit Ollama's 768-token generation limit in all nine cases. These are V2 outcomes and must not be rewritten using a newer validator or settings.

## Evaluation v3: larger output budget and categorized errors

`evaluation-v3.json` keeps V2's rubric, thresholds, contract, and exact nine synthetic packets. It changes generation settings only: `num_predict` 768→1536, context 2048→4096, response ceiling 12 KiB→24 KiB, attempt timeout 300→480 seconds, and case timeout 600→1000 seconds. This gives responses room to finish and records the longer bounded runtime. The egress-probe command timeout is 20 seconds; the probe's network-connect timeout remains 2 seconds and a successful connection still blocks inference. V2 remains frozen.

The V3 validator records specific categories such as `unsupported_context_reference`, `scope_date_invalid`, `malformed_json`, and `generation_limit_reached`. It preserves hard acceptance checks; categories explain rejections, not rubric quality. Shared Python/Kotlin fixtures test acceptance parity. The V3 Pi runner adds the currently newest Qwen family member that fits the Pi's 5 GiB container limit: `qwen3.5:4b`, pinned to manifest digest `2a654d98e6fba55d452b7043684e9b57a947e393bbffa62485a7aac05ee4eefd`. The newer Qwen3.6 27B requires about 18–19 GB and does not fit. Model registry checked 2026-10-03: [Qwen3.5](https://ollama.com/library/qwen3.5), [Qwen3.6](https://ollama.com/library/qwen3.6).

Run only after verifying previous Pi cleanup and reviewing V3 artifacts:

```sh
python3 benchmarks/connected-review/run_connected_review_v3_on_pi.py \
  --run --approve-synthetic-run \
  --models granite4.2:3b ministral-3:3b qwen3.5:4b
```

The Pi runner has no hosted-provider invocation path. The separately approved OpenCode subagent reference below uses the harness's configured provider access; it is not a direct-API or controlled V3 transport benchmark.

The user also approved DeepSeek-R1-Distill-Qwen-1.5B as an additional Pi candidate. Ollama tag `deepseek-r1:1.5b` is pinned to manifest digest `e0979632db5a88d1a53884cb2a941772d10ff5d055aabaa6801c4e36f3a6c2d7` (registry checked 2026-10-04; model layer 1,117,320,512 bytes). Select it with `--models deepseek-r1:1.5b` and the same approval flags above. It uses the same V3 packets, schema, generation settings, and `think: false` request as the other Pi candidates; any thinking output or failure to honor that request is retained as a protocol outcome.

Gemma 4 E2B and Phi-4 Mini 3.8B were subsequently approved for sequential Pi evaluation after DeepSeek. Registry manifests checked 2026-10-04:

| Candidate | Pinned manifest SHA-256 | Download layers |
|---|---|---|
| `gemma4:e2b` | `b37049369adfe3d2b653af0ab301a062ca5cbe96ace6aa0d3f2559ee9b563fc2` | Approximately 4.59 GB, including projector and draft layers; same GGUF manifest as `e2b-it-q4_K_M` |
| `phi4-mini:3.8b` | `78fad5d182a7c33065e153a5f8ba210754207ba9d91973f57dffa7f487363753` | Approximately 2.49 GB; same manifest as `3.8b-q4_K_M` |

Both use the existing V3 runtime and resource limits. Download size is not a measurement of inference memory; compatibility and memory fit remain runtime outcomes. No concurrent candidate runs on the Pi.

After an explicitly requested Pi reboot, the V3 app baseline check was corrected to allow historical container restarts. It now requires a ready, unchanged baseline across ten seconds and still stops on any readiness, image, pod-name, or restart-count change during execution. Benchmark containers still require zero restarts. Failure diagnostics now retain benchmark pod status before cleanup, when available, to distinguish termination/OOM events from unavailable monitoring. These runner changes do not alter model generation limits or validation; captures bind the exact runner hash.

## Completed runs and hosted conversational reference

On 2026-10-04, Qwen3.5 4B completed all nine V3 cases: five final drafts passed the Python V3 guard and four were rejected after correction. Cleanup completed. Granite's complete V3 run rejected all nine cases. Ministral's latest V3 attempt rejected its first case and timed out on its second case; the incomplete run is retained rather than counted as nine failures. Earlier interrupted attempts are also retained separately.

DeepSeek-R1-Distill-Qwen-1.5B subsequently completed all nine V3 cases with zero protocol passes and confirmed cleanup. All nine emitted thinking despite `think=false`; four also hit the generation limit with incomplete JSON. One had an invalid date scope and two had unsupported context references (categories can overlap). This configuration tests thinking-disabled compliance; it does not establish performance with reasoning enabled.

Phi-4 Mini 3.8B completed all nine V3 cases: the three context-present cases passed on their first attempt, while all six facts-only/context-absent cases retained `unsupported_context_reference` after correction (15 attempts total). Gemma 4 E2B staged successfully with its pinned digest, then stopped during the first attempt after 51.6 seconds with `resource_guard_failed` / `health_or_resource_sample_unavailable`; no response was captured and no cases completed. Its last successful sample was 5116 MiB current / 5120 MiB peak container memory. This suggests memory pressure but does not prove an OOM kill: the capture lacks a termination diagnostic. Both namespaces were successfully removed and the live app remained healthy. Gemma's incomplete run cannot be treated as nine quality failures.

After the requested Pi reboot and baseline-check fix, Gemma's retry stopped during model staging when a resource-monitoring command timed out (`TimeoutExpired`). No inference cases completed. The retained pod status showed both containers ready, zero restarts, and no termination/OOM evidence; the last successful sample reported 4090.9 MiB current / 4103.0 MiB peak Ollama memory. This retry does not establish inference memory fit. Namespace cleanup succeeded and the live app remained ready with its unchanged post-reboot restart count. Both the baseline-only aborted run and this staging failure are retained privately.

The next user-approved Gemma retry increases each resource-monitor kubectl command timeout from 10 to 60 seconds with `--monitor-command-timeout-seconds 60`. The default remains 10 seconds; the override is retained in runtime/evaluation metadata. Slower monitoring commands can delay detection of resource/health changes; guards still fail closed on timeouts or unhealthy samples. Inference budgets (480 seconds per attempt / 1000 seconds per case), model staging budget (900 seconds), generation settings and the 5 GiB container limit are unchanged.

That 60-second-monitoring retry staged the pinned model successfully in 221.84 seconds, then lost the provider connection (`RemoteDisconnected`) after 55.827 seconds of the first inference attempt. No response or completed case was captured. The last successful sample reached 5120 MiB peak container memory (the 5 GiB limit), with 1270.3 MiB host memory available and the app healthy. The retained pod status still showed ready containers, zero restarts and no termination record; an inference subprocess OOM is possible but not confirmed by these diagnostics. This is an infrastructure failure, not a quality score. Owned namespace cleanup succeeded and the live app remained healthy.

The user also approved a separate local Intel Mac K3s cluster for larger models. Qwen3 8B and Llama 3.1 8B are staged with pinned manifests. See [local setup and reference protocol](local-k3s/README.md). The Mac reference keeps the V3 packets/output settings but uses a 900-second attempt timeout and a 6.5 GiB container limit; label results separately from Pi V3.

Both Mac candidates completed all nine cases. Qwen3 8B passed three first attempts and all six corrections (15 attempts total); its six first-pass rejections comprised three `prohibited_claim`, two `scope_outside_cited_period`, and one `clinician_attribution_invalid`. Final responses cite supplied context in all three context-present cases; citation presence does not establish correct attribution or useful connections. Llama 3.1 8B passed all nine first attempts, but every response contained empty `interpretations` and `questions` arrays: contract compliance without substantive review. No candidate is qualified.

The actual Kotlin decoder replay matched Python outcomes for all 24 Mac drafts (18 accepted, six rejected), with private replay results bound to response hashes. Median observed attempt latency was 270.5 seconds for Qwen and 77.1 seconds for Llama; empty Llama output makes this unsuitable as a useful-answer speed ranking. Captured container memory peaks reached 6469.7 MiB during Qwen and 6656 MiB during Llama. Peaks are cumulative over the shared pod lifetime, not isolated per-model measurements. The pod remained healthy with zero restarts after the run; model storage and runtime egress isolation remain in place.

The latest private human review pack includes ten candidates and 116 worksheet rows, using the latest 60-second-monitoring Gemma attempt and both completed Mac references. It labels Mac/Pi/hosted provenance separately, verifies frozen packet and request/response hashes, and leaves human scores blank (N/A for unavailable/incomplete drafts). Earlier packs and all prior captures remain unchanged. Independent human scoring remains outstanding; production inference remains disabled.

## Pi Qwen3 8B Q3 reduced-context reference

The user approved a Qwen3 8B Q3 / 2048-context fit test with at least 1 GiB host headroom. Inspection found only the 32 GB microSD card, not an SSD; the user explicitly approved persistent microSD storage instead. This is `connected-review-pi-qwen3-q3-microsd-reference-v1`, not identical-runtime Pi V3 or the Mac Q4 reference.

```sh
caffeinate -dimsu python3 benchmarks/connected-review/run_qwen3_q3_reference_on_pi.py \
  --approve-synthetic-run --approve-microsd-model-storage
```

The preparer downloads the public `bartowski/Qwen_Qwen3-8B-GGUF` Q3_K_M artifact at revision `0b69f75b7472688e6808490aa2b85efdb81b5ce7`, verifies 4,124,161,568 bytes and SHA-256 `c2c61b55d39ec6fc43f84b4cbbed4505fde386d4e03ed005087bfd8c69c502c3`, and stores it in `/var/lib/gtrainer-models/qwen3-8b-q3/blobs/blobs/sha256-<digest>` on the Pi. This directory is mounted only by the isolated benchmark pod and survives namespace cleanup. Import uses this existing blob without retaining a second GGUF copy. A mismatched existing blob is rejected, never overwritten. Download requires 1 GiB free disk space beyond the artifact and checks at least 1 GiB available host memory. No disk is formatted and no app data is moved.

The runner re-verifies the GGUF hash inside the pod, imports it as `gtrainer-qwen3:8b-q3_k_m`, verifies Q3_K_M and the imported model's source hash, and records its generated Ollama manifest digest and template hash. Only the reference changes context from 4096 to 2048 and minimum sampled host headroom from 768 to 1024 MiB. Its 5 GiB model-container limit, 3 CPU limit, 1536 output-token ceiling, V3 validator, nine frozen packets, at most one correction, 480-second attempt budget and 1000-second case budget remain unchanged. Monitoring kubectl commands use the separately recorded 60-second timeout. Headroom checks are sampled, not a guarantee against instantaneous excursions. Runtime egress must be blocked before inference; app-health guards and owned cleanup remain enabled. Captures stay private; production inference remains disabled.

Prepared public model storage remains on microSD after success or failure, reducing repeated downloads but consuming roughly 4.1 GB of card space. SSD migration remains a separate, unperformed operation. The reference is not qualified without independent human review; smaller quantization and context may affect answer quality.

The initial Q3/2048-context run imported successfully with Ollama manifest digest `1cfe11082ca46aa64ac99dd0f41f874671c654686fc6d6b3d48942ca04b13c8a`. Its first inference attempt timed out after 480.1 seconds with no response; no cases completed. All successful guard samples passed, with a minimum 1816.6 MiB available host memory and peak 4958.7 MiB container memory. Both benchmark containers remained ready with zero restarts; namespace cleanup succeeded and app health remained stable. The capture demonstrates staying within sampled memory limits for the interrupted attempt, not completion or quality qualification.

The user requested another run with a slightly larger memory ceiling and clarified an expected ten-minute timeout. The retry uses `--model-memory-limit-mib 5376` (5.25 GiB, 256 MiB above the initial limit) and `--attempt-timeout-seconds 600`, recorded in runtime/evaluation metadata. The case budget becomes 1240 seconds to accommodate two ten-minute attempts plus overhead. The 1024 MiB host-headroom guard and 2048 context are unchanged. Only the separate Q3 reference permits these overrides; ordinary V3 candidates retain the 5 GiB ceiling and 480/1000-second budgets. The persistent GGUF is re-verified and reused, not downloaded again. The attempt timer includes loading, prefill and generation, with `keep_alive=0s` unloading after each attempt. No returned timings exist for the first timeout, so its cold-start contribution is unknown; subsequent Q3 failures retain bounded owned Ollama logs before cleanup.

The 5376 MiB / 600-second retry completed its first two cases, both accepted on the first attempt (586.400 and 482.014 seconds). The context-present draft cites the supplied context. Loading took 26.521 / 50.192 seconds, prompt processing 135.190 / 167.780 seconds, and generation 424.633 / 263.988 seconds (about 2 tokens/second). The third, context-absent case timed out at 600.101 seconds without a complete response; bounded logs show generation still progressing. The remaining six cases were not run, not quality failures. All 275 resource samples passed their guards: minimum host headroom 1789.8 MiB, container peak 5376.0 MiB, maximum temperature 72.7°C. Both benchmark containers had zero restarts; owned cleanup succeeded and the live app remained ready with unchanged restart count. This is an incomplete reference, not a qualified candidate.

On explicit user request, resume from the third case was launched with unchanged settings and `--resume-capture <original-private-jsonl>`. Resume verifies the prior settings, frozen-case/validator hashes, accepted-prefix request/response hashes and current validation, and confirmed cleanup before any remote work. It writes a separate capture with an `evaluation_resumed` record binding the original capture SHA-256 and selected remaining case IDs; it never overwrites or copies the earlier outcomes. Cases restart with fresh per-attempt budgets, so this is a separately recorded continuation, not one uninterrupted evaluation. Continuations must be reconciled explicitly for human review; automatic multi-capture-chain resume is not supported. The job uses `caffeinate -dimsu` for its lifetime.

That continuation completed four further cases, all with accepted final drafts: running/context-absent (589.941 seconds), rest-limit/facts-only (479.579), rest-limit/context-present (488.366-second rejection for `clinician_attribution_invalid`, then 400.755-second corrected acceptance), and rest-limit/context-absent (475.953). It stopped at load-question/facts-only (overall case seven) after 600.099 seconds without a complete response. Logs show generation still progressing at about 2.07 tokens/second; both containers remained ready with zero restarts and no logged OOM evidence. All 498 sampled guards passed (1773.3 MiB minimum host headroom, 5376.0 MiB container peak, 74.35°C maximum temperature); owned cleanup succeeded and the app remained ready with unchanged restart count. Across the preserved original retry and its hash-linked continuation, six distinct cases have accepted final drafts, case seven timed out, and cases eight/nine were not run. This is not a single completed evaluation or a human-quality qualification. The job-scoped `caffeinate` ended with the stopped run; another continuation requires explicit authorization and capture-chain reconciliation.

After Qwen finished, `openai/gpt-5.6-luna` produced one response in each of nine fresh `general` subagent sessions. `prepare_luna_reference.py` prepares private task files with the exact synthetic packets, application instructions, and output schema. Each candidate was allowed one read of its task file and instructed to use no other tools. Expected outcomes, rubric scores, prior model outputs, and validation feedback were not supplied. There were no corrections or retries. Private manifests retain task/packet/response hashes, invocation prompts, and session IDs. Raw responses stay outside the repository.

All nine Luna responses passed both Python V3 validation and the actual `InterpretationContractV1.decodeDraft` Kotlin decoder. However, all three context-present responses omitted the supplied context and cited only measurements. Contract validity therefore does not establish connected-review usefulness or qualification.

This is `connected-review-luna-conversational-reference-v1`, not a controlled V3 inference run: system/task roles and harness instructions differ, the schema is text rather than constrained decoding, and temperature, seed, reasoning variant, context size, and output budget were not controlled. Provider token usage and cost were not exposed and remain null. No speed or cost ranking is supported. Independent human rubric review remains outstanding for all candidates.

The separately approved `openai/gpt-6.1-sol` reference also completed nine fresh sessions on 2026-10-04, one response per case without corrections. All nine task files were byte-identical to Luna's task files; the invocation wrapper differed only in the private task-file path. No reasoning variant was explicitly selected. All nine Sol responses passed Python V3 and the actual Kotlin decoder. Like Luna, Sol's three context-present responses did not incorporate the supplied symptom, restriction, or preference. This is `connected-review-sol-conversational-reference-v1` with the same uncontrolled-harness limitations, not evidence of qualification.

`prepare_luna_reference.py --model openai/gpt-6.1-sol` prepares private Sol tasks without calling a model. `prepare_human_review.py` accepts repeated `--hosted-manifest` arguments to include both references alongside Pi captures, verifies packet/response hashes, and writes a new owner-only review pack and blank scoring worksheet. Earlier review files and any human scores are not overwritten. Private manifests retain each response and session binding; raw responses are not copied into repository fixtures.

For local replay of a retained synthetic packet and response using the actual Kotlin decoder:

```sh
./gradlew :backend:installDist
java --class-path 'backend/build/install/backend/lib/*' \
  benchmarks/connected-review/ValidateSyntheticConnectedReview.java \
  /private/path/packet.json /private/path/response.txt --synthetic-only
```

Raw requests and responses stay outside the repository in owner-only `~/.gtrainer-connected-review-*` directories. Captures include model/runtime/settings, artifact/prompt/packet/request/response hashes, latency, token counts, sampled memory/host health, and local cost as null. Never copy captures into fixtures, CI, or public benchmark records.
