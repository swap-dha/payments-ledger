# payments-ledger

[![CI](https://github.com/swap-dha/payments-ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/swap-dha/payments-ledger/actions/workflows/ci.yml)

A double-entry payments ledger in Java 21 and Spring Boot 4, built the way banking systems need to be built: every movement of money balances, nothing is ever updated in place, and retries never double-post.

> **Status:** Day 4 of a 14-day public build. Domain, schema invariants, persistence, idempotent transfers and the REST API are done; balance-level concurrency control and the Kafka outbox are next. See [Roadmap](#roadmap).

## Why this exists

Most "wallet" demos store a `balance` column and update it. Real ledgers don't: a balance is derived from an immutable history of postings, and the system's job is to make it impossible to record money that came from nowhere. This project shows how to enforce that at three layers.

| Invariant | Domain (Java) | Database (PostgreSQL) |
| --- | --- | --- |
| Every entry's postings sum to zero | `JournalEntry.create` rejects it | Deferred constraint trigger, checked at commit |
| Ledger history is immutable | No setters; postings list is unmodifiable | `UPDATE`/`DELETE` blocked by trigger |
| A retried request never double-posts | Same key + same transfer returns the original id; same key + different transfer is rejected | `UNIQUE (idempotency_key)` |
| Concurrent identical requests post once | Loser of the race reads the winner's entry | Unique constraint decides the winner |
| No entry without legs | At least two postings, one debit and one credit | Deferred trigger: entry needs ≥ 2 postings at commit |
| No zero or overflowing amounts | `Posting` rejects zero; sums use `Math.addExact` | `CHECK (amount_minor <> 0)` |

Amounts are `long` minor units (paise, cents), never `double`.

## API

Captured from a real run (`mvn spring-boot:run` against PostgreSQL):

```http
POST /transfers
Idempotency-Key: order-42
{"fromAccountId":"…","toAccountId":"…","amountMinor":150000,"currency":"INR"}

→ 201 Created
{"entryId":"65bf9ac9-…","replayed":false}
```

Retry after a timeout, same key and body: nothing new is written.

```http
→ 200 OK
Idempotent-Replayed: true
{"entryId":"65bf9ac9-…","replayed":true}
```

Same key, different amount: refused instead of falsely reporting success.

```http
→ 409 Conflict   (application/problem+json, RFC 9457)
{"type":".../problems/idempotency-key-reused","title":"Idempotency key reused",
 "detail":"idempotency key order-42 was already used for a different transfer","status":409}
```

| Endpoint | Purpose |
| --- | --- |
| `POST /accounts` | Open an account (`code`, `name`, `currency`, `type`) → 201 + `Location` |
| `POST /transfers` | Move money; `Idempotency-Key` header required. `fromAccountId` is credited, `toAccountId` debited |
| `GET /accounts/{id}/balance` | `balanceMinor` (as an accountant reads it) and `signedMinor` (raw, for reconciliation) |

Status codes follow one rule: **400** malformed request · **404** unknown account · **409** collides with stored state (key reuse, duplicate account code) · **422** well-formed but breaks a ledger rule (currency mismatch).

## Architecture

Hexagonal (ports and adapters). The dependency rule is enforced by an ArchUnit test, so a domain class importing Spring fails the build.

```
adapter/in/web  ──►  application/port/in  ──►  domain/model  ◄──  application/port/out  ◄──  adapter/out/persistence
   (REST)              (use cases)          (pure Java, no         (repository ports)          (JDBC, outbox)
                                              framework imports)
```

```
src/main/java/dev/swapnil/ledger
├── domain/model          JournalEntry, Posting, Account, AccountType
├── application/port/in   PostTransferUseCase
├── application/service   TransferService (framework-free)
├── application/port/out  JournalEntryRepository, AccountRepository
└── adapter
    ├── in/web            REST controllers (Days 5-6)
    └── out/persistence   JDBC adapters, outbox (Days 5-10)
```

## Run it

Requirements: Java 21, Maven, Docker.

```bash
docker compose up -d postgres
mvn spring-boot:run          # Flyway applies the schema on startup
mvn verify                   # unit + architecture tests, then Testcontainers integration tests
```

## Tests

- `LedgerApiIT` — the full stack over real HTTP: open accounts, transfer, retry (200 + replay header), key reuse (409), balances, unknown account (404), duplicate code (409).
- `TransferControllerTest` — 8 cases on the HTTP contract with MockMvc: status codes, headers, field-level validation errors, problem types.
- `TransferServiceTest` — 7 cases with in-memory adapters: retries, key reuse conflicts, a simulated race, unknown accounts, currency mismatch.
- `TransferServiceIT` — through real adapters and PostgreSQL, including **10 threads posting the same request at once: one entry, one id, money moved once**.
- `JournalEntryTest` — 16 cases covering balanced entries, multi-leg entries with fees, rejection of unbalanced, single-leg, duplicate-account, self-transfer, zero, negative and overflowing amounts.
- `ArchitectureTest` — neither the domain nor the application layer may depend on Spring, JDBC or Kafka; layers point inward.
- `SchemaInvariantsIT` — against real PostgreSQL via Testcontainers: unbalanced entries and entries with fewer than two postings are rejected at commit, postings are append-only, duplicate idempotency keys fail.

## Roadmap

- [x] Days 1-2 — schema, Hexagonal layout, docker-compose, CI
- [x] Days 3-4 — domain model and zero-sum invariant
- [x] Days 5-6 (part 1) — JDBC persistence, idempotent transfer use case, concurrent-retry test
- [x] Days 5-6 (part 2) — REST API with `Idempotency-Key` header, RFC 9457 errors
- [ ] Days 7-8 — concurrent debits on one account, proven with a parallel test
- [ ] Days 9-10 — transactional outbox to Kafka, exactly-once publication test
- [ ] Days 11-12 — end-of-day transaction report reconciled against balances
- [ ] Days 13-14 — OpenAPI docs, ADRs, v1.0

## Design decisions

- **Signed postings instead of debit/credit columns.** One `amount_minor` column with `+` for debit and `-` for credit makes the zero-sum check a single `SUM`.
- **Deferred constraint trigger.** The balance check runs at `COMMIT`, after all postings of an entry are inserted, so the database never sees a half-written entry as valid or invalid.
- **No stored balances (yet).** Balances are derived from postings. A cached balance with optimistic locking comes on Days 7-8, when it is needed for concurrent debit checks.
- **Transactions live in the adapter, not the service.** `TransferService` has no Spring imports. The JDBC adapter wraps entry + postings in one transaction, which also lets the service recover from a lost idempotency race in a fresh transaction. A failed `INSERT` aborts a PostgreSQL transaction, so retrying inside it would be impossible.
