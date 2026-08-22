package com.secureledger.dto;

public record TransferResult(
        TransactionResponse debit,
        TransactionResponse credit,
        boolean replayed
) {}
