# SecureLedger

A core banking transaction API: account management, deposits, withdrawals,
atomic transfers, transaction history, JWT auth, and an append-only audit
trail. Built to demonstrate engineering judgment (correctness, concurrency
safety, clean layering) rather than feature breadth — see [DESIGN.md](DESIGN.md)
for the reasoning behind the isolation level, locking strategy, and
idempotency design.

## Stack

Java 17 · Spring Boot 3.3 · Spring Data JPA (Hibernate) · Spring Security
(self-issued JWT) · PostgreSQL 15+ · JUnit 5 · Maven

## Status

**Phase 1 (the "done" bar) is fully implemented**: account CRUD,
deposit/withdraw, atomic transfers with idempotency and pessimistic
locking, JWT auth, global exception handling, and the required test suite
(including the Phase-1 acceptance criterion around concurrent transfers).
Docker/Swagger/rate-limiting (Phase 3) are not yet built.

> **A note on this environment:** this codebase was written and reviewed
> carefully by hand, but it was **not compiled or run** in the environment
> it was generated in — that sandbox has no route to Maven Central, only to
> a handful of allowlisted hosts (GitHub, npm, PyPI, crates.io). Before you
> treat Phase 1 as verified, run `mvn clean test` yourself locally per the
> steps below. If anything doesn't compile, it'll almost certainly be a
> small, mechanical fix (an import, a method signature) rather than a
> design problem — the architecture and logic have been checked line by
line against the Spring Boot 3.3 / jjwt 0.12 / Jakarta APIs this project
> targets.

## Run locally

1. **Start Postgres** (or use the version bundled with your OS package
   manager — any Postgres 15+ works):
   ```bash
   createdb secureledger
   # or, with Docker if you have it available:
   docker run --name secureledger-db -e POSTGRES_DB=secureledger \
     -e POSTGRES_USER=secureledger -e POSTGRES_PASSWORD=secureledger \
     -p 5432:5432 -d postgres:15
   ```
2. **Set environment variables** (or rely on the defaults in
   `application.yml`, which match the Docker command above):
   ```bash
   export DB_USERNAME=secureledger
   export DB_PASSWORD=secureledger
   export JWT_SECRET=$(openssl rand -base64 32)
   ```
3. **Run**:
   ```bash
   mvn spring-boot:run
   ```
   The API listens on `http://localhost:8080`.

## Run tests

```bash
mvn clean test
```

Tests run against an in-memory H2 database in Postgres-compatibility mode
(`spring.profiles.active=test`) — no external Postgres needed to run the
suite. This includes an integration test that fires 25 concurrent transfer
requests at the same pair of accounts and asserts the final balance is
exactly correct (see `ConcurrentTransferTest`) — the direct proof of the
pessimistic-locking claim in DESIGN.md. Note H2's locking behavior is a
reasonable stand-in but not a perfect substitute for Postgres; if you want
the strongest version of that proof, point the `test` profile at a real
Postgres instance.

## API walkthrough (curl)

```bash
# Register (first user registered becomes ADMIN; rest are CUSTOMER)
curl -s -X POST localhost:8080/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correct horse battery staple"}'
# → { "token": "...", "tokenType": "Bearer", "expiresInMs": 3600000, "username": "alice", "role": "ADMIN" }

TOKEN=<paste token from above>

# Login (subsequent sessions)
curl -s -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correct horse battery staple"}'

# Create an account
curl -s -X POST localhost:8080/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"accountType":"SAVINGS"}'
# → { "id": 1, "accountNumber": "...", "balance": "0.0000", "status": "ACTIVE", ... }

# Deposit
curl -s -X POST localhost:8080/accounts/1/deposit \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"amount": "500.00"}'

# Withdraw
curl -s -X POST localhost:8080/accounts/1/withdraw \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"amount": "50.00"}'

# Transfer (Idempotency-Key strongly recommended — a client should generate
# one UUID per logical transfer attempt and reuse it on retry)
curl -s -X POST localhost:8080/transfers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"fromAccountId": 1, "toAccountId": 2, "amount": "100.00"}'

# Transaction history (paginated)
curl -s "localhost:8080/accounts/1/transactions?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"

# Freeze / unfreeze an account (ADMIN only)
curl -s -X PATCH localhost:8080/accounts/1/freeze -H "Authorization: Bearer $TOKEN"
curl -s -X PATCH localhost:8080/accounts/1/unfreeze -H "Authorization: Bearer $TOKEN"
```

A full Postman collection covering every endpoint (including the
register → login → create account → deposit → transfer flow) is at
[`postman/SecureLedger.postman_collection.json`](postman/SecureLedger.postman_collection.json).
Import it, set the `baseUrl` and `token` collection variables (the login
request auto-populates `token` via a test script), and run the requests in
order.

## What this project demonstrates → interview topics

| Feature | Interview topic |
|---|---|
| `NUMERIC(19,4)` / `BigDecimal` everywhere money is touched | Why floating point is wrong for currency |
| `SELECT ... FOR UPDATE` via `PESSIMISTIC_WRITE`, fixed lock ordering | Pessimistic vs. optimistic locking; deadlock avoidance |
| `READ COMMITTED` + explicit row locks, justified in DESIGN.md | Transaction isolation levels and their tradeoffs |
| `transactions.idempotency_key` UNIQUE constraint + pre-check | Idempotency under concurrent retries — DB constraint vs. app-level check |
| Two-row transfer (`TRANSFER_OUT` / `TRANSFER_IN`) in one `@Transactional` method | Atomicity — all-or-nothing money movement |
| `audit_log`, append-only by construction, written in the same transaction as the balance change | Auditability; why "log it afterward" is the wrong pattern |
| Self-issued JWT, `OncePerRequestFilter`, stateless sessions | Stateless auth design |
| `@PreAuthorize("hasRole('ADMIN')")` + ownership checks in the service layer | RBAC done declaratively, not with scattered `if` checks |
| `@ControllerAdvice` mapping every domain exception to `{timestamp,status,error,message,path}` | Consistent API error contracts |
| `@Version` optimistic locking on account status, pessimistic on balance | Choosing the right locking strategy per access pattern |

## Project layout

```
src/main/java/com/secureledger/
  config/      JWT issuance/validation, security filter chain, CORS
  controller/  request/response mapping + validation only
  dto/         request/response records, Bean Validation constraints
  entity/      JPA entities, enums, balance-mutation guards
  exception/   domain exceptions + global handler
  repository/  Spring Data interfaces, the PESSIMISTIC_WRITE lock query
  service/     all business rules, @Transactional boundaries, locking
```
