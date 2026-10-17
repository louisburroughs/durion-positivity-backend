package com.positivity.inventory.internal.replenishment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ExtPurchaseOrderReplica;
import com.positivity.inventory.internal.entity.ExtSupplierVendorReplica;
import com.positivity.inventory.internal.entity.PurchaseSuggestion;
import com.positivity.inventory.internal.enums.OrderAttention;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.enums.VendorStatus;
import com.positivity.inventory.internal.repository.ExtPurchaseOrderRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.internal.service.VendorCopyReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** CAP:550 S36 (#2525, PROPOSED 9): order attention is derived on read from the two copies. */
class PurchaseSuggestionOrderAttentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID VENDOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final ExtPurchaseOrderRepository orders = mock(ExtPurchaseOrderRepository.class);
    private final VendorCopyReader vendors = mock(VendorCopyReader.class);
    private final PurchaseSuggestionRepository suggestions = mock(PurchaseSuggestionRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private PurchaseSuggestionOrderAttentionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        service = new PurchaseSuggestionOrderAttentionService(
                orders,
                vendors,
                suggestions,
                new PurchaseSuggestionOrderAttentionSettings(Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                provider);
        when(vendors.byIds(anyCollection())).thenReturn(Map.of());
    }

    private static PurchaseSuggestion converted(Instant convertedAt) {
        return PurchaseSuggestion.builder()
                .suggestionId(UUID.randomUUID())
                .status(PurchaseSuggestionStatus.CONVERTED)
                .convertedPurchaseOrderId(ORDER)
                .convertedAt(convertedAt)
                .build();
    }

    private static ExtPurchaseOrderReplica order(String status, UUID vendorId) {
        ExtPurchaseOrderReplica order = new ExtPurchaseOrderReplica();
        order.setPurchaseOrderId(ORDER);
        order.setStatus(status);
        order.setVendorId(vendorId);
        return order;
    }

    private static ExtSupplierVendorReplica vendor(VendorStatus status) {
        ExtSupplierVendorReplica vendor = new ExtSupplierVendorReplica();
        vendor.setVendorId(VENDOR);
        vendor.setVendorNumber("V-1");
        vendor.setStatus(status);
        return vendor;
    }

    @Test
    @DisplayName("no order row within the grace: nothing yet; past the grace: ORDER_NOT_PLACED")
    void notPlaced() {
        when(orders.findAllById(any())).thenReturn(List.of());
        PurchaseSuggestion fresh = converted(NOW.minus(Duration.ofMinutes(30)));
        PurchaseSuggestion stale = converted(NOW.minus(Duration.ofHours(2)));

        Map<UUID, PurchaseSuggestionOrderAttentionService.Assessment> out = service.assess(List.of(fresh, stale));

        assertThat(out.get(fresh.getSuggestionId()).attention()).isNull();
        assertThat(out.get(stale.getSuggestionId()).attention()).isEqualTo(OrderAttention.ORDER_NOT_PLACED);
        assertThat(out.get(stale.getSuggestionId()).orderStatus()).isNull();
    }

    @Test
    @DisplayName("an open order whose vendor is INACTIVE / absent from the copy needs attention; a closed one does not")
    void vendorAttention() {
        PurchaseSuggestion s = converted(NOW.minus(Duration.ofHours(3)));
        when(orders.findAllById(any())).thenReturn(List.of(order("DRAFT", VENDOR)));
        when(vendors.byIds(anyCollection())).thenReturn(Map.of(VENDOR, vendor(VendorStatus.INACTIVE)));
        assertThat(service.assess(List.of(s)).get(s.getSuggestionId()).attention())
                .isEqualTo(OrderAttention.ORDER_VENDOR_INACTIVE);

        when(vendors.byIds(anyCollection())).thenReturn(Map.of());
        assertThat(service.assess(List.of(s)).get(s.getSuggestionId()).attention())
                .isEqualTo(OrderAttention.ORDER_VENDOR_NOT_IN_COPY);

        when(vendors.byIds(anyCollection())).thenReturn(Map.of(VENDOR, vendor(VendorStatus.ACTIVE)));
        PurchaseSuggestionOrderAttentionService.Assessment ok =
                service.assess(List.of(s)).get(s.getSuggestionId());
        assertThat(ok.attention()).isNull();
        assertThat(ok.orderStatus()).isEqualTo("DRAFT");

        when(orders.findAllById(any())).thenReturn(List.of(order("CANCELLED", VENDOR)));
        when(vendors.byIds(anyCollection())).thenReturn(Map.of(VENDOR, vendor(VendorStatus.INACTIVE)));
        assertThat(service.assess(List.of(s)).get(s.getSuggestionId()).attention())
                .isNull();
    }

    @Test
    @DisplayName("a suggestion that is not CONVERTED has no assessment")
    void notConverted() {
        PurchaseSuggestion s = PurchaseSuggestion.builder()
                .suggestionId(UUID.randomUUID())
                .status(PurchaseSuggestionStatus.ACCEPTED)
                .build();
        assertThat(service.assess(List.of(s)).get(s.getSuggestionId()))
                .isEqualTo(PurchaseSuggestionOrderAttentionService.Assessment.NONE);
    }

    @Test
    @DisplayName("after a scan the gauge carries each reason's count for the tenant")
    void publishesGauges() {
        PurchaseSuggestion stale = converted(NOW.minus(Duration.ofHours(2)));
        when(suggestions.findByStatus(PurchaseSuggestionStatus.CONVERTED)).thenReturn(List.of(stale));
        when(orders.findAllById(any())).thenReturn(List.of());
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

        Map<OrderAttention, Integer> counts = service.publishAfterScan(tenant);

        assertThat(counts.get(OrderAttention.ORDER_NOT_PLACED)).isEqualTo(1);
        assertThat(counts.get(OrderAttention.ORDER_VENDOR_INACTIVE)).isZero();
        assertThat(registry.find(PurchaseSuggestionOrderAttentionService.GAUGE)
                        .tag("reason", "ORDER_NOT_PLACED")
                        .tag("tenant", tenant.toString())
                        .gauge())
                .isNotNull()
                .extracting(g -> (int) g.value())
                .isEqualTo(1);
        assertThat(Optional.ofNullable(registry.find(PurchaseSuggestionOrderAttentionService.GAUGE)
                        .tag("reason", "ORDER_VENDOR_INACTIVE")
                        .gauge()))
                .isPresent();
    }
}
