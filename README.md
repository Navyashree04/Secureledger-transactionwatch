<img src="https://capsule-render.vercel.app/api?type=waving&color=0:0f2027,50:203a43,100:2c5364&height=180&section=header&text=TransactionWatch&fontSize=46&fontColor=ffffff&fontAlignY=38&desc=Event-driven%20fraud%20alerting%20%C2%B7%20Spring%20Boot%20%C2%B7%20Kafka%20%C2%B7%20Redis&descAlignY=60&descSize=16" width="100%"/>

<div align="center">

<img src="https://skillicons.dev/icons?i=java,spring,kafka,redis,postgres,docker,prometheus&theme=dark" />
<br/><br/>
<img src="https://img.shields.io/badge/Delivery-at--least--once-00E5FF?style=flat-square" />
<img src="https://img.shields.io/badge/Run-docker%20compose%20up-2496ED?style=flat-square" />

</div>

> 📚 Short on time before an interview? Open the **Deep dive** at the bottom of this page.

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


---

<details>
<summary><b>📚 Deep dive: interview notes (click to expand)</b></summary>

### 1. Elevator pitch (30 seconds)
Two independent Spring Boot services connected by Kafka. **SecureLedger** (the banking API) publishes an event every time money moves. **TransactionWatch** consumes those events, keeps each account's recent activity in a Redis sliding window, runs fraud rules, and stores alerts that an admin reviews through a JWT-secured REST API. The point: fraud detection never slows down or breaks the core banking path.

### 2. Architecture and event flow
```text
SecureLedger ──commit DB tx──► publish TransactionCreatedEvent (keyed by accountId)
                                          │
                                          ▼
                            Kafka topic: transactions.created
                                          │
                                          ▼
TransactionWatch @KafkaListener (manual ack, 3 retries, 1s backoff)
      │
      ├─► Redis sorted set per account (score = timestamp, pruned on write, TTL 10 min)
      │
      ├─► RuleEngine ─► VelocityRule   (≥ 5 transactions in 60 s)
      │               └► LargeAmountRule (amount ≥ 10,000)
      │
      ├─► Alert ─► PostgreSQL (Flyway-migrated, indexed)
      │
      └─► ack offset (only after everything above succeeded)

Admin REST API (JWT, ADMIN only, OpenAPI) · Actuator health + Prometheus metrics
```
Both services, Postgres, Kafka (KRaft, no ZooKeeper) and Redis start with one `docker compose up`, each gated on the health of what it depends on.

### 3. Key design decisions and trade-offs
| Decision | Why | Trade-off |
|:--|:--|:--|
| Event-driven, not a synchronous call | A slow or broken fraud service can't hurt the banking API; new consumers subscribe without touching SecureLedger | Fraud detection is eventually consistent (seconds behind) |
| Publish **after** DB commit | Never announce a transaction that later rolls back | A crash between commit and send can drop an event (fix: outbox pattern) |
| **At-least-once** delivery | A duplicate alert is far cheaper than a missed fraud signal; processing is safe to repeat | Possible duplicate alerts |
| **Manual offset commit** | With auto-commit, a crash mid-processing could mark unfinished work as done | Slightly more code |
| Partition key = `accountId` | All events for one account stay in order on one partition; the velocity rule needs that | A very hot account can't be split across consumers |
| Redis sorted set as a sliding window | O(log N) per event and keeps Postgres off the hot path (only alerts hit the DB) | Window state lives in memory and is rebuilt from new events after a Redis loss |
| Rules as `FraudRule` beans, thresholds in config | Add a rule by adding a class; tune thresholds without code changes | No rule versioning yet |
| Shared JWT issuer | The same token works on both services | Services share a signing secret |

### 4. Where the important code lives
- `secureledger/.../event/TransactionEventPublisher.java`: publish-after-commit and the reasoning behind it
- `transactionwatch/.../event/TransactionEventListener.java`: consume, process, then ack
- `transactionwatch/.../cache/RedisAccountActivityService.java`: the sliding window
- `transactionwatch/.../rules/`: `RuleEngine`, `VelocityRule`, `LargeAmountRule`
- `transactionwatch/src/main/resources/application.yml`: rule thresholds
- `docker-compose.yml` + `postgres-init/`: the whole system in one command

### 5. Testing
Tests exist for each rule (`VelocityRuleTest`, `LargeAmountRuleTest`, `RuleEngineTest`), the Kafka listener (`TransactionEventListenerTest`), the Redis window (`RedisAccountActivityServiceTest`), alert persistence (`AlertRepositoryIntegrationTest`) and the admin API (`AdminAlertControllerTest`).

### 6. Limitations and what I'd do next
- A **dead-letter topic** (`transactions.created.DLT`) for messages that still fail after retries (today they're logged).
- The **outbox pattern** in SecureLedger to close the commit-then-publish gap.
- **Idempotent alert writes** (unique on event id) to remove duplicate alerts.
- Calling **FraudScope** (my ML service) for an ML risk score on flagged transactions. The REST contract is designed but not wired in.

### 7. Likely interview questions
- **Why Kafka instead of calling the fraud service directly?** Decoupling: the banking API's latency and availability don't depend on fraud detection, Kafka keeps events while the consumer is down, and new consumers need no producer changes.
- **What happens if TransactionWatch is down?** Nothing is lost. Kafka retains the events, and on restart the consumer group resumes from its last committed offset.
- **At-least-once vs exactly-once: which did you choose, and why?** At-least-once. Processing is safe to repeat, and a lost fraud signal is worse than a duplicate alert.
- **How do you keep per-account ordering with multiple consumers?** Key by `accountId`. One partition goes to one consumer in the group, so an account's events are always processed in order.
- **Why Redis and not a Postgres query for "transactions in the last 60 s"?** It runs on every event; a sorted-set range is O(log N) in memory, and Postgres is only touched when there's an alert to save.
- **How would you scale it?** Add partitions and TransactionWatch instances (up to the partition count) with no code change.

</details>
