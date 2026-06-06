#!/usr/bin/env bash
# Export OpenAPI spec from a running TestSeer backend.
# Usage: ./scripts/export-openapi.sh [base-url]
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
OUT="docs/openapi.yaml"

curl -sf "${BASE_URL}/v3/api-docs.yaml" -o "${OUT}"
echo "Wrote ${OUT} from ${BASE_URL}/v3/api-docs.yaml"
