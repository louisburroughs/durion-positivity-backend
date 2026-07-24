package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.valuation.ValuationReportResponse;
import com.positivity.inventory.internal.dto.valuation.ValuationRow;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.SkuCostState;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the J2 valuation read model (issue #1052): value = on-hand × current unit cost by
 * method, site-filtered aggregation, as-of ledger reconstruction, and the uncosted-SKU path.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class ValuationServiceImplTest {

    private static final String SKU_A = "PART-A";
    private static final String SKU_B = "PART-B";
    private static final UUID LOC_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOC_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant AS_OF = Instant.parse("2026-06-01T00:00:00Z");

    @Mock
    InventoryStockSummaryRepository stockSummaryRepository;

    @Mock
    InventoryLedgerEntryRepository ledgerRepository;

    @Mock
    SkuCostStateRepository costStateRepository;

    @Mock
    CostingMethodResolver methodResolver;

    @Mock
    AsOfQueryGuard asOfQueryGuard;

    ValuationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ValuationServiceImpl(
                stockSummaryRepository,
                ledgerRepository,
                costStateRepository,
                methodResolver,
                asOfQueryGuard,
                List.of(new AverageCostingStrategy(), new StandardCostingStrategy()));
    }

    private static SkuCostState costState(String sku, BigDecimal avg, BigDecimal std, long qty) {
        return SkuCostState.builder()
                .stockItemId(sku)
                .avgCost(avg)
                .standardCost(std)
                .onHandQty(qty)
                .build();
    }

    private static InventoryStockSummaryRepository.SkuOnHand skuOnHand(String sku, long qty) {
        return new InventoryStockSummaryRepository.SkuOnHand() {
            @Override
            public String getStockItemId() {
                return sku;
            }

            @Override
            public long getOnHand() {
                return qty;
            }
        };
    }

    private static InventoryLedgerEntryRepository.LocationOnHand ledgerSkuOnHand(String sku, long qty) {
        return new InventoryLedgerEntryRepository.LocationOnHand() {
            @Override
            public String getStockItemId() {
                return sku;
            }

            @Override
            public Long getOnHandQuantity() {
                return qty;
            }
        };
    }

    private static InventoryLedgerEntry entry(InventoryLedgerEventType type, int change, BigDecimal unitCost) {
        return InventoryLedgerEntry.builder()
                .eventType(type)
                .changeInQuantity(change)
                .unitCost(unitCost)
                .build();
    }

    // ── Current valuation: AVCO ────────────────────────────────────────────────

    @Test
    void currentValuation_average_valueIsOnHandTimesAvgCost() {
        when(stockSummaryRepository.sumOnHandBySku()).thenReturn(List.of(skuOnHand(SKU_A, 20L)));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.AVERAGE);
        when(costStateRepository.findByStockItemId(SKU_A))
                .thenReturn(Optional.of(costState(SKU_A, new BigDecimal("6.000000"), null, 20L)));

        ValuationReportResponse report = service.getValuation(null, null);

        assertThat(report.getRows()).hasSize(1);
        ValuationRow row = report.getRows().get(0);
        assertThat(row.getStockItemId()).isEqualTo(SKU_A);
        assertThat(row.getOnHand()).isEqualTo(20L);
        assertThat(row.getCostingMethod()).isEqualTo("AVERAGE");
        assertThat(row.getUnitCostCurrent()).isEqualByComparingTo("6.0000");
        assertThat(row.getOnHandValue()).isEqualByComparingTo("120.0000");
        assertThat(row.isUncosted()).isFalse();
        assertThat(report.getTotalOnHandValue()).isEqualByComparingTo("120.0000");
        assertThat(report.getAsOf()).isNull();
    }

    // ── Current valuation: STANDARD ────────────────────────────────────────────

    @Test
    void currentValuation_standard_usesStandardPrice() {
        when(stockSummaryRepository.sumOnHandBySku()).thenReturn(List.of(skuOnHand(SKU_A, 10L)));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.STANDARD);
        when(costStateRepository.findByStockItemId(SKU_A))
                .thenReturn(Optional.of(costState(SKU_A, new BigDecimal("6.500000"), new BigDecimal("8.000000"), 10L)));

        ValuationReportResponse report = service.getValuation(null, null);

        ValuationRow row = report.getRows().get(0);
        assertThat(row.getUnitCostCurrent()).isEqualByComparingTo("8.0000");
        assertThat(row.getOnHandValue()).isEqualByComparingTo("80.0000");
    }

    @Test
    void currentValuation_standard_fallsBackToLatestReceiptMemoWhenNoStandardPrice() {
        when(stockSummaryRepository.sumOnHandBySku()).thenReturn(List.of(skuOnHand(SKU_A, 10L)));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.STANDARD);
        when(costStateRepository.findByStockItemId(SKU_A))
                .thenReturn(Optional.of(costState(SKU_A, new BigDecimal("6.500000"), null, 10L)));

        ValuationReportResponse report = service.getValuation(null, null);

        ValuationRow row = report.getRows().get(0);
        assertThat(row.getUnitCostCurrent()).isEqualByComparingTo("6.5000");
        assertThat(row.getOnHandValue()).isEqualByComparingTo("65.0000");
    }

    // ── Uncosted SKU ───────────────────────────────────────────────────────────

    @Test
    void currentValuation_uncostedSku_surfacesZeroNotCrash() {
        when(stockSummaryRepository.sumOnHandBySku()).thenReturn(List.of(skuOnHand(SKU_A, 5L)));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.AVERAGE);
        when(costStateRepository.findByStockItemId(SKU_A)).thenReturn(Optional.empty());

        ValuationReportResponse report = service.getValuation(null, null);

        ValuationRow row = report.getRows().get(0);
        assertThat(row.getUnitCostCurrent()).isNull();
        assertThat(row.isUncosted()).isTrue();
        assertThat(row.getOnHandValue()).isEqualByComparingTo("0.0000");
        assertThat(report.getTotalOnHandValue()).isEqualByComparingTo("0.0000");
    }

    @Test
    void currentValuation_standardWithNullAvgAndNullStandard_isUncosted() {
        when(stockSummaryRepository.sumOnHandBySku()).thenReturn(List.of(skuOnHand(SKU_A, 5L)));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.STANDARD);
        when(costStateRepository.findByStockItemId(SKU_A)).thenReturn(Optional.of(costState(SKU_A, null, null, 5L)));

        ValuationReportResponse report = service.getValuation(null, null);

        assertThat(report.getRows().get(0).isUncosted()).isTrue();
        assertThat(report.getRows().get(0).getOnHandValue()).isEqualByComparingTo("0.0000");
    }

    // ── Site-filtered aggregation ──────────────────────────────────────────────

    @Test
    void currentValuation_siteFiltered_aggregatesOnlyThatSite() {
        when(stockSummaryRepository.sumOnHandBySkuAtLocation(LOC_1))
                .thenReturn(List.of(skuOnHand(SKU_A, 7L), skuOnHand(SKU_B, 3L)));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.AVERAGE);
        when(methodResolver.resolve(SKU_B)).thenReturn(CostingMethod.AVERAGE);
        when(costStateRepository.findByStockItemId(SKU_A))
                .thenReturn(Optional.of(costState(SKU_A, new BigDecimal("2.000000"), null, 7L)));
        when(costStateRepository.findByStockItemId(SKU_B))
                .thenReturn(Optional.of(costState(SKU_B, new BigDecimal("10.000000"), null, 3L)));

        ValuationReportResponse report = service.getValuation(LOC_1, null);

        assertThat(report.getLocationId()).isEqualTo(LOC_1);
        assertThat(report.getRows()).hasSize(2);
        // 7*2 + 3*10 = 44
        assertThat(report.getTotalOnHandValue()).isEqualByComparingTo("44.0000");
    }

    @Test
    void currentValuation_singleSkuAtLocation_usesLotAgnosticRow() {
        InventoryStockSummary row = InventoryStockSummary.builder()
                .stockItemId(SKU_A)
                .locationId(LOC_1)
                .onHand(4L)
                .build();
        when(stockSummaryRepository.findByStockItemIdAndLocationId(SKU_A, LOC_1))
                .thenReturn(Optional.of(row));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.AVERAGE);
        when(costStateRepository.findByStockItemId(SKU_A))
                .thenReturn(Optional.of(costState(SKU_A, new BigDecimal("5.000000"), null, 4L)));

        ValuationReportResponse report = service.getValuation(LOC_1, SKU_A);

        assertThat(report.getRows()).hasSize(1);
        assertThat(report.getRows().get(0).getOnHandValue()).isEqualByComparingTo("20.0000");
    }

    @Test
    void currentValuation_singleSkuNoStock_returnsEmpty() {
        when(stockSummaryRepository.findByStockItemIdAndLocationId(SKU_A, LOC_1))
                .thenReturn(Optional.empty());

        ValuationReportResponse report = service.getValuation(LOC_1, SKU_A);

        assertThat(report.getRows()).isEmpty();
        assertThat(report.getTotalOnHandValue()).isEqualByComparingTo("0.0000");
    }

    // ── As-of reconstruction ───────────────────────────────────────────────────

    @Test
    void asOfValuation_average_reconstructsRunningCostByReplay() {
        // Receipt 10 @ 5, then receipt 10 @ 7 → running average 6; on-hand 20.
        when(ledgerRepository.sumOnHandBySkuAsOf(any(), eq(AS_OF))).thenReturn(List.of(ledgerSkuOnHand(SKU_A, 20L)));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.AVERAGE);
        lenient().when(costStateRepository.findByStockItemId(SKU_A)).thenReturn(Optional.empty());
        when(ledgerRepository
                        .findByStockItemIdAndEventTypeInAndTimestampLessThanEqualOrderByTimestampAscLedgerEntryIdAsc(
                                eq(SKU_A), any(), eq(AS_OF)))
                .thenReturn(List.of(
                        entry(InventoryLedgerEventType.GOODS_RECEIPT, 10, new BigDecimal("5.0000")),
                        entry(InventoryLedgerEventType.GOODS_RECEIPT, 10, new BigDecimal("7.0000"))));

        ValuationReportResponse report = service.getValuationAsOf(null, null, AS_OF);

        ValuationRow row = report.getRows().get(0);
        assertThat(row.getUnitCostCurrent()).isEqualByComparingTo("6.0000");
        assertThat(row.getOnHandValue()).isEqualByComparingTo("120.0000");
        assertThat(report.getAsOf()).isEqualTo(AS_OF);
    }

    @Test
    void asOfValuation_average_afterSubsequentIssue_keepsRunningAverage() {
        // Receipt 10 @ 5, receipt 10 @ 7 (avg 6), then issue 5 → on-hand 15, avg stays 6.
        when(ledgerRepository.sumOnHandBySkuAsOf(any(), eq(AS_OF))).thenReturn(List.of(ledgerSkuOnHand(SKU_A, 15L)));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.AVERAGE);
        lenient().when(costStateRepository.findByStockItemId(SKU_A)).thenReturn(Optional.empty());
        when(ledgerRepository
                        .findByStockItemIdAndEventTypeInAndTimestampLessThanEqualOrderByTimestampAscLedgerEntryIdAsc(
                                eq(SKU_A), any(), eq(AS_OF)))
                .thenReturn(List.of(
                        entry(InventoryLedgerEventType.GOODS_RECEIPT, 10, new BigDecimal("5.0000")),
                        entry(InventoryLedgerEventType.GOODS_RECEIPT, 10, new BigDecimal("7.0000")),
                        entry(InventoryLedgerEventType.GOODS_ISSUE, -5, new BigDecimal("6.0000"))));

        ValuationReportResponse report = service.getValuationAsOf(null, null, AS_OF);

        ValuationRow row = report.getRows().get(0);
        assertThat(row.getUnitCostCurrent()).isEqualByComparingTo("6.0000");
        // 15 × 6 = 90
        assertThat(row.getOnHandValue()).isEqualByComparingTo("90.0000");
    }

    @Test
    void asOfValuation_standard_seedsStandardPriceFromCurrentState() {
        when(ledgerRepository.sumOnHandBySkuAsOf(any(), eq(AS_OF))).thenReturn(List.of(ledgerSkuOnHand(SKU_A, 10L)));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.STANDARD);
        when(costStateRepository.findByStockItemId(SKU_A))
                .thenReturn(Optional.of(costState(SKU_A, new BigDecimal("5.000000"), new BigDecimal("9.000000"), 10L)));
        when(ledgerRepository
                        .findByStockItemIdAndEventTypeInAndTimestampLessThanEqualOrderByTimestampAscLedgerEntryIdAsc(
                                eq(SKU_A), any(), eq(AS_OF)))
                .thenReturn(List.of(entry(InventoryLedgerEventType.GOODS_RECEIPT, 10, new BigDecimal("5.0000"))));

        ValuationReportResponse report = service.getValuationAsOf(null, null, AS_OF);

        ValuationRow row = report.getRows().get(0);
        assertThat(row.getUnitCostCurrent()).isEqualByComparingTo("9.0000");
        assertThat(row.getOnHandValue()).isEqualByComparingTo("90.0000");
    }

    @Test
    void asOfValuation_invokesGuard() {
        when(ledgerRepository.sumOnHandBySkuAsOf(any(), eq(AS_OF))).thenReturn(List.of());

        service.getValuationAsOf(null, null, AS_OF);

        org.mockito.Mockito.verify(asOfQueryGuard).check(AS_OF);
    }

    @Test
    void asOfValuation_siteFiltered_usesPositiveOnHandByLocation() {
        when(ledgerRepository.findPositiveOnHandByLocationAsOf(eq(LOC_2), any(), eq(AS_OF)))
                .thenReturn(List.of(ledgerSkuOnHand(SKU_A, 8L)));
        when(methodResolver.resolve(SKU_A)).thenReturn(CostingMethod.AVERAGE);
        lenient().when(costStateRepository.findByStockItemId(SKU_A)).thenReturn(Optional.empty());
        when(ledgerRepository
                        .findByStockItemIdAndEventTypeInAndTimestampLessThanEqualOrderByTimestampAscLedgerEntryIdAsc(
                                eq(SKU_A), any(), eq(AS_OF)))
                .thenReturn(List.of(entry(InventoryLedgerEventType.GOODS_RECEIPT, 8, new BigDecimal("4.0000"))));

        ValuationReportResponse report = service.getValuationAsOf(LOC_2, null, AS_OF);

        assertThat(report.getLocationId()).isEqualTo(LOC_2);
        assertThat(report.getRows().get(0).getOnHandValue()).isEqualByComparingTo("32.0000");
    }
}
