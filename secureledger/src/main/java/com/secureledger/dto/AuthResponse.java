package com.secureledger.dto;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresInMs,
        String username,
        String role
) {
    public static AuthResponse of(String token, long expiresInMs, String username, String role) {
        return new AuthResponse(token, "Bearer", expiresInMs, username, role);
    }
}
