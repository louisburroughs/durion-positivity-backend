package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("TenantBindingFilter")
class TenantBindingFilterTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-00000000000a");

    private final TenantBindingFilter filter = new TenantBindingFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    private static MockHttpServletRequest requestWith(String tenantHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (tenantHeader != null) {
            request.addHeader(GatewaySecurityConstants.HEADER_TENANT_ID, tenantHeader);
        }
        return request;
    }

    @Test
    @DisplayName("binds the header for the duration of the chain")
    void bindsForTheChain() throws ServletException, IOException {
        UUID[] seen = new UUID[1];
        FilterChain chain = (rq, rs) -> seen[0] = TenantContext.current().orElse(null);

        filter.doFilter(requestWith(TENANT.toString()), response, chain);

        assertThat(seen[0]).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("clears the binding afterwards, so the pooled thread carries nothing")
    void clearsAfterwards() throws ServletException, IOException {
        filter.doFilter(requestWith(TENANT.toString()), response, (rq, rs) -> {});
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("clears the binding even when the chain throws")
    void clearsOnFailure() {
        FilterChain boom = (rq, rs) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(requestWith(TENANT.toString()), response, boom))
                .isInstanceOf(ServletException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("binds nothing when the header is absent, so the database fails closed")
    void bindsNothingWithoutHeader() throws ServletException, IOException {
        UUID[] seen = new UUID[1];
        filter.doFilter(
                requestWith(null),
                response,
                (rq, rs) -> seen[0] = TenantContext.current().orElse(null));
        assertThat(seen[0]).isNull();
    }

    @Test
    @DisplayName("binds nothing when the header is not a UUID, rather than guessing")
    void bindsNothingForMalformedHeader() throws ServletException, IOException {
        UUID[] seen = new UUID[1];
        filter.doFilter(
                requestWith("not-a-uuid"),
                response,
                (rq, rs) -> seen[0] = TenantContext.current().orElse(null));
        assertThat(seen[0]).isNull();
    }

    @Test
    @DisplayName("does not disturb a binding it did not make")
    void leavesAnExistingBindingAlone() throws ServletException, IOException {
        TenantContext.bind(TENANT);
        filter.doFilter(requestWith(null), response, (rq, rs) -> {});
        assertThat(TenantContext.current()).contains(TENANT);
    }
}
