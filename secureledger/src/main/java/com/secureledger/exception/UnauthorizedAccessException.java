package com.secureledger.exception;

/** Thrown when a CUSTOMER attempts to reach another user's account. */
public class UnauthorizedAccessException extends RuntimeException {
    public UnauthorizedAccessException(String message) {
        super(message);
    }
}
