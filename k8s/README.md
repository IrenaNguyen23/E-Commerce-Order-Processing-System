# CommerceFlow on Kubernetes

Plain manifests, applied with Kustomize. No Helm, no operator — the platform is small enough
that a directory of readable YAML beats a template language.

## Layout

| File | Contains |
|---|---|
| `00-namespace.yaml` | Namespace (Pod Security `restricted`), ServiceAccount, ResourceQuota, LimitRange |
| `01-config.yaml` | Shared `ConfigMap` and the `Secret` **template** |
| `02-postgres.yaml` | PostgreSQL 16 StatefulSet + the five-database init script |
| `03-redis.yaml` | Redis 7 StatefulSet |
| `04-kafka.yaml` | Kafka in KRaft mode + the topic bootstrap `Job` |
| `10`–`15` | The six workloads: Service, Deployment, HPA, PodDisruptionBudget |
| `20-ingress.yaml` | The single external route, straight to the gateway |
| `21-networkpolicy.yaml` | Default deny plus exactly what the architecture requires |

## Deploy

```bash
# 1. Real secrets first — never apply the committed template as-is.
kubectl create namespace commerceflow

kubectl -n commerceflow create secret generic commerceflow-secrets \
  --from-literal=JWT_SECRET="$(openssl rand -base64 48)" \
  --from-literal=POSTGRES_USER=commerceflow \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 24)" \
  --from-literal=REDIS_PASSWORD="" \
  --dry-run=client -o yaml | kubectl apply -f -

# 2. Everything else.
kubectl apply -k .

# 3. Watch it come up.
kubectl -n commerceflow rollout status deployment/api-gateway --timeout=5m
kubectl -n commerceflow get pods -w
```

## Notes that matter

**The secret is a template.** `01-config.yaml` ships placeholder values so the file documents
the required keys. Applying it unchanged gives you a `JWT_SECRET` of
`REPLACE-ME-with-at-least-32-bytes-of-random-secret` — create the real secret first, as above,
and Kustomize will not overwrite it because the create is applied separately.

**`JWT_SECRET` is shared on purpose.** Auth Service signs with HS256 and every other service
verifies with the same key (ADR-001), which is what removes a network call from every request.
The consequence is that rotating it invalidates all live sessions. Do it during a maintenance
window, or move to an asymmetric key pair if you need zero-downtime rotation.

**Datastores are for development and staging.** The Postgres, Redis and Kafka manifests are
single replicas with local volumes. In production, point `POSTGRES_HOST`, `REDIS_HOST` and
`KAFKA_BOOTSTRAP_SERVERS` at managed instances and delete `02`, `03` and `04` — no application
code or configuration changes, because the services only ever knew those DNS names.

**Migrations run in the application.** Flyway executes on start-up (ADR-007). With
`maxUnavailable: 0` and a multi-replica rollout, that means two versions of a service can be
live at once, so every migration must be backwards compatible with the previous release —
expand first, contract in a later deploy.

**Scale-down is deliberately slow.** Each removed replica forces a Kafka consumer group
rebalance, which briefly pauses saga processing. The HPA scales up in 30 seconds and down over
5 minutes for exactly that reason.

**NetworkPolicy needs a CNI that enforces it.** On a cluster without one (some managed defaults,
Docker Desktop), the objects apply cleanly and do nothing. Check before relying on them.

## Operating

```bash
# Follow a saga across services by its correlation id.
kubectl -n commerceflow logs -l app.kubernetes.io/part-of=commerceflow --tail=-1 \
  | grep '"correlationId":"<id>"'

# Anything parked on a dead-letter topic?
kubectl -n commerceflow exec -it kafka-0 -- \
  kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic order.created.DLT --from-beginning --max-messages 10

# Orders stuck mid-saga (the gauge the alert rule watches).
kubectl -n commerceflow exec deploy/order-service -- \
  curl -s localhost:8080/actuator/metrics/commerceflow.orders.stalled

# Outbox rows that exhausted their retries.
kubectl -n commerceflow exec -it postgres-0 -- \
  psql -U commerceflow -d commerceflow_order \
  -c "SELECT id, event_type, attempts, last_error FROM outbox_event WHERE status = 'FAILED';"
```
