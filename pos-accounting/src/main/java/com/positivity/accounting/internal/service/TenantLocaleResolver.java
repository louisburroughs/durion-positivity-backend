package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtTenant;
import com.positivity.accounting.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.TenantResolver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * The tenant's locale (CAP:550 S44, #2618), from the {@code ext_tenant} replica of {@code tenant.events.v1}. S26's
 * extraction {@code localeHint} derives from it. Locale is not money and drives only language, so nothing fails
 * closed: a tenant with no known locale gets {@link #DEFAULT_LOCALE} (ADR-0030 §2).
 *
 * <p>Reads use the bound tenant's connection (row-level security), so a tenant other than the bound one reads as
 * unknown; callers pass the bound tenant. Nothing is cached, so a change is visible on every instance at once.
 */
@Service
@RequiredArgsConstructor
public class TenantLocaleResolver {

    /** ADR-0030 §2: the locale of a tenant that has none recorded. */
    public static final String DEFAULT_LOCALE = "en-US";

    private final ExtTenantRepository tenants;
    private final TenantResolver tenantResolver;

    /** The stored locale of {@code tenantId}, or {@link #DEFAULT_LOCALE}. */
    public @NonNull String localeOf(@NonNull UUID tenantId) {
        return tenants.findById(tenantId)
                .map(ExtTenant::getLocale)
                .filter(locale -> !locale.isBlank())
                .orElse(DEFAULT_LOCALE);
    }

    /** The bound tenant's locale. */
    public @NonNull String localeOfCurrentTenant() {
        return localeOf(tenantResolver.require());
    }
}
