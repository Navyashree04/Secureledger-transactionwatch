package com.secureledger.dto;

import com.secureledger.entity.Account;
import com.secureledger.entity.AccountStatus;
import com.secureledger.entity.AccountType;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        Long id,
        String accountNumber,
        AccountType accountType,
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt
) {
    public static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), a.getAccountNumber(), a.getAccountType(),
                a.getBalance(), a.getStatus(), a.getCreatedAt());
    }
}
