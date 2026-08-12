package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.repository.ExchangeAuditRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention purge for exchange-audit payloads (ADR-0050 §7): past the retention window the
 * commercial <em>content</em> expires, while the metadata row — who, when, which capability, what
 * outcome — is kept permanently.
 *
 * <p>That split is the whole design. Deleting rows outright would destroy the trail of what happened,
 * which is the part with lasting commercial and audit value; keeping payloads forever would retain
 * vendor pricing and party data long past any purpose. So the purge nulls the two payload columns and
 * stamps {@code payloads_purged_at}, which is what lets a later reader distinguish "purged" from
 * "never captured" (a {@code METADATA_ONLY} binding) — otherwise both look identical and an operator
 * cannot tell whether data was ever there.
 *
 * <p>Implemented as a bulk {@code UPDATE} rather than load-mutate-save: loading rows would decrypt
 * every payload only to discard it, needlessly widening where plaintext exists, and would fail on any
 * row whose key is no longer configured — a rotated-out key must not be able to block the purge.
 *
 * <h2>No lease, deliberately: safe to run on every instance at once</h2>
 *
 * Every replica runs this schedule, so on a multi-instance deployment several purges overlap. That is
 * harmless, and the reason is in the statement rather than in a comment: the {@code UPDATE} predicate
 * requires {@code payloads_purged_at IS NULL} and at least one non-null payload column, so it is idempotent
 * and self-limiting — the second instance to arrive matches the rows the first has not yet committed, and
 * once committed they match nothing. Concurrent runs cannot double-purge, cannot purge the wrong window, and
 * cannot resurrect anything. {@code ExchangeAuditRetentionJobTest} pins the idempotence rather than leaving
 * it as an assertion in prose.
 *
 * <p>A lease was considered and rejected. {@code supplier_schedule_lease} is keyed by {@code binding_id} with
 * a foreign key to {@code supplier_endpoint_binding} — it is a per-binding coordination table by
 * construction, and a table-wide retention sweep is not a binding, so it has nowhere to put a row. Adding a
 * general-purpose job-lease table to save a redundant indexed scan of one table per night would be
 * infrastructure bought well ahead of a problem; the scan is bounded by
 * {@code idx_saudit_started_at}. If a general job lease is ever introduced for other reasons, this is a
 * natural early user of it.
 */
@Component
public class ExchangeAuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ExchangeAuditRetentionJob.class);

    private final ExchangeAuditRepository auditRepository;
    private final Clock clock;
    private final Duration retention;

    public ExchangeAuditRetentionJob(
            @NonNull ExchangeAuditRepository auditRepository,
            @NonNull Clock clock,
            @Value("${pos.supplier.audit.retention:P400D}") Duration retention) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero()) {
            // A zero or negative window would purge everything on the next tick, including payloads
            // from exchanges still being investigated.
            throw new IllegalStateException("pos.supplier.audit.retention must be positive, was " + retention);
        }
    }

    /**
     * Purges payloads older than the retention window.
     *
     * <p>Runs daily by default rather than hourly: the window is 400 days, so purge latency of a day
     * is immaterial, and a large first run is better amortised outside business hours.
     */
    @Scheduled(cron = "${pos.supplier.audit.purge-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpiredPayloads() {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(retention);
        long candidates = auditRepository.countPurgeableOlderThan(cutoff);
        if (candidates == 0) {
            log.debug("Exchange-audit purge found nothing older than {}", cutoff);
            return;
        }
        int purged = auditRepository.purgePayloadsOlderThan(cutoff, now);
        // Logged at INFO with counts: a purge that silently does nothing, or silently empties the
        // table, are both things an operator needs to be able to see after the fact.
        log.info(
                "Exchange-audit purge nulled payloads on {} row(s) older than {} (retention {}); metadata retained",
                purged,
                cutoff,
                retention);
    }

    /** The configured retention window, for diagnostics. */
    @NonNull
    public Duration retention() {
        return retention;
    }
}
