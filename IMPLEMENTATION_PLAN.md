# CommerceFlow — Implementation Plan

> Living document. Updated after every completed phase.
> Owner: Solution Architecture + Implementation Team
> Source of truth for scope: `.github/CLAUDE_SYSTEM_PROMPT.md` and everything under `.github/docs/`.

---

## 1. Scope Confirmation (derived from `.github/docs`)

| Area | Requirement | Source |
|---|---|---|
| Language / Runtime | Java 21 | `technical-rules.md` |
| Framework | Spring Boot 3.x | `technical-rules.md` |
| Build | Maven (multi-module) | `technical-rules.md` |
| Datastore | PostgreSQL 16, **database per service** | ADR-005 |
| Cache | Redis 7 | `technical-rules.md` |
| Messaging | Apache Kafka | ADR-004 |
| Saga | **Orchestration** over Kafka | ADR-004, detailed by ADR-011 |
| Reliability | **Outbox Pattern** | ADR-006 |
| Migrations | Flyway | ADR-007 |
| Security | Spring Security + JWT access/refresh | ADR-001 |
| Gateway | Spring Cloud Gateway, no Eureka (static DNS) | ADR-002, ADR-003 |
| Observability | Actuator + Prometheus + Grafana | ADR-008 |
| Logging | Structured JSON | ADR-009 |
| Tracing | Micrometer Tracing + OTel-ready | ADR-010 |
| Read side | CQRS for Order Query | system prompt |
| Docs | OpenAPI / Swagger | `technical-rules.md` |
| Testing | JUnit 5, Mockito, Testcontainers | `technical-rules.md` |
| Packaging | Multi-stage Dockerfile, `eclipse-temurin:21-jdk`, healthcheck, `commerceflow-{service}` | `docker/standards.md` |
| Delivery | GitHub Actions → build, test, Sonar, Docker Hub, Kubernetes | `ci-cd.md` |

### Resolved ambiguities (documented decisions, no blocking questions raised)

1. **Audit Service** appears only in the ASCII sketch of `high-level-architecture.md`. Three authoritative
   documents (`CLAUDE_SYSTEM_PROMPT.md`, `README.md`, `project-structure.md`) enumerate exactly five
   business services plus the gateway. **Decision:** build the five enumerated services + gateway. The
   audit concern is covered by the immutable `processed_event` / `outbox_event` ledgers and structured
   JSON audit logging in every service.
2. **Shared library.** `coding-standard.md` fixes the *internal* package layout of a service but does not
   forbid a shared module. A `commerceflow-common` Spring Boot auto-configuration library carries the
   event contracts, JWT plumbing, outbox and idempotency infrastructure. All optional dependencies are
   `<optional>true</optional>` and every auto-configuration is `@ConditionalOn…`-guarded, so the reactive
   gateway never pulls JPA or Kafka.
3. **Kafka payload typing.** Events are serialized as JSON with **no `__TypeId__` headers**; a shared
   `spring.json.type.mapping` alias table (`KafkaTypeMappings`) is applied to both producer and consumer
   factories. This keeps the wire contract in `contracts/events.md` stable and free of Java package names.
4. **Payment trigger.** `event-flow.md` places `PAYMENT_COMPLETED` after `INVENTORY_RESERVED`, so
   Payment is the second step and money only moves once stock is held. Originally implemented by
   subscribing to `inventory.reserved`; since ADR-011 the orchestrator sends an explicit
   `PROCESS_PAYMENT` command instead, which keeps the ordering and moves the decision.
   `POST /api/payments/process` from `contracts/apis.md` is retained as the manual/retry entry point.
5. **Environment constraint.** The build host has JDK 21 but **no Maven binary and no network egress**, so
   `mvn` cannot be executed here. The repository ships the Maven Wrapper and every module is written to be
   compile-correct by construction; GitHub Actions performs the authoritative build.

---

## 2. Target Repository Layout

```
commerceflow/
├── pom.xml                                # aggregator + dependencyManagement
├── mvnw / mvnw.cmd / .mvn/                # Maven Wrapper
├── common/commerceflow-common/            # shared starter (events, JWT, outbox, idempotency)
├── gateway/spring-cloud-gateway/          # API Gateway            :8080
├── services/
│   ├── auth-service/                      # :8081  db commerceflow_auth
│   ├── order-service/                     # :8082  db commerceflow_order   (CQRS)
│   ├── inventory-service/                 # :8083  db commerceflow_inventory
│   ├── payment-service/                   # :8084  db commerceflow_payment
│   └── notification-service/              # :8085  db commerceflow_notification
├── infrastructure/
│   ├── postgres/                          # multi-database init
│   ├── kafka/                             # KRaft topic bootstrap
│   ├── redis/                             # redis.conf
│   ├── prometheus/                        # scrape config
│   └── grafana/                           # datasource + dashboard provisioning
├── k8s/                                   # namespace, config, secrets, infra, 6 workloads, ingress, HPA
├── docs/                                  # OpenAPI specs, runbook, saga docs
├── .github/workflows/                     # ci.yml, cd.yml, pr-validation.yml
├── docker-compose.yml
└── IMPLEMENTATION_PLAN.md
```

---

## 3. Message & Saga Contract

Per ADR-004 (Kafka orchestration), detailed by
[ADR-011](docs/architecture/adr-011-saga-orchestration.md). The saga was first built to a
choreography reading of ADR-004 and moved to orchestration in Phase 14; ADR-011 keeps that history
because it is the argument for the design.

**Command topics** (orchestrator → one named participant, additive):
`inventory.commands` (`RESERVE_INVENTORY`, `RELEASE_INVENTORY`, `CONFIRM_INVENTORY`),
`payment.commands` (`PROCESS_PAYMENT`), `notification.send` (`NOTIFICATION_SEND` — already a
command topic, and still used by Auth Service outside the saga).

**Reply topics** (participant → orchestrator; names unchanged from `event-flow.md`):
`inventory.reserved`, `inventory.failed`, `inventory.released`, `inventory.confirmed` (new),
`payment.completed`, `payment.failed`, `notification.sent`.

**Domain events** (published, consumed by no saga participant): `order.created`,
`order.completed`, `order.cancelled`. Retained because they are the documented public contract and
the stream external consumers read.

Plus one dead-letter topic per consumer group (`<topic>.DLT`).

**Happy path**

```
Order(orchestrator) ①RESERVE_INVENTORY ▶ Inventory  ─inventory.reserved▶  order → INVENTORY_RESERVED
                    ②PROCESS_PAYMENT   ▶ Payment    ─payment.completed▶   order → PAID
                    ③CONFIRM_INVENTORY ▶ Inventory  ─inventory.confirmed▶ order → COMPLETED
                    ④NOTIFICATION_SEND ▶ Notification ─notification.sent▶ saga  → COMPLETED
```

**Compensation**

```
①─inventory.failed▶   order → CANCELLED, ④notify                    (nothing to undo)
②─payment.failed▶     order → CANCELLED, ③RELEASE_INVENTORY, ④notify
```

Every producer writes through the **transactional outbox**; every consumer is guarded by the
**`processed_event` idempotency ledger** keyed on `(consumer_group, event_id)`. On top of those,
orchestration adds two rules, both load-bearing:

- **Step guard** — the orchestrator acts on a reply only when it names the step the saga is
  waiting for. This is what stops a late `payment.failed` cancelling an order that completed.
- **Reply rule** — a participant answers every command, including one for work already done.
  This is what makes the timeout retry safe. Silence is not idempotence.

Saga state is persisted in Order Service: `saga_instance` (live position, outstanding command,
deadline) and `saga_step_log` (one row per command, closed by the reply that answered it).

---

## 4. Phases

| # | Phase | Deliverable | Status |
|---|---|---|---|
| 0 | Documentation review | All `.github` docs read, scope table above | ✅ Done |
| 1 | Foundation | Aggregator `pom.xml`, `.gitignore`, `.editorconfig`, `.dockerignore`, `lombok.config` | ✅ Done |
| 2 | `commerceflow-common` | Event contracts, topics, JWT, outbox, idempotency, error handling, Kafka/Jackson auto-config, logging | ✅ Done |
| 3 | Auth Service | Users, BCrypt, JWT access+refresh, Redis blacklist, Flyway, OpenAPI, tests | ✅ Done |
| 4 | Inventory Service | Products, stock, reservation saga participant, Redis cache, tests | ✅ Done |
| 5 | Order Service | Command side + outbox, **CQRS read model**, saga consumers, tests | ✅ Done |
| 6 | Payment Service | Payment engine, saga participant, tests | ✅ Done |
| 7 | Notification Service | Multi-channel dispatch, saga terminal, tests | ✅ Done |
| 8 | API Gateway | Routes, reactive JWT filter, Redis rate limiter, circuit breakers, Swagger aggregation | ✅ Done |
| 9 | Containerisation | 6 multi-stage Dockerfiles + `docker-compose.yml` + infra configs | ✅ Done |
| 10 | Kubernetes | Namespace, ConfigMap, Secret, infra, 6 Deployments/Services, Ingress, HPA, NetworkPolicy | ✅ Done |
| 11 | CI/CD | `ci.yml`, `cd.yml`, `pr-validation.yml`, Sonar, Docker Hub, kubectl rollout | ✅ Done |
| 12 | Integration tests | Testcontainers suites incl. end-to-end saga (happy + compensation) | ✅ Done |
| 13 | Docs & hardening | OpenAPI contract, README, runbook, final consistency sweep | ✅ Done |
| 14 | **Saga → orchestration** | Command/reply contract, `saga_instance` + `saga_step_log`, `OrderSagaOrchestrator`, per-step timeout and retry, participants rewritten as commanded, ADR-011, docs and alerts | ✅ Done |
| 15 | **Stock is never held forever** | Per-step exhaustion policy, self-abandoning reserve, never-give-up release, stale-hold sweep and the operator lever | ✅ Done |

