package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.config.TenantSearchProperties;
import com.positivity.securityservice.internal.dto.TenantSearchResponse;
import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.replica.TenantDisplayName;
import com.positivity.tenancy.replica.TenantProjectionEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the organizations a login form may offer, out of the {@code ext_tenant} replica (ADR-0062
 * §3). Anonymous: it runs before any tenant is bound, because choosing the organization is what
 * binds one.
 *
 * <p>It is therefore a tenant-enumeration surface, deliberately and unavoidably — there is no
 * version of "pick your organization from a list" in which the list is secret. What is controlled
 * is how much a caller learns per query: a minimum query length, prefix-only matching, a result
 * cap, {@code ACTIVE} tenants only, and a response carrying nothing beyond the name and the slug.
 * No total is returned, since a total would say how much is left to enumerate.
 *
 * <p>Login itself is unchanged: it still answers one indistinguishable 401 for a wrong password, an
 * unknown tenant and an inactive one, so nothing here helps confirm a credential.
 */
@Service
@RequiredArgsConstructor
public class TenantSearchService {

    private final ExtTenantRepository extTenantRepository;
    private final TenantSearchProperties properties;

    /** Whether the endpoint is switched on at all; off answers 404 rather than an empty list. */
    public boolean isEnabled() {
        return properties.enabled();
    }

    /**
     * Organizations whose name starts with {@code query}, or one of whose words does.
     *
     * @param query raw user input, normalized here the same way the stored key was
     * @return at most {@link TenantSearchProperties#maxResults} matches; empty when the query is
     *     too short, which is the normal state while someone is still typing
     */
    @Transactional(readOnly = true)
    public @NonNull List<TenantSearchResponse> search(@Nullable String query) {
        if (query == null) {
            return List.of();
        }
        String prefix = TenantDisplayName.normalize(query);
        if (prefix.length() < properties.minQueryLength()) {
            return List.of();
        }
        return extTenantRepository
                .searchByDisplayNamePrefix(
                        escapeLike(prefix),
                        TenantProjectionEvent.STATUS_ACTIVE,
                        PageRequest.of(0, properties.maxResults()))
                .stream()
                .map(TenantSearchService::toResponse)
                .toList();
    }

    /**
     * Neutralizes the {@code LIKE} wildcards, so a query of {@code "%"} matches the literal
     * character rather than every organization in the registry. Without this the minimum length and
     * the prefix anchoring would both be bypassable in one request.
     *
     * <p>Paired with {@code ESCAPE '\'} in the query; the backslash is escaped first so an escape
     * character in the input cannot smuggle a wildcard past the others.
     */
    static @NonNull String escapeLike(@NonNull String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static TenantSearchResponse toResponse(ExtTenant tenant) {
        return new TenantSearchResponse(tenant.getSlug(), tenant.getDisplayName());
    }
}
