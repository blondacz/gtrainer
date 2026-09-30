#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew --no-daemon :backend:build
npm --prefix frontend ci --no-audit --no-fund
npm --prefix frontend test
npm --prefix frontend run build
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s benchmarks/ollama -p 'test_*.py'
