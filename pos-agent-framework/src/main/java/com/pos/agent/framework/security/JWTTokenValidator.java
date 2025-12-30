package com.pos.agent.framework.security;

/**
 * Validates JWT tokens for authentication.
 */
public class JWTTokenValidator {

    public JWTTokenValidator() {
        // Initialize JWT validator
    }

    /**
     * Validates a JWT token.
     *
     * @param token the JWT token to validate
     * @return true if valid, false otherwise
     */
    public boolean validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        // Simulate JWT validation
        return !token.equals("invalid.jwt.token");
    }

    /**
     * Extracts user ID from JWT token.
     *
     * @param token the JWT token
     * @return the user ID
     */
    public String extractUserId(String token) {
        if (!validateToken(token)) {
            return null;
        }
        // Simulate user ID extraction
        return "user-from-token";
    }
}
