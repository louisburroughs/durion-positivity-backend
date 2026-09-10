package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.TenantResolver;
import com.positivity.tenancy.replica.TenantProjectionEvent;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/**
 * Resolves the tenant a login belongs to before the user is looked up (ADR-0062 §3, plan WS2b), in
 * order: the gateway-supplied {@code X-Tenant-Slug} (derived from the {@code Host} header), the
 * {@code tenantSlug} on the login body, then the tenant already bound to the request or the
 * transitional default. A slug that the {@code ext_tenant} replica does not know, or whose tenant
 * is not {@code ACTIVE}, answers the same {@link BadCredentialsException} as a wrong password, so a
 * caller cannot enumerate tenants through the login route.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginTenantResolver {

    static final String INVALID_CREDENTIALS = "Invalid credentials";

    private final ExtTenantRepository extTenantRepository;
    private final TenantResolver tenantResolver;

    /**
     * @param headerSlug the gateway's {@code X-Tenant-Slug}, if any
     * @param bodySlug the login body's {@code tenantSlug}, if any
     * @return the tenant to bind for the login
     * @throws BadCredentialsException when the slug is unknown or inactive, or when no slug is
     *     given and nothing else resolves a tenant
     */
    public @NonNull UUID resolve(@Nullable String headerSlug, @Nullable String bodySlug) {
        String slug = firstNonBlank(headerSlug, bodySlug);
        if (slug != null) {
            Optional<ExtTenant> tenant = extTenantRepository.findBySlug(slug);
            if (tenant.isEmpty()) {
                log.warn("Login refused: unknown tenant slug={}", slug);
                throw new BadCredentialsException(INVALID_CREDENTIALS);
            }
            if (!TenantProjectionEvent.STATUS_ACTIVE.equals(tenant.get().getStatus())) {
                log.warn(
                        "Login refused: tenant slug={} is {}",
                        slug,
                        tenant.get().getStatus());
                throw new BadCredentialsException(INVALID_CREDENTIALS);
            }
            return tenant.get().getTenantId();
        }
        return tenantResolver.resolve().orElseThrow(() -> {
            log.warn("Login refused: no tenant slug and no default tenant");
            return new BadCredentialsException(INVALID_CREDENTIALS);
        });
    }

    private static @Nullable String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }
}
