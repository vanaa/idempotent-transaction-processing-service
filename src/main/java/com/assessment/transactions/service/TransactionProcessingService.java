package com.assessment.transactions.service;

import com.assessment.transactions.model.*;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Lightweight in-memory transaction processor.
 *
 * Requests are registered synchronously for idempotency, then processed by a
 * single background worker. The single worker preserves deterministic ordering
 * in this demo; it is not a substitute for a durable queue or database.
 */
@Service
public class TransactionProcessingService {
    private static final Logger LOG = Logger.getLogger(TransactionProcessingService.class.getName());
    private static final int MAX_ATTEMPTS = 3;

    private final Map<String, TransactionRecord> transactions = new ConcurrentHashMap<String, TransactionRecord>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<String, String>();
    private final Map<String, BigDecimal> balances = new ConcurrentHashMap<String, BigDecimal>();
    private final Map<String, Long> lastSequenceByAccount = new ConcurrentHashMap<String, Long>();
    private final Map<String, AtomicInteger> simulatedFailuresRemaining = new ConcurrentHashMap<String, AtomicInteger>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "transaction-processing-worker");
            thread.setDaemon(true);
            return thread;
        }
    });

    public synchronized TransactionRecord submit(final TransactionRequest request) {
        String existingId = idempotencyIndex.get(request.getIdempotencyKey());
        if (existingId != null) {
            TransactionRecord existing = transactions.get(existingId);
            if (existing != null) {
                if (!sameBusinessPayload(existing, request)) {
                    throw new TransactionConflictException(
                            "idempotencyKey has already been used with a different transaction payload");
                }
                LOG.info("Idempotent replay detected; transactionId=" + existingId);
                return existing;
            }
        }

        TransactionRecord sameTransaction = transactions.get(request.getTransactionId());
        if (sameTransaction != null) {
            if (!sameBusinessPayload(sameTransaction, request)) {
                throw new TransactionConflictException(
                        "transactionId has already been used with different transaction data");
            }
            // Register this additional key too, so its later replay is also idempotent.
            idempotencyIndex.put(request.getIdempotencyKey(), sameTransaction.getTransactionId());
            LOG.info("Duplicate transaction detected; transactionId=" + request.getTransactionId());
            return sameTransaction;
        }

        final TransactionRecord record = new TransactionRecord(request);
        transactions.put(record.getTransactionId(), record);
        idempotencyIndex.put(record.getIdempotencyKey(), record.getTransactionId());

        int failureCount = request.getSimulatedTransientFailureCount();
        if (failureCount == 0 && request.isSimulateTransientFailure()) {
            failureCount = 1;
        }
        if (failureCount > 0) {
            simulatedFailuresRemaining.put(record.getTransactionId(), new AtomicInteger(failureCount));
        }

        worker.execute(new Runnable() {
            public void run() {
                process(record);
            }
        });
        return record;
    }

    private boolean sameBusinessPayload(TransactionRecord existing, TransactionRequest request) {
        return existing.getTransactionId().equals(request.getTransactionId())
                && existing.getAccountId().equals(request.getAccountId())
                && existing.getTransactionType() == request.getTransactionType()
                && existing.getAmount().compareTo(request.getAmount()) == 0
                && existing.getCurrency().equals(request.getCurrency())
                && existing.getSequenceNumber() == request.getSequenceNumber().longValue();
    }

    private void process(TransactionRecord record) {
        Long last = lastSequenceByAccount.get(record.getAccountId());
        long expected = last == null ? 1L : last + 1L;

        if (record.getSequenceNumber() > expected) {
            record.updateStatus(TransactionStatus.PENDING_SEQUENCE, "Waiting for sequence " + expected);
            LOG.info("Out-of-order transaction pending; transactionId=" + record.getTransactionId());
            return;
        }
        if (record.getSequenceNumber() < expected) {
            record.updateStatus(TransactionStatus.FAILED, "Stale sequence number; expected " + expected);
            return;
        }

        record.updateStatus(TransactionStatus.PROCESSING, "Processing started");
        while (record.getAttempts() < MAX_ATTEMPTS) {
            record.incrementAttempts();
            AtomicInteger remaining = simulatedFailuresRemaining.get(record.getTransactionId());
            if (remaining != null && remaining.getAndDecrement() > 0) {
                record.updateStatus(TransactionStatus.RETRY_PENDING, "Simulated transient failure; retrying");
                LOG.warning("Retrying transaction; transactionId=" + record.getTransactionId()
                        + ", attempt=" + record.getAttempts());
                continue;
            }

            BigDecimal current = balances.containsKey(record.getAccountId())
                    ? balances.get(record.getAccountId()) : BigDecimal.ZERO;
            BigDecimal next;
            if (record.getTransactionType() == TransactionType.CREDIT) {
                next = current.add(record.getAmount());
            } else {
                if (current.compareTo(record.getAmount()) < 0) {
                    record.updateStatus(TransactionStatus.FAILED, "Insufficient funds");
                    LOG.warning("Transaction failed; transactionId=" + record.getTransactionId()
                            + ", reason=insufficient_funds");
                    return;
                }
                next = current.subtract(record.getAmount());
            }

            // The worker is single-threaded, so the in-memory effect and sequence
            // checkpoint are applied in one serialized processing step.
            balances.put(record.getAccountId(), next);
            lastSequenceByAccount.put(record.getAccountId(), record.getSequenceNumber());
            record.updateStatus(TransactionStatus.PROCESSED, "Processed successfully");
            simulatedFailuresRemaining.remove(record.getTransactionId());
            LOG.info("Transaction processed; transactionId=" + record.getTransactionId()
                    + ", attempts=" + record.getAttempts());
            processPendingForAccount(record.getAccountId());
            return;
        }

        record.updateStatus(TransactionStatus.FAILED, "Retry limit exceeded");
        simulatedFailuresRemaining.remove(record.getTransactionId());
        LOG.warning("Transaction failed; transactionId=" + record.getTransactionId()
                + ", reason=retry_limit_exceeded");
    }

    private void processPendingForAccount(String accountId) {
        List<TransactionRecord> pending = new ArrayList<TransactionRecord>();
        for (TransactionRecord record : transactions.values()) {
            if (accountId.equals(record.getAccountId())
                    && record.getStatus() == TransactionStatus.PENDING_SEQUENCE) {
                pending.add(record);
            }
        }
        Collections.sort(pending, new Comparator<TransactionRecord>() {
            public int compare(TransactionRecord a, TransactionRecord b) {
                return Long.compare(a.getSequenceNumber(), b.getSequenceNumber());
            }
        });

        boolean progressed = true;
        while (progressed) {
            progressed = false;
            Long last = lastSequenceByAccount.get(accountId);
            long expected = last == null ? 1L : last + 1L;
            for (TransactionRecord record : pending) {
                if (record.getStatus() == TransactionStatus.PENDING_SEQUENCE
                        && record.getSequenceNumber() == expected) {
                    process(record);
                    progressed = true;
                    break;
                }
            }
        }
    }

    public TransactionRecord get(String id) {
        return transactions.get(id);
    }

    public List<TransactionRecord> getAll() {
        return new ArrayList<TransactionRecord>(transactions.values());
    }

    public BigDecimal getBalance(String accountId) {
        BigDecimal balance = balances.get(accountId);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    public ProcessingSummary summary() {
        return new ProcessingSummary(transactions.values());
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdown();
    }
}
