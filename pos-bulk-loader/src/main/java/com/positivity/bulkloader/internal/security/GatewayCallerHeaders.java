package com.positivity.bulkloader.internal.security;

import com.positivity.security.common.GatewaySecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The headers that carry who the operator is and what they may do, as the gateway wrote them.
 *
 * <h2>Why the bearer token is not enough</h2>
 *
 * <p>pos-bulk-loader calls its siblings directly (load-balanced by service id), not back through the
 * gateway, so nothing on that path turns a JWT into authorities. The sibling authenticates with
 * {@code GatewayAuthoritiesFilter}, which reads {@code X-Perm-Bits} / {@code X-Authorities} /
 * {@code X-Roles} and clears the security context when none of them is present — a bearer token
 * alone leaves the call anonymous, and every protected endpoint answers 401. That is a silent
 * failure for business-key resolution in particular: {@code RestResolutionContext} reports a failed
 * lookup as an absence, so an unauthenticated location or product lookup does not read as
 * "forbidden", it reads as "no such location", and the rows quietly load without their references.
 *
 * <p>So the caller's own gateway headers travel with the call. Nothing is minted or widened here:
 * the loader acts as the operator who launched the job and can reach exactly what that operator
 * can. The tenant header is not in this list — {@code AuthorizationHeaderRelay} stamps it from the
 * bound {@link com.positivity.tenancy.TenantContext}, which is the job's target tenant rather than
 * whatever the request carried.
 */
public final class GatewayCallerHeaders {

    /**
     * Read in this order by the sibling's filter: the perm-bitset pair is the current shape, the
     * authority/role lists the legacy one, the location-scope bitsets ride with the bitset shape
     * (ADR-0061), and {@code X-User} names the principal the sibling records as the actor.
     */
    public static final List<String> RELAYED = List.of(
            GatewaySecurityConstants.HEADER_PERM_BITS,
            GatewaySecurityConstants.HEADER_PERM_VER,
            GatewaySecurityConstants.HEADER_AUTHORITIES,
            GatewaySecurityConstants.HEADER_ROLES,
            GatewaySecurityConstants.HEADER_LOC_SCOPE,
            GatewaySecurityConstants.HEADER_LOC_FIN_BITS,
            GatewaySecurityConstants.HEADER_LOC_OTH_BITS,
            GatewaySecurityConstants.HEADER_USER);

    private GatewayCallerHeaders() {
        // Utility class - prevent instantiation
    }

    /**
     * The relayed headers of the request bound to this thread, or an empty map when no request is
     * bound (a batch thread, or a call made outside an HTTP request altogether).
     */
    public static Map<String, String> fromCurrentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Map.of();
        }
        return from(attributes.getRequest());
    }

    /** The relayed headers present on {@code request}; absent and blank headers are left out. */
    public static Map<String, String> from(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : RELAYED) {
            String value = request.getHeader(name);
            if (StringUtils.hasText(value)) {
                headers.put(name, value);
            }
        }
        return Map.copyOf(headers);
    }
}
