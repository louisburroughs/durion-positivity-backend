package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.WorkorderCommandPublisher;
import com.positivity.accounting.internal.entity.InvoiceRegenerationRequest;
import com.positivity.accounting.internal.repository.InvoiceRegenerationRequestRepository;
import com.positivity.accounting.internal.service.InvoiceRegenerationServiceImpl;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the async-only {@link InvoiceRegenerationServiceImpl} (ADR-0044 #900 Phase
 * 5.4, extended #1537 D1): regeneration publishes a {@code workorder.invoice.regenerate-requested}
 * command, persists a PENDING tracking row keyed by the publisher's commandId, and returns
 * PENDING; a repeat call carrying an idempotency key whose row already completed returns that
 * terminal state without publishing again.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceRegenerationService Unit Tests")
class InvoiceRegenerationServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMMAND_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Mock
    private org.springframework.beans.factory.ObjectProvider<WorkorderCommandPublisher> commandPublisherProvider;

    @Mock
    private InvoiceRegenerationRequestRepository invoiceRegenerationRequestRepository;

    private InvoiceRegenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InvoiceRegenerationServiceImpl(
                commandPublisherProvider, invoiceRegenerationRequestRepository, TEST_CLOCK);
    }

    @Test
    @DisplayName("Should publish command, persist a PENDING row keyed by the commandId, and return PENDING")
    void regenerate_publishesCommandPersistsPendingRow_returnsPending() {
        WorkorderCommandPublisher publisher = org.mockito.Mockito.mock(WorkorderCommandPublisher.class);
        when(commandPublisherProvider.getIfAvailable()).thenReturn(publisher);
        when(invoiceRegenerationRequestRepository.findByIdempotencyKey("idem-1"))
                .thenReturn(Optional.empty());
        when(publisher.requestInvoiceRegeneration(WORKORDER_ID, "idem-1", "SYSTEM"))
                .thenReturn(COMMAND_ID);

        InvoiceGenerationResponse response = service.regenerateInvoiceFromWorkorder(WORKORDER_ID, "idem-1");

        assertThat(response.getStatus()).isEqualTo(InvoiceRegenerationServiceImpl.STATUS_PENDING);
        assertThat(response.getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(response.getInvoiceId()).isNull();

        ArgumentCaptor<InvoiceRegenerationRequest> saved = ArgumentCaptor.forClass(InvoiceRegenerationRequest.class);
        verify(invoiceRegenerationRequestRepository).save(saved.capture());
        assertThat(saved.getValue().getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(saved.getValue().getCommandId()).isEqualTo(COMMAND_ID);
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(saved.getValue().getStatus()).isEqualTo(InvoiceRegenerationRequest.STATUS_PENDING);
        assertThat(saved.getValue().getRequestedAt()).isEqualTo(TEST_CLOCK.instant());
    }

    @Test
    @DisplayName("Should map publish failure to 503 and not persist a request row")
    void regenerate_publishFailure_maps503() {
        WorkorderCommandPublisher publisher = org.mockito.Mockito.mock(WorkorderCommandPublisher.class);
        when(commandPublisherProvider.getIfAvailable()).thenReturn(publisher);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(publisher)
                .requestInvoiceRegeneration(WORKORDER_ID, null, "SYSTEM");

        assertThatThrownBy(() -> service.regenerateInvoiceFromWorkorder(WORKORDER_ID, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(invoiceRegenerationRequestRepository, never()).save(any());
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

    @Test
    @DisplayName("A repeat call with a completed idempotency key returns the terminal state without re-publishing")
    void regenerate_completedIdempotencyKey_returnsTerminalStateWithoutPublishing() {
        when(invoiceRegenerationRequestRepository.findByIdempotencyKey("idem-done"))
                .thenReturn(Optional.of(InvoiceRegenerationRequest.builder()
                        .workorderId(WORKORDER_ID)
                        .commandId(COMMAND_ID)
                        .idempotencyKey("idem-done")
                        .status(InvoiceRegenerationRequest.STATUS_COMPLETED)
                        .resultInvoiceId(INVOICE_ID)
                        .requestedAt(TEST_CLOCK.instant())
                        .resolvedAt(TEST_CLOCK.instant())
                        .build()));

        InvoiceGenerationResponse response = service.regenerateInvoiceFromWorkorder(WORKORDER_ID, "idem-done");

        assertThat(response.getStatus()).isEqualTo(InvoiceRegenerationRequest.STATUS_COMPLETED);
        assertThat(response.getInvoiceId()).isEqualTo(INVOICE_ID);
        assertThat(response.getWorkorderId()).isEqualTo(WORKORDER_ID);
        // Never touches the publisher (not even asked for) and never saves a new row.
        verify(commandPublisherProvider, never()).getIfAvailable();
        verify(invoiceRegenerationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("A pending (not yet completed) idempotency key re-publishes rather than short-circuiting")
    void regenerate_pendingIdempotencyKey_stillPublishes() {
        WorkorderCommandPublisher publisher = org.mockito.Mockito.mock(WorkorderCommandPublisher.class);
        when(commandPublisherProvider.getIfAvailable()).thenReturn(publisher);
        when(invoiceRegenerationRequestRepository.findByIdempotencyKey("idem-pending"))
                .thenReturn(Optional.of(InvoiceRegenerationRequest.builder()
                        .workorderId(WORKORDER_ID)
                        .commandId(COMMAND_ID)
                        .idempotencyKey("idem-pending")
                        .status(InvoiceRegenerationRequest.STATUS_PENDING)
                        .requestedAt(TEST_CLOCK.instant())
                        .build()));
        when(publisher.requestInvoiceRegeneration(WORKORDER_ID, "idem-pending", "SYSTEM"))
                .thenReturn(COMMAND_ID);

        InvoiceGenerationResponse response = service.regenerateInvoiceFromWorkorder(WORKORDER_ID, "idem-pending");

        assertThat(response.getStatus()).isEqualTo(InvoiceRegenerationServiceImpl.STATUS_PENDING);
        verify(publisher).requestInvoiceRegeneration(WORKORDER_ID, "idem-pending", "SYSTEM");
        verify(invoiceRegenerationRequestRepository).save(any());
    }
}
