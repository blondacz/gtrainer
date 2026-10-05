# Intel Mac local model cluster

Installed on the user's MacBookPro14,3 (Intel i7, 16 GB RAM, macOS 13.7.8) for the approved synthetic evaluation. Linux VMs on this setup use CPU inference; the Radeon GPU is not passed through.

## Installed configuration

- Lima 2.2.1: `~/.local/opt/lima-2.2.1`, executable symlink `/usr/local/bin/limactl`.
- Independent VM `gtrainer-models`: Apple Virtualization.framework, Ubuntu 24.04, 4 virtual CPUs, 8 GiB RAM, 40 GiB sparse disk, no host directory mounts.
- K3s `v1.37.1+k3s1`, node `gtrainer-mac-vm`. This cluster is not joined to the Pi.
- Ollama 0.35.0, pinned Linux amd64 container, namespace `gtrainer-models`, 3 CPU / 6656 MiB container limits.
- Model files persist in a 20 GiB local-path PVC inside the VM.
- Local API: **http://127.0.0.1:11435**. A guest systemd service forwards the Kubernetes service to guest localhost; Lima forwards it to Mac localhost.
- `/usr/local/bin/gtrainer-kubectl` executes kubectl inside this VM. No default host kubeconfig was replaced.

Both the Lima release archive and K3s binary were SHA-256 verified before installation; the Ubuntu image digest is pinned in `lima.yaml`.

## Everyday commands

```sh
limactl list
gtrainer-kubectl get nodes
gtrainer-kubectl -n gtrainer-models get pods
curl http://127.0.0.1:11435/api/tags

# Stop the VM to release its RAM/CPU; model storage persists.
limactl stop gtrainer-models

# Start it again; K3s, Ollama, and its port forward start inside the guest.
limactl start gtrainer-models
```

The VM was not configured to start automatically at Mac login. `caffeinate` prevents idle sleep during a running benchmark, not lid closure or loss of power.

If an evaluation stops on infrastructure failure, it scales Ollama to zero to cancel inference and retain model storage/captures. After investigating, restart with:

```sh
gtrainer-kubectl -n gtrainer-models scale deployment/ollama --replicas=1
gtrainer-kubectl -n gtrainer-models rollout status deployment/ollama
```

## Staged models

| Model | Quantization | Ollama manifest SHA-256 |
|---|---|---|
| `qwen3:8b` | Q4_K_M | `500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41` |
| `llama3.1:8b` | Q4_K_M | `46e0c10c039e019119339687c3c1757cc81b9da49709a3b3924863ba87ca666e` |

Downloaded sizes are approximately 5.23 GB and 4.92 GB. Only one model is loaded at a time. Both tags and returned digests were checked after download.

## Synthetic reference

```sh
caffeinate -dimsu python3 benchmarks/connected-review/run_connected_review_on_mac.py \
  --approve-synthetic-run --models qwen3:8b llama3.1:8b
```

`connected-review-mac-cpu-reference-v1` uses the exact nine V2 packets, V3 schema and validator, `temperature=0`, `seed=42`, `num_ctx=4096`, `num_predict=1536`, three inference threads, and `think=false`. At most one correction is allowed; truncated output, thinking output, and infrastructure failures do not trigger a correction. The Mac attempt timeout is 900 seconds rather than the Pi's 480, and its model memory limit is 6.5 GiB rather than 5 GiB. It is therefore a separate hardware/runtime reference, not an identical Pi configuration.

The runner checks the local cluster identity, model digests, Ollama version, pod health, and blocked egress before inference. It retains request/response bytes, hashes, outcomes, timings, token counts and sampled container memory privately in owner-only `~/.gtrainer-mac-review-*` directories. It supplies no expected answers and generates no human rubric scores. Models unload after each request.

Runtime egress is denied by `no-runtime-egress.yaml`. Outbound TCP connectivity was confirmed before applying this policy; the runner requires the same connection to be refused or time out afterward. Additional model downloads require deliberately removing this namespace's egress policy and restoring it before any benchmark. Ingress is denied; the local port-forward provides access. The benchmark does not use the app's model route or personal records, and production inference remains disabled.

The shared human review generator accepts these captures with `--capture` (`--pi-capture` remains an alias). Keep Mac results distinct from Pi outcomes and score usefulness independently of contract acceptance.
