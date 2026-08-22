package com.secureledger.dto;

import com.secureledger.entity.AccountType;
import jakarta.validation.constraints.NotNull;

public record CreateAccountRequest(
        @NotNull AccountType accountType
) {}
