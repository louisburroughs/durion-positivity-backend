package com.positivity.inventory.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactResponse;
import com.positivity.inventory.internal.dto.costing.SkuCategoryImpactRow;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.service.SkuCategoryCutoverService;
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
 * promise: it cannot tell a completed cut-over from an accidental one, so it
 * advises and never vetoes startup.
 */
@DisplayName("SkuCategoryCutoverStartupCheck")
class SkuCategoryCutoverStartupCheckTest {

    private final SkuCategoryCutoverService cutoverService = mock(SkuCategoryCutoverService.class);
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(SkuCategoryCutoverStartupCheck.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static SkuCategoryImpactResponse.SkuCategoryImpactResponseBuilder report(boolean flagOn) {
        return SkuCategoryImpactResponse.builder()
                .resolveFromReplicaEnabled(flagOn)
                .deploymentDefaultMethod(CostingMethod.AVERAGE)
                .activeSkuCategoryConfigCount(0)
                .categoriesWithNoReplicatedProducts(List.of())
                .evaluatedSkuCount(0)
                .impactedSkuCount(0)
                .impactedSkuWithCostStateCount(0)
                .impactedSkus(List.of())
                .impactedSourcingSkus(List.of());
    }

    private void run() {
        assertThatCode(() -> new SkuCategoryCutoverStartupCheck(cutoverService).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("flag off with active SKU_CATEGORY configs logs that they are inert, and does not throw")
    void flagOffWithActiveSkuCategoryConfigs_logsInertNoticeAndDoesNotThrow() {
        when(cutoverService.impact())
                .thenReturn(report(false).activeSkuCategoryConfigCount(3).build());

        run();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("3", "inert", "sku-category-impact");
        });
    }

    @Test
    @DisplayName("flag on with impacted SKUs warns with the count and sample ids, and does not throw")
    void flagOnWithImpactedSkus_logsWarningAndDoesNotThrow() {
        when(cutoverService.impact())
                .thenReturn(report(true)
                        .activeSkuCategoryConfigCount(1)
                        .evaluatedSkuCount(2)
                        .impactedSkuCount(2)
                        .impactedSkus(List.of(
                                SkuCategoryImpactRow.builder()
                                        .stockItemId("sku-1")
                                        .build(),
                                SkuCategoryImpactRow.builder()
                                        .stockItemId("sku-2")
                                        .build()))
                        .build());

        run();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("sku-1", "sku-2", "OPERATIONS_RUNBOOK.md");
        });
    }

    @Test
    @DisplayName("a failing impact service is swallowed so startup never blocks")
    void impactServiceFailure_isSwallowedSoStartupNeverBlocks() {
        when(cutoverService.impact()).thenThrow(new IllegalStateException("replica unavailable"));

        run();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("replica unavailable");
        });
    }
}
