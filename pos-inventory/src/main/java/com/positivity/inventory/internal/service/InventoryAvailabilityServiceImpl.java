package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.InventorySourceType;
import com.positivity.inventory.internal.exception.InvalidInventoryAvailabilityRequestException;
import com.positivity.inventory.internal.exception.ProductNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.service.InventoryAvailabilityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory availability service implementation backed by the
 * {@code inventory_stock_summary} read model (issue #1024, A1). Quantities
 * come from the stored summary maintained by {@code LedgerPostingService};
 * only the derived unit of measure still consults the ledger (single-row
 * lookup). Response shapes are unchanged from the ledger-aggregation era.
 *
 * Issue: CAP-170 (#48)
 */
@Service
@Slf4j
public class InventoryAvailabilityServiceImpl implements InventoryAvailabilityService {
    private static final String ON_HAND_QUANTITY = "onHandQuantity";

    private static final Pageable FIRST_ONLY = PageRequest.of(0, 1);
    private static final String DEFAULT_UOM = "EACH";

    private final InventoryStockSummaryRepository stockSummaryRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final ForecastQuantityService forecastQuantityService;
    private final AsOfQueryGuard asOfQueryGuard;
    private final ForecastSiteResolver forecastSiteResolver;
    private final QuantityScaleGuard quantityScaleGuard;
    private final Clock clock;

    public InventoryAvailabilityServiceImpl(
            InventoryStockSummaryRepository stockSummaryRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            ForecastQuantityService forecastQuantityService,
            AsOfQueryGuard asOfQueryGuard,
            ForecastSiteResolver forecastSiteResolver,
            QuantityScaleGuard quantityScaleGuard,
            Clock clock) {
        this.stockSummaryRepository = stockSummaryRepository;
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
        this.forecastQuantityService = forecastQuantityService;
        this.asOfQueryGuard = asOfQueryGuard;
        this.forecastSiteResolver = forecastSiteResolver;
        this.quantityScaleGuard = quantityScaleGuard;
        this.clock = clock;
    }

    /**
     * Σ per-lot on-hand of the SKU's EXPIRED, ACTIVE lots at the scope, to subtract from ATP
     * (odoo-parity E3, issue #1047; decision D-7 on-read guard). Expired lots stay counted in
     * on-hand but drop out of what can be promised. A null scope sums across every location.
     */
    private BigDecimal expiredLotDeduction(@NonNull String stockItemId, @Nullable UUID scopeLocationId) {
        return Quantities.nz(
                stockSummaryRepository.sumExpiredActiveLotOnHand(stockItemId, scopeLocationId, LocalDate.now(clock)));
    }

    /**
     * Fail-loud narrowing replacement (ADR-0055, #1414).
     *
     * <p>Every quantity leaving this service used to pass through {@code Math.toIntExact}, which
     * threw rather than wrapping. That throw is the property worth keeping; the bound it checked
     * is not, because a decimal read model has no 32-bit edge. So each of those calls became this:
     * the value is checked against the scale the product's catalog declaration permits, and a
     * quantity carrying more precision than any declaration supports still stops the computation
     * loudly instead of being reported as though it were fine.
     */
    private BigDecimal reportable(@NonNull String stockItemId, @NonNull String fieldName, BigDecimal quantity) {
        return quantityScaleGuard.requireReportable(
                QuantityScaleGuard.productIdOf(stockItemId), stockItemId, fieldName, Quantities.nz(quantity));
    }

    /** Bin → parent-site resolution shared with the replenishment orderpoint math (F2). */
    private @Nullable UUID resolveForecastSite(@Nullable UUID locationOrStorageLocationId) {
        return forecastSiteResolver.resolveForecastSite(locationOrStorageLocationId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationAvailabilityDto> getAvailabilityByProduct(@NonNull UUID productId) {
        return getAvailabilityByProduct(productId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationAvailabilityDto> getAvailabilityByProduct(@NonNull UUID productId, @Nullable Instant horizon) {
        if (productId == null) {
            throw new InvalidInventoryAvailabilityRequestException("Product ID is required");
        }

        List<InventoryStockSummary> summaryRows;
        try {
            summaryRows = stockSummaryRepository.findByStockItemId(productId.toString());
        } catch (Exception ex) {
            log.error("Failed retrieving inventory availability summary for product {}", productId, ex);
            throw new IllegalStateException("Unable to retrieve inventory availability at this time", ex);
        }

        return summaryRows.stream()
                .filter(row -> row.getLocationId() != null)
                .sorted(Comparator.comparing(InventoryStockSummary::getLocationId))
                .map(row -> toLocationAvailability(row, horizon))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationAvailabilityDto> getAvailabilityByProductAsOf(@NonNull UUID productId, @NonNull Instant asOf) {
        if (productId == null) {
            throw new InvalidInventoryAvailabilityRequestException("Product ID is required");
        }
        asOfQueryGuard.check(asOf);

        // Odoo-parity A3 (#1029): direct ledger aggregation with a timestamp bound; the
        // stock summary is not consulted. On-hand only — allocation-derived and forecast
        // fields stay null (historical allocation state is not reliably reconstructable).
        return inventoryLedgerEntryRepository
                .sumQuantityByLocationForStockItemAsOf(
                        productId.toString(), InventoryLedgerEventType.onHandAffectingTypes(), asOf)
                .stream()
                .filter(row -> row.getLocationId() != null)
                .sorted(Comparator.comparing(InventoryLedgerEntryRepository.LocationQuantity::getLocationId))
                .map(row -> LocationAvailabilityDto.builder()
                        .locationId(row.getLocationId())
                        .locationName(row.getLocationId().toString())
                        .onHandQuantity(reportable(productId.toString(), ON_HAND_QUANTITY, row.getQuantity()))
                        .build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AvailabilityView queryAvailability(
            @NonNull String productSku,
            @Nullable UUID locationId,
            @Nullable UUID storageLocationId,
            @Nullable InventorySourceType sourceType) {
        return queryAvailability(productSku, locationId, storageLocationId, sourceType, null);
    }

    @Override
    @Transactional(readOnly = true)
    public AvailabilityView queryAvailability(
            @NonNull String productSku,
            @Nullable UUID locationId,
            @Nullable UUID storageLocationId,
            @Nullable InventorySourceType sourceType,
            @Nullable Instant horizon) {
        List<InventoryStockSummary> productRows = stockSummaryRepository.findByStockItemId(productSku);
        if (productRows.isEmpty()) {
            throw new ProductNotFoundException(productSku);
        }

        UUID scopeLocationId = storageLocationId != null ? storageLocationId : locationId;
        BigDecimal onHand;
        BigDecimal allocated;
        // Every arm routes through Quantities.nz: SonarCloud's dataflow engine models a bare
        // BigDecimal.ZERO read as possibly null (see Quantities.nz), and proved an NPE at the
        // ATP subtraction below from these assignments (javabugs:S2259).
        if (scopeLocationId == null) {
            onHand = Quantities.nz(productRows.stream()
                    .map(InventoryStockSummary::getOnHand)
                    .map(Quantities::nz)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            allocated = Quantities.nz(productRows.stream()
                    .map(InventoryStockSummary::getAllocated)
                    .map(Quantities::nz)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        } else {
            InventoryStockSummary scoped = productRows.stream()
                    .filter(row -> scopeLocationId.equals(row.getLocationId()))
                    .findFirst()
                    .orElse(null);
            onHand = Quantities.nz(scoped == null ? null : scoped.getOnHand());
            allocated = Quantities.nz(scoped == null ? null : scoped.getAllocated());
        }

        // Forecast site scope (odoo-parity A2, #1028): the site parameter when present,
        // otherwise the storage location's PARENT SITE (PO/ASN supply is keyed by
        // ship-to site; passing a bin id would match nothing and zero out incoming).
        UUID forecastSiteId = locationId != null ? locationId : resolveForecastSite(storageLocationId);
        ForecastQuantityService.ForecastQuantities forecast =
                forecastQuantityService.forecast(productSku, forecastSiteId, horizon, onHand);

        // odoo-parity E3 (#1047, decision D-7): expired lots stay in on-hand but drop from ATP.
        // ATP = lot-agnostic (onHand − allocated) − Σ(expired ACTIVE lots' per-lot on-hand at scope).
        BigDecimal expiredDeduction = expiredLotDeduction(productSku, scopeLocationId);

        return AvailabilityView.builder()
                .productSku(productSku)
                .locationId(locationId)
                .storageLocationId(storageLocationId)
                .onHandQuantity(reportable(productSku, ON_HAND_QUANTITY, onHand))
                .allocatedQuantity(reportable(productSku, "allocatedQuantity", allocated))
                .availableToPromiseQuantity(reportable(
                        productSku,
                        "availableToPromiseQuantity",
                        onHand.subtract(allocated).subtract(expiredDeduction)))
                .unitOfMeasure(deriveUnitOfMeasure(productSku, scopeLocationId))
                .incomingQty(forecast.incomingQty())
                .outgoingQty(forecast.outgoingQty())
                .projectedAvailable(forecast.projectedAvailable())
                .build();
    }

    private String deriveUnitOfMeasure(String productSku, @Nullable UUID scopeLocationId) {
        List<String> units = scopeLocationId == null
                ? inventoryLedgerEntryRepository.findUnitsOfMeasureByStockItem(productSku, FIRST_ONLY)
                : inventoryLedgerEntryRepository.findUnitsOfMeasureByStockItemAtLocation(
                        productSku, scopeLocationId, FIRST_ONLY);
        return units.isEmpty() ? DEFAULT_UOM : units.getFirst();
    }

    private LocationAvailabilityDto toLocationAvailability(InventoryStockSummary row, @Nullable Instant horizon) {
        // Pre-#1024 behavior: this per-location list subtracts BOTH hard
        // allocations and soft reservations from ATP (unlike queryAvailability,
        // which per ADR-0001 subtracts allocations only). odoo-parity E3 (#1047,
        // decision D-7): also subtract the location's expired ACTIVE lot on-hand.
        BigDecimal expiredDeduction = expiredLotDeduction(row.getStockItemId(), row.getLocationId());
        BigDecimal atpWithReservations = Quantities.nz(row.getOnHand())
                .subtract(Quantities.nz(row.getAllocated()))
                .subtract(Quantities.nz(row.getReserved()))
                .subtract(expiredDeduction);
        ForecastQuantityService.ForecastQuantities forecast = forecastQuantityService.forecast(
                row.getStockItemId(),
                resolveForecastSite(row.getLocationId()),
                horizon,
                Quantities.nz(row.getOnHand()));
        return LocationAvailabilityDto.builder()
                .locationId(row.getLocationId())
                .locationName(row.getLocationId().toString())
                .onHandQuantity(reportable(row.getStockItemId(), ON_HAND_QUANTITY, row.getOnHand()))
                .availableToPromiseQuantity(
                        reportable(row.getStockItemId(), "availableToPromiseQuantity", atpWithReservations))
                .incomingQty(forecast.incomingQty())
                .outgoingQty(forecast.outgoingQty())
                .projectedAvailable(forecast.projectedAvailable())
                .build();
    }
}
