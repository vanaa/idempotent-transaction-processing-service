# Idempotent Background Transaction Processing Service

A lightweight Spring Boot REST service for the Practical FDE Assessment (L2). It demonstrates safe transaction handling when requests are duplicated, arrive out of order, or experience transient failures.

## Runtime and language compatibility

- Runtime verified by the candidate: Amazon Corretto JDK 21.0.12.1.
- Maven: 3.8.6; Maven runs on JDK 21.
- Application source is written using Java 8-compatible language features and APIs; Maven compiler source/target is configured for Java 8.
- Spring Boot 2.7.18 is used for this scoped exercise. For production, review framework support and upgrade dependencies as appropriate.

## Features

- REST transaction ingestion and transaction lookup.
- Bean Validation for required fields, positive sequence numbers, amount precision, and three-letter uppercase currency codes.
- Idempotency-key replay protection and transaction-ID duplicate detection.
- HTTP 409 for reuse of an idempotency key or transaction ID with conflicting transaction data.
- Asynchronous processing through a single-thread background worker.
- Per-account sequence ordering: future sequence numbers remain `PENDING_SEQUENCE` until the missing sequence is processed.
- Simulated transient failures and bounded retries, including retry-exhaustion testing.
- CREDIT/DEBIT balance changes with insufficient-funds protection.
- Transaction timestamps, attempt counts, processing logs, and status summary.
- Unit tests for replay, conflicting key reuse, ordering, retry, validation, and insufficient funds.

## Run locally

Prerequisites: JDK 21 and Maven 3.8+ (or IntelliJ's configured Maven).

1. Open the folder containing this `pom.xml` in IntelliJ IDEA as a Maven project.
2. Set Project SDK and Maven Runner JRE to JDK 21.
3. Run tests:

   ```bash
   mvn clean test
   ```

4. Start `com.assessment.transactions.TransactionProcessingApplication` from IntelliJ, or run:

   ```bash
   mvn spring-boot:run
   ```

5. Base URL: `http://localhost:8080`.

## REST API

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/transactions` | Submit a transaction; returns HTTP 202 Accepted |
| `GET` | `/api/transactions` | List transaction records |
| `GET` | `/api/transactions/{id}` | Retrieve a transaction by ID |
| `GET` | `/api/accounts/{accountId}/balance` | Read an account balance |
| `GET` | `/api/operations/summary` | Read total and counts by status |

Because processing is asynchronous, poll the transaction endpoint until it reaches a terminal state (`PROCESSED` or `FAILED`) before asserting the final balance.

## Sample requests and responses

- [`samples/API-examples.http`](samples/API-examples.http): requests that can be opened in IntelliJ's HTTP Client or adapted to Postman.
- [`samples/SAMPLE-RESPONSES.md`](samples/SAMPLE-RESPONSES.md): representative response shapes, status codes, and the manually verified outcomes from this implementation.

The samples are examples, not a fixed response fixture: timestamps and intermediate statuses vary because processing is asynchronous.

## Design decisions

See [`DESIGN.md`](DESIGN.md) for architecture, idempotency rules, sequence ordering, retry semantics, validation, AI-assisted development disclosure, known limitations, and production-hardening steps.

## Known limitations and production hardening

The service uses in-memory state. Restarting the process loses transaction records, idempotency keys, balances, and sequence checkpoints. It is a scoped assessment implementation, not a durable production ledger.

Before production use:

1. Persist transaction state, idempotency keys, ledger entries, balances, and sequence checkpoints in a database.
2. Add database unique constraints for transaction IDs and idempotency keys; atomically update ledger, balance, sequence checkpoint, and transaction status in one database transaction.
3. Introduce a durable queue/adapter and define delivery, acknowledgement, visibility timeout, and replay semantics.
4. Add configurable retry backoff/jitter, dead-letter handling, and an operator replay workflow.
5. Add concurrency, crash-recovery, persistence-failure, and API integration tests.
6. Add authentication/authorization, rate limiting, correlation IDs, health checks, metrics, and structured PII-safe logs.
7. Define currency-specific balance semantics; this demo maintains one numeric balance per account and assumes requests for an account use the same currency.
8. Define sequence-gap timeout and recovery policy, including what happens when an earlier sequence fails.

## Assessment coverage

- [x] Input validation
- [x] Idempotent replay and conflicting duplicate detection
- [x] Sequence-based out-of-order buffering
- [x] Asynchronous background worker
- [x] Simulated transient retry and retry exhaustion
- [x] Insufficient-funds handling
- [x] Status summary and processing logs
- [x] Automated tests for core scenarios
- [x] Design decisions, trade-offs, AI assistance, and production limitations documented
