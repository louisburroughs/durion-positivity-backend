package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.ShortageOptionDto;
import com.positivity.inventory.internal.dto.ShortageResolutionResultDto;
import com.positivity.inventory.internal.dto.ShortageResolveRequest;
import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.dto.transfer.CreateTransferOrderRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderLineRequest;
import com.positivity.inventory.internal.dto.transfer.TransferOrderResponse;
import com.positivity.inventory.internal.entity.ExtProductSubstitutionReplica;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.entity.ShortageResolutionRecord;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.enums.ShortageResolutionOption;
import com.positivity.inventory.internal.exception.ShortageResolutionException;
import com.positivity.inventory.internal.repository.ExtProductSubstitutionReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ShortageResolutionRecordRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.inventory.service.BackorderService;
import com.positivity.inventory.service.ReservationService;
import com.positivity.inventory.service.ShortageResolutionService;
import com.positivity.inventory.service.TransferOrderService;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes shortage resolution options and executes a chosen option atomically (odoo-parity G2,
 * issue #1049; plan decision D-5). Replaces the pre-G2 static BACKORDER/CANCEL stub.
 *
 * <p><b>Options</b> ({@link #computeShortageOptions}) are built from live building blocks:
 *
 * <ul>
 *   <li><b>BACKORDER</b> — always offered; expected date = today + the SKU's replenishment lead
 *       time at the site (F4 lead), or a default horizon when no policy is present.</li>
 *   <li><b>SUBSTITUTE</b> — every substitution-group member (X1 {@code ext_product_substitution}
 *       replica) that has live ATP at the shortage site, with the member's cost delta versus the
 *       original SKU.</li>
 *   <li><b>TRANSFER_IN</b> — each sibling site whose surplus ({@code onHand − allocated}) covers the
 *       shortage, materializable into a C1 transfer order.</li>
 *   <li><b>EMERGENCY_PURCHASE</b> — always offered; expected date and cost delta from the F4 vendor
 *       selection.</li>
 *   <li><b>CANCEL_LINE</b> — always offered; resolves immediately with no artifact.</li>
 * </ul>
 *
 * <p><b>Resolve</b> ({@link #resolveShortage}) is transactional and idempotent per
 * {@code idempotencyKey}: a replay returns the stored result without re-executing the option
 * (the {@code shortage_resolution_record} unique index is the cross-instance backstop). Each option
 * delegates artifact creation to the owning service (G1 backorder, substitute reservation, C1
 * transfer order, F4 purchase suggestion) and records the artifact reference.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ShortageResolutionServiceImpl implements ShortageResolutionService {

    /** Fallback backorder horizon when the SKU has no replenishment policy at the site. */
    static final int DEFAULT_BACKORDER_LEAD_DAYS = 7;

    /** Estimated days to dispatch + receive a cross-site transfer. */
    static final int TRANSFER_LEAD_DAYS = 3;

    /** Fallback purchase horizon when no vendor lead time is available. */
    static final int DEFAULT_PURCHASE_LEAD_DAYS = 14;

    /** Minor-unit scale for feed prices (2-decimal assumption, matching vendor selection). */
    private static final BigDecimal MINOR_UNIT_SCALE = new BigDecimal("100");

    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String ARTIFACT_BACKORDER = "BACKORDER";
    private static final String ARTIFACT_RESERVATION = "RESERVATION";
    private static final String ARTIFACT_TRANSFER_ORDER = "TRANSFER_ORDER";
    private static final String ARTIFACT_PURCHASE_SUGGESTION = "PURCHASE_SUGGESTION";
    private static final String ARTIFACT_NONE = "NONE";

    private final ExtProductSubstitutionReplicaRepository substitutionReplicaRepository;
    private final InventoryStockSummaryRepository stockSummaryRepository;
    private final ReplenishmentPolicyRepository replenishmentPolicyRepository;
    private final SkuCostStateRepository skuCostStateRepository;
    private final PurchaseSuggestionRepository purchaseSuggestionRepository;
    private final ShortageResolutionRecordRepository resolutionRecordRepository;
    private final BackorderService backorderService;
    private final TransferOrderService transferOrderService;
    private final ReservationService reservationService;
    private final VendorSelectionService vendorSelectionService;
    private final ForecastSiteResolver forecastSiteResolver;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ShortageOptionDto> computeShortageOptions(
            @NonNull UUID allocationId,
            @Nullable UUID workorderLineId,
            @NonNull String sku,
            BigDecimal shortQuantity,
            @Nullable UUID locationId) {
        if (shortQuantity == null || shortQuantity.signum() <= 0) {
            throw new IllegalArgumentException("shortQuantity must be positive");
        }
        LocalDate today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        BigDecimal originalCost = costOf(sku);
        List<ShortageOptionDto> options = new ArrayList<>();

        options.add(backorderOption(allocationId, sku, locationId, today));
        options.addAll(substituteOptions(allocationId, sku, shortQuantity, locationId, originalCost, today));
        options.addAll(transferInOptions(allocationId, sku, shortQuantity, locationId, today));
        options.add(emergencyPurchaseOption(allocationId, sku, shortQuantity, originalCost, today));
        options.add(cancelLineOption(allocationId, today));

        return options;
    }

    private ShortageOptionDto backorderOption(
            UUID allocationId, String sku, @Nullable UUID locationId, LocalDate today) {
        int leadDays = backorderLeadDays(sku, locationId);
        return ShortageOptionDto.builder()
                .allocationId(allocationId)
                .optionType(ShortageResolutionOption.BACKORDER)
                .description("Place the short demand on a backorder (estimated availability in " + leadDays + " days)")
                .expectedResolutionDate(today.plusDays(leadDays))
                .build();
    }

    private List<ShortageOptionDto> substituteOptions(
            UUID allocationId,
            String sku,
            BigDecimal shortQuantity,
            @Nullable UUID locationId,
            @Nullable BigDecimal originalCost,
            LocalDate today) {
        UUID productId = parseUuid(sku);
        if (productId == null || locationId == null) {
            return List.of();
        }
        List<ShortageOptionDto> options = new ArrayList<>();
        for (ExtProductSubstitutionReplica member : substitutionReplicaRepository.findByProductId(productId)) {
            UUID memberId = member.getMemberProductId();
            if (memberId == null || memberId.equals(productId)) {
                continue; // the group carries the product itself; skip self
            }
            String memberSku = memberId.toString();
            BigDecimal atp = atpOf(memberSku, locationId);
            if (!Quantities.isPositive(atp)) {
                continue; // only offer substitutes with live availability
            }
            options.add(ShortageOptionDto.builder()
                    .allocationId(allocationId)
                    .optionType(ShortageResolutionOption.SUBSTITUTE)
                    .description("Substitute with group member " + memberSku + " (" + atp + " available at site)")
                    .substituteSku(memberSku)
                    .availableQuantity(atp)
                    .expectedResolutionDate(today)
                    .costDelta(costDelta(originalCost, costOf(memberSku)))
                    .build());
        }
        return options;
    }

    private List<ShortageOptionDto> transferInOptions(
            UUID allocationId, String sku, BigDecimal shortQuantity, @Nullable UUID locationId, LocalDate today) {
        if (locationId == null) {
            return List.of();
        }
        UUID shortageSite = forecastSiteResolver.resolveForecastSite(locationId);
        Map<UUID, BigDecimal> surplusBySite = new LinkedHashMap<>();
        for (InventoryStockSummary row : stockSummaryRepository.findByStockItemId(sku)) {
            UUID loc = row.getLocationId();
            if (loc == null) {
                continue;
            }
            UUID rowSite = forecastSiteResolver.resolveForecastSite(loc);
            if (rowSite == null || Objects.equals(rowSite, shortageSite)) {
                continue; // same-site surplus is not a cross-site transfer
            }
            BigDecimal surplus = Quantities.nz(row.getOnHand()).subtract(Quantities.nz(row.getAllocated()));
            if (Quantities.isPositive(surplus)) {
                surplusBySite.merge(rowSite, surplus, BigDecimal::add);
            }
        }
        List<ShortageOptionDto> options = new ArrayList<>();
        surplusBySite.forEach((site, surplus) -> {
            if (Quantities.gte(surplus, shortQuantity)) {
                options.add(ShortageOptionDto.builder()
                        .allocationId(allocationId)
                        .optionType(ShortageResolutionOption.TRANSFER_IN)
                        .description(
                                "Transfer " + shortQuantity.toPlainString() + " in from sibling site " + site + " ("
                                        + surplus.toPlainString()
                                        + " surplus available)")
                        .sourceLocationId(site)
                        .availableQuantity(surplus)
                        .expectedResolutionDate(today.plusDays(TRANSFER_LEAD_DAYS))
                        .build());
            }
        });
        return options;
    }

    private ShortageOptionDto emergencyPurchaseOption(
            UUID allocationId,
            String sku,
            BigDecimal shortQuantity,
            @Nullable BigDecimal originalCost,
            LocalDate today) {
        VendorSelectionService.VendorCandidate vendor = vendorSelectionService
                .selectVendor(parseUuid(sku), orderableUnits(shortQuantity))
                .chosen();
        int leadDays =
                vendor != null && vendor.leadTimeDays() != null ? vendor.leadTimeDays() : DEFAULT_PURCHASE_LEAD_DAYS;
        BigDecimal vendorCost = vendor != null ? unitCost(vendor.unitCostMinor()) : null;
        return ShortageOptionDto.builder()
                .allocationId(allocationId)
                .optionType(ShortageResolutionOption.EMERGENCY_PURCHASE)
                .description("Raise an emergency purchase suggestion (estimated arrival in " + leadDays + " days)")
                .expectedResolutionDate(today.plusDays(leadDays))
                .costDelta(costDelta(originalCost, vendorCost))
                .build();
    }

    private ShortageOptionDto cancelLineOption(UUID allocationId, LocalDate today) {
        return ShortageOptionDto.builder()
                .allocationId(allocationId)
                .optionType(ShortageResolutionOption.CANCEL_LINE)
                .description("Cancel the short line and release its reservation")
                .expectedResolutionDate(today)
                .build();
    }

    @Override
    public @NonNull ShortageResolutionResultDto resolveShortage(@NonNull ShortageResolveRequest request) {
        Optional<ShortageResolutionRecord> replay =
                resolutionRecordRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (replay.isPresent()) {
            log.info(
                    "Shortage resolve replay for idempotencyKey={}; returning stored result",
                    request.getIdempotencyKey());
            return toResult(replay.get());
        }

        ArtifactRef artifact = execute(request);

        ShortageResolutionRecord record = resolutionRecordRepository.save(ShortageResolutionRecord.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .allocationId(request.getAllocationId())
                .workorderLineId(request.getWorkorderLineId())
                .sku(request.getSku())
                .shortQuantity(request.getShortQuantity())
                .locationId(request.getLocationId())
                .optionType(request.getOptionType())
                .status(STATUS_RESOLVED)
                .artifactType(artifact.type())
                .artifactId(artifact.id())
                .resolvedAt(Instant.now(clock))
                .build());

        log.info(
                "Shortage resolved: allocation={} option={} artifactType={} artifactId={} idempotencyKey={}",
                request.getAllocationId(),
                request.getOptionType(),
                artifact.type(),
                artifact.id(),
                request.getIdempotencyKey());
        return toResult(record);
    }

    /** One resolution's created-artifact reference. */
    private record ArtifactRef(
            @NonNull String type, @Nullable UUID id) {}

    private ArtifactRef execute(ShortageResolveRequest request) {
        return switch (request.getOptionType()) {
            case BACKORDER -> executeBackorder(request);
            case SUBSTITUTE -> executeSubstitute(request);
            case TRANSFER_IN -> executeTransferIn(request);
            case EMERGENCY_PURCHASE -> executeEmergencyPurchase(request);
            case CANCEL_LINE -> executeCancelLine(request);
        };
    }

    private ArtifactRef executeBackorder(ShortageResolveRequest request) {
        UUID workorderLineId = requireWorkorderLine(request);
        var backorder = backorderService.createBackorder(
                workorderLineId, request.getSku(), request.getShortQuantity(), request.getLocationId());
        return new ArtifactRef(ARTIFACT_BACKORDER, backorder.getBackorderId());
    }

    private ArtifactRef executeSubstitute(ShortageResolveRequest request) {
        UUID workorderLineId = requireWorkorderLine(request);
        if (request.getSubstituteSku() == null || request.getSubstituteSku().isBlank()) {
            throw ShortageResolutionException.missingField(
                    "substituteSku", request.getOptionType().name());
        }
        UUID substituteId = parseUuid(request.getSubstituteSku());
        if (substituteId == null) {
            throw ShortageResolutionException.invalidIdentifier("substituteSku", request.getSubstituteSku());
        }
        if (request.getLocationId() != null
                && !Quantities.isPositive(atpOf(request.getSubstituteSku(), request.getLocationId()))) {
            throw ShortageResolutionException.substituteUnavailable(request.getSubstituteSku());
        }
        ReservationResponse reservation = reservationService.createOrUpdateReservation(
                new CreateReservationRequest(workorderLineId, substituteId, request.getShortQuantity()));
        return new ArtifactRef(ARTIFACT_RESERVATION, reservation.getReservationId());
    }

    private ArtifactRef executeTransferIn(ShortageResolveRequest request) {
        if (request.getSourceLocationId() == null) {
            throw ShortageResolutionException.missingField(
                    "sourceLocationId", request.getOptionType().name());
        }
        if (request.getLocationId() == null) {
            throw ShortageResolutionException.missingField(
                    "locationId", request.getOptionType().name());
        }
        UUID sourceSite = forecastSiteResolver.resolveForecastSite(request.getSourceLocationId());
        UUID destinationSite = forecastSiteResolver.resolveForecastSite(request.getLocationId());
        CreateTransferOrderRequest transferRequest = CreateTransferOrderRequest.builder()
                .sourceLocationId(sourceSite)
                .destinationLocationId(destinationSite)
                .lines(List.of(TransferOrderLineRequest.builder()
                        .sku(request.getSku())
                        .requestedQty(Math.toIntExact(orderableUnits(request.getShortQuantity())))
                        .build()))
                .notes("Auto-sourced by shortage resolution (odoo-parity G2) for allocation "
                        + request.getAllocationId())
                .build();
        TransferOrderResponse transfer = transferOrderService.createTransferOrder(transferRequest);
        return new ArtifactRef(ARTIFACT_TRANSFER_ORDER, transfer.getTransferOrderId());
    }

    private ArtifactRef executeEmergencyPurchase(ShortageResolveRequest request) {
        UUID productId = parseUuid(request.getSku());
        VendorSelectionService.VendorSelection selection =
                vendorSelectionService.selectVendor(productId, orderableUnits(request.getShortQuantity()));
        VendorSelectionService.VendorCandidate vendor = selection.chosen();
        int leadDays =
                vendor != null && vendor.leadTimeDays() != null ? vendor.leadTimeDays() : DEFAULT_PURCHASE_LEAD_DAYS;
        UUID policyId = request.getLocationId() == null
                ? UUIDv7Generator.generate()
                : replenishmentPolicyRepository
                        .findByItemSKUAndLocationId(request.getSku(), request.getLocationId())
                        .map(policy -> policy.getPolicyId())
                        .orElseGet(UUIDv7Generator::generate);
        PurchaseSuggestion suggestion = PurchaseSuggestion.builder()
                .policyId(policyId)
                .itemSKU(request.getSku())
                .locationId(request.getLocationId() != null ? request.getLocationId() : policyId)
                .suggestedQuantity(Math.toIntExact(orderableUnits(request.getShortQuantity())))
                .suggestedVendorType(vendor != null ? vendor.sourceType() : null)
                .vendorRefId(vendor != null ? vendor.vendorRefId() : null)
                .unitCostMinor(vendor != null ? vendor.unitCostMinor() : null)
                .unitCostCurrency(vendor != null ? vendor.unitCostCurrency() : null)
                .vendorPackSize(vendor != null ? vendor.packSize() : null)
                .leadTimeDays(leadDays)
                .earliestExpectedDate(
                        LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC).plusDays(leadDays))
                .selectionReason(
                        "Emergency purchase raised by shortage resolution (G2); " + selection.selectionReason())
                .status(PurchaseSuggestionStatus.SUGGESTED)
                .build();
        PurchaseSuggestion saved = purchaseSuggestionRepository.save(suggestion);
        return new ArtifactRef(ARTIFACT_PURCHASE_SUGGESTION, saved.getSuggestionId());
    }

    private ArtifactRef executeCancelLine(ShortageResolveRequest request) {
        if (request.getWorkorderLineId() != null) {
            reservationService.cancelReservation(request.getWorkorderLineId());
        }
        return new ArtifactRef(ARTIFACT_NONE, null);
    }

    private UUID requireWorkorderLine(ShortageResolveRequest request) {
        if (request.getWorkorderLineId() == null) {
            throw ShortageResolutionException.missingField(
                    "workorderLineId", request.getOptionType().name());
        }
        return request.getWorkorderLineId();
    }

    private int backorderLeadDays(String sku, @Nullable UUID locationId) {
        if (locationId == null) {
            return DEFAULT_BACKORDER_LEAD_DAYS;
        }
        return replenishmentPolicyRepository
                .findByItemSKUAndLocationId(sku, locationId)
                .map(policy -> policy.getLeadTimeDaysOverride())
                .filter(days -> days != null && days > 0)
                .orElse(DEFAULT_BACKORDER_LEAD_DAYS);
    }

    /**
     * A shortfall expressed as a whole number of units to buy or move.
     *
     * <p>The shortfall itself is decimal since ADR-0055 (#1414) — half a drum missing is half a
     * drum missing — but a purchase suggestion and a transfer-order line are documents about
     * containers, and neither a vendor nor a shipping label understands 3.5 of something. Rounding
     * UP is the only safe direction here: ordering less than the shortfall leaves the shortage
     * unresolved, which is the failure this whole path exists to prevent. The over-order is
     * exactly the same one a whole-units catalog produces today. Purchase and transfer documents
     * gain their own UoM handling in ADR-0055 stage 3.
     */
    private static long orderableUnits(BigDecimal shortQuantity) {
        return shortQuantity.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private BigDecimal atpOf(String sku, UUID locationId) {
        return stockSummaryRepository
                .findByStockItemIdAndLocationId(sku, locationId)
                .map(InventoryStockSummary::getAtp)
                .map(Quantities::nz)
                .orElse(BigDecimal.ZERO);
    }

    private @Nullable BigDecimal costOf(String sku) {
        return skuCostStateRepository
                .findByStockItemId(sku)
                .map(state -> state.getAvgCost() != null ? state.getAvgCost() : state.getStandardCost())
                .orElse(null);
    }

    private static @Nullable BigDecimal costDelta(@Nullable BigDecimal originalCost, @Nullable BigDecimal optionCost) {
        if (originalCost == null || optionCost == null) {
            return null;
        }
        return optionCost.subtract(originalCost);
    }

    private static @Nullable BigDecimal unitCost(@Nullable Long unitCostMinor) {
        if (unitCostMinor == null) {
            return null;
        }
        return new BigDecimal(unitCostMinor).divide(MINOR_UNIT_SCALE);
    }

    private ShortageResolutionResultDto toResult(ShortageResolutionRecord record) {
        return ShortageResolutionResultDto.builder()
                .allocationId(record.getAllocationId())
                .optionType(record.getOptionType())
                .idempotencyKey(record.getIdempotencyKey())
                .artifactType(record.getArtifactType())
                .artifactId(record.getArtifactId())
                .resolvedAt(record.getResolvedAt())
                .status(record.getStatus())
                .build();
    }

    private static @Nullable UUID parseUuid(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
