package com.positivity.order;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseContractIntegrationTest {
    private static final UUID DEFAULT_TEST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    protected MockHttpServletRequestBuilder withGatewayAuth(MockHttpServletRequestBuilder requestBuilder) {
        return withGatewayAuth(requestBuilder, defaultAuthorities());
    }

    protected MockHttpServletRequestBuilder withGatewayAuth(
            MockHttpServletRequestBuilder requestBuilder, String authorities) {
        String username = DEFAULT_TEST_USER_ID.toString();
        return requestBuilder
                .header("X-User", username)
                .header("X-Authorities", authorities)
                .header("Authorization", "Bearer " + buildUnsignedJwtWithUserId(username));
    }

    protected String defaultAuthorities() {
        return "*";
    }

    private String buildUnsignedJwtWithUserId(String username) {
        String resolvedUserId = username;
        try {
            UUID.fromString(username);
        } catch (IllegalArgumentException ex) {
            resolvedUserId = "00000000-0000-0000-0000-000000000000";
        }

        String headerJson = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"userId\":\"" + resolvedUserId + "\"}";
        return base64UrlEncode(headerJson) + "." + base64UrlEncode(payloadJson) + ".signature";
    }

    private String base64UrlEncode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