---

## 5. Progress Log

> Entries 0–13 are a chronological record and describe the repository as it was at each phase.
> Where Phase 14 changed something — the saga's shape above all — the later entry is the current
> one. Nothing earlier has been rewritten to pretend otherwise.

- **Phase 0 — Documentation review — ✅ Done.** All 13 documents under `.github/` reviewed; scope,
  ADRs, contracts, topics and conventions extracted into sections 1–3 above.
- **Phase 1 — Foundation — ✅ Done.** Aggregator `pom.xml` on `spring-boot-starter-parent:3.4.1`
  importing `spring-cloud-dependencies:2024.0.0`; Java 21 release, Lombok + MapStruct annotation
  processing, Surefire (unit, excludes the `integration` tag), Failsafe (`-Pintegration`) and JaCoCo
  wired once for all modules. `.gitignore`, `.editorconfig`, `.dockerignore`, `lombok.config` added.
  *Decision:* no Maven Wrapper is committed — the build host has no network egress to fetch a
  verifiable wrapper distribution, so Maven 3.9+ is a documented prerequisite and CI uses the
  runner's Maven.
- **Phase 2 — `commerceflow-common` — ✅ Done.** Shipped as a Spring Boot auto-configuration library:
  - `constant/` — `KafkaTopics` and `EventTypes`, verbatim from `event-flow.md`.
  - `event/` — `DomainEvent` envelope plus the ten saga events; field names follow
    `contracts/events.md` and every event carries the state its consumer needs, so no participant
    ever has to call back into another service.
  - `kafka/` — `KafkaTypeMappings` (single source of truth alias ⇄ class ⇄ topic),
    `CorrelationRecordInterceptor`, `SagaConsumerGroups`.
  - `outbox/` — `OutboxEvent`, `OutboxRepository` (`FOR UPDATE SKIP LOCKED` batch claim),
    `OutboxService` (`Propagation.MANDATORY`), `OutboxRelay`, `OutboxScheduler`, retention sweep.
  - `idempotency/` — `processed_event` ledger scoped by consumer group, turning the relay's
    at-least-once delivery into effectively-once processing.
  - `security/` — `JwtProperties`, `JwtTokenProvider` (JJWT 0.12 API, HS256, issuer + type claim
    checks), `JwtAuthenticationFilter`, `TokenRevocationChecker` SPI.
  - `web/` — `GlobalExceptionHandler` + security/data-access advices, `CorrelationIdFilter`,
    REST 401/403 handlers.
  - `autoconfigure/` — six `@ConditionalOn…`-guarded auto-configurations registered through
    `AutoConfiguration.imports`; all heavyweight dependencies are `<optional>true</optional>` so the
    reactive gateway activates only the JWT half.
  - `resources/` — `commerceflow-kafka.yml` (imported by every service via `spring.config.import`)
    and `logback-commerceflow.xml` (JSON by default, plain console under the `local` profile).
  - Unit tests: `JwtTokenProviderTest`, `KafkaTypeMappingsTest`, `OutboxServiceTest`,
    `OutboxRelayTest`, `IdempotencyServiceTest`, `PageResponseTest`.
- **Phase 3 — Auth Service — ✅ Done.** Port 8081, database `commerceflow_auth`.
  - Entities `User` (+ `user_roles` element collection) and `RefreshToken`; Flyway
    `V1__create_auth_schema.sql` also creates this service's `outbox_event` table.
  - `AuthService` — registration (unique-email guard + welcome notification appended to the
    outbox in the same transaction), login, **refresh-token rotation with reuse detection**
    (presenting an already rotated token revokes every session), logout.
  - Refresh tokens are stored as a SHA-256 digest keyed by their `jti`, so a database leak
    cannot be replayed. Login failures are indistinguishable to prevent account enumeration.
  - `TokenBlacklistService` (Redis, TTL = remaining token life) + `RedisTokenRevocationChecker`
    overriding the no-op checker from `commerceflow-common`.
  - `SecurityConfig` (stateless, CORS, method security, REST 401/403), `OpenApiConfig`,
    `JpaConfig` (entity/repository scan widened to the shared outbox packages).
  - `AdminAccountInitializer` hashes the bootstrap admin password at runtime instead of seeding a
    credential literal into a migration; disabled unless explicitly enabled.
  - `application.yml` imports `classpath:commerceflow-kafka.yml`, enables virtual threads,
    graceful shutdown, Prometheus, OTLP tracing and k8s health probes.
  - Multi-stage `Dockerfile` (`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jdk`,
    non-root user, `EXPOSE 8080`, `HEALTHCHECK` on `/actuator/health/liveness`).
  - Tests: `AuthServiceTest` (14 cases across register/login/refresh/logout) and
    `AuthControllerTest` (`@WebMvcTest`: routing, validation envelope, error envelope).
- **Phase 4 — Inventory Service — ✅ Done.** Port 8083, database `commerceflow_inventory`.
  - `products` (catalogue) and `inventory_items` (stock ledger) are separate rows: stock changes on
    every order, the catalogue entry almost never does, and splitting them stops one locking the other.
  - Two counters — `available_quantity` and `reserved_quantity` — which is what makes the
    `inventory.released` compensation exact rather than approximate.
  - `InventoryReservationService` is the saga participant. Each handler is one transaction that
    claims the event in the idempotency ledger, moves stock, and appends the outgoing event to the
    outbox, so stock can never move without the corresponding event (or vice versa):
    - `order.created` → reserve → `inventory.reserved` **or** `inventory.failed` (exactly one).
    - `payment.failed` → release → `inventory.released`.
    - `order.cancelled` → defensive release (no-op if already released).
    - `order.completed` → confirm: the hold becomes a permanent deduction.
  - Concurrency: `PESSIMISTIC_WRITE` claim ordered by product id — two customers racing for the
    last unit is normal traffic, and the fixed lock order rules out deadlocks. Repeated SKUs in one
    order are summed before the check.
  - REST: `GET /api/products`, `GET /api/products/{id}`, `GET /api/products/sku/{sku}`,
    `POST /api/products` and `PUT /api/products/{id}/stock` (both `ADMIN`).
  - Redis cache bound to the concrete `ProductResponse` type — records are implicitly final, so
    Jackson default typing would never write the type header and the entry would return as a map.
  - Flyway `V1` (schema + outbox + idempotency ledger) and `V2` (seven-product demo catalogue,
    including a deliberately scarce SKU that exercises the out-of-stock branch).
  - Tests: `InventoryReservationServiceTest` (13 saga cases) and `ProductServiceTest` (6 cases).
  - *Phase 14: the four fact subscriptions became one command listener on `inventory.commands`, and every handler now replies — including for work already done.*
- **Phase 5 — Order Service — ✅ Done.** Port 8082, database `commerceflow_order`.
  - **CQRS.** Write model `orders`/`order_items`; read model `order_read_model` — one flat row with
    the line items denormalised into JSON. `OrderProjectionService` writes the projection with
    `Propagation.MANDATORY`, i.e. in the *same* transaction as the state change, so the read side is
    strongly consistent while still being a single-row, index-only read. `rebuild(orderId)` can
    regenerate any projection from the aggregate.
  - **Saga initiator.** `placeOrder` writes aggregate + projection + `order.created` outbox row in
    one transaction: there is no state where an order exists but nobody downstream will hear of it.
    *Phase 14 added the `saga_instance` row and the first command to that same transaction.*
  - **Saga terminator.** `OrderSagaService` consumes `inventory.reserved`, `inventory.failed`,
    `payment.completed`, `payment.failed` and decides the terminal state, publishing
    `order.completed` or `order.cancelled`.
    *Superseded by Phase 14: `OrderSagaService` is gone. `OrderSagaOrchestrator` now decides every
    step, not just the last one, and `SagaReplyListener` consumes all seven reply topics.*
  - `OrderStatus.canTransitionTo` makes the state machine explicit, so a duplicate or out-of-order
    event cannot move a completed order backwards; a same-state transition is a silent no-op.
  - Pricing is the one synchronous hop (`ProductCatalogClient` → `POST /api/products/lookup`,
    batched, 2s/3s timeouts). It is a read, so a catalogue outage can only reject new orders — it
    can never leave a saga half finished. Names and prices are snapshotted onto the order line.
  - Client-supplied `idempotencyKey` (partial unique index per customer) makes a retried create
    request return the original order instead of placing a second one.
  - `StalledSagaMonitor` — with no orchestrator holding a timeout, the initiator scans for orders
    stuck mid-saga and exposes `commerceflow.orders.stalled` to Prometheus. It reports rather than
    auto-compensates: silently cancelling paid orders would be worse.
    *Superseded by Phase 14: `SagaTimeoutMonitor` holds a per-step deadline, re-sends the
    outstanding command, and parks the saga as `STALLED` when the budget runs out. The refusal to
    auto-compensate survived the rewrite — it was the right call for the same reason.*
  - Order numbers come from a database sequence: `CF-20260826-000123`.
  - Tests: `OrderCommandServiceTest` (9 cases) and `OrderSagaServiceTest` (8 cases covering the
    happy path, both compensation branches, redelivery and double compensation).
    *Phase 14 replaced the latter with `OrderSagaOrchestratorTest` (13 cases), which adds the step
    guard and the terminal-saga guard.*
