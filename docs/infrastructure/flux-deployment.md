# Pi GitOps deployment

Status: task **2.3 complete**, verified on 2026-09-30. Flux pulled and reconciled
the scaffold on the physical Pi, with no health records, credentials, or model
calls. Access is operator-only through an SSH tunnel, not a public web endpoint.

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
controllers. The upstream remote manifest also contains optional controllers;
the Git overlay explicitly removes image-automation, image-reflector, and
source-watcher deployments/accounts/service. Their unused CRDs may remain.

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

For repeatable infrastructure-only checks on this Mac:

```sh
python3 scripts/verify_pi_deployment.py
```

This script reads only infrastructure objects, prints no full response bodies,
and refuses to run in GitHub Actions. It checks configured boundaries, not live
traffic or router settings; repeat traffic checks after any network change.

## Recorded deployment evidence

- GitRepository `flux-system` and Kustomizations `flux-system`/`gtrainer` were
  Ready at `main@sha1:d4843a546efcc3c6499049141063eaa421599cbe`.
- Four intended Flux controllers were available; optional deployments had been
  pruned. The root reconciler retained cluster administration, while app health
  assessment needed only added read-only pod/ReplicaSet access in `gtrainer`.
  The app service account has no secret access or rights in other namespaces.
- App Deployment was `1/1` available on ARM64, using
  `ghcr.io/blondacz/gtrainer@sha256:ccb2c1af5a44c37b15a06656e081012f108fa0e89db81dcbad2b5bbf0c768d96`.
  This is the image already verified by CI run
  [36780726075](https://github.com/blondacz/gtrainer/actions/runs/36780726075),
  not an automatically promoted new build.
- Server-side app-manifest dry-run as `gtrainer-reconciler` passed. New
  deployment regression checks passed locally and initial manifest CI run
  [36781830489](https://github.com/blondacz/gtrainer/actions/runs/36781830489)
  also passed its build/tests and published-image execution.
- `/data` was writable by UID 10001. A synthetic marker survived exact app-pod
  replacement and was removed afterward. The PVC was Bound and prune-protected.
  This tests volume persistence, **not database functionality or backup restore**.
- SSH-tunnel health/UI checks passed and `/api/trends` returned 401. Both tunnel
  listeners were loopback-only, and no app/tunnel host listener remained after
  teardown. Direct TCP 8080 connections failed on both Ethernet/Wi-Fi addresses.
- No Ingress or Traefik HTTP/TCP/UDP routing objects existed in any namespace;
  no app NodePort, external Service IP, host networking, or host port existed.
  Existing K3s Traefik 80/443 service was untouched and has no route to the app.
- Two named temporary non-root BusyBox probe pods confirmed a known-good
  network control while direct app pod-IP and ClusterIP HTTP requests timed out
  under the default-deny policy. Both test pods were removed. Probe image:
  `docker.io/library/busybox@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e`.
- Snapshot after deployment: app used about 60 MiB RAM; the four controllers
  totalled about 117 MiB. Root storage had about 11 GiB free. These idle scaffold
  figures do not establish ingestion/model resource adequacy.

The verified privacy boundary is **no direct application web path** plus
authenticated SSH port-forward. It does not certify the router, SSH/API-server
public reachability, IPv6 firewall, Ethernet DHCP reservation, or Wi-Fi failover.
Those checks remain necessary before adding a directly accessible LAN ingress.
No real user-session authentication, database, ingestion, or model is claimed.

Upgrade, restore, automated protected promotion, and tested rollback remain
separate tasks. Never remove a PVC to repair a failed rollout.
