package com.assessment.transactions.service;

/** Signals reuse of a transaction identifier or idempotency key with conflicting data. */
public class TransactionConflictException extends RuntimeException {
    public TransactionConflictException(String message) {
        super(message);
    }
}
