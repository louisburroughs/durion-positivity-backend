package com.positivity.supplier.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The inbound half of correlation (issue #1264): before this filter existed,
 * {@code SupplierCorrelationContext.withCorrelationId} had no production caller, so every vendor exchange
 * generated an id unrelated to the operator request that caused it and the audit trail could not answer
 * "who or what initiated this".
 */
class SupplierCorrelationFilterTest {

    private final SupplierCorrelationFilter filter = new SupplierCorrelationFilter();

    /** The ambient correlation id as controller/service code inside the request would observe it. */
    private AtomicReference<String> runAndCaptureAmbient(
            MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> ambient = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                ambient.set(SupplierCorrelationContext.current().orElse(null));
            }
        });
        return ambient;
    }

    @Test
    void reusesTheInboundHeaderSoAnExchangeTracesToTheRequestThatCausedIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/supplier/admin/profiles");
        request.addHeader(SupplierCorrelationContext.CORRELATION_HEADER, "operator-corr-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> ambient = runAndCaptureAmbient(request, response);

        assertThat(ambient.get())
                .as("the base client reads the ambient scope, so this IS the id every vendor exchange"
                        + " of this request will carry in supplier_exchange_audit")
                .isEqualTo("operator-corr-42");
        assertThat(response.getHeader(SupplierCorrelationContext.CORRELATION_HEADER))
                .isEqualTo("operator-corr-42");
    }

    @Test
    void generatesAndEchoesAnIdWhenTheCallerSuppliedNone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/supplier/admin/profiles");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> ambient = runAndCaptureAmbient(request, response);

        assertThat(ambient.get()).isNotBlank();
        assertThat(response.getHeader(SupplierCorrelationContext.CORRELATION_HEADER))
                .as("echoed so a caller that supplied no header can still quote the id when reporting" + " a problem")
                .isEqualTo(ambient.get());
    }

    @Test
    void aBlankHeaderIsTreatedAsAbsentNotPropagated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/supplier/admin/profiles");
        request.addHeader(SupplierCorrelationContext.CORRELATION_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> ambient = runAndCaptureAmbient(request, response);

        assertThat(ambient.get()).isNotBlank();
        assertThat(response.getHeader(SupplierCorrelationContext.CORRELATION_HEADER))
                .isEqualTo(ambient.get());
    }

    @Test
    void anOversizedHeaderIsPropagatedNotRejected() throws Exception {
        // Deliberately NOT validated here: consumers truncate at their column width, so a client's
        // header shape can neither fail an audit insert nor deny a payload read (issue #1264 --
        // the column is not the validation boundary).
        String oversized = "c".repeat(400);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/supplier/admin/profiles");
        request.addHeader(SupplierCorrelationContext.CORRELATION_HEADER, oversized);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> ambient = runAndCaptureAmbient(request, response);

        assertThat(ambient.get()).isEqualTo(oversized);
    }

    @Test
    void theScopeDoesNotLeakPastTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/supplier/admin/profiles");
        request.addHeader(SupplierCorrelationContext.CORRELATION_HEADER, "leak-check");

        runAndCaptureAmbient(request, new MockHttpServletResponse());

        assertThat(SupplierCorrelationContext.current())
                .as("servlet threads are pooled; a leaked scope would attach one request's id to an"
                        + " unrelated later request")
                .isEmpty();
    }
}
