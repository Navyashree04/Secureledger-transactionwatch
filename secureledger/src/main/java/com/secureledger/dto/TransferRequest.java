package com.secureledger.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TransferRequest(
        @NotNull Long fromAccountId,
        @NotNull Long toAccountId,
        @NotNull
        @DecimalMin(value = "0.01", message = "amount must be positive")
        @DecimalMax(value = "1000000000.00", message = "amount exceeds the allowed maximum")
        @Digits(integer = 15, fraction = 4, message = "amount has too many decimal places")
        BigDecimal amount
) {}
