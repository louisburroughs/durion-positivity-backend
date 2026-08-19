package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link CorrelationIdMdcFilter}: the MDC {@code correlationId} entry that
 * {@code SessionAgentManager}/{@code StreamingSessionAgentManager} read for telemetry attribution
 * must hold the caller's {@code X-Correlation-Id} for the duration of the chain, be echoed on the
 * response, and be removed afterwards even when the chain throws.
 */
class CorrelationIdMdcFilterTest {

    private final CorrelationIdMdcFilter filter = new CorrelationIdMdcFilter();
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("POST", "/v1/mcp/chat");
        response = new MockHttpServletResponse();
        MDC.remove(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY);
    }

    @AfterEach
    void tearDown() {
        MDC.remove(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY);
    }

    @Test
    @DisplayName("valid header: same id in MDC during chain, echoed on response, cleared after")
    void validHeaderIsUsedInMdcAndEchoed() throws Exception {
        UUID supplied = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
        request.addHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, supplied.toString());

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (req, res) -> mdcDuringChain.set(MDC.get(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY)));

        assertThat(mdcDuringChain.get()).isEqualTo(supplied.toString());
        assertThat(response.getHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                .isEqualTo(supplied.toString());
        assertThat(MDC.get(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("absent header: a UUIDv7 is generated, present in MDC during chain, cleared after")
    void absentHeaderGeneratesUuidV7() throws Exception {
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (req, res) -> mdcDuringChain.set(MDC.get(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY)));

        assertThat(mdcDuringChain.get()).isNotNull();
        UUID generated = UUID.fromString(mdcDuringChain.get());
        assertThat(generated.version()).isEqualTo(7);
        assertThat(response.getHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                .isEqualTo(generated.toString());
        assertThat(MDC.get(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("invalid header: a fresh UUIDv7 is generated instead of the malformed value")
    void invalidHeaderGeneratesUuidV7() throws Exception {
        request.addHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, "not-a-uuid");

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (req, res) -> mdcDuringChain.set(MDC.get(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY)));

        assertThat(mdcDuringChain.get()).isNotEqualTo("not-a-uuid");
        assertThat(UUID.fromString(mdcDuringChain.get()).version()).isEqualTo(7);
        assertThat(response.getHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                .isEqualTo(mdcDuringChain.get());
    }

    @Test
    @DisplayName("MDC is cleared even when the chain throws")
    void mdcClearedWhenChainThrows() {
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
                    throw new ServletException("boom");
                }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("controller-style resolveFromRequest reuses the filter's cached id (no second mint)")
    void resolveFromRequestReusesFilterResolvedId() throws Exception {
        AtomicReference<UUID> resolvedInsideChain = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (req, res) -> resolvedInsideChain.set(NltiCorrelationIdSupport.resolveFromRequest(request)));

        String echoed = response.getHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER);
        assertThat(resolvedInsideChain.get()).isNotNull();
        assertThat(resolvedInsideChain.get().toString()).isEqualTo(echoed);
        assertThat(request.getAttribute(NltiCorrelationIdSupport.CORRELATION_ID_ATTRIBUTE))
                .isEqualTo(resolvedInsideChain.get());
    }

    @Test
    @DisplayName("pre-existing MDC value set by upstream instrumentation is restored, not clobbered")
    void preExistingMdcValueIsRestoredAfterChain() throws Exception {
        UUID supplied = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
        request.addHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, supplied.toString());
        MDC.put(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY, "upstream-agent-id");

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        filter.doFilter(
                request,
                response,
                (req, res) -> mdcDuringChain.set(MDC.get(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY)));

        assertThat(mdcDuringChain.get()).isEqualTo(supplied.toString());
        assertThat(MDC.get(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY)).isEqualTo("upstream-agent-id");
    }

    @Test
    @DisplayName("pre-existing MDC value is restored even when the chain throws")
    void preExistingMdcValueIsRestoredWhenChainThrows() {
        MDC.put(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY, "upstream-agent-id");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
                    throw new ServletException("boom");
                }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(CorrelationIdMdcFilter.MDC_CORRELATION_ID_KEY)).isEqualTo("upstream-agent-id");
    }
}
