package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.enums.ReplenishmentDecisionReason;
import com.positivity.inventory.internal.enums.ReplenishmentSourceType;
import com.positivity.inventory.internal.movement.service.TransferOrderService;
import com.positivity.inventory.internal.replenishment.service.ReplenishmentSourcingService;
import com.positivity.inventory.internal.replenishment.service.ReplenishmentSourcingService.SourcingResolution;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.TransferOrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@code ReplenishmentSourcingService.resolve} (S3776 remediation, cognitive
 * complexity 19), targeting the three branches its otherwise-thorough end-to-end sibling
 * ({@code ReplenishmentSourcingScanTest}) never reaches: a stock-summary row with no location, a
 * same-site candidate that IS the pick face being sourced, and a selected cross-site source whose
 * transfer creation fails.
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentSourcingServiceTest {

    private static final String SKU = "SKU-UNIT-1";

    @Mock
    private InventoryStockSummaryRepository stockSummaryRepository;

    @Mock
    private ReplenishmentPolicyRepository replenishmentPolicyRepository;

    @Mock
    private TransferOrderRepository transferOrderRepository;

    @Mock
    private SourcingStrategyService sourcingStrategyService;

    @Mock
    private ForecastSiteResolver forecastSiteResolver;

    @Mock
    private VendorSelectionService vendorSelectionService;

    @Mock
    private TransferOrderService transferOrderService;

    private ReplenishmentSourcingService service;

    private ReplenishmentPolicy policy(UUID pickFaceLocationId, ReplenishmentSourceType sourceType) {
        return ReplenishmentPolicy.builder()
                .locationId(pickFaceLocationId)
                .itemSKU(SKU)
                .minimumQuantity(5)
                .maximumQuantity(20)
                .preferredSourceType(sourceType)
                .build();
    }

    private InventoryStockSummary summaryRow(UUID locationId, String onHand) {
        return InventoryStockSummary.builder()
                .stockItemId(SKU)
                .locationId(locationId)
                .onHand(new BigDecimal(onHand))
                .allocated(BigDecimal.ZERO)
                .build();
    }

    private void setUp() {
        service = new ReplenishmentSourcingService(
                stockSummaryRepository,
                replenishmentPolicyRepository,
                transferOrderRepository,
                sourcingStrategyService,
                forecastSiteResolver,
                vendorSelectionService,
                transferOrderService);
    }

    /**
     * The candidate loop skips a stock-summary row with no location before ever asking
     * {@code forecastSiteResolver} about it: a ledger entry can be posted without a location (e.g.
     * workorder consumption), and such a row can never be a sourceable location.
     */
    @Test
    void resolve_stockSummaryRowWithNullLocation_isSkippedAsCandidate() {
        setUp();
        UUID pickFace = UUID.randomUUID();
        UUID destSite = UUID.randomUUID();
        ReplenishmentPolicy policy = policy(pickFace, ReplenishmentSourceType.INTERNAL_TRANSFER);

        when(forecastSiteResolver.resolveForecastSite(pickFace)).thenReturn(destSite);
        when(stockSummaryRepository.findByStockItemId(SKU)).thenReturn(List.of(summaryRow(null, "50")));

        SourcingResolution resolution = service.resolve(policy, 10);

        assertThat(resolution.kind()).isEqualTo(ReplenishmentSourcingService.Kind.MATERIALIZE_TASK);
        assertThat(resolution.decisionReason()).isEqualTo(ReplenishmentDecisionReason.BACKSTOCK_UNAVAILABLE);
        assertThat(resolution.sourceLocationId()).isEqualTo(pickFace);
        // forecastSiteResolver is only ever asked about the destination site; the null-location
        // row never reaches the per-row resolveForecastSite call.
        verify(forecastSiteResolver, never()).resolveForecastSite(null);
        verify(sourcingStrategyService, never()).selectSource(any(), any());
    }

    /**
     * A row at the policy's OWN pick-face location can appear in the same-site candidate set (it
     * shares the destination site by definition), but sourcing a location from itself is
     * nonsensical — the comment on the production code calls this out explicitly. The row must be
     * excluded, not just deprioritized, so with no other candidate the need resolves to
     * BACKSTOCK_UNAVAILABLE rather than a same-site bin-move task with a source == destination.
     */
    @Test
    void resolve_onlyCandidateIsThePickFaceItself_excludedSoNoInternalSourceIsFound() {
        setUp();
        UUID pickFace = UUID.randomUUID();
        UUID destSite = UUID.randomUUID();
        ReplenishmentPolicy policy = policy(pickFace, ReplenishmentSourceType.INTERNAL_TRANSFER);

        when(forecastSiteResolver.resolveForecastSite(pickFace)).thenReturn(destSite);
        when(stockSummaryRepository.findByStockItemId(SKU)).thenReturn(List.of(summaryRow(pickFace, "50")));
        when(replenishmentPolicyRepository.findByLocationId(pickFace)).thenReturn(List.of());

        SourcingResolution resolution = service.resolve(policy, 10);

        assertThat(resolution.kind()).isEqualTo(ReplenishmentSourcingService.Kind.MATERIALIZE_TASK);
        assertThat(resolution.decisionReason()).isEqualTo(ReplenishmentDecisionReason.BACKSTOCK_UNAVAILABLE);
        assertThat(resolution.sourceLocationId()).isEqualTo(pickFace);
        assertThat(resolution.transferOrderId()).isNull();
        // Never even asks the strategy engine to pick a source: the candidate lists stayed empty.
        verify(sourcingStrategyService, never()).selectSource(any(), any());
    }

    /**
     * odoo-parity F5: when the selected cross-site source's transfer cannot be created (an
     * ineligible site, a validation failure — anything {@code createCrossSiteTransfer} catches),
     * the need must NOT be silently dropped. It falls through to the same
     * no-internal-source resolution as if nothing had qualified, so ops still sees an actionable
     * BACKSTOCK_UNAVAILABLE task instead of nothing happening at all.
     */
    @Test
    void resolve_selectedCrossSiteTransferCreationFails_fallsThroughToBackstockUnavailable() {
        setUp();
        UUID pickFace = UUID.randomUUID();
        UUID destSite = UUID.randomUUID();
        UUID sourceLoc = UUID.randomUUID();
        UUID sourceSite = UUID.randomUUID();
        ReplenishmentPolicy policy = policy(pickFace, ReplenishmentSourceType.INTERNAL_TRANSFER);

        when(forecastSiteResolver.resolveForecastSite(pickFace)).thenReturn(destSite);
        when(forecastSiteResolver.resolveForecastSite(sourceLoc)).thenReturn(sourceSite);
        when(stockSummaryRepository.findByStockItemId(SKU)).thenReturn(List.of(summaryRow(sourceLoc, "50")));
        when(replenishmentPolicyRepository.findByLocationId(sourceLoc)).thenReturn(List.of());

        SourcingStrategyService.SourcingCandidate candidate =
                new SourcingStrategyService.SourcingCandidate(sourceLoc, new BigDecimal("50"));
        SourcingStrategyService.SourcingDecision decision = new SourcingStrategyService.SourcingDecision(
                com.positivity.inventory.internal.enums.SourcingStrategy.FIFO,
                com.positivity.inventory.internal.enums.SourcingStrategy.FIFO,
                List.of(candidate));
        when(sourcingStrategyService.selectSource(
                        eq(new SourcingStrategyService.SourcingSelection(SKU, null, null, List.of(candidate))),
                        eq(BigDecimal.valueOf(10))))
                .thenReturn(java.util.Optional.of(new SourcingStrategyService.SourceSelection(candidate, decision)));
        when(transferOrderService.createTransferOrder(any())).thenThrow(new IllegalStateException("ineligible site"));

        SourcingResolution resolution = service.resolve(policy, 10);

        assertThat(resolution.kind()).isEqualTo(ReplenishmentSourcingService.Kind.MATERIALIZE_TASK);
        assertThat(resolution.decisionReason()).isEqualTo(ReplenishmentDecisionReason.BACKSTOCK_UNAVAILABLE);
        assertThat(resolution.sourceLocationId()).isEqualTo(pickFace);
        assertThat(resolution.transferOrderId()).isNull();
        assertThat(resolution.sourcingReason()).isNull();
    }
}
