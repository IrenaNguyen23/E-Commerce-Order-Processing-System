#!/usr/bin/env bash
# =====================================================================================
# Fills a running stack with order history, so the UI has something to show.
#
#   docker compose up -d --build
#   ./scripts/seed-demo-data.sh
#
# The catalogue seeds itself on start-up. Orders cannot: an order is spread across five
# databases — the aggregate and its saga in Order, the reservation in Inventory, the charge in
# Payment, the message in Notification — and every one of those rows is written by a different
# service inside its own transaction. Inserting them with SQL would mean inventing state the
# code never produced, and the moment the saga changed, the fixtures would be lying.
#
# So this places real orders through the real API and lets the saga run. Slower, and the only
# version that stays true.
#
# Safe to run more than once: it adds another round of orders each time. Idempotent where it
# matters — the demo accounts are reused rather than duplicated.
#
#   GATEWAY_URL=https://api.commerceflow.io ./scripts/seed-demo-data.sh
# =====================================================================================
set -uo pipefail

API="${GATEWAY_URL:-http://localhost:8080}"

# Seeded by V2__seed_catalogue.sql and DemoCatalogueInitializer.
LAPTOP_14="11111111-1111-1111-1111-111111111101"   # 1899.00
PHONE="11111111-1111-1111-1111-111111111102"       #  949.00
HEADSET="11111111-1111-1111-1111-111111111103"     #  279.00
KEYBOARD="11111111-1111-1111-1111-111111111104"    #  149.00
MOUSE="11111111-1111-1111-1111-111111111105"       #   79.00
MONITOR="11111111-1111-1111-1111-111111111106"     #  629.00
LIMITED="11111111-1111-1111-1111-111111111107"     #  199.00, only 2 in stock
LAPTOP_16="11111111-1111-1111-1111-111111111108"   # 2499.00
EARBUDS="11111111-1111-1111-1111-111111111111"     #  129.00
WATCH="11111111-1111-1111-1111-111111111113"       #  329.00
CABLE="11111111-1111-1111-1111-111111111117"       #   29.00

PASSWORD="Demo-pass-2026"

green() { printf '\033[32m%s\033[0m\n' "$1"; }
dim()   { printf '\033[2m%s\033[0m\n'  "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }

json() { python -c "import json,sys;d=json.load(sys.stdin);print(eval('d$1'))" 2>/dev/null || echo ""; }

# Registers the account if it is new, then logs in either way. Echoes the access token.
sign_in() {
  local email="$1" name="$2"
  curl -s -X POST "${API}/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"password\":\"${PASSWORD}\",\"fullName\":\"${name}\"}" \
    >/dev/null 2>&1
  curl -sf -X POST "${API}/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"password\":\"${PASSWORD}\"}" | json "['data']['accessToken']"
}

# Register is best-effort, so a failure here means the account exists with another password —
# the likeliest cause by far, and not something a retry will fix.
explain_signin_failure() {
  red "Could not sign in as $1."
  red "The account probably already exists with a different password. Either use that password,"
  red "or start from a clean slate:  docker compose down -v && docker compose up -d --build"
}

# place <token> <address> <json items> → order id
place() {
  local token="$1" address="$2" items="$3"
  curl -sf -X POST "${API}/api/orders" -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "{\"items\":${items},\"shippingAddress\":\"${address}\"}" | json "['data']['id']"
}

# Polls one order until its saga is terminal. The saga is asynchronous by design, so a seeded
# order is not finished the moment the POST returns.
await_terminal() {
  local order_id="$1" token="$2"
  for _ in $(seq 1 40); do
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

# order <token> <address> <items> <label>
order() {
  local token="$1" address="$2" items="$3" label="$4"
  local id status
  id=$(place "${token}" "${address}" "${items}")
  if [ -z "${id}" ]; then
    red "  ..  ${label}: rejected by the API"
    return 1
  fi
  status=$(await_terminal "${id}" "${token}")
  case "${status}" in
    COMPLETED) green "  ok  ${label} → COMPLETED" ;;
    CANCELLED)
      local step
      step=$(curl -sf "${API}/api/orders/${id}" -H "Authorization: Bearer ${token}" \
        | json "['data']['failedStep']")
      green "  ok  ${label} → CANCELLED at ${step}" ;;
    *) red   "  ..  ${label} → ${status}; the saga has not finished" ;;
  esac
}

echo "Seeding demo data into ${API}"
echo

