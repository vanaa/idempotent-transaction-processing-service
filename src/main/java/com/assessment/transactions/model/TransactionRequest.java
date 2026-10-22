package com.assessment.transactions.model;

import javax.validation.constraints.*;
import java.math.BigDecimal;

public class TransactionRequest {
    @NotBlank private String transactionId;
    @NotBlank private String accountId;
    @NotNull private TransactionType transactionType;
    @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) private BigDecimal amount;
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") private String currency;
    @NotNull @Positive private Long sequenceNumber;
    @NotBlank private String idempotencyKey;

    // Demo-only fault injection. The boolean preserves the original request contract.
    private boolean simulateTransientFailure;
    @Min(0) @Max(3) private int simulatedTransientFailureCount;

    public String getTransactionId(){return transactionId;} public void setTransactionId(String v){transactionId=v;}
    public String getAccountId(){return accountId;} public void setAccountId(String v){accountId=v;}
    public TransactionType getTransactionType(){return transactionType;} public void setTransactionType(TransactionType v){transactionType=v;}
    public BigDecimal getAmount(){return amount;} public void setAmount(BigDecimal v){amount=v;}
    public String getCurrency(){return currency;} public void setCurrency(String v){currency=v;}
    public Long getSequenceNumber(){return sequenceNumber;} public void setSequenceNumber(Long v){sequenceNumber=v;}
    public String getIdempotencyKey(){return idempotencyKey;} public void setIdempotencyKey(String v){idempotencyKey=v;}
    public boolean isSimulateTransientFailure(){return simulateTransientFailure;} public void setSimulateTransientFailure(boolean v){simulateTransientFailure=v;}
    public int getSimulatedTransientFailureCount(){return simulatedTransientFailureCount;}
    public void setSimulatedTransientFailureCount(int v){simulatedTransientFailureCount=v;}
}
