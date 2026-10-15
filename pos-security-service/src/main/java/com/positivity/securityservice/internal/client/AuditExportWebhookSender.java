package com.positivity.securityservice.internal.client;

import com.positivity.securityservice.internal.config.AuditExportWebhookProperties;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends one rendered audit export to a tenant's registered webhook, once (#2497). The retry loop and
 * what a result means for the job belong to the export worker; this class makes a single, signed
 * attempt.
 *
 * <p>Each attempt first re-checks the destination with {@link WebhookDestinationPolicy#checkDelivery}
 * (re-resolving its host), then POSTs the file with the format's {@code Content-Type} and three
 * headers: {@value #JOB_ID_HEADER}, {@value #TIMESTAMP_HEADER} (epoch seconds) and {@value
 * #SIGNATURE_HEADER}, {@code sha256=} followed by the lower-case hex HMAC-SHA256 of {@code timestamp +
 * "." + body}, keyed with the UTF-8 bytes of the signing secret exactly as it was shown at
 * registration. Any 2xx is delivered. Redirects are never followed (a 3xx is a failed attempt), the
 * connect and read timeouts are short, and the client is a plain JDK {@link HttpClient}: not
 * load-balanced, nothing service-discovery related, and never proxied (a proxy would resolve the host
 * itself, past the address check).
 *
 * <p>Nothing sensitive leaves this class in a result or a log line: the error is a status code or an
 * exception class name, never the response body, and the secret and the file are never logged.
 */
@Slf4j
@Component
public class AuditExportWebhookSender {

    public static final String JOB_ID_HEADER = "X-Audit-Export-Job-Id";
    public static final String TIMESTAMP_HEADER = "X-Audit-Export-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Audit-Export-Signature";
    static final String SIGNATURE_PREFIX = "sha256=";

    /** The error recorded when the destination policy refuses the URL or an address it resolves to. */
    public static final String DESTINATION_REFUSED = "DESTINATION_REFUSED";

    static final int MAX_ERROR_LENGTH = 200;
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final WebhookDestinationPolicy policy;
    private final RestClient restClient;
    private final Clock clock;

    @Autowired
    public AuditExportWebhookSender(
            @NonNull WebhookDestinationPolicy policy,
            @NonNull AuditExportWebhookProperties properties,
            @NonNull Clock clock) {
        this(policy, properties, clock, null);
    }

    /** Test seam: {@code sslContext} trusts a test's self-signed HTTPS server; null keeps the JDK default. */
    AuditExportWebhookSender(
            @NonNull WebhookDestinationPolicy policy,
            @NonNull AuditExportWebhookProperties properties,
            @NonNull Clock clock,
            @Nullable SSLContext sslContext) {
        this.policy = policy;
        this.clock = clock;
        HttpClient.Builder http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                // Direct, never through a proxy: the policy checked the addresses this host resolves
                // to, and a proxy would connect wherever it resolves the name itself.
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(properties.connectTimeout());
        if (sslContext != null) {
            http.sslContext(sslContext);
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http.build());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Makes one signed delivery attempt.
     *
     * @param destination the job's pinned destination URL
     * @param signingSecret CONFIDENTIAL: the registration's current signing secret
     * @param jobId the export job, sent as {@value #JOB_ID_HEADER}
     * @param contentType the file's content type ({@code text/csv} or {@code application/json})
     * @param body the rendered file, UTF-8
     * @return how the attempt went; never throws for a delivery problem
     */
    public @NonNull Attempt send(
            @NonNull URI destination,
            @NonNull String signingSecret,
            @NonNull UUID jobId,
            @NonNull String contentType,
            byte @NonNull [] body) {
        try {
            policy.checkDelivery(destination);
        } catch (WebhookDestinationPolicy.DestinationRefusedException refused) {
            log.warn(
                    "Audit export {} not sent: destination host {} refused by policy ({})",
                    jobId,
                    destination.getHost(),
                    refused.getMessage());
            return Attempt.refused(DESTINATION_REFUSED);
        } catch (UnknownHostException unresolved) {
            log.warn("Audit export {} not sent: destination host {} does not resolve", jobId, destination.getHost());
            return Attempt.failed(UnknownHostException.class.getSimpleName());
        }
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String signature = SIGNATURE_PREFIX + hmacHex(signingSecret, timestamp, body);
        try {
            Integer status = restClient
                    .post()
                    .uri(destination)
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(JOB_ID_HEADER, jobId.toString())
                    .header(TIMESTAMP_HEADER, timestamp)
                    .header(SIGNATURE_HEADER, signature)
                    .body(body)
                    // The response body is never read: it is the receiver's, and nothing of it is kept.
                    .exchange((request, response) -> response.getStatusCode().value());
            int code = status == null ? 0 : status;
            if (code >= 200 && code < 300) {
                log.info("Audit export {} delivered to host {} (HTTP {})", jobId, destination.getHost(), code);
                return Attempt.delivered(code);
            }
            String error = "HTTP " + code + (code >= 300 && code < 400 ? " (redirect not followed)" : "");
            log.warn("Audit export {} delivery to host {} failed: {}", jobId, destination.getHost(), error);
            return new Attempt(Outcome.FAILED, code, error);
        } catch (RestClientException failure) {
            String error = truncate(failure.getMostSpecificCause().getClass().getSimpleName());
            log.warn("Audit export {} delivery to host {} failed: {}", jobId, destination.getHost(), error);
            return Attempt.failed(error);
        }
    }

    /** Lower-case hex HMAC-SHA256 of {@code timestamp + "." + body}, keyed with the secret's UTF-8 bytes. */
    static @NonNull String hmacHex(@NonNull String secret, @NonNull String timestamp, byte @NonNull [] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 unavailable", unavailable);
        }
    }

    private static @NonNull String truncate(@NonNull String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    /** How one attempt went. */
    public enum Outcome {
        /** The endpoint answered 2xx. */
        DELIVERED,
        /** The attempt failed in a way a later attempt might not (status, timeout, DNS); worth retrying. */
        FAILED,
        /** The destination policy refused the URL or its addresses; nothing was sent, and retrying is pointless. */
        REFUSED
    }

    /**
     * The result of one attempt.
     *
     * @param outcome what happened
     * @param statusCode the endpoint's HTTP status when it answered, else null
     * @param error a short, non-sensitive reason when not delivered: {@code HTTP 503}, an exception
     *     class name, or {@link #DESTINATION_REFUSED}; never the response body
     */
    public record Attempt(
            @NonNull Outcome outcome,
            @Nullable Integer statusCode,
            @Nullable String error) {

        static @NonNull Attempt delivered(int statusCode) {
            return new Attempt(Outcome.DELIVERED, statusCode, null);
        }

        static @NonNull Attempt failed(@NonNull String error) {
            return new Attempt(Outcome.FAILED, null, truncate(error));
        }

        static @NonNull Attempt refused(@NonNull String error) {
            return new Attempt(Outcome.REFUSED, null, error);
        }

        public boolean delivered() {
            return outcome == Outcome.DELIVERED;
        }
    }
}
