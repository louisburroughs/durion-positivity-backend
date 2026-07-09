package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.WorkorderInvoiceClient;
import com.positivity.accounting.internal.client.WorkorderServiceException;
import com.positivity.accounting.internal.config.WorkorderCommandPublisher;
import com.positivity.accounting.internal.service.InvoiceRegenerationServiceImpl;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceRegenerationService Unit Tests")
class InvoiceRegenerationServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    private Clock clock = TEST_CLOCK;

    @Mock
    private WorkorderInvoiceClient workorderInvoiceClient;

    @Mock
    private org.springframework.beans.factory.ObjectProvider<WorkorderCommandPublisher> commandPublisherProvider;

    @InjectMocks
    private InvoiceRegenerationServiceImpl service;

    @Test
    @DisplayName("Should delegate to workorder client and return response")
    void testRegenerateInvoiceFromWorkorder_Success() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String idempotencyKey = "idem-regen-service-001";
        InvoiceGenerationResponse expected = InvoiceGenerationResponse.builder()
                .invoiceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderId(workorderId)
                .status("DRAFT")
                .build();

        when(workorderInvoiceClient.regenerateInvoiceFromWorkorder(workorderId, idempotencyKey))
                .thenReturn(expected);

        InvoiceGenerationResponse actual = service.regenerateInvoiceFromWorkorder(workorderId, idempotencyKey);

        assertThat(actual).isSameAs(expected);
        verify(workorderInvoiceClient).regenerateInvoiceFromWorkorder(workorderId, idempotencyKey);
    }

    @Test
    @DisplayName("Should map client 404 to ResponseStatusException 404")
    void testRegenerateInvoiceFromWorkorder_MapsNotFound() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String message = "Workorder not found";

        when(workorderInvoiceClient.regenerateInvoiceFromWorkorder(workorderId, null))
                .thenThrow(new WorkorderServiceException(message, 404));

        assertThatThrownBy(() -> service.regenerateInvoiceFromWorkorder(workorderId, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo(message);
                });
    }

    @Test
    @DisplayName("Should map unknown client status to ResponseStatusException 503")
    void testRegenerateInvoiceFromWorkorder_MapsUnknownStatusToServiceUnavailable() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String message = "Unexpected downstream failure";

        when(workorderInvoiceClient.regenerateInvoiceFromWorkorder(workorderId, null))
                .thenThrow(new WorkorderServiceException(message, 599));

        assertThatThrownBy(() -> service.regenerateInvoiceFromWorkorder(workorderId, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getReason()).isEqualTo(message);
                });
    }

    @Test
    @DisplayName("Should publish command and return PENDING when Kafka publisher is available (ADR-0044)")
    void testRegenerateInvoiceFromWorkorder_AsyncCommandPath() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        WorkorderCommandPublisher publisher = org.mockito.Mockito.mock(WorkorderCommandPublisher.class);
        when(commandPublisherProvider.getIfAvailable()).thenReturn(publisher);

        InvoiceGenerationResponse response = service.regenerateInvoiceFromWorkorder(workorderId, "idem-1");

        assertThat(response.getStatus()).isEqualTo(InvoiceRegenerationServiceImpl.STATUS_PENDING);
        assertThat(response.getWorkorderId()).isEqualTo(workorderId);
        assertThat(response.getInvoiceId()).isNull();
        verify(publisher)
                .requestInvoiceRegeneration(
                        org.mockito.ArgumentMatchers.eq(workorderId),
                        org.mockito.ArgumentMatchers.eq("idem-1"),
                        org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verifyNoInteractions(workorderInvoiceClient);
    }

    @Test
    @DisplayName("Should map publish failure to 503 on the async command path")
    void testRegenerateInvoiceFromWorkorder_AsyncPublishFailure() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        WorkorderCommandPublisher publisher = org.mockito.Mockito.mock(WorkorderCommandPublisher.class);
        when(commandPublisherProvider.getIfAvailable()).thenReturn(publisher);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(publisher)
                .requestInvoiceRegeneration(
                        org.mockito.ArgumentMatchers.eq(workorderId),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.regenerateInvoiceFromWorkorder(workorderId, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
