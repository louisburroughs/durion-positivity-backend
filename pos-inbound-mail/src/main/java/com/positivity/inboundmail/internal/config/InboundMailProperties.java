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
 * @param maxAttachmentBytes the largest attachment the edge will stage
 * @param stagingPurgeBatchSize rows deleted per transaction by the staging purge, so a backlog never sits in one
 *     transaction
 * @param maxConcurrentFetches staged attachments served at once; a fetch beyond it is answered 503, because a
 *     bytea is held whole in memory while it is written out
 */
@ConfigurationProperties(prefix = "pos.inbound-mail")
public record InboundMailProperties(
        @Nullable String apiSecret,
        @DefaultValue("none") String source,
        @DefaultValue("mail.invalid") String domain,
        @DefaultValue("P7D") Duration stagingTtl,
        @DefaultValue("0 15 3 * * *") String stagingPurgeCron,
        @DefaultValue("26214400") long maxAttachmentBytes,
        @DefaultValue("200") int stagingPurgeBatchSize,
        @DefaultValue("2") int maxConcurrentFetches) {

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
        if (stagingPurgeBatchSize <= 0) {
            throw new IllegalArgumentException("pos.inbound-mail.staging-purge-batch-size must be positive");
        }
        if (maxConcurrentFetches <= 0) {
            throw new IllegalArgumentException("pos.inbound-mail.max-concurrent-fetches must be positive");
        }
    }
}
