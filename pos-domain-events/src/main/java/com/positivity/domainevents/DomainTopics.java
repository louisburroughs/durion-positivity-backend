package com.positivity.domainevents;

import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * Topic naming for cross-module domain events (ADR-0044 §3).
 *
 * <p>Facts are published to {@code {domain}.events.v1} by the owning module only (one owner per
 * fact, ADR-0044 R6). Requests for another domain to change state go to that owner's
 * {@code {domain}.commands.v1} topic. Poison messages are routed to {@code {topic}.dlq}.
 *
 * <p>A new topic version ({@code .v2}) is required for breaking payload changes; the owner
 * dual-publishes both versions during the migration window.
 */
public final class DomainTopics {

    /** Domain segment, e.g. {@code customer}, {@code people-contact}, {@code vehicle}. */
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("[a-z][a-z0-9-]*");

    public static final String WORKORDER_EVENTS_V1 = "workorder.events.v1";
    public static final String WORKORDER_COMMANDS_V1 = "workorder.commands.v1";
    public static final String WORKORDER_MANIFEST_V1 = "workorder.manifest.v1";

    /**
     * Tenant registry facts (ADR-0062 §7): published by pos-tenant, plus {@code tenant.provisioned}
     * from pos-security-service. Keyed by tenant id.
     */
    public static final String TENANT_EVENTS_V1 = "tenant.events.v1";

    /**
     * Provider-neutral bank-feed facts (SPEC-manual-bank-reconciliation §2.2, §6.5): transactions,
     * discovered accounts, connection status and observed balances, published by a bank-feed
     * connector (phase 2) and consumed by pos-accounting. In phase 1 the transactions batch reaches
     * accounting's intake port in-process, so nothing publishes here yet.
     */
    public static final String BANKFEED_EVENTS_V1 = "bankfeed.events.v1";

    /** Commands accounting sends a bank-feed connector — sync and replay requests (phase 2). */
    public static final String BANKFEED_COMMANDS_V1 = "bankfeed.commands.v1";

    private DomainTopics() {}

    /** Fact topic for the given domain, version 1: {@code {domain}.events.v1}. */
    public static @NonNull String events(@NonNull String domain) {
        return validated(domain) + ".events.v1";
    }

    /** Command topic for the given domain, version 1: {@code {domain}.commands.v1}. */
    public static @NonNull String commands(@NonNull String domain) {
        return validated(domain) + ".commands.v1";
    }

    /**
     * Reconciliation-manifest topic for the given domain, version 1: {@code {domain}.manifest.v1}
     * (ADR-0044 §4). Kept separate from the fact topic so consumers' business handlers never see
     * manifests.
     */
    public static @NonNull String manifest(@NonNull String domain) {
        return validated(domain) + ".manifest.v1";
    }

    /** Dead-letter topic for a fact or command topic: {@code {topic}.dlq} (ADR-0044 §4). */
    public static @NonNull String dlq(@NonNull String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        return topic + ".dlq";
    }

    private static String validated(String domain) {
        if (domain == null || !DOMAIN_PATTERN.matcher(domain).matches()) {
            throw new IllegalArgumentException(
                    "domain must be lowercase kebab-case (e.g. customer, people-contact) but was: " + domain);
        }
        return domain;
    }
}
