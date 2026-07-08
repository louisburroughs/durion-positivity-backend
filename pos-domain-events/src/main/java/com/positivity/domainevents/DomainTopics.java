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

    private DomainTopics() {}

    /** Fact topic for the given domain, version 1: {@code {domain}.events.v1}. */
    public static @NonNull String events(@NonNull String domain) {
        return validated(domain) + ".events.v1";
    }

    /** Command topic for the given domain, version 1: {@code {domain}.commands.v1}. */
    public static @NonNull String commands(@NonNull String domain) {
        return validated(domain) + ".commands.v1";
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