- **Phase 6 — Payment Service — ✅ Done.** Port 8084, database `commerceflow_payment`.
  - Driven by `inventory.reserved`, not by `order.created`: money only moves once the goods are
    actually held, which is what makes the `payment.failed` → `inventory.released` compensation
    meaningful. The amount and customer identity travel on the event, so Payment never calls back.
    *Phase 14 kept the ordering and moved the decision: Payment now waits for an explicit
    `PROCESS_PAYMENT` command instead of inferring from what it overheard.*
  - A **unique index on `order_id`** is the strongest guarantee in the service: a redelivery, a
    manual retry through `POST /api/payments/process`, or two replicas racing can never charge a
    customer twice for one order.
  - A decline is a business outcome, not an error — it is recorded, published as `payment.failed`
    and the transaction commits. An acquirer *transport* failure is surfaced as `GATEWAY_ERROR`
    rather than swallowed, so the saga always terminates and an operator can reconcile.
  - `PaymentGateway` is the acquirer boundary; `SimulatedPaymentGateway` decides deterministically
    on an amount threshold (so the compensation branch is reproducible without injecting failures)
    plus an optional random failure rate for soak testing, defaulted to zero.
  - Tests: `PaymentServiceTest` (6 cases) and `SimulatedPaymentGatewayTest` (4 cases).
- **Phase 7 — Notification Service — ✅ Done.** Port 8085, database `commerceflow_notification`.
  - Terminal participant: consumes `order.completed`, `order.cancelled` and the generic
    `notification.send` command (used by Auth Service for the welcome mail), then publishes
    `notification.sent` so the flow is auditable end to end.
  - `NotificationSender` is the channel boundary, selected per channel at runtime;
    `LoggingNotificationSender` is the fallback for every channel so the platform runs with no
    third-party credentials and no outbound network access.
  - **A channel refusing a message still terminates the saga**: the notification is recorded as
    `FAILED`, `notification.sent` carries `status=FAILED`, and a paid order never looks unfinished
    because an email bounced.
  - `NotificationTemplateRenderer` keeps templates in code (reviewed and versioned with the
    product) and substitutes unknown placeholders with an empty string, so a customer can never
    receive literal braces.
  - Tests: `NotificationServiceTest` (6 cases including delivery failure and missing recipient).
- **Phase 8 — API Gateway — ✅ Done.** Port 8080, reactive (Spring Cloud Gateway 4.2).
  - Routes for all five services over static DNS (ADR-003), each wrapped in a Resilience4j circuit
    breaker with a `/fallback/{service}` landing that returns a 503 in the platform error envelope
    instead of a raw gateway timeout.
  - `JwtAuthenticationWebFilter` does two things that must never diverge: it authenticates the
    exchange, and it **strips the inbound `X-User-*` headers unconditionally** before replacing
    them with values derived from the verified token. Without that strip, a client could send
    `X-User-Roles: ADMIN`. The services also verify the JWT themselves, so this is defence in depth.
  - An invalid token is not rejected in the filter — the exchange continues unauthenticated and the
    authorisation rules decide, keeping every 401/403 on one consistent path.
  - Redis `RequestRateLimiter` per route: **per-IP** on the auth routes (pre-login by definition,
    and the ones worth brute forcing) and **per-user** everywhere else, so one office behind a NAT
    is not throttled as a single abusive client.
  - `CorrelationIdGlobalFilter` stamps `X-Correlation-Id` at the edge — the id that stitches one
    customer action across six services and the asynchronous saga.
  - Swagger UI aggregates all five service specs through gateway-proxied `/v3/api-docs/*` routes.
  - Tests: `JwtAuthenticationWebFilterTest` (4 cases, including header spoofing and a forged token).
- **Phase 9 — Containerisation — ✅ Done.**
  - Six multi-stage Dockerfiles (`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jdk`), POMs
    copied before sources so the dependency layer survives a code change, non-root user,
    `EXPOSE 8080`, `HEALTHCHECK` — all per `docker/standards.md`, container names
    `commerceflow-{service}`.
  - Every service listens on 8080 *inside* its container (the standard) and is published on the
    port the README documents, which resolves the apparent conflict between the two documents.
  - `docker-compose.yml`: PostgreSQL 16 (five databases created by an init script), Redis 7,
    **Kafka in KRaft mode — no ZooKeeper**, a topic-bootstrap job, Kafka UI, the six services,
    Prometheus and Grafana. Start-up is health-gated with `depends_on: condition: service_healthy`.
  - Topics are created explicitly rather than auto-created: every saga topic is partitioned by
    order id so one order is processed in order, and that only holds if the partition count is
    deliberate. Each topic gets a matching `.DLT`.
  - Prometheus alert rules reflect how *this* system fails — stalled sagas, consumer lag, a
    connection pool with threads queueing — not just CPU. Grafana dashboard provisioned to match.
- **Phase 10 — Kubernetes — ✅ Done.**
  - Namespace with Pod Security `restricted`, ResourceQuota and LimitRange; every workload runs
    non-root, read-only root filesystem (with an `emptyDir` at `/tmp` for the JVM), all
    capabilities dropped.
  - Six Deployments generated from one template so the security context, probes and rollout policy
    cannot drift: `maxUnavailable: 0`, a **startup probe** (the JVM plus Flyway are slow cold but
    should be caught quickly once warm), plus readiness and liveness on the Actuator probe groups.
  - HPA scales **up in 30s and down over 5 minutes** — every scale-down forces a Kafka consumer
    group rebalance, so flapping costs more than the capacity it saves. PDB keeps one pod during
    voluntary disruptions.
  - `NetworkPolicy`: default deny, then exactly what the architecture requires — which is what
    turns "the gateway is the only entry point" into something the cluster enforces.
  - Single Ingress to the gateway; all services are ClusterIP only.
  - `k8s/README.md` documents the secret handling, the shared-`JWT_SECRET` trade-off, the
    backwards-compatible-migration requirement implied by rolling updates, and operator runbooks.
- **Phase 11 — CI/CD — ✅ Done.**
  - `ci.yml`: build + unit tests gate everything; integration tests (Testcontainers), Sonar and a
    six-way image build matrix then run in parallel. Images push only from the canonical repo.
  - `cd.yml`: **deploys the tag CI already built — it never builds**, so what reaches production is
    byte-for-byte what was tested. Staging auto-deploys from `main`; production needs a tag plus a
    GitHub Environment approval. Real secrets are applied separately from the manifests so the
    committed template can never overwrite them. Rolls back and smoke-tests on failure.
  - `pr-validation.yml`: cheap checks that are embarrassing to find in review — compose and
    Kustomize both render, dependency review, Dockerfile lint, and a guard that fails the build if
    the placeholder Secret ever stops being a placeholder.
- **Phase 12 — Integration tests — ✅ Done.**
  - `commerceflow-common` publishes a **test-jar** so the Testcontainers harness exists once, not
    five times: `CommerceFlowContainers` (singleton PostgreSQL 16, Kafka, Redis — the same major
    versions as production) and `AbstractSagaIntegrationTest` (`@DynamicPropertySource` wiring,
    a wire-format-accurate publisher, an end-positioned consumer).
  - Nothing is mocked below the service boundary. These tests exist to prove that Flyway,
    Hibernate, the outbox relay and the Kafka serializers actually agree with each other — which
    no amount of unit testing can establish.
  - Five suites: `AuthFlowIntegrationTest` (full account lifecycle over HTTP, refresh-token reuse
    detection, Redis deny-list), `InventorySagaIntegrationTest`, `PaymentSagaIntegrationTest`,
    `NotificationSagaIntegrationTest`, `OrderSagaIntegrationTest` — together covering the happy
    path, **both** compensation branches, redelivery, and the never-charge-twice guarantee.
  - Tagged `integration`: Surefire skips them, Failsafe runs them under
    `mvn verify -Pintegration`. A developer without Docker still gets a green `mvn test`.
