package com.assessment.transactions.service;

import com.assessment.transactions.model.TransactionRecord;
import com.assessment.transactions.model.TransactionRequest;
import com.assessment.transactions.model.TransactionStatus;
import com.assessment.transactions.model.TransactionType;
import org.junit.jupiter.api.Test;

import javax.validation.Validation;
import javax.validation.Validator;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TransactionProcessingServiceTest {
    private TransactionRequest request(String id, long sequence, TransactionType type, String amount, String key) {
        TransactionRequest request = new TransactionRequest();
        request.setTransactionId(id);
        request.setAccountId("acct-1");
        request.setTransactionType(type);
        request.setAmount(new BigDecimal(amount));
        request.setCurrency("USD");
        request.setSequenceNumber(sequence);
        request.setIdempotencyKey(key);
        return request;
    }

    private void awaitStatus(TransactionRecord record, TransactionStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (record.getStatus() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(expected, record.getStatus(), "Timed out waiting for transaction status");
    }

    @Test
    void processesCreditAndExactReplayOnlyOnce() throws Exception {
        TransactionProcessingService service = new TransactionProcessingService();
        TransactionRequest request = request("tx-1", 1, TransactionType.CREDIT, "100.00", "key-1");

        TransactionRecord first = service.submit(request);
        TransactionRecord replay = service.submit(request("tx-1", 1, TransactionType.CREDIT, "100.00", "key-1"));
        awaitStatus(first, TransactionStatus.PROCESSED);

        assertSame(first, replay);
        assertEquals(1, service.getAll().size());
        assertEquals(0, new BigDecimal("100.00").compareTo(service.getBalance("acct-1")));
        service.shutdown();
    }

    @Test
    void rejectsIdempotencyKeyReusedWithDifferentPayload() throws Exception {
        TransactionProcessingService service = new TransactionProcessingService();
        TransactionRecord original = service.submit(request("tx-1", 1, TransactionType.CREDIT, "100.00", "key-1"));
        awaitStatus(original, TransactionStatus.PROCESSED);

        TransactionConflictException exception = assertThrows(TransactionConflictException.class,
                () -> service.submit(request("tx-2", 1, TransactionType.CREDIT, "500.00", "key-1")));

        assertTrue(exception.getMessage().contains("idempotencyKey"));
        assertEquals(1, service.getAll().size());
        assertEquals(0, new BigDecimal("100.00").compareTo(service.getBalance("acct-1")));
        service.shutdown();
    }

    @Test
    void buffersOutOfOrderThenProcessesWhenGapArrives() throws Exception {
        TransactionProcessingService service = new TransactionProcessingService();
        TransactionRecord second = service.submit(request("tx-2", 2, TransactionType.CREDIT, "25.00", "key-2"));
        awaitStatus(second, TransactionStatus.PENDING_SEQUENCE);

        TransactionRecord first = service.submit(request("tx-1", 1, TransactionType.CREDIT, "10.00", "key-1"));
        awaitStatus(first, TransactionStatus.PROCESSED);
        awaitStatus(second, TransactionStatus.PROCESSED);

        assertEquals(0, new BigDecimal("35.00").compareTo(service.getBalance("acct-1")));
        service.shutdown();
    }

    @Test
    void retriesTransientFailureWithoutDuplicateEffect() throws Exception {
        TransactionProcessingService service = new TransactionProcessingService();
        TransactionRequest request = request("tx-1", 1, TransactionType.CREDIT, "10.00", "key-1");
        request.setSimulateTransientFailure(true);

        TransactionRecord result = service.submit(request);
        awaitStatus(result, TransactionStatus.PROCESSED);

        assertEquals(2, result.getAttempts());
        assertEquals(0, new BigDecimal("10.00").compareTo(service.getBalance("acct-1")));
        service.shutdown();
    }

    @Test
    void marksTransactionFailedAfterRetryExhaustion() throws Exception {
        TransactionProcessingService service = new TransactionProcessingService();
        TransactionRequest request = request("tx-retry-exhausted", 1, TransactionType.CREDIT, "10.00", "key-retry-exhausted");
        request.setSimulatedTransientFailureCount(3);

        TransactionRecord result = service.submit(request);
        awaitStatus(result, TransactionStatus.FAILED);

        assertEquals(3, result.getAttempts());
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getBalance("acct-1")));
        service.shutdown();
    }

    @Test
    void beanValidationRejectsZeroOrNegativeAmount() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        TransactionRequest zero = request("tx-zero", 1, TransactionType.CREDIT, "0.00", "key-zero");
        TransactionRequest negative = request("tx-negative", 1, TransactionType.CREDIT, "-1.00", "key-negative");

        assertFalse(validator.validate(zero).isEmpty());
        assertFalse(validator.validate(negative).isEmpty());
    }

    @Test
    void debitCannotOverdraw() throws Exception {
        TransactionProcessingService service = new TransactionProcessingService();
        TransactionRecord result = service.submit(request("tx-1", 1, TransactionType.DEBIT, "1.00", "key-1"));

        awaitStatus(result, TransactionStatus.FAILED);
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getBalance("acct-1")));
        service.shutdown();
    }
}
