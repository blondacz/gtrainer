# Provider compatibility audit checkpoint

## Status

Task 1.1 is **incomplete**. Implementation was explicitly requested after planning.
The user subsequently approved fetching the five matching parser/sampler files.
Those sources reveal a second concrete provider limitation: generated finite
repetition maxima above 2000 become unbounded. The proposed schema's three
`maxLength:4000` nodes therefore cannot be claimed as provider-enforced constraints.
See [the parser finding](#parser-finding-finite-maxima-above-2000-become-unbounded).
No v3 schema was frozen and no acquisition scaffolding was added.

## Scope and identity

The proposed schema removes exactly these two v2 locations:

- `properties.suggestions.items.anyOf[0].properties.unresolvedFields.uniqueItems`
- `properties.suggestions.items.anyOf[1].properties.unresolvedFields.uniqueItems`

Both currently equal `true`. No other schema change is authorized. Original
packets, instructions, example and strict local validation remain unchanged.

Cached source is llama.cpp commit
`161755f29e415e2c33efe906e91843c068efd664`, pinned by Ollama commit
`cc4069396f3ad2c370c53eed2e4a42ac13adab84`. Private evidence directory basename:
`intake-v2-source-inspection-approved-o_hqk613`.

| Cached evidence | SHA-256 |
| --- | --- |
| `common/json-schema.cpp` | `68ba2adbb6e0578c0d89e99cc9a9a7aa9c0b554b555bcd3908da71789045509d` |
| `common/json-schema.h` | `57933a181ec0e84cbba1abea7fd898dc03e452d7de1a35795b787cee679d02af` |
| `common/json-schema-to-grammar.cpp` | `d5a82fff5d611abd1198ff27c328b20376ffe9f110b4771d579c8bd20c70541f` |
| Cached converter disassembly | `ab1fbb241374baf85c9a56ad93821de368c5295ff4ba27de95879190fe1de6f4` |

The prior hash-bound disassembly establishes the normalized-schema GBNF conversion
path, not the optional llguidance bypass. It does not establish the downstream
grammar parser/sampler's treatment of the generated rules.

## Complete keyword inventory and converter evidence

The following are all keywords and values used after the proposed uniqueness
removal. These findings describe source transformation, not a native execution test.

| Constraint | Source coverage | Boundary vectors for final verification |
| --- | --- | --- |
| `type`: object, array, string, integer, null | Builder lines 295–320 and converter typed cases | Reject a string in place of an integer; permit explicit null only in nullable/inactive branches |
| `properties`, all fields `required`, `additionalProperties:false` | Builder 133–172; converter 699–793, 897–910 | Reject a missing required key or an extra identity key; fixed generated property order is a stricter serialization subset |
| `items`, `maxItems`: 8, 10, 20 | Builder 174–194; converter 18–42, 924–931 | Permit empty and exact-limit arrays; reject arrays with one item beyond the limit |
| `anyOf`: branch objects or nullable values | Builder 117–130, 234–237; converter alternatives | Permit the context/object plus commitment/null branch; reject both payloads being active |
| Finite string `enum` values | Builder 260–269; converter enum case | Permit a declared category or recurrence frequency; reject an undeclared value |
| `minLength`: 0, 1; `maxLength`: 10, 19, 128, 160, 1000, 4000 | Builder 197–209; converter 246–247, 933–950 | Check empty/nonempty strings, exact bounds, one beyond bounds and escaped Unicode |
| Integer `minimum:1`, `maximum:366` | Builder 212–224; converter integer range construction and 952–961 | Permit 1 and 366; reject 0, 367, fractional or quoted values |

The converter expresses bounded arrays and strings through `{minimum,maximum}`
repetition. At the initial audit checkpoint, matching native parser/sampler sources
were absent. That prevented claiming enforcement from converter output alone. The
subsequently approved source fetch exposed the specific limit documented below.
Local schema interpreters do not establish native enforcement by themselves.

### Escape semantics checked independently

The audit child flagged `"\u0061"` as a possible length counterexample. Primary
inspection resolved that particular concern: converter `char` is an alternative
between one unescaped character and one complete JSON escape. Thus `\u0061` is one
`char` occurrence, not six. A one-off source-fragment check covered ASCII, raw emoji,
one Unicode escape, escaped surrogate pairs and 10/11 repeated Unicode escapes.
This check did not execute native GBNF parsing or sampling.

An escaped supplementary character uses two surrogate escape occurrences, whereas
its raw spelling uses one grammar character. That can make provider serialization
more restrictive; it does not show that the maximum is omitted. Kotlin's separate
UTF-16 length and byte checks remain stricter local protections. No validation
rule was relaxed and no provider measurement was invented.

### Constraint ownership

- Proposed provider constraints: the structural and bound keywords inventoried
  above, conditional on completing the native enforcement audit.
- Local-only uniqueness: repeated permitted unresolved-field names remain
  grammatically repeatable after the proposed removal. For example,
  `["observedOn","observedOn"]` fits the context array item/count rules, but the
  unchanged local validator rejects the whole response before binding.
- Local-only protections also include raw/canonical byte bounds, strict decoder
  checks beyond the claimed schema subset, UTF-16 excerpt binding and all existing
  semantic/date/recurrence/disclosure rules. No response repair or deduplication.

## Initial missing prerequisite and approved source fetch

The cached tree identified the following matching public source files. The user
then explicitly selected **Fetch five source files**. All five were fetched at the
exact pinned commit and verified against their cached tree Git blob identities:

| File | Git blob |
| --- | --- |
| `src/llama-grammar.cpp` | `deffec8c0741da6fb5b6af16bded3b9bfcba48be` |
| `src/llama-grammar.h` | `b5a0e588e903be1f3277026ebfbc81cb966216f2` |
| `src/llama-sampler.cpp` | `61d28ad8a82ecabf98ee75d1bae65b93a0d67962` |
| `src/llama-sampler.h` | `e5db2982bd3e01d6823b09e9c3d3e059911526fe` |
| `common/sampling.cpp` | `06dea1e1ccea368f685fdcd1fcb57774aea9ff24` |

A preliminary filename check guessed `llama-sampling.cpp`, which does not exist in
this tree. The check failed without writing files; tree inspection identified the
correct `llama-sampler.cpp` path above. This was not a converter or model failure.

The private `v3-grammar-source-fetch.json` records this explicit approval, byte
counts, SHA-256 hashes and Git blob identities. No additional files, dependency,
model weights or runtime assets were downloaded. No build, native execution,
inference or runtime mutation was performed.

## Parser finding: finite maxima above 2000 become unbounded

Matching `src/llama-grammar.cpp` has SHA-256
`668d8ad4bd9066551061e1ccd38c6a681a84c50ddc05ae92d565a473d1239986`.
The relevant source path is explicit:

1. Line 13 defines `MAX_REPETITION_THRESHOLD` as `2000`.
2. Lines 621–648 parse bounded repetition, including `{1,4000}` emitted by the
   converter for `minLength:1,maxLength:4000`.
3. Lines 655–656 replace a finite maximum greater than that threshold with
   `UINT64_MAX`; they do not reject the schema or retain the requested maximum.
4. Lines 463–528 interpret that sentinel as `no_max` and construct recursive,
   unbounded repetition. Therefore `char{1,4000}` effectively admits `char+`.

This affects exactly three locations in the frozen v2 schema and proposed v3 schema:

- `properties.suggestions.items.anyOf[0].properties.sourceExcerpt.maxLength`
- `properties.suggestions.items.anyOf[0].properties.context.properties.content.maxLength`
- `properties.suggestions.items.anyOf[1].properties.sourceExcerpt.maxLength`

A concrete source-derived countervector is an otherwise well-shaped string with
4001 ASCII `a` characters. Its `maxLength:4000` schema rejects it, but the generated
and parsed repetition no longer has that maximum. This finding is source-backed,
not an observed model output or an executed native grammar test. It does not imply
that every bound is ignored: the other string maxima (10, 19, 128, 160, 1000) and
array maxima (8, 10, 20) are below the threshold.

The sampling source attaches nonempty, non-lazy grammars through
`common/sampling.cpp:264–275`; the grammar sampler delegates apply/accept in
`src/llama-sampler.cpp:2673–2684`. Sampling uses that parsed grammar, so applying
it does not restore an upper bound discarded by its parser.

Strict local v2 validation still rejects excerpts and context content longer than
4000 UTF-16 code units, and independently retains the 12,288-byte raw/draft bounds.
Those checks prevent local acceptance, but cannot make the provider's schema claim
true. They must not be relaxed, bypassed or described as grammar enforcement.

## Current stopping point

The current v3 plan authorizes only the two `uniqueItems` removals. It explicitly
requires a user decision for another unsupported provider constraint. Therefore
implementation stops before freezing; no task checkbox is earned by this audit.

The smallest proposed amendment is to revise the **unfrozen v3 plan** to omit only
the three `maxLength:4000` keywords as well, and explicitly assign those exact
limits to unchanged local validation. Retain `minLength:1`, all lower provider
bounds, byte limits, exact prompt/example, input packets, semantic validation and
4096 context. This is not approval to change the plan or code automatically.
A provider upgrade or a custom grammar workaround would be broader alternatives,
not actions authorized by the existing implementation request.

Primary inspection identified this threshold behavior and the three affected nodes.
An independent architect review confirmed the source chain and countervector,
without executing native code or treating agreement as an additional runtime proof.

Task 1.1 remains unchecked. V1/v2 integrity and current OpenSpec validation are
separate checks, not evidence that v3 compatibility has passed.
