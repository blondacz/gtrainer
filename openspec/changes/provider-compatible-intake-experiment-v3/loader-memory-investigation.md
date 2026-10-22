# Read-only loader-memory investigation — 2026-10-19

## Scope and result

The user approved investigating loader/cache behavior after run-03. This investigation
read existing evidence, cached pinned source and strings/hashes in the already running
shared image. It did not execute a runner, load a model, generate, download, restart,
change configuration or relax a guard. Current source hashes still match run-03.

**Recommended candidate:** explicitly disable CPU weight repacking in a fresh,
v3-only owned runtime with `LLAMA_ARG_REPACK=0`. Do not apply this setting yet.
The control is supported by the installed parser, but its effective runner behavior,
memory benefit and performance are not live-verified. Another identical restart
does not address the observed loading pattern.

## What the actual samples establish

Run-03's thirteen samples show anonymous allocation and file memory growing together:

| Elapsed seconds | Current MiB | Anonymous MiB | File MiB | Mapped file MiB |
| --- | --- | --- | --- | --- |
| 12.114 | 765.86 | 416.43 | 345.78 | 0.94 |
| 28.969 | 4587.41 | 2287.09 | 2288.01 | 0.94 |
| 49.242 | 7857.17 | 3922.29 | 3915.78 | 1.01 |
| 65.986 | 9462.32 | 4757.46 | 4682.15 | 1.02 |

At the last sample, inactive file memory was 4680.18 MiB. The total crossed the
unchanged 9216-MiB stop before active-request verification or a scored case.
This supports a loading-time allocation plus cached-file footprint, not a proven
leak or output/context growth. Counters are not all disjoint or perfectly atomic;
inactive file is not guaranteed immediately reclaimable host headroom. Keep the
full `memory.current` guard and host-pressure refusal unchanged.

## Source and installed-control evidence

Cached source matches the previously pinned Ollama and llama.cpp revisions:
Ollama `cc4069396f3ad2c370c53eed2e4a42ac13adab84`, llama.cpp
`161755f29e415e2c33efe906e91843c068efd664`.

- `llama__src__llama-model.cpp:1092–1115` places optional extra CPU buffer types
  before the normal CPU buffer. The model parameter `use_extra_bufts` explicitly
  controls these types; `llama__include__llama.h:347` identifies weight repacking.
- `llama__src__llama-model.cpp:1757–1805` uses direct mapped storage only when the
  buffer is the default type and supports wrapping host pointers. Otherwise it
  allocates a real backend buffer. Thus enabling mmap alone does not guarantee
  zero-copy storage when an extra/repacked buffer is chosen.
- `llm__llama_server.go:2758–2768` explicitly distinguishes mmap-backed model
  storage from separate real allocations such as `CPU_REPACK`. This corroborates
  the mechanism, not the actual backend used by run-03.
- Installed CPU libraries contain `CPU_REPACK`. More importantly, installed
  `libllama-common.so` has SHA256
  `b1496a95a08a5fb09b66f6b1d900b114126dc94d8daebdb63719c3e826702434`, identical to the
  already cached binary. Static disassembly verifies registration of
  `--repack`/`--no-repack` and `LLAMA_ARG_REPACK` at `0x160428–0x160437`, rather than
  relying only on string presence. The callback at `0x14b410` writes the inverted
  boolean at common-parameter byte 4634; conversion at `0x1f6cf2` feeds
  `llama_model_params.use_extra_bufts` at byte 74. The parser's false-value helper
  accepts the literal `0`. No native parser or runner was executed.
- `llm__llama_server.go:434–475` initializes runner environment from `os.Environ()`
  and applies selected updates. The inspected launch path does not add a repacking
  CLI override. This supports an explicit owned-container environment setting;
  effective inheritance still needs observation in a separately approved run.

The exact stopped container's logs could not be recovered: the read-only CRI lookup
returned `NotFound`. Preserve that failure; do not recreate the run to obtain logs.
Consequently, **run-03's actual selected backend is not proven to be `CPU_REPACK`**.
Other allocated loading paths remain possible.

## Smallest useful next experiment and acceptance gate

Seek separate approval for a v3-only acquisition setting and one fresh named attempt.
Bind `LLAMA_ARG_REPACK=0` explicitly into new run/approval/tooling records. Preserve
all old attempts and leave shared deployment configuration and v1/v2 defaults alone.
Verify zero actions before authorization and exact refusal of mismatched runtime
settings. Retain existing ownership, stop, restoration and full-memory guards.

Before the non-scored probe, verify the owned pod's declared and server-process
environment and arrange bounded loader-log capture before pod deletion can remove
logs. During that already authorized probe, verify the spawned runner's configuration
and available buffer diagnostics. The runner does not exist before model loading;
do not add a hidden warm-up request to inspect it. If configuration cannot be
verified, abort through existing cleanup and refuse every scored call.
Use the same model, request bytes, schema, context/output reserve, CPU/thread counts,
12/10/9-GiB envelope, time bounds, one non-scored probe and eight single attempts.
Do not compensate for slower execution by enlarging timeouts or retrying.

Disabling repacking changes the execution path and may change throughput and numeric
behavior; disclose it as a runtime confounder. It is not a proven memory fix or
qualification evidence. Initial memory or host pressure can still fail admission.
No new attempt, runtime mutation, model download or real-response replay is authorized
by this investigation. A later run requires a fresh exact authorization.

Do not substitute `use_mmap=false`: buffered loading can still retain file cache
alongside allocated weights. Do not guess `GGML_CPU_REPACK`: that name was not found
in the inspected CPU libraries, while `LLAMA_ARG_REPACK` was positively registered.
Absence from those strings alone is not proof about every possible binary.

## Evidence and verification

Private basename: `intake-v3-loader-investigation-readonly-eprcqjfa`. It retains scope,
the failed exact-container log lookup, image/pod identity checks, installed library
hashes/control strings, matching cached-binary symbols and disassembly, thirteen-row
memory timeline, and `investigation-conclusion.json` binding source/evidence hashes.

Run-03 tooling hashes and frozen v1/v2/v3 integrity pass. This investigation changed
documentation only; no heavy build or application test suite was run. Task 4.1 stays
incomplete, with all eight scored cases unrun and no assessment or production gate
passed. The prior cleanup failure and host-pressure warning are not cleared by
read-only investigation.

## Later approved run-04 corrects the candidate hypothesis

The user subsequently approved implementation and one bounded no-repack attempt.
Actual environment verification confirmed the setting reached the runner. Retained
argv/logs nevertheless showed **`--load-mode none`**, **`load_mode = none`** and a
**4977.62-MiB allocated CPU model buffer**; the probe again exceeded full-memory
stop before scoring. Therefore disabling repacking alone did not solve the loading
footprint. The explicit non-mmap loading path now has direct evidence, unlike the
earlier unproven repacking attribution. The compiled feature banner `REPACK = 1`
does not prove selected model storage used repacking.

See the [immutable run-04 evidence and next approval boundary](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-04-2026-10-19).
An explicit `use_mmap:true` request option is a new candidate, not an applied fix;
it changes exact request bytes and needs new reviewed bindings/approval without
rewriting frozen proofs. The historical investigation and all failed attempts remain
preserved. No additional execution is authorized by this finding.