- **Phase 13 — Docs and hardening — ✅ Done.**
  - `docs/api/commerceflow-openapi.yaml` — the reviewed platform contract (18 paths, 20
    operations, 13 schemas), alongside the springdoc-generated per-service specs and
    `scripts/export-openapi.sh` for diffing the two.
  - `docs/architecture/saga.md` — the saga design, state machine, correctness argument and a
    failure matrix. `docs/runbook.md` — ten on-call scenarios in likelihood order.
    `README.md` — a runnable walkthrough of all three saga outcomes against the seeded catalogue.
    `scripts/smoke-test.sh` — the same walkthrough, executable and asserted.
  - **Two defects found and fixed during the sweep:**
    1. *Payment*: `processManually` threw on a decline, which rolled back the payment row **and
       its `payment.failed` outbox row** — the customer got a 402 and the saga waited forever.
       It now returns the outcome and the controller maps `FAILED` to `402` with the payment as
       the body, so the record and the event both commit.
    2. *Idempotency*: the insert race was caught and swallowed, leaving the transaction marked
       rollback-only and failing later with an opaque `UnexpectedRollbackException`. The
       exception now propagates so the transaction rolls back cleanly and Kafka redelivers.
  - **One wiring risk removed:** every write to Kafka now goes through `EventPublisher`, which is
    injected as `KafkaTemplate<?, ?>` — exactly the type Spring Boot declares — instead of relying
    on the leniency of generic autowiring at five separate call sites.
  - **Automated verification** (`mvn` is unavailable offline, so these substitute for a build):
    all 200 Java sources have matching packages/file names, balanced delimiters and imports that
    resolve; every `ErrorCode`, `KafkaTopics`, `EventTypes` and enum reference resolves; all 35
    YAML documents parse; all 8 POMs parse and every declared module exists; every Compose build
    context, mount and merged environment key resolves; all 46 Kubernetes objects render through
    Kustomize.

---

### Phase 14 — Saga pattern changed from choreography to orchestration ✅

Requested directly, and in conflict with ADR-004 in the supplied brief. Flagged, then implemented;
the conflict is resolved by [ADR-011](docs/architecture/adr-011-saga-orchestration.md), which
supersedes ADR-004 and states what orchestration costs as well as what it buys. `.github/` is the
specification and was left untouched.

**What changed**

| Area | Before | After |
|---|---|---|
| Flow definition | Implied by four services' `@KafkaListener` subscriptions | `SagaStep` enum + `OrderSagaOrchestrator`, one screen |
| Message direction | Facts, fanned out | Commands to one recipient, answered by replies |
| Saga state | None | `saga_instance` + `saga_step_log` (Flyway `V2`) |
| Timeouts | `StalledSagaMonitor` counted orders that had not moved | `SagaTimeoutMonitor` re-sends the outstanding command per step, then parks the saga |
| Compensation | Each participant's own subscription to `payment.failed` / `order.cancelled` | Commanded explicitly, only for steps that actually succeeded |
| Participant listeners | Inventory: 4 topics; Payment: 1; Notification: 3 | One command topic each |

**Envelope additions.** `sagaId` and `causationId` on `DomainEvent`, both nullable and additive.
`sagaId` is how a reply finds its saga — and how the orchestrator ignores Auth Service's welcome
mail, which shares `notification.sent`. `causationId` links the two halves of a step in the audit
trail.

**Where the orchestrator lives.** Inside Order Service, in its own `saga` package — not a separate
deployment. It advances the saga and the order aggregate in one transaction against one database,
so the two can never disagree about whether an order was cancelled. A standalone orchestrator
would buy independent scaling and pay for it with exactly that split-brain. It is isolated by
package, so extracting it later is a move rather than a rewrite.

**Deliberately not automatic.** A saga that exhausts its retry budget is parked as `STALLED`, not
compensated. A payment step that has gone quiet may already have taken the money; cancelling on
that guess is a worse failure than the outage. The runbook has the query to read its step log and
the one-line update to resume it once the participant is healthy.

**Tests.** 106 unit tests green. `OrderSagaOrchestratorTest` (13) pins every transition and both
guards. The integration suite was rewritten around the pattern: the orchestrator test plays each
participant, answering commands and asserting on the next one; each participant test asserts both
halves of its contract — the work done *and* the reply sent. New coverage that choreography had no
way to express: a re-sent command answering without repeating the side effect, a superseded reply
being dropped, a compensation with nothing to undo still succeeding.

**One test invariant was rewritten, not relaxed.** `KafkaTypeMappingsTest` asserted one message
type per topic. Three inventory commands now deliberately share `inventory.commands` — same
participant, same aggregate, so one partition guarantees a release cannot overtake its reserve. The
assertion was replaced with the invariant that still holds (every topic carries at least one type,
and no type escapes `KafkaTopics.ALL`) plus an explicit test documenting the shared topic.

---

### Phase 15 — A stuck saga no longer holds stock forever ✅

Found while auditing for production readiness, and the worst kind of bug: no error, no failed
request, just a shop that can sell less than it thinks it can.

**The cause was one line of judgement, applied to every step.** `SagaRecoveryService` parked any
step that used up its retries. Parking is right for a payment step — the charge may have gone
through, and guessing is worse than waiting. It is wrong twice over elsewhere:

| Step | Parked meant | Now |
|---|---|---|
| `RESERVE_INVENTORY` | Stock held for an order that will never be placed | **Abandons** — cancels the order, releases the stock, tells the customer. Safe because no payment command was ever sent |
| `RELEASE_INVENTORY` | Stock held for an order that was *already cancelled* | **Keeps trying** — the decision to undo is made; giving up is what strands the units |
| `PROCESS_PAYMENT` | Correct | Unchanged. A human decides |
| `CONFIRM_INVENTORY` | Correct — the goods are sold, holding them is right | Unchanged |

The judgement now lives in `SagaStep.onRetriesExhausted()`, and `ExhaustionPolicyTest` pins every
entry plus the invariant behind them: **only steps reached before the payment command may
abandon.** A step added later cannot quietly opt into unwinding after money has moved.

**The residue gets a sweep, not an automatic fix.** `StaleReservationMonitor` reports holds older
than the window (default 2 h) as `commerceflow.inventory.reservations.stale`. It deliberately does
not release them: from inside Inventory, a hold whose saga vanished is indistinguishable from one
whose saga is parked mid-payment, and releasing the second puts sold stock back on the shelf.
`StaleReservationMonitorTest.reportsWithoutReleasingAnything` exists to stop the next person
"improving" it.

**So there is an operator lever.** `POST /api/reservations/{orderId}/release` (ADMIN) returns the
units, closes the reservation and publishes `inventory.released` — the three things that have to
move together, and that a manual `UPDATE` gets two of. Runbook §1b has the decision table for
which case you are looking at before you use it.

**One latent bug fixed on the way.** `@EnableScheduling` was never declared in Inventory Service;
schedulers ran only because the outbox auto-configuration enabled it as a side effect, behind
`commerceflow.outbox.scheduler-enabled`. Turning the outbox scheduler off would have silently
stopped the new sweep too. Now declared explicitly, as it already was in Order Service.

134 unit tests green.

---

### Phase 16 — The four things that were stubs ✅

Tier B of the readiness audit: the parts that ran, looked finished, and would not have survived a
real customer.

| Was | Now |
|---|---|
| `SimulatedPaymentGateway` decided approvals by dice roll | **Stripe.** `PaymentIntent` with `order-{id}` as the idempotency key, so a re-sent command cannot double-charge. Webhook with raw-body signature verification; the service refuses to start without `STRIPE_WEBHOOK_SECRET` |
| Notification "sent" an email by logging it | **SMTP**, `@Order(0)` ahead of the log sender, 5-second timeouts |
| Forgot-password had a form and no backend | `AccountRecoveryService` — reset and verification tokens, no account enumeration, reset revokes every refresh token |
| `REPLACE-ME` secrets | Still open — B5/B6 wait on an infrastructure decision |

**The charge became three outcomes, not two.** `APPROVED / DECLINED / PENDING`: a card in 3-D
Secure is neither paid nor refused, and forcing it into a boolean either fails a payment that is
about to succeed or completes one that has not. `PENDING` parks the saga step and a reconciler
asks the gateway later.

**Jakarta Mail accepts `not-an-address` without complaint.** The row would have read `SENT` while
the mail bounced into nothing. Fixed in the sender rather than by loosening the test.

---

### Phase 17 — Checkout that takes money ✅

Option A: Stripe Elements on the frontend, `redirect: 'if_required'`, client secret carried on the
payment row. The payment form deliberately has **no success state** — the order page is the one
place that says what happened, and a form that congratulates the customer before the saga confirms
is a form that can lie.

Abandoned baskets are detected from `requires_payment_method` rather than a timer.

---

### Phase 18 — Product editing and user administration ✅

