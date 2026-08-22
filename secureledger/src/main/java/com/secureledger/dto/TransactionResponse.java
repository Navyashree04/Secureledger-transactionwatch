package com.secureledger.dto;

import com.secureledger.entity.Transaction;
import com.secureledger.entity.TransactionStatus;
import com.secureledger.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        Long id,
        Long accountId,
        Long relatedAccountId,
        TransactionType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String idempotencyKey,
        TransactionStatus status,
        Instant createdAt
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(t.getId(), t.getAccountId(), t.getRelatedAccountId(), t.getType(),
                t.getAmount(), t.getBalanceAfter(), t.getIdempotencyKey(), t.getStatus(), t.getCreatedAt());
    }
}
