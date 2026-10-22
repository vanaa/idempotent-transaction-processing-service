# Design Note — Idempotent Background Transaction Processing Service

## 1. Problem understanding

The assessment asks for a background transaction service that tolerates duplicate and out-of-order requests, retries transient failures safely, maintains clear transaction state, exposes operational status, and includes tests. This implementation uses a REST API as a replaceable ingestion adapter and an in-process worker to keep the exercise lightweight.

## 2. Architecture

- `TransactionController` handles HTTP endpoints, request validation errors, conflicts, transaction lookup, balance lookup, and the processing summary.
- `TransactionProcessingService` owns idempotency registration, in-memory state, sequence checks, the worker queue, retry behavior, and business effects.
- `TransactionRequest` is the validated input DTO.
- `TransactionRecord` captures the accepted request and its processing state, attempts, message, and timestamps.
- `ProcessingSummary` reports totals grouped by status.

The controller is kept thin so a queue, scheduler, or file-based adapter could be added later without moving business rules into the input layer.

## 3. Idempotency and duplicate detection

The service checks the idempotency key and transaction ID before applying a balance change. Replaying the same request returns the existing record and must not apply the business effect a second time. Reusing an identifier with conflicting business data returns HTTP 409 Conflict. This makes client retries safe within the lifetime of the current process.

The idempotency index is in memory. A process restart loses it, so this is not durable idempotency across restarts or multiple application instances.

## 4. Ordering strategy

The service uses sequence-based ordering per account, assuming sequence numbers start at 1 and are contiguous. A request whose sequence is ahead of the next expected sequence is held in `PENDING_SEQUENCE`. When the missing sequence is processed, the service drains subsequent pending transactions in order. A stale or otherwise invalid sequence is failed according to the service rules.

Trade-off: this is deterministic and easy to exercise in a small service, but a missing sequence can block later work indefinitely. A production service needs sequence-gap age metrics/alerts, reconciliation, and a documented operator policy for skipping or repairing a failed sequence.

## 5. Asynchronous processing and status lifecycle

`POST /api/transactions` returns HTTP 202 Accepted after the request is accepted for processing; this does not mean the transaction has completed. Clients should poll `GET /api/transactions/{id}` until the status is terminal. The implementation exposes states including `RECEIVED`, `PROCESSING`, `RETRY_PENDING`, `PENDING_SEQUENCE`, `PROCESSED`, and `FAILED`.

A single-thread worker serializes processing in this JVM. This simplifies ordering and limits concurrency, but it is not a distributed work queue and does not provide horizontal scaling.

## 6. Retry and business consistency

The request supports demo-only fault injection fields: `simulateTransientFailure` for one transient failure and `simulatedTransientFailureCount` for deterministic retry-exhaustion scenarios. Failures are simulated before the balance effect, so a retry does not duplicate a credit. The retry count is bounded; exhausted work ends in `FAILED`.

Debit transactions that exceed the available balance fail without reducing the balance. In-memory synchronization provides single-process coordination only. In production, the ledger effect, balance update, sequence checkpoint, idempotency record, and final transaction status should be committed atomically in a database transaction.

The fault-injection fields are intended for this assessment only and should not be exposed as client-controlled fields in a production API.

## 7. Validation and API error behavior

- Invalid fields, including missing required values, invalid currency format, non-positive sequence, or invalid amount precision, return HTTP 400 with validation details.
- Conflicting reuse of an idempotency key or transaction ID returns HTTP 409.
- A valid request is accepted asynchronously with HTTP 202.
- A business failure such as insufficient funds is represented in the transaction record as `FAILED`; it is not necessarily an HTTP error because processing occurs asynchronously.

## 8. Observability

The API exposes transaction lookup, a list of records, account balance, and a summary endpoint with total records and counts by status. Processing logs and record metadata provide transaction identifiers, attempt counts, status, message, and timestamps. Production operation would additionally require correlation IDs, structured logs, metrics, health checks, alerts, and PII-safe log policies.

## 9. AI-assisted development and validation disclosure

AI assistance (ChatGPT) was used during development to help interpret the assessment, plan the implementation and test scenarios, review code/design trade-offs, and prepare documentation and API examples. The generated suggestions were incorporated into a local project and then checked by running the Maven test suite and manually exercising the API in Postman. The candidate should review this statement for accuracy and be prepared to explain each design decision and limitation; AI-generated output was not treated as a substitute for running and reviewing the implementation.

Validation reported by the candidate on 22 October 2026:

- `mvn clean test`: 7 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.
- Manual Postman checks: exact replay did not apply the transaction twice; conflicting key/payload returned a conflict; insufficient funds failed without reducing the balance; an out-of-order request remained pending until the missing sequence arrived; transient retry succeeded on attempt 3; retry exhaustion ended as `FAILED` after 3 attempts.
- Balance check returned `1800.00` for `acc-101` after the described manual test sequence.

These are local assessment results, not a claim of production readiness or multi-instance correctness.

## 10. Limitations and production-hardening roadmap

1. Replace in-memory maps with a relational database and a durable ledger.
2. Enforce unique constraints for transaction IDs and idempotency keys.
3. Use a database transaction for atomic ledger, balance, sequence, and status changes.
4. Add a durable queue adapter with explicit delivery, acknowledgement, replay, and dead-letter semantics.
5. Add configurable retry/backoff policy, jitter, retry-exhaustion handling, and manual replay.
6. Add concurrent-submission, crash-recovery, persistence-failure, and integration tests.
7. Add authentication/authorization, rate limits, correlation IDs, metrics, health checks, and structured PII-safe logs.
8. Define currency-specific balances and sequence-gap recovery rules.