C1 and C7. `UserAdminService` refuses two things structurally: an administrator removing their own
admin role, and removing the last **enabled** administrator — counting enabled ones, because a
disabled admin cannot let anyone back in.

---

### Phase 19 — Orders remember what was bought ✅

An order line held a product id and a price. Everything else — name, image, category, what the
list price had been — was fetched from the catalogue at render time, so **editing a product
rewrote history**: last year's invoice would show this year's name and photograph.

Order items now **copy** rather than reference: `productCategory`, `productImageUrl`, `listPrice`,
`discountAmount`, with `unitPrice = listPrice - discountAmount` enforced in the aggregate. The
discount columns are populated by nothing yet; they exist because retrofitting a snapshot after
coupons ship means the earliest coupon orders can never be reconstructed.

The root cause was upstream: `CatalogProduct` only carried the fields the old flow needed.

---

### Phase 20 — Cancelling an order, and getting the money back ✅

C2. Two new saga steps, `REFUND_PAYMENT` and `RESTOCK_INVENTORY`, and a cancellation path that
reopens a finished saga.

**No `OrderStatus.REFUNDED`.** A cancelled order is `CANCELLED` whether or not money moved; where
the money is belongs to the *payment*, which already has `REFUNDED`. The order is cancelled
**immediately**, before the undoing — it will not be fulfilled, and that is knowable now.

**Money before goods.** The refund goes first and the stock follows its reply; a customer chasing
a refund is a worse outcome than a unit that reappears a second late.

**Release and restock are not interchangeable, and choosing wrong is silent:**

| Order reached | Stock is | Undo | Wrong choice does |
|---|---|---|---|
| `PAID` or earlier | Held, not deducted | `RELEASE` | Restock invents stock that was never taken |
| `COMPLETED` | Deducted, counted sold | `RESTOCK` | Release finds no hold; the units are lost |

The choice is made at cancellation time and stored on `saga_instance.stock_undo`, because
cancelling overwrites the order status it was derived from.

**Cancelling into a running saga is refused with `409`.** A mid-flight order has a command
outstanding; a second command against the same step makes the step guard drop the pending reply —
harmless right up until the dropped reply was *payment succeeded* and a refund has gone out for a
charge nothing recorded. A **parked** saga is the exception: it has already stopped, so an
administrator may cancel it, with a warning to check the payment first.

**A failed refund does not stop the saga.** The order is cancelled and the customer is already
owed; halting would strand the stock too. It logs at `error` and leaves the payment `COMPLETED`,
which is what puts it in front of a human.

**A frontend defect this surfaced:** the cancelled-order card said "You have not been charged"
unconditionally — true only before payment, and the worst possible thing to tell a customer owed a
refund. It now reads the payment's status.

194 unit tests green.

---

### Phase 21 — The rest of the commerce layer ✅

Nine items, in dependency order, because most of them are downstream of one another: an address
has to be structured before tax can be worked out, tax has to exist before a discount has anything
to reduce, and a category has to be a row before a tax rate can key on it.

| | What it needed |
|---|---|
| **C9** address book | A structured address, because a country parsed out of free text is a country that will eventually be parsed wrong — quietly, into a wrong total |
| **C4** tax & delivery | Two rate cards as tables, per-line tax, and a delivery quote |
| **C5** coupons | Percentage, fixed amount and free delivery, with a redemption ledger |
| **C10** categories | A table, replacing a free-text column that in practice held four spellings of every category |
| **C12** search | Postgres full-text with weights and ranking, replacing `LIKE '%term%'` |
| **C15** product images | Upload, stored in Postgres, type determined from the bytes |
| **C8** basket & wishlist | Server-side, merged into the browser's on sign-in |
| **C6** reviews | Moderated, with verified-purchase badges built from `order.completed` |
| **C3** fulfilment | Shipments with their own lifecycle, deliberately not the order's |
| **C11** guest checkout | A real account with no password |
| **C14** multi-warehouse | Stock located in buildings, allocated per order |

The decisions worth keeping:

**An order snapshots; a basket does not.** The two are opposites and the difference is the whole
design of both. An order records what was agreed, so it freezes the name, the price, the tax rate
and the picture. A basket is a list of intentions, so it stores product ids and quantities and
nothing else — a price stored there would mean a basket saved on Tuesday quoting Tuesday's price
and the checkout charging Friday's, with the customer watching a number change between two screens
and no explanation anywhere.

**Tax is calculated per line and then added up.** Never the other way round. Taxing an order total
once is one multiplication and is wrong the moment a basket mixes rates — which in most of Europe
is as soon as somebody buys a book and a kettle together. It is also wrong by amounts too small to
notice until an accountant reconciles a quarter. Each line is taxed at its own rate, each result is
rounded, and the rounded figures are summed, so a customer adding up their invoice by hand gets the
total printed on it.

**The category slug is immutable, and that is a money decision.** Tax rates are looked up by
category. If a slug could be edited, renaming "Computers" to "Laptops & desktops" would change the
tax charged on everything inside it — no error, no failed request, just a different figure on the
next order. Renaming the display name is free and does none of that.

**A limited coupon is limited by one conditional `UPDATE` and nothing else.** Every other check —
the validity window, the minimum basket, the per-customer limit — is a read, and any of them can be
true when read and false a moment later. Only the row count of

```sql
UPDATE coupons SET redemption_count = redemption_count + 1
 WHERE id = ? AND (max_redemptions IS NULL OR redemption_count < max_redemptions)
```

is trusted. Read-modify-write on that counter is how a code meant for the first hundred customers
is honoured a hundred and eleven times with nothing anywhere reporting an error.

**Spreading a fixed discount across lines loses a cent unless it is done deliberately.** Ten off a
basket of three equal lines is 3.333… each; rounded independently that is 9.99, and the customer is
short-changed on a coupon that said ten. Rounding up gives 10.02 and the shop is out of pocket. The
shares are floored and the remainder handed out one minor unit at a time to the lines with the
largest fractional part, so the parts sum to the whole by construction.

**Search stopped being a full table scan.** `LIKE '%term%'` cannot use an index, matches substrings
rather than words — "art" found "cartridge" — and has no notion of a word's root, so "headphones"
found nothing filed as "headphone". It is now a weighted `tsvector` with a GIN index: name and SKU
at weight A, description at B, ranked by `ts_rank`. There is deliberately **no typo tolerance**;
"labtop" finds nothing, and fixing that means trigram similarity and a second index.

**An uploaded image's type comes from its bytes.** A multipart upload arrives with a
`Content-Type` the client chose. Believing it means an attacker uploads an HTML document as
`image/png` and the service serves it back, from its own origin, with a type that makes a browser
render it. SVG is refused outright rather than sanitised, because it is a document format that can
contain script and sanitising untrusted markup is a losing game.

**A shipment's status is not an order's status.** `OrderStatus` is the saga's state machine, and
every transition in it is guarded so a late or duplicated reply cannot move an order backwards —
that guard is what makes redelivery safe. Fulfilment runs on a different clock, is driven by people
and carriers, and legitimately goes backwards: a parcel marked delivered that was signed for at the
wrong building. Merging them would mean either loosening the guard or refusing corrections
warehouse staff genuinely need to make. So an order is `COMPLETED` while its parcel is
`IN_TRANSIT` — two facts, both true.

**A guest gets a real account.** The alternative, a nullable `userId` on orders, means the basket,
the address book, the order history, the payment and the saga each grow a second code path for a
customer who might not be a customer. Instead the address is turned into an account with a random
password nobody knows, and everything downstream is unchanged. It also gives the customer
something: setting a password later through the ordinary reset flow finds their orders already
there.

An address that already has an account is **refused**. This endpoint hands out a session in
exchange for an email address; returning one for an existing account would let anybody type your
address and be signed in as you.

**Stock is located, and `inventory_items` became a summary.** A single global figure is a lie the
moment a shop has two buildings: it says twelve are available, six are in Amsterdam and six in
Milan, and an order for eight either ships in two parcels or cannot ship at all. `stock_levels` is
now the truth and the per-product totals are recomputed from it in the same transaction as every
movement. Nothing reserves against the summary.

**Verified-purchase badges are built by listening, not asking.** Inventory needs to know who bought
what. Asking Order Service at review time would be a new dependency in the wrong direction and
would make writing a review fail when Order is down; asking at render time would be a call per page
view. Instead Inventory subscribes to `order.completed` — which required no change to the saga at
all, because a domain event is an announcement and the orchestrator does not know who listens. That
is the clearest demonstration of why those events survived the move from choreography.

#### Four defects this work surfaced

1. **`/api/users/**` had no gateway route.** The account administration built in Phase 18 was
   unreachable through the gateway. Its own route now, with a per-client rate limit rather than the
   login route's per-IP one — those endpoints need a token already, and sharing the login bucket
   would give one office one allowance.
2. **`PUT /api/products/**` was administrator-only at the gateway**, which blocked customers
   submitting a review — reviews live under a product path. The customer rule is now listed before
   the admin rules that were broad enough to swallow it.
