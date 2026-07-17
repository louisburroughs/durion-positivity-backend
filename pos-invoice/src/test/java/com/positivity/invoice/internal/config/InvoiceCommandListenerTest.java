package com.positivity.invoice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.entity.ProcessedEvent;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
import com.positivity.invoice.service.InvoiceService;
import com.positivity.invoice.service.OutboxReplayService;
import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class InvoiceCommandListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final OutboxReplayService replayService = mock(OutboxReplayService.class);
    private final InvoiceService invoiceService = mock(InvoiceService.class);
    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);

    private InvoiceCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new InvoiceCommandListener(
                TEST_CLOCK, new ObjectMapper(), replayService, invoiceService, processedEvents);
        ReflectionTestUtils.setField(listener, "replayMaxLookback", Duration.ofDays(30));
    }

    private String generationCommand(String commandId) {
        return """
                {"commandType":"invoice.generation-requested","commandId":"%s",
                 "payload":{"workorderId":"%s","idempotencyKey":"key-1",
                            "lineItems":[{"description":"Brake pads","quantity":1,"unitPrice":200.00,"amount":200.00}]}}
                """.formatted(commandId, WORKORDER_ID);
    }

    @Test
    @DisplayName("generation-requested creates the invoice and records the commandId")
    void generationRequestedCreatesInvoice() {
        when(processedEvents.existsById("c-1")).thenReturn(false);
        when(invoiceService.createInvoice(any(InvoiceCreationRequest.class)))
                .thenReturn(InvoiceGenerationResponse.builder()
                        .invoiceId(INVOICE_ID)
                        .workorderId(WORKORDER_ID)
                        .build());

        listener.onCommand(generationCommand("c-1"));

        ArgumentCaptor<InvoiceCreationRequest> request = ArgumentCaptor.forClass(InvoiceCreationRequest.class);
        verify(invoiceService).createInvoice(request.capture());
        assertThat(request.getValue().getWorkorderId()).isEqualTo(WORKORDER_ID);
        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("c-1");
        assertThat(processed.getValue().getOwner()).isEqualTo("invoice-commands");
    }

    @Test
    @DisplayName("Duplicate commandId is skipped — client retries produce exactly one invoice")
    void duplicateCommandIdIsSkipped() {
        when(processedEvents.existsById("c-dup")).thenReturn(true);

        listener.onCommand(generationCommand("c-dup"));

        verify(invoiceService, never()).createInvoice(any(InvoiceCreationRequest.class));
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("generation-requested without a workorderId is ignored")
    void generationRequestedWithoutWorkorderIdIsIgnored() {
        when(processedEvents.existsById("c-2")).thenReturn(false);

        listener.onCommand("""
                {"commandType":"invoice.generation-requested","commandId":"c-2","payload":{}}
                """);

        verify(invoiceService, never()).createInvoice(any(InvoiceCreationRequest.class));
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("generation-requested without a commandId is ignored")
    void generationRequestedWithoutCommandIdIsIgnored() {
        listener.onCommand("""
                {"commandType":"invoice.generation-requested","payload":{"workorderId":"%s"}}
                """.formatted(WORKORDER_ID));

        verify(invoiceService, never()).createInvoice(any(InvoiceCreationRequest.class));
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("replay-requested with since only replays from since minus slack")
    void replaySinceOnly() {
        Instant since = Instant.now(TEST_CLOCK).minus(Duration.ofHours(2));

        listener.onCommand("""
                {"commandType":"invoice.outbox.replay-requested","payload":{"since":"%s"}}
                """.formatted(since));

        ArgumentCaptor<Instant> captured = ArgumentCaptor.forClass(Instant.class);
        verify(replayService).replaySince(captured.capture());
        assertThat(captured.getValue()).isEqualTo(since.minusSeconds(1));
    }

    @Test
    @DisplayName("replay-requested with since and until replays the bounded window with slack")
    void replayBoundedWindow() {
        Instant since = Instant.now(TEST_CLOCK).minus(Duration.ofHours(2));
        Instant until = Instant.now(TEST_CLOCK).minus(Duration.ofHours(1));

        listener.onCommand("""
                {"commandType":"invoice.outbox.replay-requested","payload":{"since":"%s","until":"%s"}}
                """.formatted(since, until));

        verify(replayService).replayBetween(since.minusSeconds(1), until.plusSeconds(1));
    }

    @Test
    @DisplayName("replay-requested beyond the max lookback is rejected")
    void replayBeyondLookbackIsRejected() {
        Instant since = Instant.now(TEST_CLOCK).minus(Duration.ofDays(31));

        listener.onCommand("""
                {"commandType":"invoice.outbox.replay-requested","payload":{"since":"%s"}}
                """.formatted(since));

        verify(replayService, never()).replaySince(any());
        verify(replayService, never()).replayBetween(any(), any());
    }

    @Test
    @DisplayName("Unsupported command types and malformed messages are dropped without throwing")
    void unsupportedAndMalformedAreDropped() {
        listener.onCommand("{\"commandType\":\"invoice.something.else\",\"payload\":{}}");
        listener.onCommand("{not json");
        listener.onCommand("{\"payload\":{}}");

        verify(invoiceService, never()).createInvoice(any(InvoiceCreationRequest.class));
        verify(replayService, never()).replaySince(any());
    }

    @Test
    @DisplayName("Transient DB errors propagate so the container retries")
    void transientErrorsPropagate() {
        when(processedEvents.existsById(anyString())).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onCommand(generationCommand("c-3")));

        verify(invoiceService, never()).createInvoice(any(InvoiceCreationRequest.class));
    }
}
