package com.positivity.inventory.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactResponse;
import com.positivity.inventory.internal.enums.CostingMethod;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The boot-time notice about {@code pos.inventory.sku-category.resolve-from-replica} (#1535).
 *
 * <p>Every test asserts the runner does not throw, because that is its central
 * promise: it advises and never vetoes startup.
 */
@DisplayName("SkuCategoryCutoverStartupCheck")
class SkuCategoryCutoverStartupCheckTest {

    private final SkuCategoryCutoverService cutoverService = mock(SkuCategoryCutoverService.class);
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(SkuCategoryCutoverStartupCheck.class);
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
        // Restore rather than leave INFO pinned on a shared, JVM-wide logger: the level outlives this
        // class and would otherwise silently reconfigure logging for every test that runs after it.
        logger.setLevel(originalLevel);
    }

    private static SkuCategoryImpactResponse.SkuCategoryImpactResponseBuilder report() {
        return SkuCategoryImpactResponse.builder()
                .resolveFromReplicaEnabled(true)
                .deploymentDefaultMethod(CostingMethod.AVERAGE)
                .activeSkuCategoryConfigCount(0)
                .categoriesWithNoReplicatedProducts(List.of())
                .categoriesWithUntrimmedScopeValue(List.of())
                .evaluatedSkuCount(0)
                .categoryMatchedSkuCount(0)
                .impactedSkuCount(0)
                .impactedSkuWithCostStateCount(0)
                .impactedSkus(List.of())
                .impactedSourcingSkus(List.of())
                .truncated(false)
                .impactSkuCap(5000);
    }

    private void run(boolean resolveFromReplicaEnabled) {
        assertThatCode(() -> new SkuCategoryCutoverStartupCheck(cutoverService, resolveFromReplicaEnabled).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("flag off with active SKU_CATEGORY configs logs that they are inert, and does not throw")
    void flagOffWithActiveSkuCategoryConfigs_logsInertNoticeAndDoesNotThrow() {
        when(cutoverService.activeSkuCategoryConfigCount()).thenReturn(3L);

        run(false);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("3", "inert", "sku-category-impact");
        });
        // Building the whole report to read one integer would make every boot pay for a sequential
        // scan of ext_product.
        verify(cutoverService, never()).impact();
    }

    @Test
    @DisplayName("flag off with no configs says nothing at all")
    void flagOffWithNoConfigs_logsNothing() {
        when(cutoverService.activeSkuCategoryConfigCount()).thenReturn(0L);

        run(false);

        // The overwhelmingly common case. A boot line about a feature nobody has configured is noise,
        // and noise is how real warnings get ignored.
        assertThat(appender.list).isEmpty();
        verify(cutoverService, never()).impact();
    }

    @Test
    @DisplayName("flag on reports how many SKUs resolve from their category, at INFO")
    void flagOnWithMatchedSkus_logsInfoAndDoesNotThrow() {
        when(cutoverService.impact())
                .thenReturn(report().activeSkuCategoryConfigCount(1)
                        .evaluatedSkuCount(2)
                        .categoryMatchedSkuCount(2)
                        .build());

        run(true);

        // After a completed cut-over the rows are SUPPOSED to be reachable, so this is the healthy
        // steady state, not an alarm. It used to WARN here on every boot forever.
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("2", "SKU_CATEGORY");
        });
    }

    @Test
    @DisplayName("flag on with a truncated report warns, because the numbers are only lower bounds")
    void flagOnWithTruncatedReport_warns() {
        when(cutoverService.impact())
                .thenReturn(report().activeSkuCategoryConfigCount(1)
                        .categoryMatchedSkuCount(5000)
                        .truncated(true)
                        .build());

        run(true);

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("5000", "impact-sku-cap"));
    }

    @Test
    @DisplayName("a failing impact service is swallowed so startup never blocks")
    void impactServiceFailure_isSwallowedSoStartupNeverBlocks() {
        when(cutoverService.impact()).thenThrow(new IllegalStateException("replica unavailable"));

        run(true);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("replica unavailable");
        });
    }

    @Test
    @DisplayName("even an Error is swallowed — the promise is never to block startup, not merely to catch Exception")
    void impactServiceError_isAlsoSwallowed() {
        when(cutoverService.impact()).thenThrow(new OutOfMemoryError("report too large"));

        run(true);

        // The report is capped so this should be unreachable; the promise must not depend on that.
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("report too large");
        });
    }
}
