# TransactionWatch

Event-driven fraud-alert service — Project 2 of the SecureLedger system.
See the [top-level README](../README.md) for the full architecture,
reliability/scaling discussion, and end-to-end flow. This file covers
running and testing this service specifically.

## Stack

Java 17 · Spring Boot 3.3 · Spring Kafka · Spring Data Redis · Spring Data
JPA (Flyway-migrated) · PostgreSQL 15+ · Spring Security (JWT, reusing
SecureLedger's issuer) · springdoc-openapi · Micrometer/Prometheus

## Status

Phases 1–7 (MUST + SHOULD from the master prompt) are implemented: Kafka
consumer with manual ack and retry backoff, Redis sliding-window activity
cache, VelocityRule + LargeAmountRule + RuleEngine, Alert persistence
(Flyway), the admin REST API, and Actuator observability. Docker Compose
(Phase 8) is at the repo root. Phase 10 (dead-letter queue) and Phase 11
(runtime-configurable rule thresholds) are documented as future work, not
implemented — see the top-level README §6.

> **Same sandbox caveat as SecureLedger:** this was written and reviewed
> by hand in an environment with no route to Maven Central (confirmed via
> a 403 from the egress proxy), so `mvn clean test` has not actually been
> run here. Please run it yourself before treating this as verified.
> Some of the integration tests (`RedisAccountActivityServiceTest`,
> `AlertRepositoryIntegrationTest`) use Testcontainers and need a working
> Docker daemon to run at all.

## Run standalone (against local infra)

You need Postgres, Kafka, and Redis reachable. Easiest path: bring up just
those three from the root `docker-compose.yml` —

```bash
cd ..
docker compose up postgres kafka redis
```

then, from this directory:

```bash
export DB_USERNAME=transactionwatch DB_PASSWORD=transactionwatch
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export REDIS_HOST=localhost REDIS_PORT=6379
export JWT_SECRET=change-this-dev-secret-change-this-dev-secret-32bytes  # must match SecureLedger's
mvn spring-boot:run
```

The API listens on `http://localhost:8081`; Swagger UI at
`http://localhost:8081/swagger-ui.html`.

## Run tests

```bash
mvn clean test
```

- Rule unit tests (`VelocityRuleTest`, `LargeAmountRuleTest`,
  `RuleEngineTest`) run with mocked dependencies — no infra needed.
- `TransactionEventListenerTest` uses an embedded Kafka broker
  (`spring-kafka-test`) — no external Kafka needed.
- `RedisAccountActivityServiceTest` and `AlertRepositoryIntegrationTest`
  use Testcontainers (real Redis / Postgres containers) — **these need
  Docker running** to execute at all.
- `AdminAlertControllerTest` runs against the H2 `test` profile — no infra
  needed.

## Admin API

All `/api/admin/**` endpoints require a Bearer JWT with `role: ADMIN`,
issued by SecureLedger's `/auth/login` (see [JwtService](src/main/java/com/transactionwatch/config/JwtService.java)
for the auth-approach justification).

| Method | Path | Description |
|---|---|---|
| GET | `/api/admin/alerts` | Paginated, filterable by `accountId`, `status`, `severity`, `from`/`to` |
| GET | `/api/admin/alerts/{id}` | Single alert detail |
| GET | `/api/admin/accounts/{accountId}/alerts` | All alerts for one account |
| PATCH | `/api/admin/alerts/{id}/status` | Update triage status (OPEN/REVIEWED/DISMISSED) |

## Rule thresholds

Set in `application.yml` under `transactionwatch.rules`:

```yaml
transactionwatch:
  rules:
    velocity:
      enabled: true
      threshold-count: 5      # > 5 transactions...
      window-seconds: 60      # ...within 60 seconds
    large-amount:
      enabled: true
      threshold-amount: 10000.00
```
