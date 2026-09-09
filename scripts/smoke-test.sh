#!/usr/bin/env bash
# =====================================================================================
# End-to-end smoke test against a running stack.
#
#   docker compose up -d --build
#   ./scripts/smoke-test.sh
#
# Walks the happy path and both compensation branches, and fails loudly if any of them does not
# reach the state the architecture says it should. Deliberately uses only curl and the seeded
# catalogue, so it also works against a deployed environment:
#
#   GATEWAY_URL=https://api.commerceflow.io ./scripts/smoke-test.sh
# =====================================================================================
set -uo pipefail

API="${GATEWAY_URL:-http://localhost:8080}"
LAPTOP="11111111-1111-1111-1111-111111111101"   # seeded: 50 units at 1899.00
LIMITED="11111111-1111-1111-1111-111111111107"  # seeded: 2 units
ADDRESS="Keizersgracht 1, 1015 CJ Amsterdam, NL"

pass=0
fail=0

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }

check() {
  local label="$1" expected="$2" actual="$3"
  if [ "${expected}" = "${actual}" ]; then
    green "  PASS  ${label}"
    pass=$((pass + 1))
  else
    red   "  FAIL  ${label}: expected '${expected}', got '${actual}'"
    fail=$((fail + 1))
  fi
}

json() { python -c "import json,sys;d=json.load(sys.stdin);print(eval('d$1'))" 2>/dev/null || echo ""; }

# Polls an order until it reaches a terminal state, or gives up.
await_terminal() {
  local order_id="$1" token="$2"
  for _ in $(seq 1 30); do
    local status
    status=$(curl -sf "${API}/api/orders/${order_id}" -H "Authorization: Bearer ${token}" \
      | json "['data']['status']")
    case "${status}" in
      COMPLETED|CANCELLED) echo "${status}"; return 0 ;;
    esac
    sleep 1
  done
  echo "TIMEOUT"
}

echo "CommerceFlow smoke test against ${API}"
echo

# ---------------------------------------------------------------- 0. the gateway is up
echo "Gateway health"
health=$(curl -sf "${API}/actuator/health" | json "['status']")
check "gateway is UP" "UP" "${health}"
[ "${health}" = "UP" ] || { red "Gateway is not healthy; aborting."; exit 1; }

# ---------------------------------------------------------------- 1. identity
echo
echo "Authentication"
EMAIL="smoke+$(date +%s)@commerceflow.io"

curl -sf -X POST "${API}/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"S3cret-pass\",\"fullName\":\"Smoke Test\"}" \
  >/dev/null
check "register" "0" "$?"

TOKEN=$(curl -sf -X POST "${API}/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"S3cret-pass\"}" | json "['data']['accessToken']")
[ -n "${TOKEN}" ] && check "login returns an access token" "yes" "yes" \
                  || check "login returns an access token" "yes" "no"

me=$(curl -sf "${API}/api/auth/me" -H "Authorization: Bearer ${TOKEN}" | json "['data']['email']")
check "the token identifies the account" "${EMAIL}" "${me}"

anon=$(curl -s -o /dev/null -w '%{http_code}' "${API}/api/orders")
check "orders reject an anonymous caller" "401" "${anon}"

# ---------------------------------------------------------------- 2. catalogue
echo
echo "Catalogue"
public=$(curl -s -o /dev/null -w '%{http_code}' "${API}/api/products?size=1")
check "the catalogue is public" "200" "${public}"

stock_before=$(curl -sf "${API}/api/products/${LAPTOP}" | json "['data']['availableQuantity']")
echo "  laptop stock before: ${stock_before}"

# ---------------------------------------------------------------- 3. happy path
echo
echo "Saga: happy path"
order=$(curl -sf -X POST "${API}/api/orders" -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"productId\":\"${LAPTOP}\",\"quantity\":1}],\"shippingAddress\":\"${ADDRESS}\"}")

order_id=$(echo "${order}" | json "['data']['id']")
check "order is accepted as CREATED" "CREATED" "$(echo "${order}" | json "['data']['status']")"

