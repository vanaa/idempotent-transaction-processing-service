# Sample API Requests and Responses

Base URL: `http://localhost:8080`

**Important:** `POST /api/transactions` returns HTTP 202 when a request is accepted. Processing is asynchronous, so the status returned by the POST may be `RECEIVED` or another intermediate state. Poll `GET /api/transactions/{transactionId}` for the final outcome. Timestamps and intermediate statuses vary by run.

The examples below combine representative API shapes with the candidate's observed local Postman results. IDs are demo values; use a fresh account and unique keys when replaying examples. Because state is in memory, restart the application to reset the demo state.

## 1. Successful credit — request

```http
POST /api/transactions
Content-Type: application/json
```

```json
{
  "transactionId": "tx-101",
  "accountId": "acc-101",
  "transactionType": "CREDIT",
  "amount": 1000.00,
  "currency": "INR",
  "sequenceNumber": 1,
  "idempotencyKey": "idem-101"
}
```

Representative final transaction response (HTTP 200 from the GET lookup; timestamps are illustrative):

```json
{
  "transactionId": "tx-101",
  "accountId": "acc-101",
  "idempotencyKey": "idem-101",
  "currency": "INR",
  "transactionType": "CREDIT",
  "amount": 1000.00,
  "sequenceNumber": 1,
  "status": "PROCESSED",
  "attempts": 1,
  "message": "Processed successfully",
  "createdAt": "<server-generated timestamp>",
  "updatedAt": "<server-generated timestamp>"
}
```

## 2. Idempotent replay

Submitting the exact same request again returns the original record. The balance must not be increased a second time. In the observed test, `tx-101` remained `PROCESSED` with `attempts: 1` and its original timestamps.

## 3. Conflicting idempotency key — response

Reusing `idem-101` with a different transaction payload returns HTTP 409 Conflict:

```json
{
  "error": "Transaction conflict",
  "message": "idempotencyKey has already been used with a different transaction payload"
}
```

## 4. Insufficient funds — final transaction response

A debit request for ₹1,500 against a balance of ₹1,000 was accepted asynchronously, then finished as `FAILED`. The account balance was not reduced:

```json
{
  "transactionId": "tx-103",
  "accountId": "acc-101",
  "idempotencyKey": "idem-103",
  "currency": "INR",
  "transactionType": "DEBIT",
  "amount": 1500.00,
  "sequenceNumber": 2,
  "status": "FAILED",
  "attempts": 1,
  "message": "Insufficient funds",
  "createdAt": "<server-generated timestamp>",
  "updatedAt": "<server-generated timestamp>"
}
```

## 5. Out-of-order processing

The sequence-3 request was submitted before sequence 2 and initially returned `PENDING_SEQUENCE` with message `Waiting for sequence 2`. After sequence 2 was accepted and processed, both sequence 2 and sequence 3 reached `PROCESSED` in order. The observed account balance after credits of ₹1,000, ₹200, and ₹500 was ₹1,700.00.

## 6. Retry succeeds

A credit request with `simulatedTransientFailureCount: 2` ultimately reached `PROCESSED`, with `attempts: 3` and message `Processed successfully`. The balance increased once by ₹100.

## 7. Retry exhaustion

A request with `simulatedTransientFailureCount: 3` ultimately returned this observed final state:

```json
{
  "transactionId": "tx-107",
  "accountId": "acc-101",
  "idempotencyKey": "idem-107",
  "currency": "INR",
  "transactionType": "CREDIT",
  "amount": 50.00,
  "sequenceNumber": 5,
  "status": "FAILED",
  "attempts": 3,
  "message": "Retry limit exceeded",
  "createdAt": "2026-10-22T17:29:38.651553800Z",
  "updatedAt": "2026-10-22T17:29:38.668581700Z"
}
```

The account balance remained `1800.00` because this failed ₹50 credit did not apply.

## 8. Balance endpoint — observed response

```json
{
  "accountId": "acc-101",
  "balance": 1800.00
}
```

## 9. Operations summary — observed response

```json
{
  "total": 6,
  "countsByStatus": {
    "RECEIVED": 0,
    "PROCESSING": 0,
    "PROCESSED": 4,
    "DUPLICATE": 0,
    "FAILED": 2,
    "RETRY_PENDING": 0,
    "PENDING_SEQUENCE": 0
  }
}
```

The summary counts only the six transactions registered in that application run. A conflicting request rejected with HTTP 409 is not registered as a new transaction.

## 10. Validation error

A negative amount is rejected with HTTP 400. The response shape is:

```json
{
  "error": "Validation failed",
  "details": [
    "amount: must be greater than or equal to 0.01"
  ]
}
```

Exact validation wording depends on the validation provider and failed fields.
