package com.positivity.inventory.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The boot-time notice about enabled putaway rules whose destinations do not resolve (#1543).
 *
 * <p>Every test asserts the runner does not throw, because that is its central promise: it advises
 * and never vetoes startup.
 */
@DisplayName("PutawayRuleDestinationStartupCheck")
class PutawayRuleDestinationStartupCheckTest {

    private static final UUID ANY_RULE_ID = UUID.fromString("6f46541c-937d-397a-076f-63e092cabed6");
    private static final UUID CATEGORY_RULE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a01");
    private static final UUID KNOWN_BIN = UUID.fromString("01960004-0001-7000-8000-000000000003");
    private static final UUID UNKNOWN_BIN = UUID.fromString("96dd346a-047c-86f5-3c9a-7c8cac53da86");

    private final PutawayRuleRepository ruleRepository = mock(PutawayRuleRepository.class);
    private final ExtStorageLocationReplicaRepository replicaRepository =
            mock(ExtStorageLocationReplicaRepository.class);

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        logger =
                ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(PutawayRuleDestinationStartupCheck.class);
        originalLevel = logger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        appender.stop();
        // Restore rather than leave INFO pinned on a shared, JVM-wide logger: the level outlives
        // this class and would otherwise silently reconfigure logging for every test after it.
        logger.setLevel(originalLevel);
    }

    private void run() {
        assertThatCode(() -> new PutawayRuleDestinationStartupCheck(ruleRepository, replicaRepository).run(null))
                .doesNotThrowAnyException();
    }

    private static PutawayRule rule(UUID ruleId, PutawayRuleMatchType matchType, UUID destination) {
        return PutawayRule.builder()
                .ruleId(ruleId)
                .priority(1)
                .matchType(matchType)
                .destinationLocationId(destination)
                .isEnabled(true)
                .build();
    }

    private static ExtStorageLocationReplica replica(UUID locationId, String status) {
        return ExtStorageLocationReplica.builder()
                .storageLocationId(locationId)
                .status(status)
                .build();
    }

    private void withEnabledRules(PutawayRule... rules) {
        when(ruleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc()).thenReturn(List.of(rules));
        when(replicaRepository.count()).thenReturn(1L);
    }

    @Test
    @DisplayName("every destination resolving to an ACTIVE bin says nothing at all")
    void healthyConfigurationLogsNothing() {
        withEnabledRules(rule(ANY_RULE_ID, PutawayRuleMatchType.ANY, KNOWN_BIN));
        when(replicaRepository.findAllById(anyCollection())).thenReturn(List.of(replica(KNOWN_BIN, "ACTIVE")));

        run();

        // The common case. A boot line about healthy configuration is noise, and noise is how the
        // one ERROR this check exists for gets ignored.
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("#1543 - the ANY rule aimed at a bin the replica has never seen is an ERROR: all putaway fails")
    void missingAnyDestinationIsAnError() {
        withEnabledRules(rule(ANY_RULE_ID, PutawayRuleMatchType.ANY, UNKNOWN_BIN));
        when(replicaRepository.findAllById(anyCollection())).thenReturn(List.of());

        run();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .contains(ANY_RULE_ID.toString(), UNKNOWN_BIN.toString(), "EVERY putaway");
        });
    }

    @Test
    @DisplayName("an unresolvable non-ANY rule breaks only its own routes, so it warns")
    void missingNonAnyDestinationWarns() {
        withEnabledRules(rule(CATEGORY_RULE_ID, PutawayRuleMatchType.CATEGORY, UNKNOWN_BIN));
        when(replicaRepository.findAllById(anyCollection())).thenReturn(List.of());

        run();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(CATEGORY_RULE_ID.toString(), "CATEGORY");
        });
    }

    @Test
    @DisplayName("a destination that exists but is not ACTIVE warns, since execution will refuse it")
    void inactiveDestinationWarns() {
        withEnabledRules(rule(ANY_RULE_ID, PutawayRuleMatchType.ANY, KNOWN_BIN));
        when(replicaRepository.findAllById(anyCollection())).thenReturn(List.of(replica(KNOWN_BIN, "DECOMMISSIONED")));

        run();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(KNOWN_BIN.toString(), "DECOMMISSIONED");
        });
    }

    @Test
    @DisplayName("an empty replica is a hydration gap, not a per-rule alarm: one WARN, no false ERROR")
    void emptyReplicaWarnsOnceInsteadOfAccusingEveryRule() {
        when(ruleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenReturn(List.of(
                        rule(ANY_RULE_ID, PutawayRuleMatchType.ANY, KNOWN_BIN),
                        rule(CATEGORY_RULE_ID, PutawayRuleMatchType.CATEGORY, KNOWN_BIN)));
        when(replicaRepository.count()).thenReturn(0L);

        run();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("replica is empty", "2");
        });
        verify(replicaRepository, never()).findAllById(anyCollection());
    }

    @Test
    @DisplayName("no enabled rules says nothing and never touches the replica")
    void noEnabledRulesLogsNothing() {
        when(ruleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc()).thenReturn(List.of());

        run();

        assertThat(appender.list).isEmpty();
        verify(replicaRepository, never()).count();
    }

    @Test
    @DisplayName("a failing repository is swallowed so startup never blocks")
    void repositoryFailureIsSwallowed() {
        when(ruleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenThrow(new IllegalStateException("database unavailable"));

        run();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("database unavailable");
        });
    }

    @Test
    @DisplayName("even an Error is swallowed — the promise is never to block startup, not merely to catch Exception")
    void errorIsAlsoSwallowed() {
        when(ruleRepository.findAllByIsEnabledTrueOrderByPriorityAscRuleIdAsc())
                .thenThrow(new OutOfMemoryError("rule set too large"));

        run();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("rule set too large");
        });
    }
}
