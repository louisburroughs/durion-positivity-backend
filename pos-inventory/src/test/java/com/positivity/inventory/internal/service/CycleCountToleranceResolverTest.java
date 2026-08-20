package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.CycleCountTolerance;
import com.positivity.inventory.internal.repository.CycleCountToleranceRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tolerance resolution precedence and the absolute/percentage combining rule (ADR-0055 stage 4,
 * #1416; issue #1414 owner spec).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CycleCountToleranceResolverTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String LOCATION = "TANK-04";

    @Mock
    private CycleCountToleranceRepository repository;

    private CycleCountToleranceResolver resolver;

    private CycleCountToleranceResolver newResolver() {
        return new CycleCountToleranceResolver(repository);
    }

    private CycleCountTolerance tolerance(UUID productId, String location, String absolute) {
        return CycleCountTolerance.builder()
                .toleranceId(UUID.randomUUID())
                .productId(productId)
                .storageLocation(location)
                .absoluteTolerance(absolute == null ? null : new BigDecimal(absolute))
                .active(true)
                .createdBy("tester")
                .build();
    }

    @Test
    void resolve_prefersProductAndLocationOverEveryBroaderScope() {
        resolver = newResolver();
        CycleCountTolerance mostSpecific = tolerance(PRODUCT_ID, LOCATION, "10");
        when(repository.findByProductIdAndStorageLocationAndActiveTrue(PRODUCT_ID, LOCATION))
                .thenReturn(Optional.of(mostSpecific));

        CycleCountToleranceResolver.Resolution resolution = resolver.resolve(PRODUCT_ID, LOCATION);

        assertThat(resolution.absoluteTolerance()).isEqualByComparingTo("10");
        verify(repository, never()).findByProductIdAndStorageLocationIsNullAndActiveTrue(any());
        verify(repository, never()).findByProductIdIsNullAndStorageLocationAndActiveTrue(any());
        verify(repository, never()).findByProductIdIsNullAndStorageLocationIsNullAndActiveTrue();
    }

    @Test
    void resolve_fallsBackToProductOnlyWhenNoProductAndLocationRowMatches() {
        resolver = newResolver();
        when(repository.findByProductIdAndStorageLocationAndActiveTrue(PRODUCT_ID, LOCATION))
                .thenReturn(Optional.empty());
        when(repository.findByProductIdAndStorageLocationIsNullAndActiveTrue(PRODUCT_ID))
                .thenReturn(Optional.of(tolerance(PRODUCT_ID, null, "20")));

        CycleCountToleranceResolver.Resolution resolution = resolver.resolve(PRODUCT_ID, LOCATION);

        assertThat(resolution.absoluteTolerance()).isEqualByComparingTo("20");
        verify(repository, never()).findByProductIdIsNullAndStorageLocationAndActiveTrue(any());
    }

    @Test
    void resolve_fallsBackToLocationOnlyWhenProductScopeAbsent() {
        resolver = newResolver();
        when(repository.findByProductIdIsNullAndStorageLocationAndActiveTrue(LOCATION))
                .thenReturn(Optional.of(tolerance(null, LOCATION, "30")));

        CycleCountToleranceResolver.Resolution resolution = resolver.resolve(null, LOCATION);

        assertThat(resolution.absoluteTolerance()).isEqualByComparingTo("30");
    }

    @Test
    void resolve_fallsBackToGlobalDefaultWhenNoScopedRowMatches() {
        resolver = newResolver();
        when(repository.findByProductIdAndStorageLocationAndActiveTrue(PRODUCT_ID, LOCATION))
                .thenReturn(Optional.empty());
        when(repository.findByProductIdAndStorageLocationIsNullAndActiveTrue(PRODUCT_ID))
                .thenReturn(Optional.empty());
        when(repository.findByProductIdIsNullAndStorageLocationAndActiveTrue(LOCATION))
                .thenReturn(Optional.empty());
        when(repository.findByProductIdIsNullAndStorageLocationIsNullAndActiveTrue())
                .thenReturn(Optional.of(tolerance(null, null, "5")));

        CycleCountToleranceResolver.Resolution resolution = resolver.resolve(PRODUCT_ID, LOCATION);

        assertThat(resolution.absoluteTolerance()).isEqualByComparingTo("5");
    }

    @Test
    void resolve_returnsNoneWhenNothingMatchesAtAnyScope() {
        resolver = newResolver();

        CycleCountToleranceResolver.Resolution resolution = resolver.resolve(PRODUCT_ID, LOCATION);

        assertThat(resolution).isEqualTo(CycleCountToleranceResolver.Resolution.NONE);
        assertThat(resolution.isConfigured()).isFalse();
    }

    @Test
    void resolve_treatsBlankLocationAsNoLocationScope() {
        resolver = newResolver();
        when(repository.findByProductIdAndStorageLocationIsNullAndActiveTrue(PRODUCT_ID))
                .thenReturn(Optional.of(tolerance(PRODUCT_ID, null, "7")));

        CycleCountToleranceResolver.Resolution resolution = resolver.resolve(PRODUCT_ID, "   ");

        assertThat(resolution.absoluteTolerance()).isEqualByComparingTo("7");
        verify(repository, never()).findByProductIdAndStorageLocationAndActiveTrue(any(), any());
    }

    @Test
    void isWithinTolerance_zeroToleranceRequiresExactMatch() {
        resolver = newResolver();
        CycleCountToleranceResolver.Resolution none = CycleCountToleranceResolver.Resolution.NONE;

        assertThat(resolver.isWithinTolerance(none, BigDecimal.ZERO, new BigDecimal("100")))
                .isTrue();
        assertThat(resolver.isWithinTolerance(none, new BigDecimal("0.0001"), new BigDecimal("100")))
                .isFalse();
    }

    @Test
    void isWithinTolerance_absoluteBoundAlone() {
        resolver = newResolver();
        CycleCountToleranceResolver.Resolution resolution =
                new CycleCountToleranceResolver.Resolution(UUID.randomUUID(), new BigDecimal("50"), null);

        assertThat(resolver.isWithinTolerance(resolution, new BigDecimal("50"), new BigDecimal("8000")))
                .isTrue();
        assertThat(resolver.isWithinTolerance(resolution, new BigDecimal("50.01"), new BigDecimal("8000")))
                .isFalse();
    }

    @Test
    void isWithinTolerance_percentageBoundAlone() {
        resolver = newResolver();
        CycleCountToleranceResolver.Resolution resolution =
                new CycleCountToleranceResolver.Resolution(UUID.randomUUID(), null, new BigDecimal("1"));

        // 1% of 8000 = 80.
        assertThat(resolver.isWithinTolerance(resolution, new BigDecimal("80"), new BigDecimal("8000")))
                .isTrue();
        assertThat(resolver.isWithinTolerance(resolution, new BigDecimal("80.01"), new BigDecimal("8000")))
                .isFalse();
    }

    @Test
    void isWithinTolerance_bothConfigured_acceptsUnderEitherBound_percentageWiderThanAbsolute() {
        resolver = newResolver();
        // Absolute 50, percentage 1% of 8000 = 80: the wider bound (80) should govern.
        CycleCountToleranceResolver.Resolution resolution = new CycleCountToleranceResolver.Resolution(
                UUID.randomUUID(), new BigDecimal("50"), new BigDecimal("1"));

        assertThat(resolver.isWithinTolerance(resolution, new BigDecimal("75"), new BigDecimal("8000")))
                .as("75 exceeds the absolute bound (50) but fits the percentage bound (80)")
                .isTrue();
        assertThat(resolver.isWithinTolerance(resolution, new BigDecimal("110"), new BigDecimal("8000")))
                .isFalse();
    }

    @Test
    void isWithinTolerance_bothConfigured_absoluteWiderThanPercentage() {
        resolver = newResolver();
        // Absolute 200, percentage 1% of 8000 = 80: the wider bound (200) should govern.
        CycleCountToleranceResolver.Resolution resolution = new CycleCountToleranceResolver.Resolution(
                UUID.randomUUID(), new BigDecimal("200"), new BigDecimal("1"));

        assertThat(resolver.isWithinTolerance(resolution, new BigDecimal("150"), new BigDecimal("8000")))
                .as("150 exceeds the percentage bound (80) but fits the absolute bound (200)")
                .isTrue();
    }

    @Test
    void isWithinTolerance_zeroBookQuantity_percentageBoundAllowsNothing() {
        resolver = newResolver();
        CycleCountToleranceResolver.Resolution resolution =
                new CycleCountToleranceResolver.Resolution(UUID.randomUUID(), null, new BigDecimal("5"));

        assertThat(resolver.isWithinTolerance(resolution, BigDecimal.ZERO, BigDecimal.ZERO))
                .isTrue();
        assertThat(resolver.isWithinTolerance(resolution, new BigDecimal("0.01"), BigDecimal.ZERO))
                .isFalse();
    }
}