health=$(curl -sf "${API}/actuator/health" | json "['status']")
if [ "${health}" != "UP" ]; then
  red "Gateway is not healthy (${health:-unreachable}). Start the stack first:"
  red "  docker compose up -d --build"
  exit 1
fi

# ---------------------------------------------------------------- Ada: the busy customer
echo "ada@commerceflow.io"
ADA=$(sign_in "ada@commerceflow.io" "Ada Lovelace")
[ -n "${ADA}" ] || { explain_signin_failure "ada@commerceflow.io"; exit 1; }
ADA_ADDRESS="Keizersgracht 1, 1015 CJ Amsterdam, NL"

order "${ADA}" "${ADA_ADDRESS}" \
  "[{\"productId\":\"${LAPTOP_14}\",\"quantity\":1},{\"productId\":\"${MOUSE}\",\"quantity\":1}]" \
  "laptop + mouse"

order "${ADA}" "${ADA_ADDRESS}" \
  "[{\"productId\":\"${HEADSET}\",\"quantity\":1},{\"productId\":\"${CABLE}\",\"quantity\":2}]" \
  "headset + two cables"

order "${ADA}" "${ADA_ADDRESS}" \
  "[{\"productId\":\"${MONITOR}\",\"quantity\":2},{\"productId\":\"${KEYBOARD}\",\"quantity\":1}]" \
  "two monitors + keyboard"

# 6 x 1899.00 = 11394.00, at or above the simulated acquirer's 10000.00 decline threshold.
order "${ADA}" "${ADA_ADDRESS}" \
  "[{\"productId\":\"${LAPTOP_14}\",\"quantity\":6}]" \
  "six laptops (declined on purpose)"

# Only two of these exist, so the saga stops at the inventory step and compensates nothing.
order "${ADA}" "${ADA_ADDRESS}" \
  "[{\"productId\":\"${LIMITED}\",\"quantity\":50}]" \
  "fifty limited docks (out of stock on purpose)"

# ---------------------------------------------------------------- Liam: the second customer
echo
echo "liam@commerceflow.io"
LIAM=$(sign_in "liam@commerceflow.io" "Liam Nguyen")
[ -n "${LIAM}" ] || { explain_signin_failure "liam@commerceflow.io"; exit 1; }
LIAM_ADDRESS="Nguyen Hue 45, District 1, Ho Chi Minh City, VN"

order "${LIAM}" "${LIAM_ADDRESS}" \
  "[{\"productId\":\"${PHONE}\",\"quantity\":1},{\"productId\":\"${EARBUDS}\",\"quantity\":1}]" \
  "phone + earbuds"

order "${LIAM}" "${LIAM_ADDRESS}" \
  "[{\"productId\":\"${WATCH}\",\"quantity\":1}]" \
  "smart watch"

# 5 x 2499.00 = 12495.00, also over the threshold.
order "${LIAM}" "${LIAM_ADDRESS}" \
  "[{\"productId\":\"${LAPTOP_16}\",\"quantity\":5}]" \
  "five 16 inch laptops (declined on purpose)"

# ---------------------------------------------------------------- what is now there
echo
ada_orders=$(curl -sf "${API}/api/orders?size=1" -H "Authorization: Bearer ${ADA}" \
  | json "['data']['totalElements']")
ada_notes=$(curl -sf "${API}/api/notifications?size=1" -H "Authorization: Bearer ${ADA}" \
  | json "['data']['totalElements']")

echo "─────────────────────────────────────────"
green "Done."
echo
echo "Sign in at http://localhost:3001"
echo
printf '  %-28s %s\n' "ada@commerceflow.io"   "${PASSWORD}   (${ada_orders:-?} orders, ${ada_notes:-?} notifications)"
printf '  %-28s %s\n' "liam@commerceflow.io"  "${PASSWORD}"
printf '  %-28s %s\n' "admin@commerceflow.io" "ChangeMe-Admin-2026   (admin console)"
echo
dim "Worth looking at:"
dim "  /orders                  mixed COMPLETED and CANCELLED, each with its own timeline"
dim "  /notifications           one message per terminal order"
dim "  /admin                   dashboard counts, now non-zero"
dim "  /admin/orders            both customers, filterable by status"
dim "  /admin/payments          completed charges and declines side by side"
dim "  /admin/inventory         CF-SPEAKER-001 sold out, CF-BAND-001 below its reorder level"
dim "  /admin/products          CF-PROTO-001 is deactivated and absent from the storefront"
dim "  /admin/reports           revenue and the two failure reasons, grouped"
