#!/usr/bin/env bash
#
# Restores a CommerceFlow backup taken by ./scripts/backup.sh.
#
# ---------------------------------------------------------------------------------------------
# THE DRILL MATTERS MORE THAN THE SCRIPT
# ---------------------------------------------------------------------------------------------
#
# The first time anybody runs a restore must not be the day the disk died. Run this against a
# throwaway database periodically -- --into does exactly that and touches nothing real:
#
#   ./scripts/restore.sh ./backups/20260826T101500Z --into commerceflow_drill
#
# It restores each dump under a suffixed name, counts the rows in the tables that matter, and
# drops what it created. What you get out of it is the answer to the only question that counts:
# would this backup have worked.
#
# ---------------------------------------------------------------------------------------------
# USAGE
# ---------------------------------------------------------------------------------------------
#
#   ./scripts/restore.sh <backup-dir> --into <prefix>   # drill: safe, throwaway, self-cleaning
#   ./scripts/restore.sh <backup-dir> --force           # the real thing: DESTROYS live data
#   ./scripts/restore.sh <backup-dir> --force --only commerceflow_order
#
# Without --into or --force it does nothing but tell you which of the two you meant. A restore
# that overwrites production because an argument was missing is not a recovery, it is a second
# outage.

set -Eeuo pipefail

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-commerceflow-postgres}"
POSTGRES_USER="${POSTGRES_USER:-commerceflow}"

DATABASES=(
  commerceflow_auth
  commerceflow_inventory
  commerceflow_order
  commerceflow_payment
  commerceflow_notification
)

backup_dir=""
drill_prefix=""
force=false
only=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --into)  drill_prefix="${2:-}"; shift 2 ;;
    --force) force=true; shift ;;
    --only)  only="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    -*) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    *)  backup_dir="$1"; shift ;;
  esac
done

log()  { printf '  %s\n' "$*"; }
fail() { printf '\n  FAILED: %s\n\n' "$*" >&2; exit 1; }

psql_admin() {
  docker exec -i "${POSTGRES_CONTAINER}" \
    psql --username="${POSTGRES_USER}" --dbname=postgres --quiet --no-align --tuples-only "$@"
}

[[ -n "${backup_dir}" ]] \
  || fail "which backup? Usage: ./scripts/restore.sh <backup-dir> [--into <prefix> | --force]"
[[ -d "${backup_dir}" ]] || fail "no such backup directory: ${backup_dir}"

# The manifest is written last by backup.sh, so its absence means the dump never finished. A
# half-written dump restores without complaint and leaves you missing rows you will not notice.
[[ -f "${backup_dir}/MANIFEST" ]] \
  || fail "${backup_dir} has no MANIFEST, so that backup never completed. Do not restore from it."

if [[ -z "${drill_prefix}" && "${force}" != true ]]; then
  cat >&2 <<'USAGE'

  Refusing to guess.

    --into <prefix>   restore into throwaway databases and drop them afterwards (the drill)
    --force           overwrite the live databases (recovery -- this destroys what is there now)

USAGE
  exit 2
fi

if [[ -n "${only}" ]]; then
  DATABASES=("${only}")
fi

docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1 \
  || fail "container ${POSTGRES_CONTAINER} is not running"

printf '\nCommerceFlow restore -- %s\n' "${backup_dir}"
sed -n '2,3p' "${backup_dir}/MANIFEST" | sed 's/^/  /'
printf '\n'

# ---------------------------------------------------------------------------------------------
# The tables worth counting after a restore
# ---------------------------------------------------------------------------------------------
#
# Not every table -- the ones whose absence would mean the business lost something
# irreplaceable. A restore that brings back the catalogue but not the orders is a failed restore
# that looks like a successful one.

table_for() {
  case "$1" in
    *auth)         echo "users" ;;
    *inventory)    echo "products" ;;
    *order)        echo "orders" ;;
    *payment)      echo "payments" ;;
    *notification) echo "notifications" ;;
    *)             echo "" ;;
  esac
}

