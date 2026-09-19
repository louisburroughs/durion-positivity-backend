package com.positivity.mcp.internal.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #2073: retention of persisted assistant conversations ({@code mcp.conversation.*}).
 *
 * @param retentionDays days an unpinned conversation may sit idle before the retention purge deletes
 *     it; at least 1, validated at startup (a zero or negative value fails the boot rather than
 *     purging every conversation on the first run)
 * @param purgeInterval delay between purge runs (read by the scheduler's {@code fixedDelayString};
 *     bound here so a malformed value also fails at startup)
 */
@Validated
@ConfigurationProperties(prefix = "mcp.conversation")
public record ConversationProperties(
        @DefaultValue("30") @Min(1) int retentionDays,
        @DefaultValue("1h") @NotNull Duration purgeInterval) {}