3. **The cancellation email said "You have not been charged" unconditionally.** True of an order
   cancelled before payment and the worst possible thing to tell somebody owed a refund — which,
   since Phase 20, is a real case. The line is now a parameter the saga fills in, because the saga
   knows whether money moved and the template does not.
4. **`tsc --noEmit` missed errors that `tsc -b` caught.** The frontend uses project references, and
   the check that matters is `npm run build`. Every frontend result quoted here is from that.

344 unit tests green; frontend `tsc -b`, `eslint` and `vite build` clean.

---

### Phase 22 — The back office catches up ✅

Four screens the services already had endpoints for, plus the one set of endpoints that was
genuinely missing.

| Screen | Behind it |
|---|---|
| **Reviews** | The moderation queue, oldest first |
| **Warehouses** | Buildings, their contents, and a per-building low-stock list |
| **Product images** | Upload, choose the tile, delete — a dialog on the product list |
| **Coupons** | New: `GET/POST /api/coupons`, `PUT`/`DELETE /api/coupons/{id}`, `GET /{id}/redemptions` |

**A queue is worked oldest first.** Newest-first leaves a tail nobody ever reads: the reviews that
have waited longest are exactly the ones a busy moderator never reaches. Oldest first bounds the
wait by throughput rather than by luck.

**Held stock is shown and cannot be edited.** Those units belong to orders whose saga is still
running. Editing them breaks the compensation arithmetic — a later release would put back stock
that had already been counted away — so the column is read-only and its tooltip says why.

**The percentage field takes a percentage.** The API takes a fraction and refuses a bare `10`,
which is right for an API and wrong for a form. An operator types 10 into a field labelled `%` and
the conversion happens at the edge, once, visibly. A form that made somebody type `0.1` would
eventually get a `10` and create a code worth a thousand per cent.

**Three refusals in the coupon API, each preventing something specific:**

| Refused | What it would otherwise be |
|---|---|
| Changing a code | It is on posters and stored as text on every order that used it. Renaming orphans all of that, and nobody notices until a customer rings about a code that worked yesterday |
| Lowering the cap below the redemption count | A check constraint violation arriving as a 500 with a constraint name in it. Caught here, it is a sentence naming the actual number |
| Deleting a used code | The redemption rows are the record of what the campaign cost. Switching it off stops it now and keeps the history |

The value, the window and the caps **are** editable on a live campaign. One doing better or worse
than expected gets adjusted, and refusing that sends operators to the database.

#### Two more defects this surfaced

1. **Users was still under "Not yet available" in the admin nav**, four phases after the screen was
   built. The screen worked; the navigation said it did not exist.
2. **`/api/warehouses/stock/low` shared a prefix with `/stock/{productId}`.** Whether `low` is read
   as a literal or as a product id came down to Spring's pattern precedence — correct today, and
   not something worth relying on. Now `/api/warehouses/low-stock`, which cannot be got wrong.

`ServiceUnavailable` and the nav's `unavailable` flag now have no callers: every admin screen has a
service behind it. Both are kept and annotated rather than deleted — the pattern is the right
answer the next time a gap appears, and this working copy has no version control to recover them
from.

356 unit tests green.

---

### Phase 23 — What was missing to run this for real ✅

Seven gaps between "the features work" and "somebody can operate this". Six were closable in the
repository; the seventh is a platform decision and is stated as one rather than papered over.

| # | Gap | What was done |
|---|---|---|
| 1 | Single-replica datastores, no backup | `scripts/backup.sh` + `scripts/restore.sh`, with a drill mode. Replication still needs the deferred platform decision. |
| 2 | No record of administrative actions | `admin_audit_log` in all five services, written in the same transaction as the change |
| 3 | No way to erase an account | `DELETE /api/users/{id}` — anonymise, announce `user.erased`, subscribers scrub their own copies |
| 4 | No lockout after repeated failed sign-ins | Redis-backed throttle: five failures in thirty minutes, locked for fifteen |
| 5 | No retention policy | [`docs/data-retention.md`](docs/data-retention.md), plus the sweeper that was missing |
| 6 | No load test | [`load-test/checkout-contention.js`](load-test/checkout-contention.js) |
| 7 | Runbook did not cover the new features | Eight new sections, §11–§18 |

**The audit log is local to each service, like the outbox.** A shared audit database would be a
synchronous dependency on the write path of every service — a service that could not write its
audit row could not accept the change, which turns a logging outage into a shop outage.

**It records what changed, not the before and after.** Storing old and new values means storing
personal data in a table with a two-year retention, which is the opposite of what a retention
policy is for. To find out what a value used to be, use the backups.

**Erasure anonymises; it does not delete.** Orders reference the account and orders are financial
records that have to survive — deleting the row would take the money with it and leave an
accountant looking at a hole where a sale used to be. The address becomes a placeholder in the
reserved `.invalid` domain, so an anonymised account can never become a real person's. Country is
kept on the order because it chose the tax rate; the postcode is not, because combined with almost
anything it is close to an identifier.

**A review's words survive its author.** Deleting reviews on erasure would silently change the
shop's ratings and remove opinions the customers reading them still hold. The byline goes; the
text and the rating stay.

**The login throttle fails open.** If Redis is unreachable, sign-in is allowed rather than
blocked. A cache outage must not lock every customer out of the shop, and the throttle raises the
cost of guessing rather than being the only thing between an attacker and an account.

**A failed attempt against an unknown address is counted too.** Otherwise the timing difference
between "no such account" and "wrong password" tells an attacker which addresses are registered.

**The last enabled administrator cannot be erased.** The same rule already guarded role changes,
and it matters more here: an operator who realises what they have done cannot put it back.

**The load test measures contention against a control.** The same order rate is run twice — once
with every order for one product, once spread over ten. The second run is the baseline cost of
checkout on that machine; the difference is what the `PESSIMISTIC_WRITE` lock on `stock_levels`
costs. Without the control there is no way to tell a slow lock from a slow laptop. The run also
asserts that available stock never goes negative, because a load test that only measured
throughput would happily report a fast system that sold eleven of the ten laptops it had.

**`shipment_events` is deliberately never swept.** It grows faster than any other business table
and is the obvious next candidate, but a parcel's event history *is* the parcel: without it,
`status = 'DELIVERED'` is an assertion with nothing behind it. Half a parcel's history is worse
than none, because it reads as a complete account and is not.

**`STALLED` sagas and `FAILED` notifications are never swept either.** They are the record of
what went wrong — the one thing a retention job must not quietly delete.

### Defects found in Phase 23

| # | Found by | Defect |
|---|---|---|
| 1 | Writing the retention policy | `refresh_tokens` grew without bound. `RefreshTokenRepository#deleteAllExpiredBefore` existed and was never called from anywhere. A `RefreshTokenSweeper` now calls it hourly, taking only expired rows — a revoked-but-unexpired token is what makes a sign-out stick. |
| 2 | Restore drill | Nothing distinguished a finished backup from an interrupted one, and a partial dump restores without complaint. `backup.sh` now writes its `MANIFEST` last and `restore.sh` refuses any directory without one. |

397 unit tests green.

---

### Phase 24 — The audit trail becomes readable, and becomes real ✅

Phase 23 wrote the audit log and left two things undone, both recorded as limitations rather than
quietly skipped. This closes them.

| | |
|---|---|
| **Read API** | `GET /api/audit/{service}` and `/actions`, ADMIN only, read only |
| **Back office** | Admin → Audit trail: one screen over all five logs |
| **Append-only in fact** | A trigger that refuses every `UPDATE` and any `DELETE` under 30 days |

**One controller serves all five services.** `AuditController` lives in `commerceflow-common` and
is contributed by `CommonAuditAutoConfiguration`, exactly as the writing side is. No service
contains a line of code for this. The gateway routes `/api/audit/{service}/**` to that service's
`/api/audit/**`, which is the same `Path` + rewrite shape already used for the aggregated
API documentation.

**The log stays local, and the merge is the cost of that.** A central audit database would be a
synchronous dependency on the write path of every service — audit database down, shop stops
accepting changes. So each service answers for its own rows and the browser merges five answers.
The cost is paid on the read side on purpose: a slow audit search is an inconvenience, an audit
write that can fail is a hole in the record.

**Paged by position, not by page number.** Page 3 of one service and page 3 of another do not
cover the same span of time, so offsets cannot be merged into a single ordered list at all — a
position can. The response carries `nextBeforeAt` / `nextBeforeId` and the client sends them back.
`id` is a tiebreak within one instant: the values are random and mean nothing on their own, but
they make the ordering total, which is what stops a row appearing twice or never.

**No total count.** It would be a second query over a two-year table on every request, five times
over for a merged view, to produce a number nobody acts on. `hasMore` is what the screen needs, and
it is truthful because the query fetches one row more than it returns.

**A service that cannot be reached is named, never swallowed.** This is the whole reason the screen
is more than a table: rows missing because a service is down look exactly like a service where
nothing happened, and those are opposite conclusions. `Promise.allSettled` over the five, and a
warning above the list saying which are missing and that an empty result does not mean nothing
happened.

