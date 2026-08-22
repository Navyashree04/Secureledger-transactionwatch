# SecureLedger + TransactionWatch — Event-Driven Fraud Alerting

Two independently-deployable Spring Boot services connected by Kafka:
**SecureLedger** (core banking transaction API) publishes an event every
time money moves; **TransactionWatch** consumes those events, runs
fraud-detection rules against a Redis-backed sliding window of recent
account activity, and exposes an admin API to review the alerts it raises.

## 1. Architecture

```
┌─────────────────────┐     TransactionCreated      ┌──────────────────────────┐
│     SecureLedger     │ ───────────────────────────▶│   Kafka: transactions.   │
│  (Project 1 — REST   │   (JSON, keyed by accountId) │       created            │
│   banking API)       │                              └──────────────┬───────────┘
│                       │                                             │
│  deposit/withdraw/    │                                             ▼
│  transfer → commit →  │                              ┌──────────────────────────┐
│  publish (best-effort)│                              │      TransactionWatch     │
└──────────┬────────────┘                              │  (Project 2 — fraud svc)  │
           │                                            │                          │
           ▼                                            │  @KafkaListener (manual  │
  ┌─────────────────┐                                   │  ack, 3 retries)         │
  │ Postgres         │                                  │       │                  │
  │ (secureledger DB)│                                  │       ▼                  │
  └─────────────────┘                                   │  Redis: sliding-window   │
                                                          │  per-account activity   │
                                                          │       │                  │
                                                          │       ▼                  │
                                                          │  RuleEngine              │
                                                          │   - VelocityRule         │
                                                          │   - LargeAmountRule      │
                                                          │       │                  │
                                                          │       ▼                  │
                                                          │  Postgres (transaction-  │
                                                          │  watch DB) → alerts      │
                                                          │       │                  │
                                                          │       ▼                  │
                                                          │  Admin REST API (JWT,    │
                                                          │  ADMIN role only)        │
                                                          │       │                  │
                                                          │       ▼                  │
                                                          │  Actuator: health,       │
                                                          │  metrics, prometheus     │
                                                          └──────────────────────────┘
```

Each service has its own repo, its own `pom.xml`, and its own Postgres
database — see `secureledger/README.md` and `transactionwatch/` for the
per-service detail. This document covers the system as a whole.

## 2. Why event-driven

SecureLedger doesn't know TransactionWatch exists, and that's the point.
Fraud detection is a downstream concern of "a transaction happened," not a
step in the transaction itself — bolting fraud-rule evaluation into the
transfer/deposit code path would mean a slow or misbehaving fraud service
could make the core banking API slow or fail, for a concern that has
nothing to do with whether the money movement itself was valid. Publishing
an event and letting a separate service consume it on its own schedule
decouples the two: SecureLedger can be deployed, scaled, and changed
independently of TransactionWatch, and a second, third, or tenth consumer
(risk scoring, statement generation, a data warehouse feed) could be added
later by subscribing to the same topic — with zero changes to SecureLedger.

## 3. Reliability discussion

- **At-least-once delivery, not exactly-once.** SecureLedger publishes
  *after* its DB transaction commits (see
  `TransactionEventPublisher` for the full reasoning), so a crash between
  "DB commit" and "Kafka send succeeded" can drop an event, and a network
  hiccup that causes a retried send could in principle produce a duplicate.
  TransactionWatch's processing (recording Redis activity, evaluating
  rules, writing an alert) is safe to run more than once for the same
  event — an extra Redis sorted-set entry or an extra alert row is a much
  smaller problem than a lost fraud signal — so at-least-once is the right
  tradeoff here, not exactly-once.
- **Manual offset commits.** `TransactionEventListener` acknowledges only
  after the Redis write and rule evaluation (including any alert
  persistence) have both finished. With auto-commit, a crash mid-processing
  could commit an offset for work that was never actually completed —
  Kafka would believe that event was handled and never redeliver it. Manual
  ack means "committed" and "actually processed" can't drift apart.
- **Retry + (documented, not yet implemented) DLQ.** Kafka consumer
  processing retries 3 times with a 1-second fixed backoff
  (`KafkaConsumerConfig`) before giving up on a message. Today, "giving up"
  means logging and moving past it — a genuine dead-letter topic
  (`transactions.created.DLT`) that a permanently-failing message gets
  republished to, so it's inspectable rather than just logged, is the
  documented next step (see §6).
- **What happens if TransactionWatch is down when SecureLedger publishes
  an event?** Nothing is lost. Kafka retains the message on the topic
  (subject to its retention policy) regardless of whether any consumer is
  currently running. When TransactionWatch restarts, its consumer group
  resumes from its last committed offset and processes everything that
  accumulated while it was down — this is the core resilience property of
  a durable log versus a direct synchronous call, and it's the reason a
  Kafka outage window doesn't mean a permanent gap in fraud coverage.

