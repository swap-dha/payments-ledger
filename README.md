# payments-ledger

[![CI](https://github.com/swap-dha/payments-ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/swap-dha/payments-ledger/actions/workflows/ci.yml)

A double-entry payments ledger in Java 21 and Spring Boot 4, built the way banking systems need to be built: every movement of money balances, nothing is ever updated in place, and retries never double-post.

> **Status:** Day 2 of a 14-day public build. Domain model, schema and invariants are done; the REST API, concurrency control and Kafka outbox are next. See [Roadmap](#roadmap).

## Why this exists

Most "wallet" demos store a `balance` column and update it. Real ledgers don't: a balance is derived from an immutable history of postings, and the system's job is to make it impossible to record money that came from nowhere. This project shows how to enforce that at three layers.

| Invariant | Domain (Java) | Database (PostgreSQL) |
| --- | --- | --- |
| Every entry's postings sum to zero | `JournalEntry.create` rejects it | Deferred constraint trigger, checked at commit |
| Ledger history is immutable | No setters; postings list is unmodifiable | `UPDATE`/`DELETE` blocked by trigger |
| A retried request never double-posts | Idempotency key required on every entry | `UNIQUE (idempotency_key)` |
| No zero or overflowing amounts | `Posting` rejects zero; sums use `Math.addExact` | `CHECK (amount_minor <> 0)` |

Amounts are `long` minor units (paise, cents), never `double`.

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

- `JournalEntryTest` — 16 cases covering balanced entries, multi-leg entries with fees, rejection of unbalanced, single-leg, duplicate-account, self-transfer, zero, negative and overflowing amounts.
- `ArchitectureTest` — the domain may not depend on Spring, JDBC, JPA or Kafka; layers point inward.
- `SchemaInvariantsIT` — against real PostgreSQL via Testcontainers: unbalanced entries are rejected at commit, postings are append-only, duplicate idempotency keys fail.

## Roadmap

- [x] Days 1-2 — schema, Hexagonal layout, docker-compose, CI
- [x] Days 3-4 — domain model and zero-sum invariant
- [ ] Days 5-6 — REST API with `Idempotency-Key` header; retry returns the original result
- [ ] Days 7-8 — concurrent debits on one account, proven with a parallel test
- [ ] Days 9-10 — transactional outbox to Kafka, exactly-once publication test
- [ ] Days 11-12 — end-of-day transaction report reconciled against balances
- [ ] Days 13-14 — OpenAPI docs, ADRs, v1.0

## Design decisions

- **Signed postings instead of debit/credit columns.** One `amount_minor` column with `+` for debit and `-` for credit makes the zero-sum check a single `SUM`.
- **Deferred constraint trigger.** The balance check runs at `COMMIT`, after all postings of an entry are inserted, so the database never sees a half-written entry as valid or invalid.
- **No stored balances (yet).** Balances are derived from postings. A cached balance with optimistic locking comes on Days 7-8, when it is needed for concurrent debit checks.