**"Append-only" stopped being a convention.** Until now it was a rule the application followed,
while anyone with a database connection could `UPDATE` or `DELETE` a row — including the person an
entry describes. A trigger now refuses every update, and refuses to delete anything younger than
30 days. The retention sweep is unaffected because it only ever deletes rows two years old.

*The consequence, stated where somebody will hit it:* `commerceflow.audit.retention` must not be
set below 30 days, or the nightly sweep fails against the trigger. It is documented in
`AuditProperties`, in the migration header and in `docs/data-retention.md`. Failing loudly beats a
trigger that quietly permits what it was added to prevent.

**What the trigger does not stop:** a superuser dropping it, or `TRUNCATE`, which does not fire row
triggers. Neither is the threat it was written for.

**Administrators can read the record of their own actions.** Correct — the point of a trail is that
the action was recorded, not that it was hidden from whoever took it. Separating "may administer"
from "may audit" needs an auditor role that does not exist, and that is a limitation below rather
than something half-built here.

### Defects found in Phase 24

| # | Found by | Defect |
|---|---|---|
| 1 | Reasoning about the merge | The client's "is there more" test was `merged.length > pageSize` alone. Five services returning ten entries each is exactly one page with nothing held back, while every one of them may still have hundreds more — the trail would have ended early and silently. Now `anyServiceHasMore ‖ merged.length > pageSize`. |
| 2 | `tsc -b` | The merge matched failed requests back to service names by array index. Correct today, wrong the moment the request list is filtered — and it would have attributed an outage to the wrong service in the warning. Each request now carries its own name through. |
| 3 | `javac` | `commerceflow-common` had no springdoc dependency, so the OpenAPI annotations on the new controller did not compile. Added `optional`, like every other web dependency there, so the reactive gateway does not inherit the servlet flavour. |

407 unit tests green, `npm run build` clean, eslint zero warnings.

---

### Phase 25 — The three things that stopped this being a shop ✅

A go-live review compared the API surface against the UI surface and found four blockers. One was
deferred by the owner (Kubernetes configuration — the system runs locally and there is no budget
for a cluster). The other three are closed here.

| # | Blocker | What was wrong |
|---|---|---|
| 1 | **Fulfilment could not be operated** | Order Service had `POST /orders/{id}/shipments`, `POST /shipments/{id}/events` and `GET /shipments`. The console had none of them: `shipments-api.ts` was read-only and the two write URLs were declared in `endpoints.ts` and never called. Parcels could only be driven by curl — and because the four shipment emails fire on those events, customers were getting nothing after the order confirmation. |
| 2 | **No legal pages** | No terms, privacy notice, returns policy or company details anywhere. For a shop pricing in EUR and charging VAT by delivery country, those are pre-contract information a consumer must be able to read *before* deciding to buy. |
| 3 | **No returns or post-delivery refund** | `OrderStatus` had no way to represent a return, and refunds existed only as saga compensation on a cancelled order. A delivered order could not be refunded at all, which makes the statutory 14-day right of withdrawal impossible to service. |

**Fulfilment: a queue and a panel, from the same component.** `/admin/shipments` is the warehouse
queue — a tab per status, oldest first, and no way to re-sort it, because a queue worked
newest-first leaves a tail that is somebody's order sitting on a shelf. The same `ShipmentCard`
appears under each order, since a parcel only means anything next to what is in it.

**The transition buttons say which ones email the customer.** Dispatch, a failed attempt, delivery
and a return all reach an inbox; picking does not. Somebody pressing `DISPATCHED` should know it is
not a private note.

**Legal pages ship as marked drafts.** The wording has the structure a document like this needs and
none of the authority, so every page carries a visible banner and every field the owner must supply
is highlighted in the text. `draft={false}` removes the banner, and that is a deliberate act rather
than a default — a quiet placeholder that looks finished is worse than no page at all.

### Returns: the design decisions

**A return is not an order status.** `orders.status` is the saga's state machine and every value in
it is set by the orchestrator; the guard that stops a late message moving an order backwards
depends on that being true. A return happens after the saga is finished. Adding `RETURNED` would
put a state in the machine that the machine never sets. This is the same call already made for
shipments, and "has this order been returned" is a question about the return rows — exactly as
"where is this parcel" is a question about the shipment rows.

**Money follows the goods, in four separate acts.** Request, approve, receive, refund. Each is a
distinct action by a person, because refunding on approval means paying for goods that may never
arrive, and because the warehouse hand that records a parcel often does not have the authority to
move money.

**A line refunds what was charged, not what it costs.** `unitPrice` already has any coupon
allocated into it, so refunding `listPrice` would fund the discount twice — once at checkout and
once on return. Tax is recomputed on the returned quantity at the frozen rate rather than
apportioned out of the line's rounded tax, which is the rule the order itself follows: dividing a
rounded total by three gives three numbers that do not add back up to it.

**Delivery comes back only when the whole order does.** A customer keeping one item of three has
still had the parcel delivered.

**Refunds are not the compensation path, and that is not a duplication.** `RefundPaymentCommand`
reverses a payment whole and answers a repeat with "already refunded" — correct for a cancelled
order, and wrong for a return, which is partial by nature and repeatable. Routed through it, the
first partial refund would have marked the payment fully refunded and the second would have
reported success while sending nothing. So returns get their own command, their own topic and a
`payment_refunds` ledger where what has gone back is the sum of the rows.

**The return id is the idempotency key, and it is a unique constraint.** Not the message id: that
would only deduplicate one delivery of one message, not a relay republishing after a restart.

**No synchronous call between services, because there is no way to make one.** There is no
service-to-service authentication in this platform — the catalogue lookup works only because that
endpoint is `permitAll`. The refund command goes into the outbox in the same transaction as the
state change, so the two cannot disagree, and the reply carries the outcome.

**A failed refund returns to `RECEIVED` with the reason on the row.** Not a terminal failure state:
the goods really are here and the money really is owed, so the only useful state is one somebody
can act on. The console shows the provider's reason in red next to the retry.

### Defects found in Phase 25

| # | Found by | Defect |
|---|---|---|
| 1 | `KafkaTypeMappingsTest` | The new events were registered in the class table and not the topic table, so publishing either would have failed at runtime with "no topic registered". The existing guard test caught it before it ran once. |
| 2 | Reading `PaymentService.refundPayment` | It calls the gateway with the command's amount but then marks the payment `REFUNDED` unconditionally. Harmless while only the saga used it; the moment returns did, the second partial refund would have "succeeded" without sending anything. This is why returns got their own path. |
| 3 | Checking the gateway routes | `/api/returns/**` matched no route, so the entire feature would have 404'd at the edge with every service healthy. Added to the Order Service predicate. |
| 4 | `eslint` | `lines.data ?? []` recreated an empty array each render, invalidating the refund estimate's memo on every keystroke. |

424 unit tests green, `npm run build` clean, eslint zero warnings.

---

### Phase 26 — Two holes the returns work left behind ✅

Asked whether anything remained, a pass over Phase 25 found two gaps in work that had just been
delivered. Both were mine, and both would have been discovered by a shopkeeper rather than a test.

**Returned goods never went back into stock.** `ReturnService` did not mention inventory at all. A
customer returned two laptops, was refunded, and those two units vanished from the shop's books —
still on a shelf, invisible to the catalogue. The stock figure would have drifted down by exactly
the number of items customers sent back, for as long as the shop ran.

**The console promised an email nobody sent.** The customer's return panel said, in as many words,
*"Post the items back to us. We will email you the address."* There was no return notification type
and no message of any kind at any point in the flow. Somebody approved to send a parcel back would
have been left with no idea where to send it.

### Restocking: a decision, not a consequence

**Not everything that comes back can be sold again.** A change of mind returns to the shelf; a
cracked screen returns to the warehouse and never to a customer. Only the person holding the box
can tell, so `restock` is a required field with no default — guessing `true` sells the next
customer something broken, guessing `false` quietly writes off stock that was fine. The console
offers two buttons rather than one plus a checkbox, because a checkbox beside a single button is a
thing people click past.

`restocked` is a nullable `Boolean` for the same reason: `null` means "not opened yet" and `false`
means "opened and written off", and collapsing them would make a write-off look like a return
nobody had touched.

**A third command that could not be the existing one.** `RestockInventoryCommand` restocks *every
item in a reservation* and then marks the reservation returned. That is right for a cancelled order
and wrong twice over here: a customer returning one of three items would have all three put back on
sale, and the reservation would be closed so a second return weeks later would silently do nothing.
It is the same trap as `RefundPaymentCommand`, found by reading rather than by running — and it is
the second time the same shape of bug has appeared, which is the argument for looking at the
compensation path every time returns touch something new.

**Units go back where they came from.** The original reservation records the building each line
shipped from, and returning stock to a different warehouse leaves one short, one over, and a picker
sent to an empty shelf.