check "order reaches COMPLETED" "COMPLETED" "$(await_terminal "${order_id}" "${TOKEN}")"

stock_after=$(curl -sf "${API}/api/products/${LAPTOP}" | json "['data']['availableQuantity']")
check "one unit was deducted" "$((stock_before - 1))" "${stock_after}"

payment=$(curl -s "${API}/api/payments/order/${order_id}" -H "Authorization: Bearer ${TOKEN}" \
  | json "['data']['status']")
check "a payment was recorded as COMPLETED" "COMPLETED" "${payment}"

# ---------------------------------------------------------------- 4. payment compensation
echo
echo "Saga: compensation on a declined payment"
stock_before=$(curl -sf "${API}/api/products/${LAPTOP}" | json "['data']['availableQuantity']")

# 6 x 1899.00 = 11394.00, at or above the simulated acquirer decline threshold of 10000.00.
declined_id=$(curl -sf -X POST "${API}/api/orders" -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"productId\":\"${LAPTOP}\",\"quantity\":6}],\"shippingAddress\":\"${ADDRESS}\"}" \
  | json "['data']['id']")

check "order reaches CANCELLED" "CANCELLED" "$(await_terminal "${declined_id}" "${TOKEN}")"

declined=$(curl -sf "${API}/api/orders/${declined_id}" -H "Authorization: Bearer ${TOKEN}")
check "it failed at the PAYMENT step" "PAYMENT" "$(echo "${declined}" | json "['data']['failedStep']")"

stock_after=$(curl -sf "${API}/api/products/${LAPTOP}" | json "['data']['availableQuantity']")
check "every reserved unit was released" "${stock_before}" "${stock_after}"

# ---------------------------------------------------------------- 5. stock compensation
echo
echo "Saga: compensation on insufficient stock"
oversold_id=$(curl -sf -X POST "${API}/api/orders" -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"productId\":\"${LIMITED}\",\"quantity\":50}],\"shippingAddress\":\"${ADDRESS}\"}" \
  | json "['data']['id']")

check "order reaches CANCELLED" "CANCELLED" "$(await_terminal "${oversold_id}" "${TOKEN}")"

oversold=$(curl -sf "${API}/api/orders/${oversold_id}" -H "Authorization: Bearer ${TOKEN}")
check "it failed at the INVENTORY step" "INVENTORY" "$(echo "${oversold}" | json "['data']['failedStep']")"

# ---------------------------------------------------------------- 6. idempotency
echo
echo "Idempotency"
KEY="smoke-$(date +%s)"
first=$(curl -sf -X POST "${API}/api/orders" -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"productId\":\"${LAPTOP}\",\"quantity\":1}],\"shippingAddress\":\"${ADDRESS}\",\"idempotencyKey\":\"${KEY}\"}" \
  | json "['data']['id']")
second=$(curl -sf -X POST "${API}/api/orders" -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"productId\":\"${LAPTOP}\",\"quantity\":1}],\"shippingAddress\":\"${ADDRESS}\",\"idempotencyKey\":\"${KEY}\"}" \
  | json "['data']['id']")
check "a replayed request returns the original order" "${first}" "${second}"

# ---------------------------------------------------------------- 7. notifications
echo
echo "Notifications"
sleep 2
count=$(curl -sf "${API}/api/notifications" -H "Authorization: Bearer ${TOKEN}" \
  | json "['data']['totalElements']")
if [ "${count:-0}" -ge 3 ]; then
  green "  PASS  the customer was notified about every terminal order (${count})"
  pass=$((pass + 1))
else
  red   "  FAIL  expected at least 3 notifications, got ${count:-0}"
  fail=$((fail + 1))
fi

# ---------------------------------------------------------------- summary
echo
echo "─────────────────────────────────────────"
green "passed: ${pass}"
[ "${fail}" -gt 0 ] && red "failed: ${fail}" || echo "failed: 0"
exit $([ "${fail}" -eq 0 ] && echo 0 || echo 1)
