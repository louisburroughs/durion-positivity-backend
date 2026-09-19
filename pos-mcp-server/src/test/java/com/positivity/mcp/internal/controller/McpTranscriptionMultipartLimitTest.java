package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.service.SpeechToTextClient;
import com.positivity.mcp.internal.service.SpeechToTextClient.SpeechToTextResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves {@code spring.servlet.multipart.max-file-size}/{@code max-request-size} enforcement for
 * #2074, which {@code MockMvc} does not reproduce: {@code MockMvc}'s multipart support never
 * routes through the servlet container's {@code Part} parsing, so it cannot throw {@link
 * org.springframework.web.multipart.MaxUploadSizeExceededException} the way a real request does.
 * This runs against a real embedded servlet container ({@code RANDOM_PORT}), mirroring {@link
 * StreamingSseAsyncDispatchSecurityTest}'s header-based auth (the gateway's {@code
 * X-Authorities}/{@code X-User}/{@code Authorization} contract, trusted as-is by {@code
 * GatewayAuthoritiesFilter} in a real deployment).
 *
 * <p>No {@code server.tomcat.max-swallow-size} test override: {@code application.yml} now ships
 * {@code 10MB} in production, so this class tests that shipped value rather than shadowing it.
 *
 * <p>{@link SpeechToTextClient} is mocked so the 200 case does not need a real provider.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "pos.security.permission-registration.enabled=false"})
@ActiveProfiles("test")
class McpTranscriptionMultipartLimitTest {

    private static final String BOUNDARY = "----McpTranscriptionMultipartLimitTestBoundary";
    private static final int FIVE_MIB = 5 * 1024 * 1024;

    @LocalServerPort
    private int port;

    @MockitoBean
    private SpeechToTextClient speechToTextClient;

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Mirrors {@code StreamingSseAsyncDispatchSecurityTest}'s unsigned-but-decodable test JWT. */
    private static String unsignedJwtWithUid() {
        String payload = "{\"uid\":\"00000000-0000-7000-8000-000000002074\",\"username\":\"transcribe-user\"}";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".sig";
    }