**Fire and forget, deliberately.** No reply topic. The outbox guarantees delivery, the consumer is
idempotent, and both sides audit — so a failure surfaces in the dead-letter topic and the audit log
rather than silently. A reply loop would double the machinery to tell the return something it does
not act on: the money has gone back either way.

### Emails: three moments, not six

Approved, refused, refunded. Those are the points where the customer must act or has been waiting
for an answer. "We received your parcel" is not one — it lands a day before the refund and teaches
people that mail from this shop can be ignored, which is expensive on the day one of these
messages matters.

The refunded message is sent from the reply listener rather than from the button that asked for the
refund. Until that reply arrives the money has not moved, and saying it had would be a lie.

`RETURN_APPROVED` is deliberately distinct from the existing `SHIPMENT_RETURNED`: one is a customer
deciding to send something back, the other is a carrier bouncing a parcel nobody could deliver.
Same word, opposite situations.

### A shared rule instead of a second copy

Restocking needed the product-level stock summary recomputed, and that logic was private inside
`InventoryReservationService`. It moved to `InventorySummaryRefresher`, used by both. Two copies of
a summing rule is how two figures drift apart, and the day they do, whichever is wrong is wrong
invisibly. The existing reservation tests were pointed at a real refresher over the same mocked
repositories rather than a mock of it, so they still observe exactly the writes they observed
before — which is what makes the move provably pure.

430 unit tests green, `npm run build` clean, eslint zero warnings.

---

## 6. Final state

| | |
|---|---|
| Maven modules | 8 (parent, common, gateway, 5 services) |
| Java sources | 411 — 354 main, 57 test |
| Test classes | 55, of which 5 are Testcontainers saga suites |
| Flyway migrations | 36 across 5 databases |
| Kafka topics | 18 business + 18 dead-letter |
| Dockerfiles | 6, multi-stage, non-root, health-checked |
| Compose services | 13 (5 infrastructure, 6 application, 2 observability) |
| Kubernetes objects | 46 across 14 manifests |
| GitHub Actions workflows | 3 |
| REST endpoints | 99 operations |

Every command topic has exactly one consumer — the participant it names — and every reply topic
has exactly one, the orchestrator. `inventory.commands` → Inventory; `payment.commands` →
Payment; `notification.send` → Notification; the nine reply topics → Order. `order.created`,
`order.completed` and `order.cancelled` are domain events rather than saga traffic: they are
announcements, and anything may subscribe without the saga knowing.

### Known limitations, stated rather than hidden

1. **Integration tests have not been run.** `mvn clean verify` now passes (see section 7), but
   the Testcontainers suites need a Docker daemon and Docker is not installed on this host. They
   run in CI, where `ci.yml` executes `mvn verify -Pintegration` on a runner that has one.
2. **No Maven Wrapper is committed.** Fetching a verifiable wrapper distribution was impossible
   offline. Maven 3.9+ is a documented prerequisite; CI uses the runner's Maven.
3. **Stripe runs in test mode.** `StripePaymentGateway` is real; going live is a key change, not
   a code change. `SimulatedPaymentGateway` remains behind a property for local work without keys.
4. **Datastores in `k8s/` are single-replica** and intended for development or staging. Production
   should point at managed instances and delete `02`–`04`.
5. **No Audit Service.** It appears only in the ASCII sketch of `high-level-architecture.md`;
   three authoritative documents enumerate five services plus the gateway. The audit concern is
   covered by an append-only `admin_audit_log` in each service (Phase 23), the immutable
   `outbox_event` / `processed_event` ledgers, and structured JSON logging.
6. **No auditor role.** Reading the audit trail requires `ADMIN`, so somebody who only needs to
   read the log has to be given the ability to change things. Separation of duties needs a third
   role, and the two that exist are `CUSTOMER` and `ADMIN`.
7. **The legal pages are developer placeholders.** Terms, privacy, returns and contact have the
   right structure and none of the authority. Every page shows a draft banner and highlights the
   fields the owner must supply; `draft={false}` removes the banner once real wording is in.
8. **Reports are a sample, not a ledger.** Revenue is computed in the browser from the last 100 to
   200 orders because there is no aggregate endpoint. The screens say so; a business cannot run on
   it.
9. **The retention sweepers are not leader-elected.** With more than one replica of a service,
   every replica runs every job. The deletes are idempotent, so the result is correct and the work
   is duplicated — worth fixing before scaling out, not after.

---

## 7. Verification status

`mvn clean test` — **BUILD SUCCESS**, Maven 3.9.16 / Temurin 21.0.2, 28 August 2026.

```
CommerceFlow ....................................... SUCCESS
CommerceFlow :: Common ............................. SUCCESS   55 tests
CommerceFlow :: API Gateway ........................ SUCCESS    4 tests
CommerceFlow :: Auth Service ....................... SUCCESS   83 tests
CommerceFlow :: Inventory Service .................. SUCCESS   87 tests
CommerceFlow :: Order Service ...................... SUCCESS  164 tests
CommerceFlow :: Payment Service .................... SUCCESS   22 tests
CommerceFlow :: Notification Service ............... SUCCESS   15 tests
------------------------------------------------------------------------
Tests run: 430, Failures: 0, Errors: 0, Skipped: 0       Warnings: 0
```

Frontend alongside it: `npm run build` clean (`tsc -b` then `vite build`, 3.0 s), `eslint` with
zero warnings, 85.60 kB gzipped on the initial payload.

**`npm run build`, not `tsc --noEmit`.** The frontend uses TypeScript project references, and
`--noEmit` silently skipped files that `tsc -b` type-checked — it passed on code calling a boolean
as a function. Every frontend result quoted in this document is from the build.

**`clean` is not optional, and that is a finding rather than a habit.** A bare `mvn compile` once
reported success while `OrderMapper` did not compile at all — incremental compilation had decided
the file was unchanged and skipped it, so the build passed on a stale class file. Every
verification quoted in this document is from a clean build.

| Check | Result |
|---|---|
| Compilation, all 8 modules | ✅ Pass |
| Unit tests | ✅ 430 / 430 |
| Build warnings | ✅ 0 |
| Executable jars | ✅ 6 produced |
| Annotation processing (MapStruct, Lombok) | ✅ Verified in the packaged jar |
| **Integration tests** (Testcontainers) | ⚠️ **Not run — no Docker on this host**; CI runs them |
| **Application context startup** | ⚠️ **Not exercised** — see below |

### What passing tests do *not* prove

Most of the tests are true unit tests: they construct collaborators directly or with Mockito, so
**almost no full Spring application context is started anywhere in `mvn test`**. The Spring tests
are `@WebMvcTest` slices. That leaves one class of risk unverified — that the
auto-configurations in `commerceflow-common` wire up correctly against a real context: the outbox
relay, the idempotency ledger, the Kafka type mappings, Flyway matching the JPA mapping under
`ddl-auto: validate`, and the Redis cache serializers.

Exactly that is what the five Testcontainers suites exist to prove, and they need Docker. Until
they run, `docker compose up -d --build` followed by `./scripts/smoke-test.sh` is the fastest way
to exercise the same ground by hand.

### Build environment notes

Two obstacles had to be cleared on this host, both external to the code:

1. **Proxy authentication.** Skyhigh Secure Web Gateway at `10.98.153.68:9090` requires HTTP Basic
   auth (realm `FNT-Proxy-LAN01`); Windows default credentials, NTLM and Negotiate are all
   rejected. Resolved with a `<proxies>` block in `~/.m2/settings.xml`.
2. **TLS interception.** The proxy re-signs TLS with `CN=Fsoft Proxy CA` and `CN=Fsoft_Cert_MWG`.
   Windows trusts both; the JDK truststore does not, which surfaced as
   `PKIX path building failed`. Resolved by importing both CAs into a **copy** of the JDK
   `cacerts` and pointing Maven at it — the JDK itself is untouched:

   ```
   MAVEN_OPTS="-Djavax.net.ssl.trustStore=<path>\commerceflow-truststore.jks
               -Djavax.net.ssl.trustStorePassword=changeit"
   ```

   To make this permanent, import the two CAs into `$JAVA_HOME/lib/security/cacerts` directly, or
   set `MAVEN_OPTS` as a user environment variable. Neither is needed in CI, which has direct
   network access.

### Defects found by actually building

| # | Found by | Defect |
|---|---|---|
| 1 | `javac` parse pass | `RegisterRequest` had lost a level of backslash escaping, leaving `".*\d.*"` and `"^$\|^\+?..."` as invalid Java escape sequences — a hard compile error. Both `@Pattern` regexes rewritten with character classes (`[0-9]`, `[+]`) so they carry no escapes and cannot regress the same way. |
| 2 | build warnings | The global `-Amapstruct.*` compiler arguments were unclaimed in the seven modules that have no `@Mapper`, producing 13 warnings per build. Removed; `componentModel` and `unmappedTargetPolicy` now sit on the `@Mapper` annotation, and the policy was tightened to `ERROR` so an unmapped target field fails the build rather than silently arriving as `null`. |

Earlier review passes had already fixed two behavioural defects (the Payment rollback and the
idempotency race) — see Phase 13.
