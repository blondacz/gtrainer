#!/usr/bin/env bash
set -euo pipefail

: "${IMAGE:?An immutable registry image reference is required}"
if [[ ! "$IMAGE" =~ ^ghcr\.io/blondacz/gtrainer@sha256:[a-f0-9]{64}$ ]]; then
  echo 'Expected an immutable image digest, not a mutable tag.' >&2
  exit 1
fi

name="gtrainer-arm64-check-${GITHUB_RUN_ID:-local}"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker pull --platform linux/arm64 "$IMAGE"
[[ "$(docker image inspect "$IMAGE" --format '{{.Architecture}}')" == arm64 ]]
[[ "$(docker run --rm --platform linux/arm64 --entrypoint uname "$IMAGE" -m)" == aarch64 ]]
docker run --detach --name "$name" --platform linux/arm64 \
  --memory=1g --cpus=2 --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --publish 127.0.0.1:18080:8080 "$IMAGE"

for attempt in $(seq 1 60); do
  if curl --fail --silent http://127.0.0.1:18080/healthz > /dev/null; then
    break
  fi
  sleep 2
done
[[ "$(curl --fail --silent http://127.0.0.1:18080/healthz)" == '{"status":"ok"}' ]]
curl --fail --silent http://127.0.0.1:18080/ | grep --quiet '<title>GTrainer</title>'
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' http://127.0.0.1:18080/api/trends)" == 401 ]]
echo 'Published Linux ARM64 image starts, serves the UI, and keeps private routes closed.'
