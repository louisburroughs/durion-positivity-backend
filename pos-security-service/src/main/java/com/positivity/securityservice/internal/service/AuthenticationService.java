package com.positivity.securityservice.internal.service;

// Note: internal.dto types are referenced here by module convention — all service interfaces
// in pos-security-service use internal.dto as the API contract type. Moving DTOs to a shared
// package is deferred as a separate structural decision (no ADR yet).
import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public service interface for user authentication.
 *
 * Accepts user-supplied credentials and returns a token pair on success.
 * Authentication is delegated to Spring Security's AuthenticationManager;
 * this interface does not perform raw password comparison.
 *
 * @since 1.0
 */
public interface AuthenticationService {

    /**
     * Authenticates a user and issues a JWT access + refresh token pair.
     *
     * @param request login credentials (username + password)
     * @return a {@link TokenPairResponse} containing access and refresh tokens
     * @throws org.springframework.security.core.AuthenticationException if
     *                                                                   credentials
     *                                                                   are invalid
     */
    @NonNull
    TokenPairResponse login(@NonNull LoginRequest request);

    /**
     * Login with the tenant resolved first (ADR-0062 §3): from {@code tenantSlugHeader} (the
     * gateway's {@code X-Tenant-Slug}), else the request's {@code tenantSlug}, else the bound or
     * transitional default tenant. The user lookup, lockout bookkeeping and token issue all run
     * under that tenant.
     */
    @NonNull
    TokenPairResponse login(@NonNull LoginRequest request, @Nullable String tenantSlugHeader);
}
