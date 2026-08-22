package com.secureledger.exception;

/**
 * Thrown only for the edge case where the same idempotency key is reused
 * with a DIFFERENT request payload (amount/accounts). A retried request
 * with an identical payload is NOT an error — it returns the original
 * transaction result. See TransferService.
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {
    public DuplicateIdempotencyKeyException(String message) {
        super(message);
    }
}
