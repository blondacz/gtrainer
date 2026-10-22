# Guarded live intake pilot

This is one known synthetic u02 acquisition, not a new eight-case evaluation or
production admission. The frozen prompt, five demonstrations, packet, schema,
Qwen3:8b/Ollama 0.35.0 settings and strict validators remain unchanged.

The separate profile admits at most one mandatory stop probe and one intake send.
It has a 3600-second runtime grant, 240-second cleanup reserve, 120-second preflight,
900-second probe and generation phases, 1800-second case and 600-second validation
cap. There is no retry, correction, replacement root or provider fallback.

User approval on 2026-10-22 covers implementation and this bounded test after
offline checks. The user subsequently states Ollama is used only by development
and need not be restored. New-pilot cleanup therefore leaves the original deployment
paused after proving all owned work stopped; historical restore-required behavior
is unchanged. A fresh exact grant and source closure are still required before
any tokenizer/model/API/runtime operation. Private responses never enter this tree.

`pilot_profile.py` verifies the exact derived u02 source and v5.1 comparison policy.
`revision_pilot.py` binds requests and distinguishes fake from real vocabulary
evidence. `run_mac_pilot.py` consumes one grant/root and uses the existing launchd
supervisor. Fake transport uses explicitly labeled witnesses and cannot admit live
execution. `replay_pilot.py` calls the unchanged packet-aware Kotlin bridge.

The vocabulary counter reads the pinned library from the original Ollama container's
current root filesystem. The old recorded container ID is not reused. During the
authorized supervised preflight, metadata resolves its current ID, requires the
pinned image and ready single pod, then uses non-interactive sudo and the matching
`LD_LIBRARY_PATH` to read existing library/model assets. The helper verifies the
library digest and vocabulary-only ABI; nothing is installed or downloaded.

Offline profile checks require an explicit private copy of the frozen prompt:

```sh
INTAKE_PILOT_TEST_PROMPT=/private/path/to/candidate.txt \
  python3 -m unittest discover -s benchmarks/live-intake-pilot -p 'test_*.py'
```

The remaining import/UI checks and actual run are tracked in
[the active tasks](../../openspec/changes/guarded-live-intake-pilot/tasks.md).
No browser refresh or suggestion inspection can invoke inference. Original generated
facts, case comparison and user-edited confirmation must remain separate outcomes.
