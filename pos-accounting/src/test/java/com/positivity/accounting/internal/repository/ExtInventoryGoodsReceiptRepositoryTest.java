package com.positivity.accounting.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.entity.ExtInventoryGoodsReceipt;
import com.positivity.accounting.internal.service.GoodsReceiptReplica;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** The goods-receipt copy and its writer mapped once on the H2 test profile (#2750): ids, auditing, the upsert. */
@Transactional
@DisplayName("Goods receipt copy mapping (#2750)")
class ExtInventoryGoodsReceiptRepositoryTest extends BaseIntegrationTest {

    @Autowired
    private ExtInventoryGoodsReceiptRepository repository;

    @Autowired
    private GoodsReceiptReplica replica;

    @Test
    @DisplayName("the writer inserts under the fact's receipt id, audits it, and a re-emit updates the same row")
    void upsertKeepsReceivedAt() {
        UUID receipt = UUID.fromString("019a0000-0000-7000-8000-00000000d101");
        UUID order = UUID.fromString("019a0000-0000-7000-8000-00000000b101");
        Instant first = Instant.parse("2026-10-01T09:00:00Z");

        replica.recorded(new GoodsReceiptRecordedV1(receipt, "GR-1", order, null, 100L, first, List.of(), "USD"));
        repository.flush();
        replica.recorded(new GoodsReceiptRecordedV1(
                receipt, "GR-1A", order, null, 250L, first.plusSeconds(3_600), List.of(), "USD"));
        repository.flush();

        assertThat(repository.count()).isEqualTo(1);
        ExtInventoryGoodsReceipt row = repository.findById(receipt).orElseThrow();
        assertThat(row.getReceiptNumber()).isEqualTo("GR-1A");
        assertThat(row.getAccruedAmountMinor()).isEqualTo(250L);
        assertThat(row.getReceivedAt()).isEqualTo(first);
        assertThat(row.getUpdatedAt()).isNotNull();
        assertThat(row.getTenantId()).isNotNull();
    }
}
