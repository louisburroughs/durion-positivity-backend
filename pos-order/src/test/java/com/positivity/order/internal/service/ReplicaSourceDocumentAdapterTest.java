package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.SourceDocumentLine;
import com.positivity.order.internal.entity.ExtEstimate;
import com.positivity.order.internal.entity.ExtEstimateLine;
import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.entity.ExtWorkorder;
import com.positivity.order.internal.entity.ExtWorkorderLine;
import com.positivity.order.internal.entity.SourceType;
import com.positivity.order.internal.repository.ExtEstimateLineRepository;
import com.positivity.order.internal.repository.ExtEstimateRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.ExtWorkorderLineRepository;
import com.positivity.order.internal.repository.ExtWorkorderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for the replica-backed source-document import (parity story E1, #1077, spec R7.1):
 * priced-as-approved import, APPROVED-only estimate items, declined-line exclusion, returnable
 * flag propagation, labor normalization, and the loud unknown-document failure.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReplicaSourceDocumentAdapter — parity story E1")
class ReplicaSourceDocumentAdapterTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ESTIMATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private ExtWorkorderRepository extWorkorderRepository;

    @Mock
    private ExtWorkorderLineRepository extWorkorderLineRepository;

    @Mock
    private ExtEstimateRepository extEstimateRepository;

    @Mock
    private ExtEstimateLineRepository extEstimateLineRepository;

    @Mock
    private ExtProductRepository extProductRepository;

    private ReplicaSourceDocumentAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReplicaSourceDocumentAdapter(
                extWorkorderRepository,
                extWorkorderLineRepository,
                extEstimateRepository,
                extEstimateLineRepository,
                extProductRepository);
        when(extProductRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(ExtProduct.builder()
                        .productId(PRODUCT_ID)
                        .sku("BRAKE-PAD")
                        .active(true)
                        .aggregateVersion(1)
                        .syncedAt(Instant.now())
                        .build()));
    }

    @Test
    @DisplayName("SDA-001: workorder import — non-declined parts+labor, approved prices, returnable carried")
    void workorderImport() {
        when(extWorkorderRepository.findById(WORKORDER_ID))
                .thenReturn(Optional.of(ExtWorkorder.builder()
                        .workorderId(WORKORDER_ID)
                        .aggregateVersion(1)
                        .syncedAt(Instant.now())
                        .build()));
        UUID partLineId = UUID.randomUUID();
        UUID laborLineId = UUID.randomUUID();
        when(extWorkorderLineRepository.findByWorkorderId(WORKORDER_ID))
                .thenReturn(List.of(
                        ExtWorkorderLine.builder()
                                .lineId(partLineId)
                                .workorderId(WORKORDER_ID)
                                .lineKind(ExtWorkorderLine.LineKind.PART)
                                .productId(PRODUCT_ID)
                                .description("Brake pads")
                                .quantity(new BigDecimal("2"))
                                .unitPrice(new BigDecimal("45.00"))
                                .lineTotal(new BigDecimal("90.00"))
                                .returnable(true)
                                .build(),
                        ExtWorkorderLine.builder()
                                .lineId(laborLineId)
                                .workorderId(WORKORDER_ID)
                                .lineKind(ExtWorkorderLine.LineKind.SERVICE)
                                .description("Brake labor")
                                .quantity(new BigDecimal("1.50"))
                                .unitPrice(new BigDecimal("100.00"))
                                .lineTotal(new BigDecimal("150.00"))
                                .build(),
                        ExtWorkorderLine.builder()
                                .lineId(UUID.randomUUID())
                                .workorderId(WORKORDER_ID)
                                .lineKind(ExtWorkorderLine.LineKind.PART)
                                .declined(true)
                                .build()));

        List<SourceDocumentLine> lines = adapter.fetchLines(SourceType.WORKORDER, WORKORDER_ID.toString());

        assertThat(lines).hasSize(2);
        SourceDocumentLine part = lines.getFirst();
        assertThat(part.itemSku()).isEqualTo("BRAKE-PAD");
        assertThat(part.quantity()).isEqualTo(2);
        assertThat(part.unitPrice()).isEqualByComparingTo("45.00");
        assertThat(part.returnable()).isTrue();
        assertThat(part.sourceLineId()).isEqualTo(partLineId.toString());
        // Fractional labor hours collapse to one line-total unit; labor is never returnable.
        SourceDocumentLine labor = lines.get(1);
        assertThat(labor.itemSku()).isEqualTo("LABOR");
        assertThat(labor.quantity()).isEqualTo(1);
        assertThat(labor.unitPrice()).isEqualByComparingTo("150.00");
        assertThat(labor.returnable()).isFalse();
    }

    @Test
    @DisplayName("SDA-002: estimate import — only APPROVED, non-deleted items; returnable undecided (null)")
    void estimateImport_approvedOnly() {
        when(extEstimateRepository.findById(ESTIMATE_ID))
                .thenReturn(Optional.of(ExtEstimate.builder()
                        .estimateId(ESTIMATE_ID)
                        .aggregateVersion(1)
                        .syncedAt(Instant.now())
                        .build()));
        when(extEstimateLineRepository.findByEstimateId(ESTIMATE_ID))
                .thenReturn(List.of(
                        ExtEstimateLine.builder()
                                .itemId(UUID.randomUUID())
                                .estimateId(ESTIMATE_ID)
                                .itemType("PART")
                                .description("Approved part")
                                .quantity(new BigDecimal("1"))
                                .unitPrice(new BigDecimal("25.00"))
                                .productId(PRODUCT_ID)
                                .approvalStatus("APPROVED")
                                .build(),
                        ExtEstimateLine.builder()
                                .itemId(UUID.randomUUID())
                                .estimateId(ESTIMATE_ID)
                                .itemType("PART")
                                .description("Pending part")
                                .approvalStatus("PENDING_APPROVAL")
                                .build(),
                        ExtEstimateLine.builder()
                                .itemId(UUID.randomUUID())
                                .estimateId(ESTIMATE_ID)
                                .itemType("PART")
                                .description("Deleted part")
                                .approvalStatus("APPROVED")
                                .deleted(true)
                                .build()));

        List<SourceDocumentLine> lines = adapter.fetchLines(SourceType.ESTIMATE, ESTIMATE_ID.toString());

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().itemSku()).isEqualTo("BRAKE-PAD");
        assertThat(lines.getFirst().unitPrice()).isEqualByComparingTo("25.00");
        assertThat(lines.getFirst().returnable()).isNull();
    }

    @Test
    @DisplayName("SDA-003: unknown document fails loudly instead of importing nothing")
    void unknownDocument_failsLoudly() {
        when(extWorkorderRepository.findById(any())).thenReturn(Optional.empty());
        when(extEstimateRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.fetchLines(SourceType.WORKORDER, WORKORDER_ID.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replica");
        assertThatThrownBy(() -> adapter.fetchLines(SourceType.ESTIMATE, ESTIMATE_ID.toString()))
                .isInstanceOf(IllegalStateException.class);
    }
}