## 4. Scaling discussion

- **Partitioning by `accountId`.** Every event is published keyed by
  `accountId`, which guarantees all events for one account land on the
  same Kafka partition and are therefore processed **in order** relative
  to each other. The velocity rule depends on this — counting "how many
  transactions has this account had recently" only makes sense if events
  for that account arrive in the order they happened.
- **Scaling consumers.** Because `transactionwatch-fraud-engine` is a
  Kafka consumer group, running multiple TransactionWatch instances
  scales throughput automatically: Kafka assigns each partition to exactly
  one consumer in the group, so adding instances (up to the partition
  count) adds parallelism without any code change. The per-account
  ordering guarantee still holds because a given account's events all stay
  on the same partition, hence the same consumer instance, no matter how
  many instances are running.
- **Redis keeps Postgres off the hot path.** Every event triggers a
  read-and-write of that account's recent activity — that's a Redis
  sorted-set operation (O(log N)), not a Postgres query, specifically so
  the database only gets touched when there's an actual alert to persist
  (a small fraction of traffic), not on every single event.

## 5. How to run locally

```bash
docker compose up
```

That single command builds both services (multi-stage Dockerfiles), and
brings up Postgres (with `secureledger` and `transactionwatch` databases
created by `postgres-init/01-create-databases.sql`), Kafka (KRaft mode,
single broker, no separate Zookeeper container — see the compose file
comment for that tradeoff), Redis, and both application containers —
each gated on `depends_on: condition: service_healthy` so nothing starts
before what it needs is actually ready.

- SecureLedger: `http://localhost:8080`
- TransactionWatch: `http://localhost:8081` (Swagger UI at `/swagger-ui.html`)

**End-to-end flow via curl:**

```bash
# 1. Register + get a token from SecureLedger (first user registered is ADMIN)
TOKEN=$(curl -s -X POST localhost:8080/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correct horse battery staple"}' | jq -r .token)

# 2. Create an account and deposit — this publishes a TransactionCreatedEvent
ACCOUNT_ID=$(curl -s -X POST localhost:8080/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"accountType":"SAVINGS"}' | jq -r .id)

curl -s -X POST localhost:8080/accounts/$ACCOUNT_ID/deposit \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"amount": "15000.00"}'   # over the 10,000 large-amount threshold

# 3. Give the consumer a moment, then check TransactionWatch's admin API
#    (same token works — TransactionWatch trusts SecureLedger's JWT issuer)
curl -s "localhost:8081/api/admin/accounts/$ACCOUNT_ID/alerts" \
  -H "Authorization: Bearer $TOKEN" | jq
# → should show one LARGE_AMOUNT alert
```

Each service's own README (`secureledger/README.md`) has the full curl
walkthrough and Postman collection for that service in isolation.

## 6. What's implemented vs. stated future improvement

**Implemented:**
- Kafka producer (SecureLedger) → consumer (TransactionWatch), JSON over
  the wire, keyed by `accountId`, manual offset commits, retry with backoff
- Two fraud rules (velocity, large-amount), both config-driven, with a
  `RuleEngine` that runs all registered `FraudRule` beans
- Redis sliding-window activity cache (sorted set, pruned on write, TTL'd)
- Alert persistence (Postgres, Flyway-migrated schema, indexed)
- Admin REST API: list/filter, get by id, get by account, update status —
  JWT-secured, ADMIN-only, OpenAPI-documented
- Actuator health (custom Kafka + Redis connectivity indicator), metrics,
  and a Prometheus scrape endpoint, with custom Micrometer counters/timers
  for events consumed, alerts triggered (tagged by rule), and rule
  evaluation latency
- Full Docker Compose stack with healthcheck-gated startup

**Stated future improvement, not implemented (called out deliberately,
not an oversight):**
- **Transactional outbox pattern** for the SecureLedger→Kafka publish, to
  close the "committed but never published" gap that best-effort
  publish-after-commit leaves open (see §3 and `TransactionEventPublisher`)
- **Dead-letter queue** — retries are implemented; routing permanently-
  failed messages to `transactions.created.DLT` for inspection, instead of
  just logging and moving on, is not
- **Configurable rule thresholds at runtime** — thresholds live in
  `application.yml` today; a Postgres-backed config table with an admin
  endpoint to change them without a redeploy is the natural next step
- **Schema registry** for the event contract — versioning is currently
  "a comment saying this is v1," not an enforced, evolvable schema
- **Exactly-once semantics** — deliberately not pursued; see §3 for why
  at-least-once is the right tradeoff for this system's actual failure
  modes
- **Currency normalization** in `LargeAmountRule` — amounts are compared
  against a single threshold with no FX conversion between currencies
