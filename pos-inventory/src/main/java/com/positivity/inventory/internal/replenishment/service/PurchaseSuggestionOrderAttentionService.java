package com.positivity.inventory.internal.replenishment.service;

import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.entity.ExtSupplierVendorReplica;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.enums.OrderAttention;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.enums.VendorStatus;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.internal.service.VendorCopyReader;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * CAP:550 S36 (#2525, PROPOSED 9): a CONVERTED suggestion's order status and attention, derived on read from the
 * purchase-order copy ({@code ext_purchase_order}) and the vendor copy ({@code ext_supplier_vendor}); nothing is
 * stored. Netting is untouched: a DRAFT order keeps covering its need while a person fixes it.
 */
@Component
@Slf4j
public class PurchaseSuggestionOrderAttentionService {

    static final String GAUGE = "inventory.purchase_suggestion.order_attention";
    private static final Set<String> OPEN_ORDER_STATUSES = Set.of("DRAFT", "APPROVED", "PARTIALLY_RECEIVED");

    /** What the read shows beside a CONVERTED suggestion. */
    public record Assessment(
            @Nullable String orderStatus, @Nullable OrderAttention attention) {
        static final Assessment NONE = new Assessment(null, null);
    }

    private final ExtPurchaseOrderRepository purchaseOrderRepository;
    private final VendorCopyReader vendorCopyReader;
    private final PurchaseSuggestionRepository purchaseSuggestionRepository;
    private final PurchaseSuggestionOrderAttentionSettings settings;
    private final Clock clock;
    private final @Nullable MeterRegistry meterRegistry;
    private final Map<String, AtomicInteger> gaugeValues = new ConcurrentHashMap<>();

    public PurchaseSuggestionOrderAttentionService(
            @NonNull ExtPurchaseOrderRepository purchaseOrderRepository,
            @NonNull VendorCopyReader vendorCopyReader,
            @NonNull PurchaseSuggestionRepository purchaseSuggestionRepository,
            @NonNull PurchaseSuggestionOrderAttentionSettings settings,
            @NonNull Clock clock,
            @NonNull ObjectProvider<MeterRegistry> meterRegistry) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.vendorCopyReader = vendorCopyReader;
        this.purchaseSuggestionRepository = purchaseSuggestionRepository;
        this.settings = settings;
        this.clock = clock;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /** Assessments keyed by suggestion id; suggestions that are not CONVERTED get {@link Assessment#NONE}. */
    public @NonNull Map<UUID, Assessment> assess(@NonNull Collection<PurchaseSuggestion> suggestions) {
        List<PurchaseSuggestion> converted = suggestions.stream()
                .filter(s -> s.getStatus() == PurchaseSuggestionStatus.CONVERTED)
                .toList();
        Map<UUID, Assessment> out = new HashMap<>();
        suggestions.forEach(s -> out.put(s.getSuggestionId(), Assessment.NONE));
        if (converted.isEmpty()) {
            return out;
        }
        Set<UUID> orderIds = converted.stream()
                .map(PurchaseSuggestion::getConvertedPurchaseOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ExtPurchaseOrderReplica> orders = purchaseOrderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(ExtPurchaseOrderReplica::getPurchaseOrderId, o -> o));
        Set<UUID> vendorIds = orders.values().stream()
                .map(ExtPurchaseOrderReplica::getVendorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ExtSupplierVendorReplica> vendors =
                vendorIds.isEmpty() ? Map.of() : vendorCopyReader.byIds(vendorIds);
        Instant now = Instant.now(clock);
        for (PurchaseSuggestion suggestion : converted) {
            ExtPurchaseOrderReplica order = suggestion.getConvertedPurchaseOrderId() == null
                    ? null
                    : orders.get(suggestion.getConvertedPurchaseOrderId());
            out.put(suggestion.getSuggestionId(), assessOne(suggestion, order, vendors, now));
        }
        return out;
    }

    private Assessment assessOne(
            PurchaseSuggestion suggestion,
            @Nullable ExtPurchaseOrderReplica order,
            Map<UUID, ExtSupplierVendorReplica> vendors,
            Instant now) {
        if (order == null) {
            Instant convertedAt = suggestion.getConvertedAt();
            boolean overdue = convertedAt != null && now.isAfter(convertedAt.plus(settings.orderPlacementGrace()));
            return new Assessment(null, overdue ? OrderAttention.ORDER_NOT_PLACED : null);
        }
        String status = order.getStatus();
        if (status == null || !OPEN_ORDER_STATUSES.contains(status)) {
            return new Assessment(status, null);
        }
        UUID vendorId = order.getVendorId();
        ExtSupplierVendorReplica vendor = vendorId == null ? null : vendors.get(vendorId);
        if (vendor == null) {
            return new Assessment(status, OrderAttention.ORDER_VENDOR_NOT_IN_COPY);
        }
        if (vendor.getStatus() == VendorStatus.INACTIVE) {
            return new Assessment(status, OrderAttention.ORDER_VENDOR_INACTIVE);
        }
        return new Assessment(status, null);
    }

    /**
     * After a scheduled scan, for the tenant in context: sets {@code inventory.purchase_suggestion.order_attention}
     * per reason and WARN-logs a non-zero count, so a converted need that pos-order will refuse is seen before the
     * next scan suggests nothing for it.
     */
    public @NonNull Map<OrderAttention, Integer> publishAfterScan(@Nullable UUID tenantId) {
        List<PurchaseSuggestion> converted =
                purchaseSuggestionRepository.findByStatus(PurchaseSuggestionStatus.CONVERTED);
        Map<OrderAttention, Integer> counts = new EnumMap<>(OrderAttention.class);
        for (OrderAttention reason : OrderAttention.values()) {
            counts.put(reason, 0);
        }
        assess(converted).values().stream()
                .map(Assessment::attention)
                .filter(Objects::nonNull)
                .forEach(reason -> counts.merge(reason, 1, Integer::sum));
        counts.forEach((reason, count) -> {
            gauge(tenantId, reason).set(count);
            if (count > 0) {
                log.warn(
                        "Converted purchase suggestions needing attention: reason={} count={} tenant={}",
                        reason,
                        count,
                        tenantId);
            }
        });
        return counts;
    }

    private AtomicInteger gauge(@Nullable UUID tenantId, OrderAttention reason) {
        String key = tenantId + "|" + reason;
        return gaugeValues.computeIfAbsent(key, k -> {
            AtomicInteger value = new AtomicInteger();
            if (meterRegistry != null) {
                Gauge.builder(GAUGE, value, AtomicInteger::get)
                        .description("CONVERTED purchase suggestions whose order needs a buyer's attention")
                        .tag("reason", reason.name())
                        .tag("tenant", tenantId == null ? "unbound" : tenantId.toString())
                        .register(meterRegistry);
            }
            return value;
        });
    }
}
