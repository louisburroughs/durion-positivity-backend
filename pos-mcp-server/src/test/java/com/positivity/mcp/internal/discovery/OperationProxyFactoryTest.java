package com.positivity.mcp.internal.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OperationProxyFactoryTest {

    @Test
    @DisplayName("a JSON object body parses into structuredContent")
    void parseStructuredContent_returnsMap_forObjectBody() {
        Map<String, Object> structured =
                OperationProxyFactory.parseStructuredContent("{\"id\":\"7\",\"amount\":12.5,\"active\":true}");

        assertThat(structured)
                .containsEntry("id", "7")
                .containsEntry("amount", 12.5)
                .containsEntry("active", true);
    }

    @Test
    @DisplayName("array, scalar, empty, null, and malformed bodies fall back to an empty object")
    void parseStructuredContent_returnsEmptyObject_forNonObjectBodies() {
        // An empty object always validates against the permissive object outputSchema, so the async
        // server never rewrites a successful result into an error — the raw body stays in text content.
        assertThat(OperationProxyFactory.parseStructuredContent("[1,2,3]")).isEmpty();
        assertThat(OperationProxyFactory.parseStructuredContent("\"just a string\""))
                .isEmpty();
        assertThat(OperationProxyFactory.parseStructuredContent("42")).isEmpty();
        assertThat(OperationProxyFactory.parseStructuredContent("")).isEmpty();
        assertThat(OperationProxyFactory.parseStructuredContent("   ")).isEmpty();
        assertThat(OperationProxyFactory.parseStructuredContent(null)).isEmpty();
        assertThat(OperationProxyFactory.parseStructuredContent("{not json")).isEmpty();
    }

    @Test
    @DisplayName("gateway trust headers are stripped (case-insensitive); other headers pass through")
    void stripGatewayAuthHeaders_removesTrustHeaders_keepsOthers() {
        Map<String, Object> headers = new java.util.LinkedHashMap<>();
        headers.put("X-Authorities", "order:order:discount");
        headers.put("X-User", "attacker");
        headers.put("x-user-id", "00000000-0000-0000-0000-000000000000");
        headers.put("Authorization", "Bearer legit-token");
        headers.put("X-Correlation-Id", "abc-123");

        Map<String, Object> sanitized = OperationProxyFactory.stripGatewayAuthHeaders(headers);

        // A forged gateway trust header can never reach a service directly via the fallback path.
        assertThat(sanitized).doesNotContainKeys("X-Authorities", "X-User", "x-user-id");
        assertThat(sanitized)
                .containsEntry("Authorization", "Bearer legit-token")
                .containsEntry("X-Correlation-Id", "abc-123");
    }
}
