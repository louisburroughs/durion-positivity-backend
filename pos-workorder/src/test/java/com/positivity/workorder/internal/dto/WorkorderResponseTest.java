package com.positivity.workorder.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.entity.Workorder;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #2232: the linked invoice id travels through the one mapper every read uses. */
@DisplayName("WorkorderResponse.fromEntity — invoiceId")
class WorkorderResponseTest {

    @Test
    @DisplayName("maps the persisted invoiceId once the invoice fact has linked it")
    void mapsLinkedInvoiceId() {
        UUID invoiceId = UUID.fromString("550e8400-e29b-41d4-a716-446655440005");
        Workorder entity =
                Workorder.builder().id(UUID.randomUUID()).invoiceId(invoiceId).build();

        assertThat(WorkorderResponse.fromEntity(entity).getInvoiceId()).isEqualTo(invoiceId);
    }

    @Test
    @DisplayName("is null before any invoice has been linked")
    void nullBeforeLinkage() {
        Workorder entity = Workorder.builder().id(UUID.randomUUID()).build();

        assertThat(WorkorderResponse.fromEntity(entity).getInvoiceId()).isNull();
    }
}
