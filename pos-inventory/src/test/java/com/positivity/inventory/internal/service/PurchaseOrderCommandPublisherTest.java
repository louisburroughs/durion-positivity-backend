package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.order.PurchaseOrderRequestedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.exception.PurchaseOrderRequestUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** CAP:550 S36 (#2525, PROPOSED 8): no silent conversion without the outbox. */
class PurchaseOrderCommandPublisherTest {

    private static PurchaseOrderRequestedV1 command() {
        return new PurchaseOrderRequestedV1(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "USD",
                UUID.randomUUID(),
                LocalDate.of(2026, 10, 17),
                null,
                "buyer",
                "test",
                Instant.parse("2026-10-17T12:00:00Z"),
                List.of());
    }

    @Test
    @DisplayName("without an outbox writer the request is refused, not dropped")
    @SuppressWarnings("unchecked")
    void noWriterThrows() {
        ObjectProvider<OutboxEventWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        PurchaseOrderCommandPublisher publisher =
                new PurchaseOrderCommandPublisher(provider, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThatThrownBy(() -> publisher.request(command()))
                .isInstanceOf(PurchaseOrderRequestUnavailableException.class);
    }

    @Test
    @DisplayName("with an outbox writer the request is queued on order.commands.v1")
    @SuppressWarnings("unchecked")
    void writerPublishes() {
        ObjectProvider<OutboxEventWriter> provider = mock(ObjectProvider.class);
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        when(provider.getIfAvailable()).thenReturn(writer);
        PurchaseOrderCommandPublisher publisher =
                new PurchaseOrderCommandPublisher(provider, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        publisher.request(command());

        verify(writer).publish(eq(DomainTopics.commands("order")), any());
    }
}
