package com.transactionwatch.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event contract v1 — mirrors SecureLedger's com.secureledger.event.TransactionCreatedEvent
 * exactly. Deliberately duplicated rather than shared via a library dependency
 * (per project scope: a small POJO/record duplicated across two portfolio
 * services is a reasonable tradeoff against the coupling/versioning overhead
 * of a shared internal artifact). If this shape needs to change in a
 * backward-incompatible way, the convention is a new topic/version
 * ("transactions.created.v2"), not a silent field change here.
 */
public record TransactionCreatedEvent(
        String eventId,
        String transactionId,
        String accountId,
        BigDecimal amount,
        String currency,
        String direction, // DEBIT or CREDIT
        Instant occurredAt,
        String sourceService
) {}
