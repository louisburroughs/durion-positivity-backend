package com.positivity.inboundmail.internal.config;

import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pos.inbound-mail.*}: the shared secret, the mail source, the intake domain and the staging
 * limits.
 *
 * @param apiSecret the shared secret callers send as {@code X-Inbound-Mail-Secret}; blank refuses
 *     every request
 * @param source where mail comes from. {@code none} receives nothing; it is the only source this
 *     build knows (the SES source arrives with Part B), and any other value fails start-up rather
 *     than silently running with no mail
 * @param domain the domain of every intake address, lower-cased
 * @param stagingTtl how long a staged attachment lives before the purge removes it
 * @param stagingPurgeCron cron of the nightly per-tenant purge (read where the job is declared)
 * @param maxAttachmentBytes the largest staged attachment the fetch endpoint will serve
 */
@ConfigurationProperties(prefix = "pos.inbound-mail")
public record InboundMailProperties(
        @Nullable String apiSecret,
        @DefaultValue("none") String source,
        @DefaultValue("mail.invalid") String domain,
        @DefaultValue("P7D") Duration stagingTtl,
        @DefaultValue("0 15 3 * * *") String stagingPurgeCron,
        @DefaultValue("26214400") long maxAttachmentBytes) {

    public static final String SOURCE_NONE = "none";

    public InboundMailProperties {
        if (!SOURCE_NONE.equalsIgnoreCase(source == null ? "" : source.trim())) {
            throw new IllegalArgumentException(
                    "pos.inbound-mail.source must be 'none': no other mail source exists in this build");
        }
        source = SOURCE_NONE;
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("pos.inbound-mail.domain must not be blank");
        }
        domain = domain.trim().toLowerCase(Locale.ROOT);
        if (stagingTtl == null || stagingTtl.isZero() || stagingTtl.isNegative()) {
            throw new IllegalArgumentException("pos.inbound-mail.staging-ttl must be positive");
        }
        if (maxAttachmentBytes <= 0) {
            throw new IllegalArgumentException("pos.inbound-mail.max-attachment-bytes must be positive");
        }
    }
}
