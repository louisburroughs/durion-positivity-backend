package com.positivity.tenancy.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.positivity.tenancy.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.LoggingSystemProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;

class TenantLogPatternEnvironmentPostProcessorTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private final TenantLogPatternEnvironmentPostProcessor processor = new TenantLogPatternEnvironmentPostProcessor();

    @AfterEach
    void clear() {
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    void suppliesTheCorrelationPatternWithTheTenantKeyAtLowestPrecedence() {
        StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(TenantLogPatternEnvironmentPostProcessor.CORRELATION_PATTERN_PROPERTY))
                .isEqualTo(TenantLogPatternEnvironmentPostProcessor.CORRELATION_PATTERN)
                .contains("%X{" + TenantContext.MDC_KEY + ":-}");
        assertThat(environment.getPropertySources().stream().toList())
                .as("last, so any module property wins")
                .last()
                .extracting(source -> source.getName())
                .isEqualTo(TenantLogPatternEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
    }

    @Test
    void aModuleThatSetsItsOwnCorrelationPatternWins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "module",
                        Map.of(TenantLogPatternEnvironmentPostProcessor.CORRELATION_PATTERN_PROPERTY, "[mine] ")));

        processor.postProcessEnvironment(environment, new SpringApplication());
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(TenantLogPatternEnvironmentPostProcessor.CORRELATION_PATTERN_PROPERTY))
                .isEqualTo("[mine] ");
        assertThat(environment.getPropertySources().stream()
                        .filter(source ->
                                TenantLogPatternEnvironmentPostProcessor.PROPERTY_SOURCE_NAME.equals(source.getName()))
                        .count())
                .as("idempotent")
                .isEqualTo(1);
    }

    @Test
    void bootHandsThePatternToTheLoggingSystem() {
        StandardEnvironment environment = new StandardEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());
        Map<String, String> applied = new HashMap<>();

        new LoggingSystemProperties(environment, applied::put).apply();

        assertThat(applied)
                .as("LOG_CORRELATION_PATTERN is what defaults.xml splices into the console and file patterns")
                .containsEntry("LOG_CORRELATION_PATTERN", TenantLogPatternEnvironmentPostProcessor.CORRELATION_PATTERN);
    }

    @Test
    void theBoundTenantRendersOnTheLineAndAnUnboundLineKeepsItsShape() {
        PatternLayout layout = new PatternLayout();
        layout.setContext(new LoggerContext());
        layout.setPattern(TenantLogPatternEnvironmentPostProcessor.CORRELATION_PATTERN + "%m");
        layout.start();

        AtomicReference<String> bound = new AtomicReference<>();
        TenantContext.runAs(TENANT, () -> bound.set(layout.doLayout(event("order created"))));
        String unbound = layout.doLayout(event("scheduler tick"));

        assertThat(bound.get()).isEqualTo("[,," + TENANT + "] order created");
        assertThat(unbound).isEqualTo("[,,] scheduler tick");
    }

    @Test
    void bothTracingKeyNamesRenderInTheSameFields() {
        PatternLayout layout = new PatternLayout();
        layout.setContext(new LoggerContext());
        layout.setPattern(TenantLogPatternEnvironmentPostProcessor.CORRELATION_PATTERN + "%m");
        layout.start();

        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");
        String micrometer = layout.doLayout(event("bridge"));
        MDC.clear();
        MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("span_id", "00f067aa0ba902b7");
        String agent = layout.doLayout(event("agent"));

        assertThat(micrometer).isEqualTo("[4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7,] bridge");
        assertThat(agent).isEqualTo("[4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7,] agent");

        // Both injectors in one JVM (agent + Micrometer bridge) put the same ids under both names:
        // the field renders the id once, not twice.
        MDC.clear();
        MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("span_id", "00f067aa0ba902b7");
        MDC.put("spanId", "00f067aa0ba902b7");
        String both = layout.doLayout(event("both"));
        assertThat(both).isEqualTo("[4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7,] both");
    }

    @Test
    void isRegisteredWithBootUnderTheEnvironmentPostProcessorKey() throws IOException {
        Properties factories = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/spring.factories")) {
            assertThat(in).as("META-INF/spring.factories on the classpath").isNotNull();
            factories.load(in);
        }
        assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
                .contains(TenantLogPatternEnvironmentPostProcessor.class.getName());

        // Boot's own entries have package-private constructors: skip what cannot be instantiated here.
        List<EnvironmentPostProcessor> loaded = SpringFactoriesLoader.forDefaultResourceLocation(
                        getClass().getClassLoader())
                .load(
                        EnvironmentPostProcessor.class,
                        SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> {}));
        assertThat(loaded)
                .as("Boot's loader instantiates it")
                .anyMatch(TenantLogPatternEnvironmentPostProcessor.class::isInstance);
    }

    private static LoggingEvent event(String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setLoggerName("test");
        event.setTimeStamp(0L);
        event.setMDCPropertyMap(Map.copyOf(mdc()));
        return event;
    }

    private static Map<String, String> mdc() {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        return copy == null ? Map.of() : copy;
    }
}
