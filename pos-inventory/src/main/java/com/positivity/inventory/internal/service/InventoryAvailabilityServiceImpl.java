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

    private static final Pageable FIRST_ONLY = PageRequest.of(0, 1);
    private static final String DEFAULT_UOM = "EACH";

    private final InventoryStockSummaryRepository stockSummaryRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final ForecastQuantityService forecastQuantityService;
    private final AsOfQueryGuard asOfQueryGuard;
    private final ForecastSiteResolver forecastSiteResolver;
    private final Clock clock;

    public InventoryAvailabilityServiceImpl(
            InventoryStockSummaryRepository stockSummaryRepository,
            InventoryLedgerEntryRepository inventoryLedgerEntryRepository,
            ForecastQuantityService forecastQuantityService,
            AsOfQueryGuard asOfQueryGuard,
            ForecastSiteResolver forecastSiteResolver,
            Clock clock) {
        this.stockSummaryRepository = stockSummaryRepository;
        this.inventoryLedgerEntryRepository = inventoryLedgerEntryRepository;
        this.forecastQuantityService = forecastQuantityService;
        this.asOfQueryGuard = asOfQueryGuard;
        this.forecastSiteResolver = forecastSiteResolver;
        this.clock = clock;
    }

    /**
     * Σ per-lot on-hand of the SKU's EXPIRED, ACTIVE lots at the scope, to subtract from ATP
     * (odoo-parity E3, issue #1047; decision D-7 on-read guard). Expired lots stay counted in
     * on-hand but drop out of what can be promised. A null scope sums across every location.
     */
    private long expiredLotDeduction(@NonNull String stockItemId, @Nullable UUID scopeLocationId) {
        return stockSummaryRepository.sumExpiredActiveLotOnHand(stockItemId, scopeLocationId, LocalDate.now(clock));
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
                        .onHandQuantity(Math.toIntExact(row.getQuantity()))
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
        long onHand;
        long allocated;
        if (scopeLocationId == null) {
            onHand = productRows.stream()
                    .mapToLong(InventoryStockSummary::getOnHand)
                    .sum();
            allocated = productRows.stream()
                    .mapToLong(InventoryStockSummary::getAllocated)
                    .sum();
        } else {
            InventoryStockSummary scoped = productRows.stream()
                    .filter(row -> scopeLocationId.equals(row.getLocationId()))
                    .findFirst()
                    .orElse(null);
            onHand = scoped == null ? 0L : scoped.getOnHand();
            allocated = scoped == null ? 0L : scoped.getAllocated();
        }

        // Forecast site scope (odoo-parity A2, #1028): the site parameter when present,
        // otherwise the storage location's PARENT SITE (PO/ASN supply is keyed by
        // ship-to site; passing a bin id would match nothing and zero out incoming).
        UUID forecastSiteId = locationId != null ? locationId : resolveForecastSite(storageLocationId);
        ForecastQuantityService.ForecastQuantities forecast =
                forecastQuantityService.forecast(productSku, forecastSiteId, horizon, onHand);

        // odoo-parity E3 (#1047, decision D-7): expired lots stay in on-hand but drop from ATP.
        // ATP = lot-agnostic (onHand − allocated) − Σ(expired ACTIVE lots' per-lot on-hand at scope).
        long expiredDeduction = expiredLotDeduction(productSku, scopeLocationId);

        return AvailabilityView.builder()
                .productSku(productSku)
                .locationId(locationId)
                .storageLocationId(storageLocationId)
                .onHandQuantity(Math.toIntExact(onHand))
                .allocatedQuantity(Math.toIntExact(allocated))
                .availableToPromiseQuantity(Math.toIntExact(onHand - allocated - expiredDeduction))
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
        long expiredDeduction = expiredLotDeduction(row.getStockItemId(), row.getLocationId());
        long atpWithReservations = row.getOnHand() - row.getAllocated() - row.getReserved() - expiredDeduction;
        ForecastQuantityService.ForecastQuantities forecast = forecastQuantityService.forecast(
                row.getStockItemId(), resolveForecastSite(row.getLocationId()), horizon, row.getOnHand());
        return LocationAvailabilityDto.builder()
                .locationId(row.getLocationId())
                .locationName(row.getLocationId().toString())
                .onHandQuantity(Math.toIntExact(row.getOnHand()))
                .availableToPromiseQuantity(Math.toIntExact(atpWithReservations))
                .incomingQty(forecast.incomingQty())
                .outgoingQty(forecast.outgoingQty())
                .projectedAvailable(forecast.projectedAvailable())
                .build();
    }
}
