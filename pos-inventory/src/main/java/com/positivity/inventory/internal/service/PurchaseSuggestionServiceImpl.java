package com.positivity.inventory.internal.service;

import com.positivity.domainevents.order.PurchaseOrderRequestedLine;
import com.positivity.domainevents.order.PurchaseOrderRequestedV1;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsRequest;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsResponse;
import com.positivity.inventory.internal.dto.purchasesuggestion.PurchaseSuggestionResponse;
import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.exception.PurchaseSuggestionConversionException;
import com.positivity.inventory.internal.exception.PurchaseSuggestionStateException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.service.PurchaseSuggestionService;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase suggestion lifecycle implementation (odoo-parity F4, issue #1044; plan D-3).
 *
 * <p><b>Conversion (one vendor → one DRAFT PO):</b> all listed suggestions must be
 * ACCEPTED, share one (vendorType, vendorRefId) pair, carry a feed price, and resolve to
 * one ship-to site; the multi-line DRAFT PO is created through the existing
 * {@link PurchaseOrderService} so the standard approval workflow (the second human spend
 * gate after D-3's accept gate) applies unchanged.
 *
 * <p><b>Pack UoM on PO lines (B2 rule):</b> when the suggestion captured a vendor pack
 * size &gt; 1, the suggested quantity divides evenly by it, and the catalog UoM replica
 * ({@code ext_product_uom}) has a PACK-type row whose {@code factorToBase} equals that
 * pack size, the PO line is keyed in that document UoM ({@code documentQuantity} = packs,
 * {@code unitCostMinor} = per-pack cost = per-unit feed cost × pack size). Otherwise the
 * line falls back to the base-UoM quantity with the per-unit cost — the multiple-rounding
 * already happened at suggestion time, so base quantities stay pack-aligned either way.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseSuggestionServiceImpl implements PurchaseSuggestionService {

    private static final String PACK_UOM_TYPE = "PACK";
    private static final String DEFAULT_CURRENCY = "USD";
    private static final String RESOURCE_NAME = "PurchaseSuggestion";

    private final PurchaseSuggestionRepository purchaseSuggestionRepository;
    private final ExtProductUomReplicaRepository extProductUomReplicaRepository;
    private final PurchaseOrderCommandPublisher purchaseOrderCommandPublisher;
    private final ForecastSiteResolver forecastSiteResolver;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<PurchaseSuggestionResponse> listPurchaseSuggestions(
            @Nullable String status, @Nullable String itemSKU, @Nullable UUID locationId, @NonNull Pageable pageable) {
        PurchaseSuggestionStatus statusFilter =
                status != null ? PurchaseSuggestionStatus.valueOf(status.toUpperCase(Locale.ROOT)) : null;
        // In-memory filter + slice over the full set, mirroring the replenishment policy
        // listing — suggestion volume is per-policy-bounded, so this stays small.
        List<PurchaseSuggestionResponse> filtered = purchaseSuggestionRepository.findAll().stream()
                .filter(suggestion -> statusFilter == null || suggestion.getStatus() == statusFilter)
                .filter(suggestion -> itemSKU == null || itemSKU.equals(suggestion.getItemSKU()))
                .filter(suggestion -> locationId == null || locationId.equals(suggestion.getLocationId()))
                .sorted(Comparator.comparing(
                        PurchaseSuggestion::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toResponse)
                .toList();
        if (pageable.isUnpaged()) {
            return new PageImpl<>(filtered);
        }
        int fromIndex = Math.min((int) pageable.getOffset(), filtered.size());
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(fromIndex, toIndex), pageable, filtered.size());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PurchaseSuggestionResponse getPurchaseSuggestion(@NonNull UUID suggestionId) {
        return toResponse(requireSuggestion(suggestionId));
    }

    @Override
    @Transactional
    public @NonNull PurchaseSuggestionResponse acceptPurchaseSuggestion(
            @NonNull UUID suggestionId, @NonNull String actorId) {
        PurchaseSuggestion suggestion = requireSuggestion(suggestionId);
        if (suggestion.getStatus() != PurchaseSuggestionStatus.SUGGESTED) {
            throw new PurchaseSuggestionStateException(suggestionId, suggestion.getStatus(), "accepted");
        }
        suggestion.setStatus(PurchaseSuggestionStatus.ACCEPTED);
        PurchaseSuggestion saved = purchaseSuggestionRepository.save(suggestion);
        log.info(
                "Purchase suggestion accepted: suggestionId={} policyId={} sku={} quantity={} actorId={}",
                saved.getSuggestionId(),
                saved.getPolicyId(),
                saved.getItemSKU(),
                saved.getSuggestedQuantity(),
                actorId);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull PurchaseSuggestionResponse dismissPurchaseSuggestion(
            @NonNull UUID suggestionId, @NonNull String reason, @NonNull String actorId) {
        PurchaseSuggestion suggestion = requireSuggestion(suggestionId);
        if (suggestion.getStatus() != PurchaseSuggestionStatus.SUGGESTED
                && suggestion.getStatus() != PurchaseSuggestionStatus.ACCEPTED) {
            throw new PurchaseSuggestionStateException(suggestionId, suggestion.getStatus(), "dismissed");
        }
        suggestion.setStatus(PurchaseSuggestionStatus.DISMISSED);
        suggestion.setDismissalReason(reason);
        PurchaseSuggestion saved = purchaseSuggestionRepository.save(suggestion);
        log.info(
                "Purchase suggestion dismissed: suggestionId={} policyId={} sku={} reason=[{}] actorId={}",
                saved.getSuggestionId(),
                saved.getPolicyId(),
                saved.getItemSKU(),
                reason,
                actorId);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull ConvertPurchaseSuggestionsResponse convertPurchaseSuggestions(
            @NonNull ConvertPurchaseSuggestionsRequest request, @NonNull String actorId) {
        List<PurchaseSuggestion> suggestions = loadAllOrThrow(request.getSuggestionIds());
        validateConvertible(suggestions);

        UUID vendorId = suggestions.getFirst().getVendorRefId();
        UUID shipToSite = resolveSingleShipToSite(suggestions);
        String currency = suggestions.stream()
                .map(PurchaseSuggestion::getUnitCostCurrency)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(DEFAULT_CURRENCY);
        LocalDate expectedDeliveryDate = suggestions.stream()
                .map(PurchaseSuggestion::getEarliestExpectedDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        // The identity is minted here and used as the order's primary key, so one conversion can
        // only ever produce one order: a redelivered command or a retried publish collides on the
        // key rather than placing a second order for the same goods (CAP-320 #1334).
        UUID purchaseOrderId = UUIDv7Generator.generate();

        List<PurchaseOrderRequestedLine> lines = new ArrayList<>();
        for (int i = 0; i < suggestions.size(); i++) {
            lines.add(toRequestedLine(suggestions.get(i), i + 1));
        }

        purchaseOrderCommandPublisher.request(new PurchaseOrderRequestedV1(
                purchaseOrderId,
                vendorId,
                currency,
                shipToSite,
                LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC),
                expectedDeliveryDate,
                actorId,
                "Converted from purchase suggestion(s) "
                        + suggestions.stream()
                                .map(suggestion -> suggestion.getSuggestionId().toString())
                                .reduce((left, right) -> left + ", " + right)
                                .orElse(""),
                Instant.now(clock),
                lines));

        // Marked converted in the same transaction as the request. A suggestion that stayed
        // SUGGESTED after the order was asked for would be picked up by the next replenishment
        // scan and ordered a second time.
        for (PurchaseSuggestion suggestion : suggestions) {
            suggestion.setStatus(PurchaseSuggestionStatus.CONVERTED);
            suggestion.setConvertedPurchaseOrderId(purchaseOrderId);
        }
        purchaseSuggestionRepository.saveAll(suggestions);
        log.info(
                "Purchase suggestions converted: purchaseOrderId={} vendorId={} suggestionIds={} actorId={}",
                purchaseOrderId,
                vendorId,
                request.getSuggestionIds(),
                actorId);

        // The order number and status are pos-order's to assign; they arrive on the fact and land
        // in the ext_purchase_order projection. Reporting a guess here would be reporting a number
        // no purchase order actually carries.
        return ConvertPurchaseSuggestionsResponse.builder()
                .purchaseOrderId(purchaseOrderId)
                .poNumber(null)
                .purchaseOrderStatus("REQUESTED")
                .convertedSuggestionIds(suggestions.stream()
                        .map(PurchaseSuggestion::getSuggestionId)
                        .toList())
                .build();
    }

    private List<PurchaseSuggestion> loadAllOrThrow(List<UUID> suggestionIds) {
        List<PurchaseSuggestion> suggestions = new ArrayList<>();
        for (UUID suggestionId : new LinkedHashSet<>(suggestionIds)) {
            suggestions.add(requireSuggestion(suggestionId));
        }
        return suggestions;
    }

    /** Conversion preconditions: every suggestion ACCEPTED, priced, and on the same single vendor. */
    private void validateConvertible(List<PurchaseSuggestion> suggestions) {
        for (PurchaseSuggestion suggestion : suggestions) {
            if (suggestion.getStatus() != PurchaseSuggestionStatus.ACCEPTED) {
                throw PurchaseSuggestionConversionException.notAccepted(
                        suggestion.getSuggestionId(),
                        suggestion.getStatus() != null ? suggestion.getStatus().name() : "unknown");
            }
            if (suggestion.getVendorRefId() == null || suggestion.getSuggestedVendorType() == null) {
                throw PurchaseSuggestionConversionException.missingVendor(suggestion.getSuggestionId());
            }
            if (suggestion.getUnitCostMinor() == null) {
                throw PurchaseSuggestionConversionException.missingUnitCost(suggestion.getSuggestionId());
            }
        }
        long distinctVendors = suggestions.stream()
                .map(suggestion -> suggestion.getSuggestedVendorType() + ":" + suggestion.getVendorRefId())
                .distinct()
                .count();
        if (distinctVendors > 1) {
            throw PurchaseSuggestionConversionException.vendorMismatch();
        }
    }

    /**
     * All suggestions must resolve (bin → parent site, A2 semantics) to ONE ship-to site —
     * a purchase order ships to a single site.
     */
    private UUID resolveSingleShipToSite(List<PurchaseSuggestion> suggestions) {
        Set<UUID> sites = new LinkedHashSet<>();
        for (PurchaseSuggestion suggestion : suggestions) {
            UUID site = forecastSiteResolver.resolveForecastSite(suggestion.getLocationId());
            sites.add(site != null ? site : suggestion.getLocationId());
        }
        if (sites.size() > 1) {
            throw PurchaseSuggestionConversionException.siteMismatch();
        }
        return sites.iterator().next();
    }

    /** One PO line per suggestion, expressed in the vendor's pack UoM when the B2 rule applies. */
    private PurchaseOrderRequestedLine toRequestedLine(PurchaseSuggestion suggestion, int lineNumber) {
        UUID productId = parseUuid(suggestion.getItemSKU());
        String description = "Replenishment purchase suggestion for SKU " + suggestion.getItemSKU();

        Optional<ExtProductUomReplica> packUom = packUomFor(productId, suggestion);
        if (packUom.isPresent()) {
            int packSize = suggestion.getVendorPackSize();
            // Ordered in vendor packs, and the order should say so. unitCostMinor refers to one
            // pack: per-pack cost = per-base-unit feed cost × pack size. pos-order converts the
            // pack quantity to base, which is why the base quantity is left unstated here rather
            // than computed twice.
            return new PurchaseOrderRequestedLine(
                    lineNumber,
                    productId,
                    description,
                    null,
                    suggestion.getUnitCostMinor() * (long) packSize,
                    packUom.get().getUomCode(),
                    BigDecimal.valueOf(suggestion.getSuggestedQuantity() / packSize),
                    null,
                    null);
        }
        return new PurchaseOrderRequestedLine(
                lineNumber,
                productId,
                description,
                BigDecimal.valueOf(suggestion.getSuggestedQuantity()),
                suggestion.getUnitCostMinor(),
                null,
                null,
                null,
                null);
    }

    /**
     * The catalog PACK-type UoM row matching the suggestion's captured vendor pack size,
     * when the suggested quantity divides evenly by it (it should — the suggestion was
     * pack-rounded — unless the MOQ floor overrode the multiple); empty means the line is
     * keyed in base UoM.
     */
    private Optional<ExtProductUomReplica> packUomFor(@Nullable UUID productId, PurchaseSuggestion suggestion) {
        Integer packSize = suggestion.getVendorPackSize();
        if (productId == null
                || packSize == null
                || packSize <= 1
                || suggestion.getSuggestedQuantity() == null
                || suggestion.getSuggestedQuantity() % packSize != 0) {
            return Optional.empty();
        }
        return extProductUomReplicaRepository.findByProductId(productId).stream()
                .filter(row -> PACK_UOM_TYPE.equalsIgnoreCase(row.getUomType()))
                .filter(row -> row.getFactorToBase() != null
                        && row.getFactorToBase().compareTo(BigDecimal.valueOf(packSize)) == 0)
                .findFirst();
    }

    private PurchaseSuggestion requireSuggestion(UUID suggestionId) {
        return purchaseSuggestionRepository
                .findById(suggestionId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, suggestionId.toString()));
    }

    private PurchaseSuggestionResponse toResponse(PurchaseSuggestion suggestion) {
        return PurchaseSuggestionResponse.builder()
                .suggestionId(suggestion.getSuggestionId())
                .policyId(suggestion.getPolicyId())
                .itemSKU(suggestion.getItemSKU())
                .locationId(suggestion.getLocationId())
                .suggestedQuantity(suggestion.getSuggestedQuantity())
                .suggestedVendorType(
                        suggestion.getSuggestedVendorType() != null
                                ? suggestion.getSuggestedVendorType().name()
                                : null)
                .vendorRefId(suggestion.getVendorRefId())
                .unitCostMinor(suggestion.getUnitCostMinor())
                .unitCostCurrency(suggestion.getUnitCostCurrency())
                .vendorPackSize(suggestion.getVendorPackSize())
                .leadTimeDays(suggestion.getLeadTimeDays())
                .earliestExpectedDate(suggestion.getEarliestExpectedDate())
                .selectionReason(suggestion.getSelectionReason())
                .status(suggestion.getStatus() != null ? suggestion.getStatus().name() : null)
                .dismissalReason(suggestion.getDismissalReason())
                .convertedPurchaseOrderId(suggestion.getConvertedPurchaseOrderId())
                .createdAt(suggestion.getCreatedAt())
                .updatedAt(suggestion.getUpdatedAt())
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