restore_one() {
  local source_db="$1"
  local target_db="$2"
  local dump="${backup_dir}/${source_db}.dump"
  local table
  local count

  [[ -f "${dump}" ]] || fail "the backup has no dump for ${source_db}"

  log "restoring ${source_db} -> ${target_db}"

  psql_admin --command "DROP DATABASE IF EXISTS \"${target_db}\";" >/dev/null
  psql_admin --command "CREATE DATABASE \"${target_db}\";" >/dev/null

  # --no-owner because the dump was taken that way and the roles may not exist here;
  # --exit-on-error so a failure stops rather than leaving a partly-populated database that
  # looks restored.
  if ! docker exec -i "${POSTGRES_CONTAINER}" \
        pg_restore --username="${POSTGRES_USER}" --dbname="${target_db}" \
                   --no-owner --no-privileges --exit-on-error < "${dump}"; then
    fail "pg_restore failed for ${source_db}. That backup is not usable -- find out why now."
  fi

  table="$(table_for "${source_db}")"
  if [[ -n "${table}" ]]; then
    count="$(docker exec -i "${POSTGRES_CONTAINER}" psql --username="${POSTGRES_USER}" \
             --dbname="${target_db}" --quiet --no-align --tuples-only \
             --command "SELECT count(*) FROM ${table};" | tr -d '[:space:]')"
    log "  ${table}: ${count} row(s)"

    # Zero is not automatically wrong -- a fresh environment has no orders. It is worth saying
    # out loud, because a restore that silently produces empty tables is the failure mode this
    # whole drill exists to catch.
    if [[ "${count}" == "0" ]]; then
      log "  NOTE: ${table} came back empty. Correct only if it was empty when the dump was taken."
    fi
  fi
}

if [[ -n "${drill_prefix}" ]]; then
  printf '  Drill mode: nothing live is touched.\n\n'

  created=()
  for db in "${DATABASES[@]}"; do
    target="${drill_prefix}_${db#commerceflow_}"
    restore_one "${db}" "${target}"
    created+=("${target}")
  done

  printf '\n  Cleaning up\n'
  for target in "${created[@]}"; do
    psql_admin --command "DROP DATABASE IF EXISTS \"${target}\";" >/dev/null
    log "dropped ${target}"
  done

  printf '\n  Drill passed. Every dump in %s restored and read back.\n' "${backup_dir}"
  printf '  Write the date down: an untested backup older than the last migration is a guess.\n\n'
  exit 0
fi

# ---------------------------------------------------------------------------------------------
# Real recovery
# ---------------------------------------------------------------------------------------------

cat <<'WARNING'

  ------------------------------------------------------------------
  This DROPS the live databases and replaces them with the backup.
  Everything written since the backup was taken is lost.

  Stop the services first. A running service reconnects mid-restore
  and writes into a half-restored database.
  ------------------------------------------------------------------

WARNING

printf '  Type RESTORE to continue: '
read -r confirmation
[[ "${confirmation}" == "RESTORE" ]] || fail "cancelled"

printf '\n'
for db in "${DATABASES[@]}"; do
  restore_one "${db}" "${db}"
done

cat <<'AFTER'

  Restored.

  Before letting traffic back in:

    1. Start the services and watch the logs. Flyway reports the schema version, and it
       must match the code you are running. A dump older than the last migration restores
       fine and then fails at the first query.

    2. Redis was not restored, on purpose. The token blacklist is empty, so tokens revoked
       before the failure are valid again until they expire. If the outage involved a
       compromised account, revoke it again now.

    3. The outbox relay republishes anything unacknowledged at the moment of the dump.
       Consumers are idempotent -- processed_event dedupes on (consumer group, event id) --
       so duplicates are absorbed rather than charging anybody twice.

    4. Reconcile payments against Stripe. Anything captured after the dump exists at the
       provider and not here. That gap is money, and it is the one thing no restore brings
       back.

AFTER
