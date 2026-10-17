package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ExtOrderPurchaseOrder;
import com.positivity.accounting.internal.repository.ExtOrderPurchaseOrderRepository;
import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Purchase order replica (#2515)")
class PurchaseOrderReplicaTest {

    private static final UUID PO = UUID.fromString("019a0000-0000-7000-8000-00000000b001");
    private static final UUID VENDOR = UUID.fromString("019a0000-0000-7000-8000-00000000c001");

    private final ExtOrderPurchaseOrderRepository repository = mock(ExtOrderPurchaseOrderRepository.class);
    private final Map<UUID, ExtOrderPurchaseOrder> rows = new LinkedHashMap<>();
    private PurchaseOrderReplica replica;

    @BeforeEach
    void setUp() {
        replica = new PurchaseOrderReplica(
                repository, Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC));
        when(repository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        when(repository.save(any())).thenAnswer(invocation -> {
            ExtOrderPurchaseOrder row = invocation.getArgument(0);
            rows.put(row.getPurchaseOrderId(), row);
            return row;
        });
    }

    private static PurchaseOrderUpdatedV1 fact(String terms) {
        return new PurchaseOrderUpdatedV1(
                PO,
                "PO-1",
                VENDOR,
                "APPROVED",
                null,
                null,
                "USD",
                null,
                null,
                Instant.parse("2026-10-17T08:00:00Z"),
                List.of(),
                terms);
    }

    @Test
    @DisplayName("the first fact inserts the order with its terms and version")
    void firstFactInserts() {
        replica.updated(fact("NET45"), 2);

        ExtOrderPurchaseOrder row = rows.get(PO);
        assertThat(row.getPaymentTermsId()).isEqualTo("NET45");
        assertThat(row.getVendorId()).isEqualTo(VENDOR);
        assertThat(row.getPoNumber()).isEqualTo("PO-1");
        assertThat(row.getAggregateVersion()).isEqualTo(2);
        assertThat(row.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-17T12:00:00Z"));
    }

    @Test
    @DisplayName("a newer or equal version overwrites; an older one changes nothing")
    void versionOrdering() {
        replica.updated(fact("NET45"), 5);

        replica.updated(fact("NET10"), 4);
        assertThat(rows.get(PO).getPaymentTermsId()).isEqualTo("NET45");
        assertThat(rows.get(PO).getAggregateVersion()).isEqualTo(5);

        replica.updated(fact("NET60"), 5);
        assertThat(rows.get(PO).getPaymentTermsId()).isEqualTo("NET60");

        replica.updated(fact(null), 6);
        assertThat(rows.get(PO).getPaymentTermsId()).isNull();
        assertThat(rows.get(PO).getAggregateVersion()).isEqualTo(6);
    }
}
