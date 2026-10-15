package com.positivity.securityservice.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.config.AuditExportWebhookProperties;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #2497: one signed delivery attempt against a real local HTTPS receiver — the headers and signature a
 * receiver verifies, 2xx as success, redirects never followed, the response body never kept, and the
 * destination re-checked before anything is sent.
 */
@DisplayName("AuditExportWebhookSender - one signed attempt to a local HTTPS receiver (#2497)")
class AuditExportWebhookSenderTest {

    private static final Instant NOW = Instant.parse("2026-10-12T09:30:00Z");
    private static final String SECRET = "test-signing-secret-base64url-0000000000000";
    private static final UUID JOB_ID = UUID.fromString("01960000-0000-7000-8000-0000000000aa");
    private static final AuditExportWebhookProperties PROPERTIES =
            new AuditExportWebhookProperties(3, Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(10));
    private static final byte[] CSV =
            "eventId,timestamp\r\nabc,2026-10-12T09:00:00Z\r\n".getBytes(StandardCharsets.UTF_8);

    private LocalWebhookServer receiver;
    private AuditExportWebhookSender sender;

    @BeforeEach
    void start() throws Exception {
        receiver = LocalWebhookServer.start();
        sender = receiver.sender(PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        receiver.close();
    }

    private static String expectedSignature(String secret, String timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    @DisplayName("POSTs the file with its content type, job id, timestamp and a verifiable HMAC-SHA256 signature")
    void signedDelivery() throws Exception {
        AuditExportWebhookSender.Attempt attempt =
                sender.send(receiver.url("/hooks/audit"), SECRET, JOB_ID, "text/csv", CSV);

        assertThat(attempt.delivered()).isTrue();
        assertThat(attempt.statusCode()).isEqualTo(200);
        assertThat(attempt.error()).isNull();
        assertThat(receiver.received()).hasSize(1);
        LocalWebhookServer.Received request = receiver.received().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/hooks/audit");
        assertThat(request.body()).isEqualTo(CSV);
        assertThat(request.header("Content-Type")).startsWith("text/csv");
        assertThat(request.header(AuditExportWebhookSender.JOB_ID_HEADER)).isEqualTo(JOB_ID.toString());
        String timestamp = request.header(AuditExportWebhookSender.TIMESTAMP_HEADER);
        assertThat(timestamp).isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(request.header(AuditExportWebhookSender.SIGNATURE_HEADER))
                .isEqualTo(expectedSignature(SECRET, timestamp, CSV))
                .isNotEqualTo(expectedSignature("another-secret", timestamp, CSV));
        assertThat(request.headers()).doesNotContainKey("Authorization");
    }

    @Test
    @DisplayName("any 2xx is delivered; a non-2xx is a failed attempt that names only the status")
    void statusClasses() {
        receiver.replyWith(202, 503, 400);

        assertThat(sender.send(receiver.url("/a"), SECRET, JOB_ID, "application/json", CSV)
                        .delivered())
                .isTrue();
        AuditExportWebhookSender.Attempt unavailable =
                sender.send(receiver.url("/a"), SECRET, JOB_ID, "application/json", CSV);
        AuditExportWebhookSender.Attempt rejected =
                sender.send(receiver.url("/a"), SECRET, JOB_ID, "application/json", CSV);

        assertThat(unavailable.outcome()).isEqualTo(AuditExportWebhookSender.Outcome.FAILED);
        assertThat(unavailable.error()).isEqualTo("HTTP 503").doesNotContain("receiver body");
        assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(rejected.error()).isEqualTo("HTTP 400");
    }

    @Test
    @DisplayName("a redirect is never followed: the 3xx is a failed attempt and the Location is never requested")
    void redirectNotFollowed() {
        receiver.redirectTo(302, receiver.url("/elsewhere").toString());

        AuditExportWebhookSender.Attempt attempt = sender.send(receiver.url("/hook"), SECRET, JOB_ID, "text/csv", CSV);

        assertThat(attempt.delivered()).isFalse();
        assertThat(attempt.outcome()).isEqualTo(AuditExportWebhookSender.Outcome.FAILED);
        assertThat(attempt.error()).isEqualTo("HTTP 302 (redirect not followed)");
        assertThat(receiver.received())
                .extracting(LocalWebhookServer.Received::path)
                .containsExactly("/hook");
    }

    @Test
    @DisplayName("the destination is re-resolved and re-checked before sending: a private answer sends nothing")
    void deliveryTimeAddressRecheck() throws Exception {
        AuditExportWebhookSender rebinding = receiver.senderResolvingTo(
                PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC), InetAddress.getByName("10.0.0.7"));

        AuditExportWebhookSender.Attempt attempt =
                rebinding.send(receiver.url("/hook"), SECRET, JOB_ID, "text/csv", CSV);

        assertThat(attempt.outcome()).isEqualTo(AuditExportWebhookSender.Outcome.REFUSED);
        assertThat(attempt.error()).isEqualTo(AuditExportWebhookSender.DESTINATION_REFUSED);
        assertThat(receiver.received()).isEmpty();
    }

    @Test
    @DisplayName("production's policy refuses the loopback receiver outright: only the test seam admits it")
    void productionPolicyRefusesLoopback() {
        AuditExportWebhookSender production = new AuditExportWebhookSender(
                new WebhookDestinationPolicy(), PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

        AuditExportWebhookSender.Attempt attempt =
                production.send(receiver.url("/hook"), SECRET, JOB_ID, "text/csv", CSV);

        assertThat(attempt.outcome()).isEqualTo(AuditExportWebhookSender.Outcome.REFUSED);
        assertThat(receiver.received()).isEmpty();
    }

    @Test
    @DisplayName("a connection failure is a failed attempt naming the exception class, worth retrying")
    void connectionFailure() {
        URI closed = receiver.url("/hook");
        receiver.close();

        AuditExportWebhookSender.Attempt attempt = sender.send(closed, SECRET, JOB_ID, "text/csv", CSV);

        assertThat(attempt.outcome()).isEqualTo(AuditExportWebhookSender.Outcome.FAILED);
        assertThat(attempt.error()).isNotBlank().doesNotContain(" ").hasSizeLessThanOrEqualTo(200);
    }

    @Test
    @DisplayName("the signature helper matches an independent HMAC-SHA256 over timestamp + '.' + body")
    void hmacHelper() throws Exception {
        assertThat(AuditExportWebhookSender.SIGNATURE_PREFIX
                        + AuditExportWebhookSender.hmacHex(SECRET, "1760261400", CSV))
                .isEqualTo(expectedSignature(SECRET, "1760261400", CSV));
    }
}
