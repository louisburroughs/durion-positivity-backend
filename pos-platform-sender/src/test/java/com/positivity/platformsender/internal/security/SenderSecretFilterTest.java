package com.positivity.platformsender.internal.security;

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

@DisplayName("SenderSecretFilter — fail-closed shared secret on the send API")
class SenderSecretFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String path, String secret) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (secret != null) {
            request.addHeader(SenderSecretFilter.SECRET_HEADER, secret);
        }
        return request;
    }

    @Test
    @DisplayName("a blank configured secret refuses every request, whatever the caller sends")
    void blankSecretRefusesAll() throws Exception {
        SenderSecretFilter filter = new SenderSecretFilter(" ", CLOCK, new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/platform-sender/v1/messages", " "), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains(SenderSecretFilter.CODE_SECRET_MISSING);
        assertThat(chain.getRequest()).as("the chain never ran").isNull();
    }

    @Test
    @DisplayName("the matching secret authenticates the request as the sender client")
    void matchingSecretAuthenticates() throws Exception {
        SenderSecretFilter filter = new SenderSecretFilter("s3cret", CLOCK, new ObjectMapper());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/platform-sender/v1/messages", "s3cret"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo(SenderSecretFilter.PRINCIPAL);
    }

    @Test
    @DisplayName("paths outside the send API are not this filter's business")
    void otherPathsPassThrough() throws Exception {
        SenderSecretFilter filter = new SenderSecretFilter("s3cret", CLOCK, new ObjectMapper());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/actuator/health", null), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
