package com.pos.agent.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Security validation details for a request (Singleton pattern).
 * Provides both validation logic and per-request security details.
 * Implements caching for JWT token decoding to improve performance.
 */
public class SecurityValidation {
    private static final SecurityValidation INSTANCE = new SecurityValidation();
    private static final int MAX_CACHE_SIZE = 1000;

    private final boolean tls13Compliant;
    private final String tlsVersion;
    private final EncryptionDetails encryptionDetails;

    // Cache for decoded JWT tokens to avoid repeated decoding
    private final Map<String, JwtTokenUtil.SecurityPayload> tokenCache = new ConcurrentHashMap<>();

    /**
     * Private constructor for singleton pattern.
     */
    private SecurityValidation() {
        this.tls13Compliant = true;
        this.tlsVersion = "TLSv1.3";
        this.encryptionDetails = new EncryptionDetails();
    }

    /**
     * Gets the singleton instance of SecurityValidation.
     * 
     * @return the singleton instance
     */
    public static SecurityValidation getInstance() {
        return INSTANCE;
    }

    /**
     * Decodes a JWT token with caching to avoid repeated decoding.
     * 
     * @param jwtToken the JWT token to decode
     * @return the decoded security payload
     */
    private JwtTokenUtil.SecurityPayload decodeWithCache(String jwtToken) {
        if (jwtToken == null || jwtToken.isBlank()) {
            return null;
        }

        // Check cache first
        JwtTokenUtil.SecurityPayload cached = tokenCache.get(jwtToken);
        if (cached != null) {
            return cached;
        }

        // Decode and cache
        try {
            JwtTokenUtil.SecurityPayload payload = JwtTokenUtil.decode(jwtToken);

            // Evict oldest entry if cache is full (simple LRU-like behavior)
            if (tokenCache.size() >= MAX_CACHE_SIZE) {
                String firstKey = tokenCache.keySet().iterator().next();
                tokenCache.remove(firstKey);
            }

            tokenCache.put(jwtToken, payload);
            return payload;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Clears the JWT token cache. Useful for testing or periodic cleanup.
     */
    public void clearCache() {
        tokenCache.clear();
    }

    public ServiceAuthentication getServiceAuthentication(SecurityContext securityContext) {
        if (securityContext == null) {
            throw new IllegalArgumentException("SecurityContext is required for service authentication");
        }

        String jwtToken = securityContext.getJwtToken();
        if (jwtToken == null || jwtToken.isBlank()) {
            throw new IllegalArgumentException("JWT token is required for service authentication");
        }

        JwtTokenUtil.SecurityPayload payload = decodeWithCache(jwtToken);
        if (payload == null) {
            throw new IllegalArgumentException("Failed to decode JWT token for service authentication");
        }

        String serviceId = payload.serviceId();
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("ServiceId is required in JWT token for service authentication");
        }

        return new ServiceAuthentication(serviceId);
    }

    public boolean isTLS13Compliant() {
        return tls13Compliant;
    }

    public String getTLSVersion() {
        return tlsVersion;
    }

    public EncryptionDetails getEncryptionDetails() {
        return encryptionDetails;
    }

    /**
     * Validates the security context by checking JWT token presence and validity.
     * Decodes the token and verifies all required fields are present.
     * 
     * @param securityContext the security context to validate
     * @return true if the security context is valid, false otherwise
     */
    public boolean validateSecurityContext(SecurityContext securityContext) {
        if (securityContext == null) {
            return false;
        }
        if (securityContext.getJwtToken() == null || securityContext.getJwtToken().isEmpty()) {
            return false;
        }

        // Decode JWT token and validate all fields
        JwtTokenUtil.SecurityPayload payload = decodeWithCache(securityContext.getJwtToken());
        if (payload == null) {
            return false; // Decode failure
        }

        // Validate userId is present
        if (payload.userId() == null || payload.userId().isBlank()) {
            return false;
        }

        // Validate roles list is not empty
        if (payload.roles() == null || payload.roles().isEmpty()) {
            return false;
        }

        // Validate permissions list is not empty
        if (payload.permissions() == null || payload.permissions().isEmpty()) {
            return false;
        }

        // Validate serviceId is present
        if (payload.serviceId() == null || payload.serviceId().isBlank()) {
            return false;
        }

        // Validate serviceType is present
        if (payload.serviceType() == null || payload.serviceType().isBlank()) {
            return false;
        }

        return true;
    }

    /**
     * Validates authorization for an agent request based on the agent's declared
     * requirements.
     * Checks if the security context contains any of the required roles or
     * permissions.
     * 
     * @param request the agent request to validate
     * @param agent   the agent being accessed
     * @return true if the request is authorized, false otherwise
     */
    public boolean validateAuthorization(AgentRequest request, Agent agent) {
        SecurityContext securityContext = request.getSecurityContext();
        if (securityContext == null) {
            return true; // No security context means no authorization required
        }

        if (agent == null) {
            return true; // No agent means no specific requirements
        }

        // Check if user has any of the required roles
        List<String> requiredRoles = agent.getRequiredRoles();
        if (requiredRoles != null && !requiredRoles.isEmpty()) {
            for (String role : requiredRoles) {
                if (hasRole(securityContext, role)) {
                    return true;
                }
            }
        }

        // Check if user has any of the required permissions
        List<String> requiredPermissions = agent.getRequiredPermissions();
        if (requiredPermissions != null && !requiredPermissions.isEmpty()) {
            for (String permission : requiredPermissions) {
                if (hasPermission(securityContext, permission)) {
                    return true;
                }
            }
        }

        // If no requirements specified, default to allow
        if ((requiredRoles == null || requiredRoles.isEmpty()) &&
                (requiredPermissions == null || requiredPermissions.isEmpty())) {
            return true;
        }

        // User doesn't have any required roles or permissions
        return false;
    }

    /**
     * Checks if the security context has a specific role.
     * Decodes the JWT token to verify the role.
     * 
     * @param securityContext the security context
     * @param role            the role to check
     * @return true if the role is present, false otherwise
     */
    public boolean hasRole(SecurityContext securityContext, String role) {
        if (securityContext == null || securityContext.getJwtToken() == null
                || securityContext.getJwtToken().isBlank()) {
            return false;
        }

        JwtTokenUtil.SecurityPayload payload = decodeWithCache(securityContext.getJwtToken());
        if (payload == null) {
            return false;
        }

        return payload.roles() != null &&
                payload.roles().stream()
                        .anyMatch(r -> r.equalsIgnoreCase(role));
    }

    /**
     * Checks if the security context has a specific permission.
     * Decodes the JWT token to verify the permission.
     * 
     * @param securityContext the security context
     * @param permission      the permission to check
     * @return true if the permission is present, false otherwise
     */
    public boolean hasPermission(SecurityContext securityContext, String permission) {
        if (securityContext == null || securityContext.getJwtToken() == null
                || securityContext.getJwtToken().isBlank()) {
            return false;
        }

        JwtTokenUtil.SecurityPayload payload = decodeWithCache(securityContext.getJwtToken());
        if (payload == null) {
            return false;
        }

        return payload.permissions() != null &&
                payload.permissions().stream()
                        .anyMatch(p -> p.equalsIgnoreCase(permission));
    }

    /**
     * Extracts the user ID from the security context by decoding the JWT token.
     * 
     * @param request the agent request containing security context
     * @return the user ID from the token, or "unknown" if not available
     */
    public String extractUserId(AgentRequest request) {
        if (request == null || request.getSecurityContext() == null) {
            return "unknown";
        }

        SecurityContext securityContext = request.getSecurityContext();
        String jwtToken = securityContext.getJwtToken();

        if (jwtToken == null || jwtToken.isBlank()) {
            return "unknown";
        }

        JwtTokenUtil.SecurityPayload payload = decodeWithCache(jwtToken);
        if (payload == null) {
            return "unknown";
        }

        String userId = payload.userId();
        return (userId != null && !userId.isBlank()) ? userId : "unknown";
    }

    /**
     * Encryption details for security validation.
     */
    public static class EncryptionDetails {
        private final String algorithm;
        private final int keySize;

        public EncryptionDetails() {
            this.algorithm = "AES-256-GCM";
            this.keySize = 256;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public int getKeySize() {
            return keySize;
        }
    }

    /**
     * Service authentication details.
     */
    public static class ServiceAuthentication {
        private final String serviceId;

        public ServiceAuthentication(String serviceId) {
            this.serviceId = serviceId;
        }

        public String getServiceId() {
            return serviceId;
        }
    }
}