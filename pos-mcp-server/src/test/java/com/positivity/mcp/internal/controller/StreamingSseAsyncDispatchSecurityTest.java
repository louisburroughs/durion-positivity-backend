package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

/**
 * Regression test for the async-dispatch 401 found live on alpha (2026-08-08, #1196 session):
 * an SSE stream that completes without emitting any event leaves the response uncommitted, and the
 * stream's completion is processed on a container ASYNC dispatch. Spring Security re-authorizes
 * that dispatch, but {@code GatewayAuthoritiesFilter} (a {@code OncePerRequestFilter}) does not run
 * on async dispatches — so the re-authorization found an empty security context and
 * {@code ApiErrorAuthenticationEntryPoint} replaced the real (empty 200) outcome with a spurious
 * {@code 401 "Authentication is required"}, even though the caller's token was valid and the
 * request-level authorization had already passed. The fix permits {@code DispatcherType.ASYNC} in
 * the MCP API security chain (the dispatch is container-internal and unreachable by clients).
 *
 * <p>This must run against a REAL servlet container ({@code RANDOM_PORT}): MockMvc's
 * {@code asyncDispatch} does not reproduce the container's filter-registration dispatcher-type
 * behavior, so a MockMvc variant passes even without the fix.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "pos.security.permission-registration.enabled=false"})
@ActiveProfiles("test")
class StreamingSseAsyncDispatchSecurityTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private StreamingAgentOrchestrationService streamingSessionAgentManager;

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> postStream(boolean authenticated) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/mcp/chat/stream"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hello\"}"));
        if (authenticated) {
            request.header("X-Authorities", "mcp:chat:stream")
                    .header("X-User", "stream-user")
                    .header("Authorization", "Bearer " + unsignedJwtWithUid());
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * {@code GatewayAuthoritiesFilter} resolves the authenticated userId detail by base64-decoding
     * the JWT payload's {@code uid} claim (the gateway has already verified the signature in
     * production), and {@code CurrentUserContextResolver} requires that detail — so the test token
     * only needs a decodable payload, not a valid signature.
     */
    private static String unsignedJwtWithUid() {
        String payload = "{\"uid\":\"00000000-0000-7000-8000-000000001196\",\"username\":\"stream-user\"}";
        java.util.Base64.Encoder encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ".sig";
    }

    @Test
    @DisplayName("#1196 defect 2: SSE stream with zero events completes as 200 with an empty body, not 401")
    void emptyStream_completesAs200_not401() throws Exception {
        when(streamingSessionAgentManager.streamChat(any(CurrentUserContext.class), any()))
                .thenReturn(Flux.empty());

        HttpResponse<String> response = postStream(true);

        // Before the fix the async completion dispatch was re-authorized with an empty security
        // context and this returned 401 {"code":"UNAUTHORIZED",...} despite the valid caller.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("SSE stream with events still delivers them as 200")
    void nonEmptyStream_deliversSsePayload() throws Exception {
        when(streamingSessionAgentManager.streamChat(any(CurrentUserContext.class), any()))
                .thenReturn(Flux.just("token-1", "token-2"));

        HttpResponse<String> response = postStream(true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("data:token-1").contains("data:token-2");
    }

    @Test
    @DisplayName("Unauthenticated initial request is still rejected up front with the ApiError 401")
    void unauthenticatedRequest_stillRejectedUpFront() throws Exception {
        HttpResponse<String> response = postStream(false);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"UNAUTHORIZED\"").contains("Authentication is required");
    }
}
