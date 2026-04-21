package com.positivity.mcp.internal.config;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Relays the incoming Authorization Bearer token to outbound facade tool
 * requests.
 */
@Component
public class BearerTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest httpRequest = servletAttrs.getRequest();
            String authHeader = httpRequest.getHeader(AUTHORIZATION);
            if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
                String outboundAuthHeader = request.getHeaders().getFirst(AUTHORIZATION);
                if (outboundAuthHeader == null || outboundAuthHeader.startsWith(BEARER_PREFIX)) {
                    request.getHeaders().set(AUTHORIZATION, authHeader);
                } else {
                    return execution.execute(request, body);
                }
            }
        }
        return execution.execute(request, body);
    }
}
