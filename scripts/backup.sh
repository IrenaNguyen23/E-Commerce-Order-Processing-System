#!/usr/bin/env bash
#
# Backs up all five CommerceFlow databases.
#
# ---------------------------------------------------------------------------------------------
# WHAT THIS IS, AND WHAT IT IS NOT
# ---------------------------------------------------------------------------------------------
#
# This is a backup. It is not high availability, and the two solve different problems:
#
#   * A backup answers "the data is gone, can we get it back" -- and costs you whatever happened
#     between the last dump and the failure.
#   * HA answers "a machine died, is the shop still open" -- and costs money and a platform
#     decision nobody has made yet.
#
# The single-replica Postgres, Redis and Kafka in docker-compose.yml and k8s/ are development
# topology. Going to production on them means accepting that losing the machine means an outage;
# what this script removes is the far worse case, which is losing the machine meaning losing the
# orders.
#
# ---------------------------------------------------------------------------------------------
# WHAT IS AND IS NOT BACKED UP
# ---------------------------------------------------------------------------------------------
#
#   Postgres  -- yes. Everything of record: orders, payments, catalogue, accounts, audit trail.
#   Redis     -- no, deliberately. It holds the token blacklist, the login throttle and caches.
#                All of it either rebuilds or expires; a restored two-day-old blacklist would be
#                worse than an empty one, because it would let revoked tokens back in.
#   Kafka     -- no. Messages in flight are covered by the outbox: every one of them is derived
#                from a row in Postgres, and the relay republishes anything unacknowledged. That
#                is the whole reason the outbox exists.
#
# ---------------------------------------------------------------------------------------------
# USAGE
# ---------------------------------------------------------------------------------------------
#
#   ./scripts/backup.sh                     # dump into ./backups
#   BACKUP_DIR=/mnt/backups ./scripts/backup.sh
#   RETENTION_DAYS=30 ./scripts/backup.sh
#
# A backup that lives on the same disk as the database is not a backup. Point BACKUP_DIR at
# something else, and copy it somewhere else again.

set -Eeuo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-commerceflow-postgres}"
POSTGRES_USER="${POSTGRES_USER:-commerceflow}"

DATABASES=(
  commerceflow_auth
  commerceflow_inventory
  commerceflow_order
  commerceflow_payment
  commerceflow_notification
)

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="${BACKUP_DIR}/${timestamp}"

log()  { printf '  %s\n' "$*"; }
fail() { printf '\n  FAILED: %s\n\n' "$*" >&2; exit 1; }

# Any failure leaves a partial directory behind, which on the next restore looks exactly like a
# complete one. Remove it, so what is on disk is only ever a finished backup.
cleanup_partial() {
  if [[ -d "${target}" && ! -f "${target}/MANIFEST" ]]; then
    rm -rf "${target}"
    printf '  Removed the partial backup at %s\n' "${target}" >&2
  fi
}
trap cleanup_partial ERR INT TERM

printf '\nCommerceFlow backup -- %s\n\n' "${timestamp}"

docker inspect "${POSTGRES_CONTAINER}" >/dev/null 2>&1 \
  || fail "container ${POSTGRES_CONTAINER} is not running. Start the stack, or set POSTGRES_CONTAINER."

mkdir -p "${target}"

for db in "${DATABASES[@]}"; do
  log "dumping ${db}"

  # --format=custom, not plain SQL: it compresses, and pg_restore can then restore selectively
  # and in parallel. A plain dump of a database with product images in it is enormous.
  if ! docker exec "${POSTGRES_CONTAINER}" \
        pg_dump --username="${POSTGRES_USER}" --format=custom --no-owner --no-privileges \
                --dbname="${db}" > "${target}/${db}.dump" 2>"${target}/${db}.err"; then
    cat "${target}/${db}.err" >&2
    fail "pg_dump failed for ${db}"
  fi
  rm -f "${target}/${db}.err"

  size="$(du -h "${target}/${db}.dump" | cut -f1)"
  log "  ${size}"
done

# Written last, and its presence is what marks the backup complete. Anything without it is a
# partial dump and the restore script refuses it.
{
  printf 'CommerceFlow backup\n'
  printf 'taken:      %s\n' "${timestamp}"
  printf 'host:       %s\n' "$(hostname)"
  printf 'container:  %s\n' "${POSTGRES_CONTAINER}"
  printf 'databases:  %s\n' "${DATABASES[*]}"
  printf '\nRestore with: ./scripts/restore.sh %s\n' "${target}"
  printf '\nNOT included: Redis (rebuilds; a stale token blacklist would be worse than none)\n'
  printf 'NOT included: Kafka (every message is derived from an outbox row in Postgres)\n'
} > "${target}/MANIFEST"

trap - ERR INT TERM

printf '\n  Backup complete: %s\n' "${target}"
printf '  Total: %s\n\n' "$(du -sh "${target}" | cut -f1)"

# ---------------------------------------------------------------------------------------------
# Retention
# ---------------------------------------------------------------------------------------------
#
# Only directories that have a MANIFEST are removed. A partial one is left alone deliberately:
# it means a backup failed, and quietly deleting the evidence would hide a run of failures.

if [[ "${RETENTION_DAYS}" -gt 0 ]]; then
  removed=0
  while IFS= read -r -d '' old; do
    if [[ -f "${old}/MANIFEST" ]]; then
      rm -rf "${old}"
      removed=$((removed + 1))
    fi
  done < <(find "${BACKUP_DIR}" -mindepth 1 -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" -print0)

  if [[ "${removed}" -gt 0 ]]; then
    printf '  Removed %s backup(s) older than %s days\n\n' "${removed}" "${RETENTION_DAYS}"
  fi
fi

# ---------------------------------------------------------------------------------------------
# THE PART PEOPLE SKIP
# ---------------------------------------------------------------------------------------------
#
# A backup nobody has restored is not a backup, it is a file. Run ./scripts/restore.sh against a
# throwaway target at least once, and again whenever the schema changes materially. The first
# time you find out a dump is unusable must not be the time you need it.
printf '  Reminder: a backup that has never been restored is a file, not a backup.\n'
printf '  Prove it: ./scripts/restore.sh %s --into commerceflow_restore_test\n\n' "${target}"
