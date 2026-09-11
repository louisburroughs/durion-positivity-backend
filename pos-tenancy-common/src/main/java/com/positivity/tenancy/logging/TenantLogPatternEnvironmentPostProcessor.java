package com.positivity.tenancy.logging;

import com.positivity.tenancy.TenantContext;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Puts the bound tenant on every log line of every module that carries this library (ADR-0062
 * plan WS6, R-B6: "MDC key {@code tenantId} on every log line").
 *
 * <p>{@link TenantContext} mirrors the binding into the {@value TenantContext#MDC_KEY} MDC key;
 * this post-processor makes Spring Boot's default console and file patterns print it, by supplying
 * {@code logging.pattern.correlation} — the one slot the default patterns reserve for MDC values —
 * as the lowest-precedence property source. The bracket keeps the trace and span ids in front of
 * the tenant under either MDC naming in use here: the OpenTelemetry agent's {@code trace_id} /
 * {@code span_id} and Micrometer Tracing's {@code traceId} / {@code spanId} (the bridge modules,
 * e.g. pos-accounting); each field concatenates both keys and only one is ever set, so a line reads
 * {@code [<trace_id>,<span_id>,<tenantId>]} with empty fields where nothing is bound;
 * the Promtail pipeline and the Grafana Loki datasource key on that shape. A module that sets
 * {@code logging.pattern.correlation} itself, or a {@code logback-spring.xml} of its own, wins.
 *
 * <p>Runs before Boot initialises logging (Boot's own logging listener consumes the environment
 * after every {@link EnvironmentPostProcessor}), and adds no fields beyond the tenant: log volume
 * is unchanged.
 */
public class TenantLogPatternEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** The Boot property the default console and file patterns read MDC correlation from. */
    public static final String CORRELATION_PATTERN_PROPERTY = "logging.pattern.correlation";

    /** Trace, span, tenant: fixed positions, comma-delimited, always bracketed. */
    public static final String CORRELATION_PATTERN =
            "[%X{trace_id:-}%X{traceId:-},%X{span_id:-}%X{spanId:-},%X{" + TenantContext.MDC_KEY + ":-}] ";

    static final String PROPERTY_SOURCE_NAME = "tenantLogPattern";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(
                        PROPERTY_SOURCE_NAME, Map.of(CORRELATION_PATTERN_PROPERTY, CORRELATION_PATTERN)));
    }
}
