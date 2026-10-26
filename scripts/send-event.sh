#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SECRET="${DEMO_WEBHOOK_SECRET:-dev-secret}"
EVENT_ID="${EVENT_ID:-${1:-evt-$(date +%s)-$RANDOM}}"
TIMESTAMP="$(date +%s)"
if [[ -z "${BODY:-}" ]]; then
  BODY="{\"type\":\"demo.ping\",\"data\":{\"id\":\"${EVENT_ID}\"}}"
fi
SIGNATURE="$(printf '%s.%s' "${TIMESTAMP}" "${BODY}" | openssl dgst -sha256 -hmac "${SECRET}" -hex | sed 's/^.*= //')"

curl -sS -X POST "${BASE_URL}/v1/events" \
  -H 'Content-Type: application/json' \
  -H "X-Hook-Source: demo" \
  -H "X-Hook-Id: ${EVENT_ID}" \
  -H "X-Hook-Type: demo.ping" \
  -H "X-Hook-Timestamp: ${TIMESTAMP}" \
  -H "X-Hook-Signature: ${SIGNATURE}" \
  --data-binary "${BODY}"
echo
