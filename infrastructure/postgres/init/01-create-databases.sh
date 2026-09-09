#!/bin/bash
# =====================================================================================
# Database per service (ADR-005).
#
# One PostgreSQL instance in local development, but five genuinely separate databases with
# separate owners: no service can read another one's tables even by accident, which is what
# keeps the boundary honest before it is split across instances in production.
#
# Runs once, on first start of an empty data volume.
# =====================================================================================
set -euo pipefail

APP_USER="${POSTGRES_USER:-commerceflow}"

DATABASES=(
  "commerceflow_auth"
  "commerceflow_order"
  "commerceflow_inventory"
  "commerceflow_payment"
  "commerceflow_notification"
)

for database in "${DATABASES[@]}"; do
  echo "Creating database ${database}"
  psql -v ON_ERROR_STOP=1 --username "${APP_USER}" --dbname "${POSTGRES_DB:-postgres}" <<-EOSQL
    SELECT 'CREATE DATABASE ${database}'
     WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${database}')\gexec

    GRANT ALL PRIVILEGES ON DATABASE ${database} TO ${APP_USER};
EOSQL
done

echo "All CommerceFlow databases are ready."
