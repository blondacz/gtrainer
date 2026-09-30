#!/usr/bin/env bash
set -euo pipefail

# Keep both ends on loopback. Stop with Ctrl-C; no background listener is left.
# This uses the existing dedicated SSH key, never a copied kubeconfig.
ssh -tt -i "$HOME/.ssh/gtrainer_pi" \
  -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes \
  -o HostKeyAlias=192.168.1.232 -o ConnectTimeout=5 \
  -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
  -L 127.0.0.1:8080:127.0.0.1:18080 \
  blondacz@192.168.1.231 \
  'sudo -n k3s kubectl -n gtrainer port-forward --address=127.0.0.1 service/gtrainer 18080:8080'
