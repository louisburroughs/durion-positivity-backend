package com.positivity.bulkloader.internal.service;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The launching operator's credentials, held for the length of one batch run.
 *
 * <p>Two parts, both captured at launch and both needed downstream: the bearer token, which is what
 * a sibling resolves a user id from, and the gateway's authentication headers, which are what a
 * sibling authenticates and authorizes with ({@code GatewayCallerHeaders}). A relay that carried
 * only the token would leave every direct sibling call anonymous.
 */
@Component
public class BulkLoadAuthorizationContext {

    private final ThreadLocal<String> authorizationHeaderHolder = new ThreadLocal<>();
    private final ThreadLocal<Map<String, String>> gatewayHeadersHolder = new ThreadLocal<>();

    public void setAuthorizationHeader(@Nullable String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            authorizationHeaderHolder.remove();
            return;
        }
        authorizationHeaderHolder.set(authorizationHeader);
    }

    public @Nullable String getAuthorizationHeader() {
        return authorizationHeaderHolder.get();
    }

    /** @param gatewayHeaders the caller's gateway authentication headers, or empty/null for none */
    public void setGatewayHeaders(@Nullable Map<String, String> gatewayHeaders) {
        if (gatewayHeaders == null || gatewayHeaders.isEmpty()) {
            gatewayHeadersHolder.remove();
            return;
        }
        gatewayHeadersHolder.set(Map.copyOf(gatewayHeaders));
    }

    /** The captured gateway headers, empty when the launch carried none. */
    public Map<String, String> getGatewayHeaders() {
        Map<String, String> headers = gatewayHeadersHolder.get();
        return headers == null ? Map.of() : headers;
    }

    public void clear() {
        authorizationHeaderHolder.remove();
        gatewayHeadersHolder.remove();
    }
}
