package com.positivity.bulkloader.internal.config;

import com.positivity.bulkloader.internal.service.BulkLoadAuthorizationContext;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Puts the operator's credentials on an outbound call to a sibling service.
 *
 * <p>A batch step runs on its own thread with no HTTP request bound to it, so the token cannot
 * simply be read from the current request the way an ordinary controller would. It is captured when
 * the job is launched and read back from {@link BulkLoadAuthorizationContext} here; the request
 * lookups remain as fallbacks for calls that do happen on a request thread.
 *
 * <p>Shared by the ingest writers and by business-key resolution: both act as the operator, and a
 * resolver that lost the token would report every name as unresolvable rather than as forbidden.
 *
 * <p>The job's tenant travels the same way (ADR-0062, plan WS8): the batch runs inside {@code
 * TenantContext.runAs(job tenant)}, and every call carries that tenant as {@code X-Tenant-Id} so
 * the owning service binds it before writing. The sibling is called directly (load-balanced, not
 * through the gateway), so nothing else would stamp the header. An unbound thread sends none and
 * says so at WARN: the sibling then falls back to its own transitional default or refuses with
 * 401, either of which is better than a silent load into the wrong tenant.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthorizationHeaderRelay {

    private static final String BEARER_PREFIX = "Bearer ";

    private final BulkLoadAuthorizationContext bulkLoadAuthorizationContext;

    /**
     * Adds the tenant header from the bound {@link TenantContext}, then the bearer and gateway-token
     * headers, or no credential headers when no credential can be found.
     */
    public void apply(RestClient.RequestHeadersSpec<?> requestSpec) {
        TenantContext.current()
                .ifPresentOrElse(
                        tenantId -> requestSpec.header(TenantHeaders.HTTP_TENANT_ID, tenantId.toString()),
                        () -> log.warn(
                                "Outbound bulk-load call with no tenant bound: {} is not sent (ADR-0062)",
                                TenantHeaders.HTTP_TENANT_ID));
        String authorizationHeader = resolveAuthorizationHeader();
        if (!StringUtils.hasText(authorizationHeader)) {
            return;
        }
        requestSpec.header(HttpHeaders.AUTHORIZATION, authorizationHeader);
        requestSpec.header(GatewaySecurityConstants.HEADER_TOKEN, extractTokenValue(authorizationHeader));
    }

    @Nullable
    private String resolveAuthorizationHeader() {
        String launchAuthorizationHeader = bulkLoadAuthorizationContext.getAuthorizationHeader();
        if (StringUtils.hasText(launchAuthorizationHeader) && launchAuthorizationHeader.startsWith(BEARER_PREFIX)) {
            return launchAuthorizationHeader;
        }

        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes requestAttributes)) {
            return null;
        }

        HttpServletRequest request = requestAttributes.getRequest();
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorizationHeader) && authorizationHeader.startsWith(BEARER_PREFIX)) {
            return authorizationHeader;
        }

        String gatewayTokenHeader = request.getHeader(GatewaySecurityConstants.HEADER_TOKEN);
        if (!StringUtils.hasText(gatewayTokenHeader)) {
            return null;
        }
        return gatewayTokenHeader.startsWith(BEARER_PREFIX) ? gatewayTokenHeader : BEARER_PREFIX + gatewayTokenHeader;
    }

    private String extractTokenValue(String authorizationHeader) {
        return authorizationHeader.startsWith(BEARER_PREFIX)
                ? authorizationHeader.substring(BEARER_PREFIX.length())
                : authorizationHeader;
    }
}
