package com.assessment.transactions.model;

import java.math.BigDecimal;
import java.time.Instant;

public class TransactionRecord {
    private final String transactionId, accountId, idempotencyKey, currency;
    private final TransactionType transactionType;
    private final BigDecimal amount;
    private final long sequenceNumber;
    private volatile TransactionStatus status;
    private volatile int attempts;
    private volatile String message;
    private final Instant createdAt;
    private volatile Instant updatedAt;

    public TransactionRecord(TransactionRequest r) {
        transactionId=r.getTransactionId(); accountId=r.getAccountId(); idempotencyKey=r.getIdempotencyKey();
        currency=r.getCurrency(); transactionType=r.getTransactionType(); amount=r.getAmount();
        sequenceNumber=r.getSequenceNumber(); status=TransactionStatus.RECEIVED; attempts=0;
        message="Accepted"; createdAt=Instant.now(); updatedAt=createdAt;
    }
    public void updateStatus(TransactionStatus s, String m) { status=s; message=m; updatedAt=Instant.now(); }
    public void incrementAttempts(){attempts++; updatedAt=Instant.now();}
    public String getTransactionId(){return transactionId;} public String getAccountId(){return accountId;}
    public String getIdempotencyKey(){return idempotencyKey;} public String getCurrency(){return currency;}
    public TransactionType getTransactionType(){return transactionType;} public BigDecimal getAmount(){return amount;}
    public long getSequenceNumber(){return sequenceNumber;} public TransactionStatus getStatus(){return status;}
    public int getAttempts(){return attempts;} public String getMessage(){return message;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
