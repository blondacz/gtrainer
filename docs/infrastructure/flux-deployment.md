# Pi GitOps deployment

Status: task-2.3 manifests prepared; bootstrap/live validation pending. This
deploys only the scaffold, with no health records, credentials, or model calls.

## Layout and access boundary

- `clusters/pi/flux-system`: official Flux **v2.9.5** release manifests and a
  public HTTPS Git source for `main`; no Git token or kubeconfig is in Git/CI.
- `clusters/pi`: self-reconciliation, the protected app namespace, and a
  namespace-scoped app reconciler. Flux's installation/root reconciler has
  cluster privileges; the app reconciler cannot read secrets or change other
  namespaces. Restrict repository writes accordingly.
- `deploy/gtrainer`: a single ARM64 replica using the task-2.2 verified digest,
  Recreate strategy, non-root/read-only runtime, bounded CPU/memory/tmp, process
  probes, and a 2 GiB `local-path` PVC mounted at `/data`.
- Namespace and PVC have Flux pruning disabled. Removing a manifest must not
  silently erase records. Explicit PVC deletion is still destructive under the
  storage class's Delete reclaim policy; the Pi's SD card is not a backup.
  Local-path requested capacity is not a filesystem quota; monitor free space.
- The app Service is **ClusterIP only**, with no Ingress, NodePort,
  LoadBalancer, host networking, or host port. Default-deny ingress/egress covers
  the app namespace. Kubelet probes and authenticated API-server port-forward
  remain available. Health probes check the process, not source/model readiness.
- The scaffold does not yet use the PVC for a database; task 3.2 will connect
  storage. Task 2.5 still needs real user authentication. Do not import records.
- No model/Intervals.icu egress is allowed yet. Later ingestion must add explicit
  network rules; network permission alone is never hosted-model consent.

## Bootstrap (operator Mac, not CI)

Use a checksum-verified Flux v2.9.5 CLI. The official pinned installation URL is
in `clusters/pi/flux-system/kustomization.yaml`; no floating release is used.
The standard install includes source, kustomize, helm, and notification
controllers; image-automation controllers are not installed.

1. Review and push the manifests to `main`. Image promotion remains manual until
   task 2.4 adds a protected promotion path.
2. Render the official controllers with `flux install --version v2.9.5 --export`.
   Send the rendered YAML through the dedicated SSH connection into
   `sudo -n k3s kubectl apply --server-side -f -` on the Pi. No kubeconfig leaves
   the Pi. Wait for CRDs and controllers to be available.
3. Send `clusters/pi/flux-system/gotk-sync.yaml` through the same SSH stdin
   channel and apply it server-side. Do **not** apply the app Deployment directly:
   Flux pulls `main`, applies `clusters/pi`, then reconciles `deploy/gtrainer`.
4. Verify the GitRepository and both Flux Kustomizations are Ready, the app
   Deployment is available, and the PVC is Bound. Compare the running image
   digest to task 2.2's verified reference.
5. Test the tunnel, 401 API denial, absence of public app routes/listeners, and
   rejection of direct pod/service connections from a separate pod. These do
   not by themselves verify the router's SSH/API firewall or public reachability.

## Open the scaffold

On this Mac, keep this command running:

```sh
bash scripts/pi-tunnel.sh
```

Open **http://127.0.0.1:8080**. The Mac listener and Pi port-forward listener both
bind only to IPv4 loopback. SSH authenticates the operator and encrypts the LAN
connection. The local HTTP hop is not a public HTTP service. Stop with Ctrl-C.
If the local port is already occupied, stop that listener rather than broadening
the binding. A single `kubectl port-forward` ends if its selected pod is replaced;
restart the script after a rollout.

This is the initial operator-only access path, not a claim of browser session
authentication or a directly reachable LAN web address. Any future direct LAN
ingress requires TLS, real authentication, network/firewall review, and IPv4/IPv6
outside-LAN testing before use.

## Health and reconciliation

Through the dedicated SSH connection, run on the Pi:

```sh
sudo -n k3s kubectl -n flux-system get gitrepositories,kustomizations
sudo -n k3s kubectl -n flux-system get deployments
sudo -n k3s kubectl -n gtrainer get deployment,pods,service,pvc,networkpolicy
```

Flux polls Git every minute and reconciles every five minutes; retries are
30 seconds. To request an immediate Git read, annotate `GitRepository/flux-system`
with `reconcile.fluxcd.io/requestedAt` using a new timestamp. Do not fix drift by
editing the live Deployment: change the reviewed Git manifest instead.

Upgrade, restore, automated protected promotion, and tested rollback remain
separate tasks. Never remove a PVC to repair a failed rollout.
