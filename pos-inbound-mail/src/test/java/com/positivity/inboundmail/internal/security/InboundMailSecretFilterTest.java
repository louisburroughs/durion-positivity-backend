package com.positivity.inboundmail.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

@DisplayName("InboundMailSecretFilter: fail-closed shared secret on the edge API")
class InboundMailSecretFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String path, String secret) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (secret != null) {
            request.addHeader(InboundMailSecretFilter.SECRET_HEADER, secret);
        }
        return request;
    }

    @Test
    @DisplayName(
            "a blank configured secret refuses every request with INBOUND_MAIL_SECRET_MISSING, whatever the caller sends")
    void blankSecretRefusesAll() throws Exception {
        InboundMailSecretFilter filter = new InboundMailSecretFilter(" ", CLOCK, new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/inbound-mail/v1/addresses", " "), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INBOUND_MAIL_SECRET_MISSING");
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(chain.getRequest()).as("the chain never ran").isNull();
    }

    @Test
    @DisplayName("a missing header is 401 INVALID_INBOUND_MAIL_SECRET")
    void missingHeaderIsRefused() throws Exception {
        InboundMailSecretFilter filter = new InboundMailSecretFilter("s3cret", CLOCK, new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/inbound-mail/v1/addresses", null), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INVALID_INBOUND_MAIL_SECRET");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("a wrong secret is refused, including one that is a prefix of the real one")
    void wrongSecretIsRefused() throws Exception {
        InboundMailSecretFilter filter = new InboundMailSecretFilter("s3cret", CLOCK, new ObjectMapper());
        for (String wrong : new String[] {"wrong", "s3cre", "s3cret ", ""}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request("/inbound-mail/v1/addresses", wrong), response, chain);

            assertThat(response.getStatus()).as("secret '%s'", wrong).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("INVALID_INBOUND_MAIL_SECRET");
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    @DisplayName("the matching secret authenticates the request as the inbound-mail client")
    void matchingSecretAuthenticates() throws Exception {
        InboundMailSecretFilter filter = new InboundMailSecretFilter("s3cret", CLOCK, new ObjectMapper());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/inbound-mail/v1/addresses", "s3cret"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo(InboundMailSecretFilter.PRINCIPAL);
    }

    @Test
    @DisplayName("paths outside the edge API are not this filter's business")
    void otherPathsPassThrough() throws Exception {
        InboundMailSecretFilter filter = new InboundMailSecretFilter("s3cret", CLOCK, new ObjectMapper());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/actuator/health", null), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
