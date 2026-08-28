package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.WorkorderCommandPublisher;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.InvoiceRegenerationRequest;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.InvoiceRegenerationRequestRepository;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

    @Mock
    private ExtInvoiceRepository extInvoiceRepository;

    private InvoiceRegenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InvoiceRegenerationServiceImpl(
                commandPublisherProvider, invoiceRegenerationRequestRepository, extInvoiceRepository, TEST_CLOCK);
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
        // No replicated invoice yet for this workorder: nothing to distinguish a future fact from.
        assertThat(saved.getValue().getPriorInvoiceId()).isNull();
    }

    @Test
    @DisplayName("Captures the workorder's existing invoiceId as priorInvoiceId on the new row (#1537 F4)")
    void regenerate_capturesPriorInvoiceId() {
        WorkorderCommandPublisher publisher = org.mockito.Mockito.mock(WorkorderCommandPublisher.class);
        when(commandPublisherProvider.getIfAvailable()).thenReturn(publisher);
        when(invoiceRegenerationRequestRepository.findByIdempotencyKey("idem-prior"))
                .thenReturn(Optional.empty());
        when(publisher.requestInvoiceRegeneration(WORKORDER_ID, "idem-prior", "SYSTEM"))
                .thenReturn(COMMAND_ID);
        UUID staleInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID currentInvoiceId = INVOICE_ID;
        when(extInvoiceRepository.findByWorkorderId(WORKORDER_ID))
                .thenReturn(List.of(
                        ExtInvoice.builder()
                                .invoiceId(staleInvoiceId)
                                .workorderId(WORKORDER_ID)
                                .status("VOID")
                                .updatedAt(TEST_CLOCK.instant().minusSeconds(3600))
                                .build(),
                        ExtInvoice.builder()
                                .invoiceId(currentInvoiceId)
                                .workorderId(WORKORDER_ID)
                                .status("FINALIZED")
                                .updatedAt(TEST_CLOCK.instant())
                                .build()));

        service.regenerateInvoiceFromWorkorder(WORKORDER_ID, "idem-prior");

        ArgumentCaptor<InvoiceRegenerationRequest> saved = ArgumentCaptor.forClass(InvoiceRegenerationRequest.class);
        verify(invoiceRegenerationRequestRepository).save(saved.capture());
        // The most recently updated replicated invoice is the workorder's current one.
        assertThat(saved.getValue().getPriorInvoiceId()).isEqualTo(currentInvoiceId);
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
    @DisplayName("A pending (not yet completed) idempotency key short-circuits to PENDING without re-publishing"
            + " (#1537 F3)")
    void regenerate_pendingIdempotencyKey_shortCircuitsWithoutRepublishing() {
        // The original scenario (F3): a 30s-long regeneration retried at 5s finds the row still
        // PENDING. Re-publishing here double-fires the Kafka command, and the second insert then
        // violates the partial unique index on idempotency_key (V26) with a 5xx — the opposite of
        // idempotent. The only safe response is the existing PENDING state, with no new command
        // and no new row.
        when(invoiceRegenerationRequestRepository.findByIdempotencyKey("idem-pending"))
                .thenReturn(Optional.of(InvoiceRegenerationRequest.builder()
                        .workorderId(WORKORDER_ID)
                        .commandId(COMMAND_ID)
                        .idempotencyKey("idem-pending")
                        .status(InvoiceRegenerationRequest.STATUS_PENDING)
                        .requestedAt(TEST_CLOCK.instant())
                        .build()));

        InvoiceGenerationResponse response = service.regenerateInvoiceFromWorkorder(WORKORDER_ID, "idem-pending");

        assertThat(response.getStatus()).isEqualTo(InvoiceRegenerationServiceImpl.STATUS_PENDING);
        assertThat(response.getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(response.getInvoiceId()).isNull();
        // Never touches the publisher (not even asked for) and never attempts a second insert.
        verify(commandPublisherProvider, never()).getIfAvailable();
        verify(invoiceRegenerationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("A FAILED row for the matching workorder returns FAILED and does not re-publish (finding 2a)")
    void regenerate_failedRowSameWorkorder_returnsFailedWithoutRepublishing() {
        when(invoiceRegenerationRequestRepository.findByIdempotencyKey("idem-failed"))
                .thenReturn(Optional.of(InvoiceRegenerationRequest.builder()
                        .workorderId(WORKORDER_ID)
                        .commandId(COMMAND_ID)
                        .idempotencyKey("idem-failed")
                        .status(InvoiceRegenerationRequest.STATUS_FAILED)
                        .requestedAt(TEST_CLOCK.instant())
                        .build()));

        InvoiceGenerationResponse response = service.regenerateInvoiceFromWorkorder(WORKORDER_ID, "idem-failed");

        assertThat(response.getStatus()).isEqualTo(InvoiceRegenerationRequest.STATUS_FAILED);
        assertThat(response.getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(response.getInvoiceId()).isNull();
        verify(commandPublisherProvider, never()).getIfAvailable();
        verify(invoiceRegenerationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("Same idempotency key reused for a different workorder returns 409 CONFLICT and does not"
            + " publish (finding 2b)")
    void regenerate_sameKeyDifferentWorkorder_returns409WithoutPublishing() {
        UUID otherWorkorderId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(invoiceRegenerationRequestRepository.findByIdempotencyKey("idem-shared"))
                .thenReturn(Optional.of(InvoiceRegenerationRequest.builder()
                        .workorderId(otherWorkorderId)
                        .commandId(COMMAND_ID)
                        .idempotencyKey("idem-shared")
                        .status(InvoiceRegenerationRequest.STATUS_COMPLETED)
                        .resultInvoiceId(INVOICE_ID)
                        .requestedAt(TEST_CLOCK.instant())
                        .resolvedAt(TEST_CLOCK.instant())
                        .build()));

        assertThatThrownBy(() -> service.regenerateInvoiceFromWorkorder(WORKORDER_ID, "idem-shared"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(commandPublisherProvider, never()).getIfAvailable();
        verify(invoiceRegenerationRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("A same-key retry never attempts a second command publish or a second insert (#1537 F3)")
    void regenerate_sameKeyRetry_neverDoublePublishesOrDoubleInserts() {
        WorkorderCommandPublisher publisher = org.mockito.Mockito.mock(WorkorderCommandPublisher.class);
        when(invoiceRegenerationRequestRepository.findByIdempotencyKey("idem-retry"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(InvoiceRegenerationRequest.builder()
                        .workorderId(WORKORDER_ID)
                        .commandId(COMMAND_ID)
                        .idempotencyKey("idem-retry")
                        .status(InvoiceRegenerationRequest.STATUS_PENDING)
                        .requestedAt(TEST_CLOCK.instant())
                        .build()));
        when(commandPublisherProvider.getIfAvailable()).thenReturn(publisher);
        when(publisher.requestInvoiceRegeneration(WORKORDER_ID, "idem-retry", "SYSTEM"))
                .thenReturn(COMMAND_ID);

        InvoiceGenerationResponse first = service.regenerateInvoiceFromWorkorder(WORKORDER_ID, "idem-retry");
        InvoiceGenerationResponse retry = service.regenerateInvoiceFromWorkorder(WORKORDER_ID, "idem-retry");

        assertThat(first.getStatus()).isEqualTo(InvoiceRegenerationServiceImpl.STATUS_PENDING);
        assertThat(retry.getStatus()).isEqualTo(InvoiceRegenerationServiceImpl.STATUS_PENDING);
        verify(publisher, org.mockito.Mockito.times(1))
                .requestInvoiceRegeneration(WORKORDER_ID, "idem-retry", "SYSTEM");
        verify(invoiceRegenerationRequestRepository, org.mockito.Mockito.times(1))
                .save(any());
    }
}