    private HttpRequest.Builder authenticatedRequest(URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(30))
                .header("X-Authorities", "mcp:chat:execute")
                .header("X-User", "transcribe-user")
                .header("Authorization", "Bearer " + unsignedJwtWithUid());
    }

    private static byte[] multipartBodyWithAudioPart(int audioBytes) {
        StringBuilder head = new StringBuilder();
        head.append("--").append(BOUNDARY).append("\r\n");
        head.append("Content-Disposition: form-data; name=\"audio\"; filename=\"clip.webm\"\r\n");
        head.append("Content-Type: audio/webm\r\n\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] tailBytes = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);

        byte[] body = new byte[headBytes.length + audioBytes + tailBytes.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        // Non-zero filler: an all-zero clip is still a well-formed multipart part for the purpose
        // of exercising the size limit, which is checked before any audio content is inspected.
        Arrays.fill(body, headBytes.length, headBytes.length + audioBytes, (byte) 'a');
        System.arraycopy(tailBytes, 0, body, headBytes.length + audioBytes, tailBytes.length);
        return body;
    }

    private HttpResponse<String> postTranscription(byte[] body, String contentType) throws Exception {
        HttpRequest request = authenticatedRequest(URI.create("http://127.0.0.1:" + port + "/v1/mcp/transcriptions"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("#2074: a clip exactly at the 5 MiB limit reaches the service and returns 200")
    void transcribe_clipExactlyAtLimit_reachesServiceAndReturns200() throws Exception {
        when(speechToTextClient.isConfigured()).thenReturn(true);
        when(speechToTextClient.transcribe(any(), any(), any(), any()))
                .thenReturn(new SpeechToTextResult("hello", "en-US", null));

        HttpResponse<String> response =
                postTranscription(multipartBodyWithAudioPart(FIVE_MIB), "multipart/form-data; boundary=" + BOUNDARY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"text\":\"hello\"");
    }

    @Test
    @DisplayName("#2074: a clip one byte over the 5 MiB limit returns 413 AUDIO_TOO_LARGE")
    void transcribe_clipOneByteOverLimit_returns413AudioTooLarge() throws Exception {
        HttpResponse<String> response = postTranscription(
                multipartBodyWithAudioPart(FIVE_MIB + 1), "multipart/form-data; boundary=" + BOUNDARY);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"AUDIO_TOO_LARGE\"");
    }

    @Test
    @DisplayName("#2074: a 6 MiB+ clip (over both max-file-size and max-request-size) returns 413 AUDIO_TOO_LARGE")
    void transcribe_clipOverRequestSizeLimit_returns413AudioTooLarge() throws Exception {
        HttpResponse<String> response = postTranscription(
                multipartBodyWithAudioPart(6 * 1024 * 1024 + 1024), "multipart/form-data; boundary=" + BOUNDARY);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"AUDIO_TOO_LARGE\"");
    }

    @Test
    @DisplayName("#2074: a malformed multipart boundary returns 400 VALIDATION_ERROR, not a bare 500")
    void transcribe_malformedMultipartBoundary_returns400ValidationError() throws Exception {
        // A body with no boundary markers at all parses as zero parts (no exception) and would just
        // surface as a missing-audio-part 415 through the normal argument-resolution path. To force
        // the servlet container's own multipart *parser* to fail (a genuine MultipartException, not
        // "no such part"), open one part's headers correctly but end the stream before its closing
        // boundary — the parser expects more data and throws mid-stream instead of returning cleanly.
        byte[] malformedBody = ("--" + BOUNDARY + "\r\n"
                        + "Content-Disposition: form-data; name=\"audio\"; filename=\"clip.webm\"\r\n"
                        + "Content-Type: audio/webm\r\n\r\n"
                        + "truncated-audio-bytes-with-no-closing-boundary-ever-sent")
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = postTranscription(malformedBody, "multipart/form-data; boundary=" + BOUNDARY);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"VALIDATION_ERROR\"");
    }

    @Test
    @DisplayName("#2074: the pre-dispatch advice is scoped to the transcription route — an unrelated 404 is unaffected")
    void unrelatedRoute_notFound_unaffectedByTranscriptionPreDispatchAdvice() throws Exception {
        HttpRequest request = authenticatedRequest(URI.create("http://127.0.0.1:" + port + "/v1/mcp/does-not-exist"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("AUDIO_TOO_LARGE").doesNotContain("UNSUPPORTED_AUDIO");
    }

    @Test
    @DisplayName("parity: a JSON route sent text/plain gets pos-web-common's own 415 UNSUPPORTED_MEDIA_TYPE wording")
    void jsonRoute_sentTextPlain_returns415WithGlobalHandlerWording() throws Exception {
        HttpRequest request = authenticatedRequest(URI.create("http://127.0.0.1:" + port + "/v1/mcp/chat"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hi"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.body())
                .contains("\"code\":\"UNSUPPORTED_MEDIA_TYPE\"")
                .contains("\"message\":\"Unsupported media type\"");
    }

    @Test
    @DisplayName("parity: oversize multipart to a non-transcription route gets pos-web-common's own 413 wording, "
            + "with a non-UUID X-Correlation-Id echoed verbatim")
    void nonTranscriptionRoute_oversizeMultipart_returns413WithGlobalHandlerWordingAndEchoedCorrelationId()
            throws Exception {
        String inboundCorrelationId = "not-a-uuid-123";
        byte[] body = multipartBodyWithAudioPart(6 * 1024 * 1024 + 1024);
        HttpRequest request = authenticatedRequest(URI.create("http://127.0.0.1:" + port + "/v1/mcp/does-not-exist"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("X-Correlation-Id", inboundCorrelationId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body())
                .contains("\"code\":\"PAYLOAD_TOO_LARGE\"")
                .contains("\"message\":\"Request rejected\"");
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains(inboundCorrelationId);
    }
}
