package com.positivity.inventory.internal.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.ShortageOptionDto;
import com.positivity.inventory.internal.dto.ShortageResolutionResultDto;
import com.positivity.inventory.internal.dto.ShortageResolveRequest;
import com.positivity.inventory.internal.dto.backorder.BackorderResponse;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.dto.transfer.TransferOrderResponse;
import com.positivity.inventory.internal.entity.ExtProductSubstitutionReplica;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ShortageResolutionRecord;
import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.enums.ShortageResolutionOption;
import com.positivity.inventory.internal.exception.ShortageResolutionException;
import com.positivity.inventory.internal.movement.service.TransferOrderService;
import com.positivity.inventory.internal.repository.ExtProductSubstitutionReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ShortageResolutionRecordRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.inventory.internal.service.ForecastSiteResolver;
import com.positivity.inventory.internal.service.VendorSelectionService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShortageResolutionServiceImpl (odoo-parity G2)")
class ShortageResolutionServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);

    private static final UUID ALLOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID WORKORDER_LINE = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID SKU_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final String SKU = SKU_ID.toString();
    private static final UUID SUBSTITUTE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final String SUBSTITUTE = SUBSTITUTE_ID.toString();
    private static final UUID SITE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID SIBLING_SITE = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID SIBLING_LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000d3");

    private ExtProductSubstitutionReplicaRepository substitutionReplicaRepository;
    private InventoryStockSummaryRepository stockSummaryRepository;
    private ReplenishmentPolicyRepository replenishmentPolicyRepository;
    private SkuCostStateRepository skuCostStateRepository;
    private PurchaseSuggestionRepository purchaseSuggestionRepository;
    private ShortageResolutionRecordRepository resolutionRecordRepository;
    private BackorderService backorderService;
    private TransferOrderService transferOrderService;
    private ReservationService reservationService;
    private VendorSelectionService vendorSelectionService;
    private ForecastSiteResolver forecastSiteResolver;

    private ShortageResolutionServiceImpl service;

    @BeforeEach
    void setUp() {
        substitutionReplicaRepository = mock(ExtProductSubstitutionReplicaRepository.class);
        stockSummaryRepository = mock(InventoryStockSummaryRepository.class);
        replenishmentPolicyRepository = mock(ReplenishmentPolicyRepository.class);
        skuCostStateRepository = mock(SkuCostStateRepository.class);
        purchaseSuggestionRepository = mock(PurchaseSuggestionRepository.class);
        resolutionRecordRepository = mock(ShortageResolutionRecordRepository.class);
        backorderService = mock(BackorderService.class);
        transferOrderService = mock(TransferOrderService.class);
        reservationService = mock(ReservationService.class);
        vendorSelectionService = mock(VendorSelectionService.class);
        forecastSiteResolver = mock(ForecastSiteResolver.class);

        service = new ShortageResolutionServiceImpl(
                substitutionReplicaRepository,
                stockSummaryRepository,
                replenishmentPolicyRepository,
                skuCostStateRepository,
                purchaseSuggestionRepository,
                resolutionRecordRepository,
                backorderService,
                transferOrderService,
                reservationService,
                vendorSelectionService,
                forecastSiteResolver,
                TEST_CLOCK);

        // Default: no vendor, no replica, no surplus, no cost state, no policy.
        when(vendorSelectionService.selectVendor(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new VendorSelectionService.VendorSelection(null, "no vendor"));
        when(substitutionReplicaRepository.findByProductId(any())).thenReturn(List.of());
        when(stockSummaryRepository.findByStockItemId(any())).thenReturn(List.of());
        when(stockSummaryRepository.findByStockItemIdAndLocationId(any(), any()))
                .thenReturn(Optional.empty());
        when(skuCostStateRepository.findByStockItemId(any())).thenReturn(Optional.empty());
        when(replenishmentPolicyRepository.findByItemSKUAndLocationId(any(), any()))
                .thenReturn(Optional.empty());
        when(resolutionRecordRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(resolutionRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("options always include BACKORDER, EMERGENCY_PURCHASE and CANCEL_LINE with expected dates")
    void options_alwaysIncludeCoreOptions() {
        List<ShortageOptionDto> options =
                service.computeShortageOptions(ALLOCATION, WORKORDER_LINE, SKU, new BigDecimal("3"), SITE);

        assertThat(options)
                .extracting(ShortageOptionDto::getOptionType)
                .contains(
                        ShortageResolutionOption.BACKORDER,
                        ShortageResolutionOption.EMERGENCY_PURCHASE,
                        ShortageResolutionOption.CANCEL_LINE);

        ShortageOptionDto backorder = optionOf(options, ShortageResolutionOption.BACKORDER);
        assertThat(backorder.getExpectedResolutionDate()).isEqualTo(TODAY.plusDays(7));

        ShortageOptionDto cancel = optionOf(options, ShortageResolutionOption.CANCEL_LINE);
        assertThat(cancel.getExpectedResolutionDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("BACKORDER expected date uses the SKU's replenishment lead time when a policy exists")
    void backorderOption_usesPolicyLeadTime() {
        when(replenishmentPolicyRepository.findByItemSKUAndLocationId(SKU, SITE))
                .thenReturn(Optional.of(
                        ReplenishmentPolicy.builder().leadTimeDaysOverride(2).build()));

        List<ShortageOptionDto> options =
                service.computeShortageOptions(ALLOCATION, WORKORDER_LINE, SKU, new BigDecimal("3"), SITE);

        assertThat(optionOf(options, ShortageResolutionOption.BACKORDER).getExpectedResolutionDate())
                .isEqualTo(TODAY.plusDays(2));
    }

    @Test
    @DisplayName("SUBSTITUTE option computed from the X1 replica with live ATP and a cost delta")
    void substituteOption_computedFromReplicaWithCostDelta() {
        when(substitutionReplicaRepository.findByProductId(SKU_ID))
                .thenReturn(List.of(
                        ExtProductSubstitutionReplica.builder()
                                .productId(SKU_ID)
                                .memberProductId(SKU_ID)
                                .build(), // self — must be skipped
                        ExtProductSubstitutionReplica.builder()
                                .productId(SKU_ID)
                                .memberProductId(SUBSTITUTE_ID)
                                .build()));
        when(stockSummaryRepository.findByStockItemIdAndLocationId(SUBSTITUTE, SITE))
                .thenReturn(Optional.of(summary(SUBSTITUTE, SITE, 10, 0)));
        when(skuCostStateRepository.findByStockItemId(SKU)).thenReturn(Optional.of(costState("20.00")));
        when(skuCostStateRepository.findByStockItemId(SUBSTITUTE)).thenReturn(Optional.of(costState("24.00")));

        List<ShortageOptionDto> options =
                service.computeShortageOptions(ALLOCATION, WORKORDER_LINE, SKU, new BigDecimal("3"), SITE);

        List<ShortageOptionDto> substitutes = options.stream()
                .filter(o -> o.getOptionType() == ShortageResolutionOption.SUBSTITUTE)
                .toList();
        assertThat(substitutes).hasSize(1);
        ShortageOptionDto sub = substitutes.getFirst();
        assertThat(sub.getSubstituteSku()).isEqualTo(SUBSTITUTE);
        assertThat(sub.getAvailableQuantity()).isEqualByComparingTo("10");
        assertThat(sub.getExpectedResolutionDate()).isEqualTo(TODAY);
        assertThat(sub.getCostDelta()).isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("SUBSTITUTE option omitted when the member has no live ATP at the site")
    void substituteOption_omittedWhenNoAtp() {
        when(substitutionReplicaRepository.findByProductId(SKU_ID))
                .thenReturn(List.of(ExtProductSubstitutionReplica.builder()
                        .productId(SKU_ID)
                        .memberProductId(SUBSTITUTE_ID)
                        .build()));
        when(stockSummaryRepository.findByStockItemIdAndLocationId(SUBSTITUTE, SITE))
                .thenReturn(Optional.of(summary(SUBSTITUTE, SITE, 0, 0)));

        List<ShortageOptionDto> options =
                service.computeShortageOptions(ALLOCATION, WORKORDER_LINE, SKU, new BigDecimal("3"), SITE);

        assertThat(options)
                .extracting(ShortageOptionDto::getOptionType)
                .doesNotContain(ShortageResolutionOption.SUBSTITUTE);
    }

    @Test
    @DisplayName("TRANSFER_IN option offered when a sibling site's surplus covers the shortage")
    void transferInOption_offeredWhenSiblingSurplusCovers() {
        when(forecastSiteResolver.resolveForecastSite(SITE)).thenReturn(SITE);
        when(forecastSiteResolver.resolveForecastSite(SIBLING_LOCATION)).thenReturn(SIBLING_SITE);
        when(stockSummaryRepository.findByStockItemId(SKU))
                .thenReturn(List.of(summary(SKU, SIBLING_LOCATION, 8, 3))); // surplus 5 >= 3

        List<ShortageOptionDto> options =
                service.computeShortageOptions(ALLOCATION, WORKORDER_LINE, SKU, new BigDecimal("3"), SITE);

        ShortageOptionDto transfer = optionOf(options, ShortageResolutionOption.TRANSFER_IN);
        assertThat(transfer.getSourceLocationId()).isEqualTo(SIBLING_SITE);
        assertThat(transfer.getAvailableQuantity()).isEqualByComparingTo("5");
        assertThat(transfer.getExpectedResolutionDate()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    @DisplayName("TRANSFER_IN option omitted when sibling surplus is below the shortage")
    void transferInOption_omittedWhenSurplusInsufficient() {
        when(forecastSiteResolver.resolveForecastSite(SITE)).thenReturn(SITE);
        when(forecastSiteResolver.resolveForecastSite(SIBLING_LOCATION)).thenReturn(SIBLING_SITE);
        when(stockSummaryRepository.findByStockItemId(SKU))
                .thenReturn(List.of(summary(SKU, SIBLING_LOCATION, 4, 3))); // surplus 1 < 3

        List<ShortageOptionDto> options =
                service.computeShortageOptions(ALLOCATION, WORKORDER_LINE, SKU, new BigDecimal("3"), SITE);

        assertThat(options)
                .extracting(ShortageOptionDto::getOptionType)
                .doesNotContain(ShortageResolutionOption.TRANSFER_IN);
    }

    @Test
    @DisplayName("EMERGENCY_PURCHASE option uses vendor lead time and cost delta")
    void emergencyPurchaseOption_usesVendorLeadAndCost() {
        when(skuCostStateRepository.findByStockItemId(SKU)).thenReturn(Optional.of(costState("20.00")));
        VendorSelectionService.VendorCandidate vendor = new VendorSelectionService.VendorCandidate(
                com.positivity.inventory.internal.enums.SuggestedVendorType.MANUFACTURER,
                UUID.randomUUID(),
                100,
                5,
                2500L,
                "USD",
                null,
                null);
        when(vendorSelectionService.selectVendor(eq(SKU_ID), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new VendorSelectionService.VendorSelection(vendor, "chosen"));

        List<ShortageOptionDto> options =
                service.computeShortageOptions(ALLOCATION, WORKORDER_LINE, SKU, new BigDecimal("3"), SITE);

        ShortageOptionDto emergency = optionOf(options, ShortageResolutionOption.EMERGENCY_PURCHASE);
        assertThat(emergency.getExpectedResolutionDate()).isEqualTo(TODAY.plusDays(5));
        assertThat(emergency.getCostDelta()).isEqualByComparingTo("5.00"); // 25.00 - 20.00
    }

    @Test
    @DisplayName("resolve BACKORDER creates a backorder and records the artifact reference")
    void resolveBackorder_createsBackorderAndRecords() {
        UUID backorderId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        when(backorderService.createBackorder(WORKORDER_LINE, SKU, new BigDecimal("3"), SITE))
                .thenReturn(BackorderResponse.builder().backorderId(backorderId).build());

        ShortageResolutionResultDto result = service.resolveShortage(
                request(ShortageResolutionOption.BACKORDER, "k1").build());

        assertThat(result.getOptionType()).isEqualTo(ShortageResolutionOption.BACKORDER);
        assertThat(result.getArtifactType()).isEqualTo("BACKORDER");
        assertThat(result.getArtifactId()).isEqualTo(backorderId);
        assertThat(result.getStatus()).isEqualTo("RESOLVED");
        assertThat(result.getResolvedAt()).isEqualTo(Instant.parse("2026-07-24T00:00:00Z"));
        verify(backorderService).createBackorder(WORKORDER_LINE, SKU, new BigDecimal("3"), SITE);
    }

    @Test
    @DisplayName("resolve replay with the same idempotency key returns the stored result without re-executing")
    void resolveReplay_returnsStoredResultWithoutDoubleEffect() {
        ShortageResolutionRecord stored = ShortageResolutionRecord.builder()
                .idempotencyKey("k-replay")
                .allocationId(ALLOCATION)
                .optionType(ShortageResolutionOption.BACKORDER)
                .status("RESOLVED")
                .artifactType("BACKORDER")
                .artifactId(UUID.fromString("00000000-0000-0000-0000-0000000000e2"))
                .resolvedAt(Instant.parse("2026-07-01T00:00:00Z"))
                .build();
        when(resolutionRecordRepository.findByIdempotencyKey("k-replay")).thenReturn(Optional.of(stored));

        ShortageResolutionResultDto result = service.resolveShortage(
                request(ShortageResolutionOption.BACKORDER, "k-replay").build());

        assertThat(result.getArtifactId()).isEqualTo(stored.getArtifactId());
        assertThat(result.getResolvedAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        verify(backorderService, never()).createBackorder(any(), any(), any(), any());
        verify(resolutionRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("resolve SUBSTITUTE reserves the substitute SKU when it has availability")
    void resolveSubstitute_createsReservation() {
        UUID reservationId = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        when(stockSummaryRepository.findByStockItemIdAndLocationId(SUBSTITUTE, SITE))
                .thenReturn(Optional.of(summary(SUBSTITUTE, SITE, 10, 0)));
        when(reservationService.createOrUpdateReservation(any()))
                .thenReturn(ReservationResponse.builder()
                        .reservationId(reservationId)
                        .workorderLineId(WORKORDER_LINE)
                        .stockItemId(SUBSTITUTE_ID)
                        .build());

        ShortageResolutionResultDto result = service.resolveShortage(request(ShortageResolutionOption.SUBSTITUTE, "k2")
                .substituteSku(SUBSTITUTE)
                .build());

        assertThat(result.getArtifactType()).isEqualTo("RESERVATION");
        assertThat(result.getArtifactId()).isEqualTo(reservationId);
        verify(reservationService).createOrUpdateReservation(any());
    }

    @Test
    @DisplayName("resolve SUBSTITUTE without a substituteSku is a 422")
    void resolveSubstitute_missingSubstituteSku_throws() {
        assertThatThrownBy(() -> service.resolveShortage(
                        request(ShortageResolutionOption.SUBSTITUTE, "k3").build()))
                .isInstanceOf(ShortageResolutionException.class);
        verify(reservationService, never()).createOrUpdateReservation(any());
    }

    @Test
    @DisplayName("resolve TRANSFER_IN materializes a cross-site transfer order")
    void resolveTransferIn_createsTransferOrder() {
        UUID transferId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        when(forecastSiteResolver.resolveForecastSite(SIBLING_SITE)).thenReturn(SIBLING_SITE);
        when(forecastSiteResolver.resolveForecastSite(SITE)).thenReturn(SITE);
        when(transferOrderService.createTransferOrder(any()))
                .thenReturn(TransferOrderResponse.builder()
                        .transferOrderId(transferId)
                        .build());

        ShortageResolutionResultDto result = service.resolveShortage(request(ShortageResolutionOption.TRANSFER_IN, "k4")
                .sourceLocationId(SIBLING_SITE)
                .build());

        assertThat(result.getArtifactType()).isEqualTo("TRANSFER_ORDER");
        assertThat(result.getArtifactId()).isEqualTo(transferId);
        verify(transferOrderService).createTransferOrder(any());
    }

    @Test
    @DisplayName("resolve EMERGENCY_PURCHASE saves a pre-filled purchase suggestion")
    void resolveEmergencyPurchase_savesSuggestion() {
        UUID suggestionId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        when(purchaseSuggestionRepository.save(any())).thenAnswer(inv -> {
            PurchaseSuggestion s = inv.getArgument(0);
            s.setSuggestionId(suggestionId);
            return s;
        });

        ShortageResolutionResultDto result = service.resolveShortage(
                request(ShortageResolutionOption.EMERGENCY_PURCHASE, "k5").build());

        assertThat(result.getArtifactType()).isEqualTo("PURCHASE_SUGGESTION");
        assertThat(result.getArtifactId()).isEqualTo(suggestionId);
        verify(purchaseSuggestionRepository).save(any());
    }

    @Test
    @DisplayName("resolve CANCEL_LINE cancels the reservation and records no artifact")
    void resolveCancelLine_cancelsReservation() {
        ShortageResolutionResultDto result = service.resolveShortage(
                request(ShortageResolutionOption.CANCEL_LINE, "k6").build());

        assertThat(result.getArtifactType()).isEqualTo("NONE");
        assertThat(result.getArtifactId()).isNull();
        verify(reservationService).cancelReservation(WORKORDER_LINE);
    }

    private static ShortageOptionDto optionOf(List<ShortageOptionDto> options, ShortageResolutionOption type) {
        return options.stream()
                .filter(o -> o.getOptionType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("option not present: " + type));
    }

    private static ShortageResolveRequest.ShortageResolveRequestBuilder request(
            ShortageResolutionOption option, String key) {
        return ShortageResolveRequest.builder()
                .idempotencyKey(key)
                .allocationId(ALLOCATION)
                .optionType(option)
                .sku(SKU)
                .shortQuantity(new BigDecimal("3"))
                .locationId(SITE)
                .workorderLineId(WORKORDER_LINE);
    }

    private static InventoryStockSummary summary(String sku, UUID locationId, long onHand, long allocated) {
        InventoryStockSummary s = new InventoryStockSummary();
        s.setStockItemId(sku);
        s.setLocationId(locationId);
        s.setOnHand(BigDecimal.valueOf(onHand));
        s.setAllocated(BigDecimal.valueOf(allocated));
        s.setAtp(BigDecimal.valueOf(onHand - allocated));
        return s;
    }

    private static SkuCostState costState(String avgCost) {
        SkuCostState state = new SkuCostState();
        state.setAvgCost(new BigDecimal(avgCost));
        return state;
    }
}
