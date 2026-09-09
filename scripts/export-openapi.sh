#!/usr/bin/env bash
# =====================================================================================
# Exports the live, springdoc-generated specification of every service.
#
# docs/api/commerceflow-openapi.yaml is the reviewed platform contract; these exports are what
# the code actually serves. Diffing the two is how drift gets caught:
#
#   ./scripts/export-openapi.sh
#   git diff docs/api/generated/
#
# Requires the stack to be running (docker compose up -d). jq is optional, for pretty printing.
# =====================================================================================
set -euo pipefail

OUTPUT_DIR="${1:-docs/api/generated}"
GATEWAY="${GATEWAY_URL:-http://localhost:8080}"

SERVICES=(
  "auth-service"
  "order-service"
  "inventory-service"
  "payment-service"
  "notification-service"
)

mkdir -p "${OUTPUT_DIR}"
failures=0

for service in "${SERVICES[@]}"; do
  url="${GATEWAY}/v3/api-docs/${service}"
  target="${OUTPUT_DIR}/${service}.json"
  echo "Exporting ${service} from ${url}"

  if ! curl -fsS --max-time 15 "${url}" -o "${target}.tmp"; then
    echo "  could not reach ${service}; is the stack running?" >&2
    rm -f "${target}.tmp"
    failures=$((failures + 1))
    continue
  fi

  if command -v jq >/dev/null 2>&1; then
    # Sorted keys, so a re-export produces a diff only when the API actually changed.
    jq --sort-keys . "${target}.tmp" > "${target}"
    rm -f "${target}.tmp"
  else
    mv "${target}.tmp" "${target}"
  fi
  echo "  wrote ${target}"
done

echo
if [ "${failures}" -gt 0 ]; then
  echo "${failures} service(s) could not be exported." >&2
  exit 1
fi

echo "Done. Aggregated Swagger UI: ${GATEWAY}/swagger-ui.html"
