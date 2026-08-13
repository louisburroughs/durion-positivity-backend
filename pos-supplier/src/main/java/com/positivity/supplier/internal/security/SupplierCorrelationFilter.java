package com.positivity.supplier.internal.security;

import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Opens a {@link SupplierCorrelationContext} scope for every inbound request, reusing the caller's
 * {@code X-Correlation-Id} header when present and generating an id otherwise (ADR-0050 §7).
 *
 * <p>This is the join between an operator action and the vendor exchanges it causes: the base client
 * reuses the ambient id on every outbound call, so the {@code supplier_exchange_audit} rows an admin
 * request produces now carry that request's correlation id rather than an uncaused fresh one, and
 * {@code supplier_audit_access} rows record which request read a payload. Before this filter existed,
 * {@code SupplierCorrelationContext.withCorrelationId} had no production caller and every exchange
 * generated its own id.
 *
 * <p>The effective id is echoed on the response — before the chain runs, so it survives a committed
 * response — which is what lets a caller that supplied no header still quote the id when reporting a
 * problem.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so it wraps the security filter chain: a 401/403
 * carries the correlation header too, and the exception handler's own read-or-generate fallback
 * ({@code SupplierExceptionHandler.resolveCorrelationId}) now agrees with the ambient scope instead of
 * potentially minting a second id for the same request.
 *
 * <p><strong>The inbound header is caller-controlled and deliberately not validated here.</strong>
 * Consumers bound it where it matters — {@code ExchangeAuditWriter} and {@code AuditAccessRecorder}
 * truncate to their column width — so an oversized or odd-looking header can never fail an audit
 * insert and delete evidence. Rejecting requests over header shape would make the column the
 * validation boundary, which issue #1264 explicitly rules out.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SupplierCorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String inbound = request.getHeader(SupplierCorrelationContext.CORRELATION_HEADER);
        try (SupplierCorrelationContext.CorrelationScope scope = SupplierCorrelationContext.open(inbound)) {
            response.setHeader(SupplierCorrelationContext.CORRELATION_HEADER, scope.correlationId());
            filterChain.doFilter(request, response);
        }
    }
}
