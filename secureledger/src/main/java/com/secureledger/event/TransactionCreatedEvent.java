package com.secureledger.event;

import com.secureledger.entity.Transaction;
import com.secureledger.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event contract v1 — published to Kafka topic "transactions.created" on every
 * completed transaction (deposit, withdrawal, or either leg of a transfer).
 * Consumed by TransactionWatch (Project 2). Any breaking change to this shape
 * must bump the topic/contract version (e.g. "transactions.created.v2") rather
 * than silently changing v1's fields, since TransactionWatch deserializes by
 * this exact shape.
 *
 * NOTE on accountId/transactionId: SecureLedger's internal identifiers are
 * numeric (Long) rather than UUIDs. This event stringifies them rather than
 * generating parallel UUID identifiers, which is a simplification worth
 * flagging — a real cross-service contract would give accounts/transactions
 * stable external UUIDs independent of any one service's primary key scheme.
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
) {
    public static TransactionCreatedEvent from(Transaction tx, String currency) {
        String direction = (tx.getType() == TransactionType.WITHDRAWAL || tx.getType() == TransactionType.TRANSFER_OUT)
                ? "DEBIT" : "CREDIT";
        return new TransactionCreatedEvent(
                UUID.randomUUID().toString(),
                String.valueOf(tx.getId()),
                String.valueOf(tx.getAccountId()),
                tx.getAmount(),
                currency,
                direction,
                tx.getCreatedAt(),
                "secureledger"
        );
    }
}
