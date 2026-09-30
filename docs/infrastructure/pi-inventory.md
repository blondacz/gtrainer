# Raspberry Pi and K3s inventory

Verified by read-only SSH commands on 2026-09-30 at approximately 19:05 UTC.
This is a point-in-time infrastructure inventory, not an application deployment
or a claim that public-network isolation has been tested.

## Hardware and operating system

| Item | Observed state |
| --- | --- |
| Host | `k3s-pi` |
| Hardware | Raspberry Pi 5 Model B Rev 1.1 |
| Architecture | `aarch64` / Linux ARM64 |
| CPU | 4 ARM Cortex-A76 cores, maximum reported frequency 2.4 GHz |
| OS | Debian GNU/Linux 12 (bookworm) |
| Kernel | `6.12.109+rpt-rpi-2712` |
| RAM | 7.9 GiB total; approximately 6.7 GiB available |
| Swap | None |
| Storage | 29.5 GiB SD card (`mmcblk0`); no additional block device reported |
| Root filesystem | 29 GiB ext4; 8.8 GiB used, 19 GiB available, 33% used |

Memory and free-space figures are snapshots. No local-model benchmark has been
performed, and available RAM is not evidence that inference will be usable.
The SD card is a single point of failure; local volumes are not backups.

## Networking

- Ethernet: `eth0`, DHCP address `192.168.1.231/24`.
- Wi-Fi fallback: `wlan0`, DHCP address `192.168.1.232/24`.
- Primary default route: gateway `192.168.1.254` through Ethernet, metric 100.
- Secondary default route: the same gateway through Wi-Fi, metric 600.
- Pod network: `10.42.0.0/24` on this node; `cni0` has `10.42.0.1` and
  `flannel.1` has `10.42.0.0`.
- The old `192.168.4.51` address is absent from the observed interfaces/routes.
- SSH through Ethernet succeeded using the previously trusted Pi host key.
- Outbound HTTPS to GitHub returned HTTP 200.

The Ethernet DHCP reservation in the router has not been verified. Keep the
Ethernet address stable because K3s was configured with that address during the
fresh installation. Wi-Fi is connected, but failover was not tested by
disconnecting Ethernet.

## Cluster

- Single node `k3s-pi`: `Ready`, control-plane role, internal IP
  `192.168.1.231`, no node external IP.
- K3s: `v1.36.4+k3s1`; containerd: `2.3.4-k3s1.36`.
- K3s service active; K3s and SSH enabled at boot.
- CoreDNS, local-path-provisioner, metrics-server, and Traefik deployments each
  have one available replica. The Traefik ServiceLB pod has both containers
  ready. Helm installation jobs are completed.
- Metrics-server snapshot: approximately 148 millicores (3% CPU) and 1287 MiB
  memory (15%) for the node.
- Only the four standard namespaces are present. No app workloads or
  `flux-system` namespace are present; Flux is not bootstrapped.
- No PersistentVolumes or PersistentVolumeClaims exist.
- Default StorageClass: `local-path`, provisioner `rancher.io/local-path`,
  reclaim policy `Delete`, binding mode `WaitForFirstConsumer`, volume expansion
  disabled. Deleting a claim may delete its local data; define backup/retention
  before deploying the app.

## Routing and privacy checks still required before deployment

- No Kubernetes Ingress objects exist.
- Traefik is already a LoadBalancer service on `192.168.1.231`, exposing ports
  80/443 with NodePorts 31833/31800. This is the standard K3s installation, not
  an authenticated application endpoint.
- Router port forwarding, IPv6 reachability, host firewall behaviour, and
  access from outside the LAN have not been verified. A private IPv4 address
  alone does not establish that the future dashboard is LAN-only.
- Application authentication, private routing, bounded model resources,
  persistent storage, backups, and Flux rollout verification remain pending.

## Deployment-time storage recheck (2026-09-30)

The user expected a 64 GB card. Read-only `lsblk -b` and `fdisk -l` instead
reported the physical `/dev/mmcblk0` device as **31,719,424,000 bytes** (29.54 GiB,
approximately a marketed 32 GB card), not a 64 GB device with a small partition.
The 512 MiB boot partition plus root partition fill the device through its last
sector; no unused partition space was found. No partition/filesystem resizing
was attempted. Physically check the card if this differs from its label.
After Flux/app deployment, root had approximately 11 GiB free. The original
inventory above remains a pre-deployment snapshot; current deployment evidence
is in [`flux-deployment.md`](flux-deployment.md).

## Verification commands

Run these on the Pi through the established SSH connection. Do not print or
commit kubeconfig, cluster tokens, API keys, or real health records.

```sh
date -u +%FT%TZ
hostname
tr -d '\000' < /proc/device-tree/model
uname -m
lscpu
free -h
df -h /
lsblk -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINTS
ip -br -4 addr
ip -4 route
systemctl is-active k3s
systemctl is-enabled k3s ssh
sudo k3s kubectl get nodes -o wide --request-timeout=10s
sudo k3s kubectl get pods,deployments,services,ingress,pvc -A --request-timeout=10s
sudo k3s kubectl get pv,storageclass --request-timeout=10s
sudo k3s kubectl get namespaces --request-timeout=10s
sudo k3s kubectl top nodes --request-timeout=10s
curl -fsS -o /dev/null -w 'GitHub HTTPS status: %{http_code}\n' --max-time 10 https://github.com
```
