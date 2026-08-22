package com.transactionwatch.api;

import com.transactionwatch.alert.Alert;
import com.transactionwatch.alert.AlertStatus;
import com.transactionwatch.rules.Severity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AlertResponse(
        UUID id,
        String accountId,
        String transactionId,
        String ruleName,
        String reason,
        Severity severity,
        BigDecimal amount,
        AlertStatus status,
        Instant createdAt
) {
    public static AlertResponse from(Alert alert) {
        return new AlertResponse(alert.getId(), alert.getAccountId(), alert.getTransactionId(),
                alert.getRuleName(), alert.getReason(), alert.getSeverity(), alert.getAmount(),
                alert.getStatus(), alert.getCreatedAt());
    }
}
