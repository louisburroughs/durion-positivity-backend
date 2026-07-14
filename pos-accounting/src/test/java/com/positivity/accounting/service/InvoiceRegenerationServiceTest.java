package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.WorkorderCommandPublisher;
import com.positivity.accounting.internal.service.InvoiceRegenerationServiceImpl;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the async-only {@link InvoiceRegenerationServiceImpl} (ADR-0044 #900 Phase
 * 5.4): regeneration publishes a {@code workorder.invoice.regenerate-requested} command and
 * returns PENDING; without the event feed the endpoint fails fast with 503.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceRegenerationService Unit Tests")
class InvoiceRegenerationServiceTest {

    @Mock
    private org.springframework.beans.factory.ObjectProvider<WorkorderCommandPublisher> commandPublisherProvider;

    @InjectMocks
    private InvoiceRegenerationServiceImpl service;

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("Should publish command and return PENDING (ADR-0044)")
    void regenerate_publishesCommandAndReturnsPending() {
        WorkorderCommandPublisher publisher = org.mockito.Mockito.mock(WorkorderCommandPublisher.class);
        when(commandPublisherProvider.getIfAvailable()).thenReturn(publisher);

        InvoiceGenerationResponse response = service.regenerateInvoiceFromWorkorder(WORKORDER_ID, "idem-1");

        assertThat(response.getStatus()).isEqualTo(InvoiceRegenerationServiceImpl.STATUS_PENDING);
        assertThat(response.getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(response.getInvoiceId()).isNull();
        verify(publisher)
                .requestInvoiceRegeneration(
                        org.mockito.ArgumentMatchers.eq(WORKORDER_ID),
                        org.mockito.ArgumentMatchers.eq("idem-1"),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Should map publish failure to 503")
    void regenerate_publishFailure_maps503() {
        WorkorderCommandPublisher publisher = org.mockito.Mockito.mock(WorkorderCommandPublisher.class);
        when(commandPublisherProvider.getIfAvailable()).thenReturn(publisher);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(publisher)
                .requestInvoiceRegeneration(
                        org.mockito.ArgumentMatchers.eq(WORKORDER_ID),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.regenerateInvoiceFromWorkorder(WORKORDER_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("Should fail fast with 503 when the Kafka event feed is disabled")
    void regenerate_feedDisabled_maps503() {
        when(commandPublisherProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.regenerateInvoiceFromWorkorder(WORKORDER_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getReason()).contains("pos.accounting.kafka.enabled");
                });
    }
}
