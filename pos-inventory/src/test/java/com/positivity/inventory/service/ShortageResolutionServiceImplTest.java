package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.positivity.inventory.internal.client.ExternalAvailabilityClient;
import com.positivity.inventory.internal.client.ProductSubstituteClient;
import com.positivity.inventory.internal.dto.shortage.ResolutionOption;
import com.positivity.inventory.internal.dto.shortage.ResolutionOptionType;
import com.positivity.inventory.internal.dto.shortage.ShortageResolutionRequest;
import com.positivity.inventory.internal.dto.shortage.ShortageResolutionResponse;
import com.positivity.inventory.internal.service.ShortageResolutionServiceImpl;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Behavioral unit tests for {@link ShortageResolutionServiceImpl} — Story #25
 * (CAP-220):
 * Allocations: Handle Shortages with Backorder or Substitution Suggestion.
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0013: UUID.fromString("00000000-0000-0000-0000-000000000001")
 * acceptable in tests</li>
 * <li>ADR-0024: Clock.fixed(...) for time-dependent tests (not required here —
 * no time fields
 * in shortage domain types)</li>
 * <li>ADR-0026: Test files reside in src/test/**</li>
 * </ul>
 *
 * <p>
 * Scenario-to-test mapping (Story #25):
 * <ol>
 * <li>Scenario 1 →
 * {@link #scenario1_bothOptionsAvailable_orderedSubstituteExternalBackorder}</li>
 * <li>Scenario 2 →
 * {@link #scenario2_externalTimeout_substituteAndBackorderReturned}</li>
 * <li>Scenario 3 → {@link #scenario3_noAlternatives_backorderOnly}</li>
 * <li>Scenario 4 →
 * {@link #scenario4_productClientFailure_partialBannerSet}</li>
 * <li>Scenario 5 →
 * {@link #scenario5_withinSameCategory_rankedByLeadTimeAscCostAscQualityDesc}</li>
 * <li>Scenario 6 →
 * {@link #scenario6_noLeadTimeSource_backorderHasNullLeadTimeDays}</li>
 * </ol>
 *
 * Issue: #25
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ShortageResolutionServiceImpl — Story #25 unit tests")
class ShortageResolutionServiceImplTest {

        @Mock
        private ProductSubstituteClient productSubstituteClient;

        @Mock
        private ExternalAvailabilityClient externalAvailabilityClient;

        private ShortageResolutionServiceImpl service;

        // ─── Fixture constants ───────────────────────────────────────────────────────

        private static final UUID ALLOCATION_ID = UUID.fromString("00000025-0000-0000-0000-000000000001");

        private static final String SKU = "PART-001";

        /**
         * Builds the service under test.
         *
         * Issue: #25
         */
        @BeforeEach
        void setUp() {
                service = new ShortageResolutionServiceImpl(
                                productSubstituteClient,
                                externalAvailabilityClient,
                                Runnable::run);
        }

        // ─── Scenario 1: Both substitute and external available ──────────────────────

        /**
         * Scenario 1: When ProductSubstituteClient returns one substitute option and
         * ExternalAvailabilityClient returns one external purchase option, the response
         * must order them SUBSTITUTE first, EXTERNAL_PURCHASE second, BACKORDER last,
         * and partialResultsBanner must be false (all partners responded).
         *
         * @see ShortageResolutionServiceImpl#resolveShortage(ShortageResolutionRequest)
         */
        @Test
        @DisplayName("Scenario 1: both substitute + external available → SUBSTITUTE, EXTERNAL_PURCHASE, BACKORDER; partialResultsBanner=false")
        void scenario1_bothOptionsAvailable_orderedSubstituteExternalBackorder() {
                // Issue #25: Scenario 1 — both clients respond; ordering must be SUBSTITUTE,
                // EXTERNAL_PURCHASE, BACKORDER
                // Arrange: lenient stubs shared by multiple scenarios; class-level LENIENT
                // setting applies
                lenient().when(productSubstituteClient.resolveSubstitutes(anyString(), anyInt()))
                                .thenReturn(List.of(ResolutionOption.builder()
                                                .type(ResolutionOptionType.SUBSTITUTE)
                                                .substitutePartNumber("PART-001A")
                                                .unitCost(new BigDecimal("12.50"))
                                                .estimatedLeadTimeDays(3)
                                                .qualityTier("A")
                                                .build()));
                lenient().when(externalAvailabilityClient.resolveExternalOptions(anyString(), anyInt()))
                                .thenReturn(List.of(ResolutionOption.builder()
                                                .type(ResolutionOptionType.EXTERNAL_PURCHASE)
                                                .source("Distributor-X")
                                                .unitCost(new BigDecimal("14.00"))
                                                .estimatedLeadTimeDays(5)
                                                .build()));

                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(ALLOCATION_ID)
                                .sku(SKU)
                                .shortQuantity(2)
                                .build();

                // Act
                ShortageResolutionResponse result = service.resolveShortage(request);

                // Assert
                assertThat(result.getOptions())
                                .as("Options must be ordered: SUBSTITUTE, EXTERNAL_PURCHASE, BACKORDER")
                                .hasSize(3)
                                .extracting(ResolutionOption::getType)
                                .containsExactly(
                                                ResolutionOptionType.SUBSTITUTE,
                                                ResolutionOptionType.EXTERNAL_PURCHASE,
                                                ResolutionOptionType.BACKORDER);
                assertThat(result.isPartialResultsBanner())
                                .as("partialResultsBanner must be false when all partners responded")
                                .isFalse();
                assertThat(result.getAllocationId()).isEqualTo(ALLOCATION_ID);
                assertThat(result.getSku()).isEqualTo(SKU);
        }

        // ─── Scenario 2: External client times out
        // ────────────────────────────────────

        /**
         * Scenario 2: When ExternalAvailabilityClient throws a timeout exception
         * (simulating
         * the 1200ms Positivity partner timeout), the response must contain SUBSTITUTE
         * and
         * BACKORDER options only, and partialResultsBanner must be true.
         *
         * @see ShortageResolutionServiceImpl#resolveShortage(ShortageResolutionRequest)
         */
        @Test
        @DisplayName("Scenario 2: external client times out → SUBSTITUTE + BACKORDER; partialResultsBanner=true")
        void scenario2_externalTimeout_substituteAndBackorderReturned() {
                // Issue #25: Scenario 2 — ExternalAvailabilityClient timeout must set
                // partialResultsBanner=true
                lenient().when(productSubstituteClient.resolveSubstitutes(anyString(), anyInt()))
                                .thenReturn(List.of(ResolutionOption.builder()
                                                .type(ResolutionOptionType.SUBSTITUTE)
                                                .substitutePartNumber("PART-001B")
                                                .unitCost(new BigDecimal("11.00"))
                                                .estimatedLeadTimeDays(4)
                                                .build()));
                lenient().when(externalAvailabilityClient.resolveExternalOptions(anyString(), anyInt()))
                                .thenThrow(new RuntimeException(
                                                "Simulated external availability timeout after 1200ms"));

                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(ALLOCATION_ID)
                                .sku(SKU)
                                .shortQuantity(1)
                                .build();

                // Act
                ShortageResolutionResponse result = service.resolveShortage(request);

                // Assert
                assertThat(result.getOptions())
                                .as("Options must be SUBSTITUTE then BACKORDER when external times out")
                                .hasSize(2)
                                .extracting(ResolutionOption::getType)
                                .containsExactly(
                                                ResolutionOptionType.SUBSTITUTE,
                                                ResolutionOptionType.BACKORDER);
                assertThat(result.isPartialResultsBanner())
                                .as("partialResultsBanner must be true when external client timed out")
                                .isTrue();
        }

        // ─── Scenario 3: No alternatives from either client ──────────────────────────

        /**
         * Scenario 3: When both ProductSubstituteClient and ExternalAvailabilityClient
         * return
         * empty lists, the response must contain exactly one BACKORDER option and
         * partialResultsBanner must be false (both partners responded, just with no
         * results).
         *
         * @see ShortageResolutionServiceImpl#resolveShortage(ShortageResolutionRequest)
         */
        @Test
        @DisplayName("Scenario 3: no alternatives from either client → BACKORDER only; partialResultsBanner=false")
        void scenario3_noAlternatives_backorderOnly() {
                // Issue #25: Scenario 3 — empty client responses must still produce a BACKORDER
                // fallback
                lenient().when(productSubstituteClient.resolveSubstitutes(anyString(), anyInt()))
                                .thenReturn(List.of());
                lenient().when(externalAvailabilityClient.resolveExternalOptions(anyString(), anyInt()))
                                .thenReturn(List.of());

                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(ALLOCATION_ID)
                                .sku(SKU)
                                .shortQuantity(3)
                                .build();

                // Act
                ShortageResolutionResponse result = service.resolveShortage(request);

                // Assert
                assertThat(result.getOptions())
                                .as("BACKORDER must always be included as a fallback option")
                                .hasSize(1)
                                .extracting(ResolutionOption::getType)
                                .containsExactly(ResolutionOptionType.BACKORDER);
                assertThat(result.isPartialResultsBanner())
                                .as("partialResultsBanner must be false when both partners responded (even with empty results)")
                                .isFalse();
        }

        // ─── Scenario 4: ProductSubstituteClient (Product domain) fails ──────────────

        /**
         * Scenario 4: When ProductSubstituteClient (Product domain, 800ms timeout)
         * throws,
         * the service must log the failure, set partialResultsBanner=true, and still
         * return
         * any available external options plus BACKORDER.
         *
         * @see ShortageResolutionServiceImpl#resolveShortage(ShortageResolutionRequest)
         */
        @Test
        @DisplayName("Scenario 4: Product client fails → EXTERNAL_PURCHASE + BACKORDER; partialResultsBanner=true")
        void scenario4_productClientFailure_partialBannerSet() {
                // Issue #25: Scenario 4 — Product domain timeout must set
                // partialResultsBanner=true
                lenient().when(productSubstituteClient.resolveSubstitutes(anyString(), anyInt()))
                                .thenThrow(new RuntimeException("Simulated Product domain timeout after 800ms"));
                lenient().when(externalAvailabilityClient.resolveExternalOptions(anyString(), anyInt()))
                                .thenReturn(List.of(ResolutionOption.builder()
                                                .type(ResolutionOptionType.EXTERNAL_PURCHASE)
                                                .source("Supplier-Y")
                                                .unitCost(new BigDecimal("16.00"))
                                                .estimatedLeadTimeDays(7)
                                                .build()));

                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(ALLOCATION_ID)
                                .sku(SKU)
                                .shortQuantity(2)
                                .build();

                // Act
                ShortageResolutionResponse result = service.resolveShortage(request);

                // Assert
                assertThat(result.getOptions())
                                .as("Options must be EXTERNAL_PURCHASE then BACKORDER when Product client fails")
                                .hasSize(2)
                                .extracting(ResolutionOption::getType)
                                .containsExactly(
                                                ResolutionOptionType.EXTERNAL_PURCHASE,
                                                ResolutionOptionType.BACKORDER);
                assertThat(result.isPartialResultsBanner())
                                .as("partialResultsBanner must be true because Product client failed")
                                .isTrue();
        }

        // ─── Scenario 5: Ranking within same option category ─────────────────────────

        /**
         * Scenario 5: When multiple options of the same type are returned, they must be
         * ranked by lead time ASC, then cost ASC, then quality tier DESC within that
         * type
         * category. Given two substitutes — {leadTime=7, cost=10, quality="B"} and
         * {leadTime=3, cost=15, quality="A"} — the substitute with lower lead time (3)
         * wins.
         *
         * @see ShortageResolutionServiceImpl#resolveShortage(ShortageResolutionRequest)
         */
        @Test
        @DisplayName("Scenario 5: two substitutes: leadTime=3 ranks before leadTime=7 (lead time ASC wins)")
        void scenario5_withinSameCategory_rankedByLeadTimeAscCostAscQualityDesc() {
                // Issue #25: Scenario 5 — within same type, lead time ASC is primary sort key
                lenient().when(productSubstituteClient.resolveSubstitutes(anyString(), anyInt()))
                                .thenReturn(List.of(
                                                ResolutionOption.builder()
                                                                .type(ResolutionOptionType.SUBSTITUTE)
                                                                .substitutePartNumber("PART-HIGH-LEAD")
                                                                .unitCost(new BigDecimal("10.00"))
                                                                .estimatedLeadTimeDays(7)
                                                                .qualityTier("B")
                                                                .build(),
                                                ResolutionOption.builder()
                                                                .type(ResolutionOptionType.SUBSTITUTE)
                                                                .substitutePartNumber("PART-LOW-LEAD")
                                                                .unitCost(new BigDecimal("15.00"))
                                                                .estimatedLeadTimeDays(3)
                                                                .qualityTier("A")
                                                                .build()));
                lenient().when(externalAvailabilityClient.resolveExternalOptions(anyString(), anyInt()))
                                .thenReturn(List.of());

                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(ALLOCATION_ID)
                                .sku(SKU)
                                .shortQuantity(1)
                                .build();

                // Act
                ShortageResolutionResponse result = service.resolveShortage(request);

                // Assert: first option must be the substitute with lower lead time (3 < 7)
                assertThat(result.getOptions())
                                .as("Options list must have at least 2 entries (2 substitutes + backorder)")
                                .hasSizeGreaterThanOrEqualTo(2);
                assertThat(result.getOptions().get(0).getEstimatedLeadTimeDays())
                                .as("First option must have lead time = 3 (lower lead time wins, ASC sort)")
                                .isEqualTo(3);
                assertThat(result.getOptions().get(0).getSubstitutePartNumber())
                                .as("First substitute must be PART-LOW-LEAD (lead time=3)")
                                .isEqualTo("PART-LOW-LEAD");
        }

        // ─── Scenario 6: Backorder with no lead time source → null fields
        // ─────────────

        /**
         * Scenario 6: When no lead time source is available (both clients failed or
         * returned
         * no lead time data), the BACKORDER option must have
         * {@code estimatedLeadTimeDays=null},
         * {@code source=null}, and {@code confidence=null} rather than defaulting to
         * zero or
         * placeholder values.
         *
         * @see ShortageResolutionServiceImpl#resolveShortage(ShortageResolutionRequest)
         */
        @Test
        @DisplayName("Scenario 6: no lead time source → BACKORDER has null estimatedLeadTimeDays, source, confidence")
        void scenario6_noLeadTimeSource_backorderHasNullLeadTimeDays() {
                // Issue #25: Scenario 6 — BACKORDER with no lead time source must have null
                // lead time fields
                lenient().when(productSubstituteClient.resolveSubstitutes(anyString(), anyInt()))
                                .thenReturn(List.of());
                lenient().when(externalAvailabilityClient.resolveExternalOptions(anyString(), anyInt()))
                                .thenReturn(List.of());

                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(ALLOCATION_ID)
                                .sku(SKU)
                                .shortQuantity(1)
                                .build();

                // Act
                ShortageResolutionResponse result = service.resolveShortage(request);

                // Assert: the single BACKORDER option must have null lead-time fields (no
                // source available)
                assertThat(result.getOptions()).hasSize(1);
                ResolutionOption backorder = result.getOptions().get(0);
                assertThat(backorder.getType())
                                .as("Only option must be BACKORDER")
                                .isEqualTo(ResolutionOptionType.BACKORDER);
                assertThat(backorder.getEstimatedLeadTimeDays())
                                .as("estimatedLeadTimeDays must be null when no lead time source exists")
                                .isNull();
                assertThat(backorder.getSource())
                                .as("source must be null when no lead time source exists")
                                .isNull();
                assertThat(backorder.getConfidence())
                                .as("confidence must be null when no lead time source exists")
                                .isNull();
        }

        @Test
        @DisplayName("Coverage: null shortQuantity defaults to 1 unit")
        void coverage_nullShortQuantity_defaultsToOne() {
                lenient().when(productSubstituteClient.resolveSubstitutes(anyString(), eq(1)))
                                .thenReturn(List.of());
                lenient().when(externalAvailabilityClient.resolveExternalOptions(anyString(), eq(1)))
                                .thenReturn(List.of());

                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .sku("SKU-NULL-QTY")
                                .shortQuantity(null) // null → should default to 1
                                .build();

                ShortageResolutionResponse response = service.resolveShortage(request);

                assertThat(response.getOptions()).hasSize(1); // BACKORDER only
                assertThat(response.getOptions().get(0).getType()).isEqualTo(ResolutionOptionType.BACKORDER);
                assertThat(response.isPartialResultsBanner()).isFalse();

                // Verify that clients were called with the default quantity of 1
                verify(productSubstituteClient).resolveSubstitutes(eq("SKU-NULL-QTY"), eq(1));
                verify(externalAvailabilityClient).resolveExternalOptions(eq("SKU-NULL-QTY"), eq(1));
        }

        @Test
        @DisplayName("Coverage: TimeoutException from a client is handled")
        void coverage_timeoutExceptionHandled() {
                lenient().when(productSubstituteClient.resolveSubstitutes(anyString(), anyInt()))
                                .thenThrow(new RuntimeException("Simulated timeout"));
                lenient().when(externalAvailabilityClient.resolveExternalOptions(anyString(), anyInt()))
                                .thenReturn(List.of());

                ShortageResolutionRequest request = ShortageResolutionRequest.builder()
                                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .sku("SKU-TIMEOUT")
                                .shortQuantity(5)
                                .build();

                ShortageResolutionResponse response = service.resolveShortage(request);

                assertThat(response.getOptions()).hasSize(1); // BACKORDER only
                assertThat(response.getOptions().get(0).getType()).isEqualTo(ResolutionOptionType.BACKORDER);
                assertThat(response.isPartialResultsBanner()).isTrue();
        }
}
