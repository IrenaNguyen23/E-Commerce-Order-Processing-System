#!/bin/bash
# =====================================================================================
# Creates the saga topics and their dead-letter counterparts.
#
# The brokers run with auto-creation enabled for convenience, but auto-created topics get the
# broker defaults, which is exactly what we do not want: every saga topic is partitioned by
# order id so that one order is always processed in order, and that only holds if the partition
# count is deliberate.
#
# Idempotent: safe to run on every start.
# =====================================================================================
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
REPLICATION="${TOPIC_REPLICATION_FACTOR:-1}"
RETENTION_MS="${TOPIC_RETENTION_MS:-604800000}"   # 7 days

TOPICS=(
  "order.created"
  "order.completed"
  "order.cancelled"
  "inventory.reserved"
  "inventory.failed"
  "inventory.released"
  "payment.completed"
  "payment.failed"
  "notification.send"
  "notification.sent"
)

echo "Waiting for Kafka at ${BOOTSTRAP}"
until kafka-topics --bootstrap-server "${BOOTSTRAP}" --list >/dev/null 2>&1; do
  sleep 2
done
echo "Kafka is up."

for topic in "${TOPICS[@]}"; do
  for name in "${topic}" "${topic}.DLT"; do
    echo "Ensuring topic ${name}"
    kafka-topics --bootstrap-server "${BOOTSTRAP}" \
      --create --if-not-exists \
      --topic "${name}" \
      --partitions "${PARTITIONS}" \
      --replication-factor "${REPLICATION}" \
      --config retention.ms="${RETENTION_MS}" \
      --config min.insync.replicas=1 \
      --config cleanup.policy=delete
  done
done

echo "Topics ready:"
kafka-topics --bootstrap-server "${BOOTSTRAP}" --list | sort
