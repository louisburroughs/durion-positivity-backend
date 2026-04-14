package com.positivity.accounting.internal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Default GL Mapping feature.
 *
 * <p>
 * Controls whether the accounting system should fall back to default GL
 * mappings
 * when no explicit posting rules exist for an event type.
 * </p>
 *
 * <p>
 * <strong>Configuration Example:</strong>
 * </p>
 *
 * <pre>
 * pos:
 *   accounting:
 *     default-mappings:
 *       enabled: true                      # Enable default GL mapping fallback
 *       allow-global-defaults: true        # Allow organization-agnostic defaults
 *       require-amount-field: true         # Require payload.amount field in events
 * </pre>
 *
 * <p>
 * <strong>Feature Behavior:</strong>
 * </p>
 * <ul>
 * <li><strong>enabled=true:</strong> When no posting rule exists, system
 * attempts to
 * resolve a default GL mapping (org-specific → global → fail)</li>
 * <li><strong>enabled=false:</strong> System fails immediately with
 * UNMAPPED_EVENT_TYPE when no posting rule exists</li>
 * <li><strong>allow-global-defaults=true:</strong> Permits fallback to global
 * (null
 * organizationId) default mappings</li>
 * <li><strong>allow-global-defaults=false:</strong> Only org-specific default
 * mappings are used</li>
 * <li><strong>require-amount-field=true:</strong> Validates that events have
 * payload.amount before using default mappings</li>
 * </ul>
 *
 * @see com.positivity.accounting.internal.entity.DefaultGLMapping
 * @see com.positivity.accounting.internal.service.PostingRuleEvaluatorImpl
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "pos.accounting.default-mappings")
public class DefaultGLMappingProperties {

    /**
     * Master switch for default GL mapping fallback feature.
     *
     * <p>
     * When enabled, the posting rule evaluator will attempt to use default GL
     * mappings when no explicit posting rules are configured for an event type.
     * </p>
     *
     * <p>
     * Default: {@code true}
     * </p>
     */
    private boolean enabled = true;

    /**
     * Whether to allow global (organization-agnostic) default mappings.
     *
     * <p>
     * When enabled, the system will fall back to global default mappings
     * (organizationId=null) if no organization-specific default exists.
     * </p>
     *
     * <p>
     * Resolution priority when this is enabled:
     * </p>
     * <ol>
     * <li>Explicit posting rule (highest priority)</li>
     * <li>Organization-specific default mapping</li>
     * <li>Global default mapping (if allowed)</li>
     * <li>Fail with UNMAPPED_EVENT_TYPE</li>
     * </ol>
     *
     * <p>
     * Default: {@code true}
     * </p>
     */
    private boolean allowGlobalDefaults = true;

    /**
     * Whether to require the {@code payload.amount} field in events using default
     * mappings.
     *
     * <p>
     * When enabled, events must contain a {@code payload.amount} field to use
     * default mappings. This ensures that generated journal entries have valid
     * transaction amounts.
     * </p>
     *
     * <p>
     * When disabled, the system will attempt to generate journal entries with zero
     * amounts if {@code payload.amount} is missing (not recommended for
     * production).
     * </p>
     *
     * <p>
     * Default: {@code true}
     * </p>
     */
    private boolean requireAmountField = true;
}
