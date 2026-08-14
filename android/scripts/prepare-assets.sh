#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
"$ROOT/scripts/build-bootstrap.sh"
printf 'Hermes Mobile assets are ready\n'
