package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.receiving.CreateReceivingSessionRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveLineRequest;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import com.positivity.inventory.internal.entity.ReceivingLine;
import com.positivity.inventory.internal.entity.ReceivingSession;
import com.positivity.inventory.internal.enums.ReceivingLineStatus;
import com.positivity.inventory.internal.enums.ReceivingSessionStatus;
import com.positivity.inventory.internal.exception.ReceivingSessionNotFoundException;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.WorkorderClosedException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryVarianceRepository;
import com.positivity.inventory.internal.repository.ReceivingSessionRepository;
import com.positivity.inventory.internal.service.ReceivingServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceivingServiceImplTest {

    @Mock
    private ReceivingSessionRepository receivingSessionRepository;

    @Mock
    private InventoryVarianceRepository inventoryVarianceRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @InjectMocks
    private ReceivingServiceImpl receivingService;

    @Test
    void createReceivingSession_manualHappyPath() {
        when(receivingSessionRepository.save(any(ReceivingSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-123", "MANUAL");
        ReceivingSessionResponse response = receivingService.createReceivingSession(request, "test-user");

        assertThat(response.getStatus()).isEqualTo("OPEN");
        assertThat(response.getEntryMethod()).isEqualTo("MANUAL");
        assertThat(response.getLines()).hasSize(2);
        assertThat(response.getCreatedByUserId()).isEqualTo("test-user");
        assertThat(response.getSourceDocumentType()).isEqualTo("PO");
    }

    @Test
    void createReceivingSession_scanHappyPath() {
        when(receivingSessionRepository.save(any(ReceivingSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-123", "SCAN");
        ReceivingSessionResponse response = receivingService.createReceivingSession(request, "test-user");

        assertThat(response.getStatus()).isEqualTo("OPEN");
        assertThat(response.getEntryMethod()).isEqualTo("SCAN");
        assertThat(response.getLines()).hasSize(2);
        assertThat(response.getCreatedByUserId()).isEqualTo("test-user");
    }

    @Test
    void createReceivingSession_asnPrefix_setsSourceDocumentType() {
        when(receivingSessionRepository.save(any(ReceivingSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("ASN-ABC-789", "MANUAL");
        ReceivingSessionResponse response = receivingService.createReceivingSession(request, "test-user");

        assertThat(response.getSourceDocumentType()).isEqualTo("ASN");
    }

    @Test
    void createReceivingSession_sourceDocumentNotFound() {
        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-999", "MANUAL");

        assertThrows(SourceDocumentNotFoundException.class,
                () -> receivingService.createReceivingSession(request, "test-user"));
    }

    @Test
    void createReceivingSession_sourceDocumentAlreadyReceived() {
        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-456", "MANUAL");

        assertThrows(SourceDocumentAlreadyReceivedException.class,
                () -> receivingService.createReceivingSession(request, "test-user"));
    }

    @Test
    void createReceivingSession_invalidEntryMethod() {
        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-123", "INVALID");

        assertThrows(IllegalArgumentException.class,
                () -> receivingService.createReceivingSession(request, "test-user"));
    }

    @Test
    void getReceivingSession_found() {
        UUID sessionId = UUID.randomUUID();
        ReceivingSession session = new ReceivingSession();
        session.setSessionId(sessionId);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ReceivingSessionResponse response = receivingService.getReceivingSession(sessionId);

        assertThat(response.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    void getReceivingSession_notFound() {
        UUID sessionId = UUID.randomUUID();
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThrows(ReceivingSessionNotFoundException.class,
                () -> receivingService.getReceivingSession(sessionId));
    }

    @Test
    void getReceivingSession_notFound_throwsException() {
        UUID sessionId = UUID.randomUUID();
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThrows(ReceivingSessionNotFoundException.class,
                () -> receivingService.getReceivingSession(sessionId));
    }

    // ─── Story #34: receiveItemsIntoStaging service-layer tests ──────────────

    // Issue #34: AC1 — exact receipt must return linesProcessed=1 and empty
    // variances
    @Test
    void receiveItemsIntoStaging_exactQuantity_returnsLinesProcessedAndEmptyVariances() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        ReceiveLineRequest line = new ReceiveLineRequest(lineId, new BigDecimal("10"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(line));

        // Mock the session repository to return a session
        ReceivingLine receivingLine = ReceivingLine.builder().lineId(lineId).expectedQuantity(new BigDecimal("10"))
                .build();
        ReceivingSession session = ReceivingSession.builder().sessionId(sessionId).lines(List.of(receivingLine))
                .build();
        receivingLine.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getLinesProcessed()).isEqualTo(1);
        assertThat(response.getVariances()).isEmpty();
        assertThat(response.getSessionStatus()).isEqualTo("COMPLETED");
    }

    // Issue #34: AC2 — short receipt must yield a SHORTAGE variance with correct
    // quantities
    @Test
    void receiveItemsIntoStaging_shortQuantity_returnsShortageVariance() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        ReceiveLineRequest line = new ReceiveLineRequest(lineId, new BigDecimal("8"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(line));

        // Mock the session repository to return a session
        ReceivingLine receivingLine = ReceivingLine.builder().lineId(lineId).productId("PROD-1")
                .expectedQuantity(new BigDecimal("10")).build();
        ReceivingSession session = ReceivingSession.builder().sessionId(sessionId).lines(List.of(receivingLine))
                .build();
        receivingLine.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(inventoryVarianceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getVariances()).hasSize(1);
        ReceiveItemsResponse.VarianceSummaryResponse variance = response.getVariances().get(0);
        assertThat(variance.getVarianceType()).isEqualTo("SHORTAGE");
        assertThat(variance.getVarianceQuantity()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(variance.getExpectedQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(variance.getReceivedQuantity()).isEqualByComparingTo(new BigDecimal("8"));
    }

    // Issue #34: AC3 — over receipt must yield an OVERAGE variance with correct
    // quantities
    @Test
    void receiveItemsIntoStaging_overQuantity_returnsOverageVariance() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        ReceiveLineRequest line = new ReceiveLineRequest(lineId, new BigDecimal("12"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(line));

        // Mock the session repository to return a session
        ReceivingLine receivingLine = ReceivingLine.builder().lineId(lineId).productId("PROD-1")
                .expectedQuantity(new BigDecimal("10")).build();
        ReceivingSession session = ReceivingSession.builder().sessionId(sessionId).lines(List.of(receivingLine))
                .build();
        receivingLine.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(inventoryVarianceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getVariances()).hasSize(1);
        ReceiveItemsResponse.VarianceSummaryResponse variance = response.getVariances().get(0);
        assertThat(variance.getVarianceType()).isEqualTo("OVERAGE");
        assertThat(variance.getVarianceQuantity()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(variance.getExpectedQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(variance.getReceivedQuantity()).isEqualByComparingTo(new BigDecimal("12"));
    }

    // Issue #34: failure path — unknown session must throw
    // ReceivingSessionNotFoundException
    @Test
    void receiveItemsIntoStaging_sessionNotFound_throwsNotFoundException() {
        UUID unknownSessionId = UUID.randomUUID();
        ReceiveLineRequest line = new ReceiveLineRequest(UUID.randomUUID(), new BigDecimal("5"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(line));

        // We need to mock the repository to throw the exception
        when(receivingSessionRepository.findById(unknownSessionId)).thenReturn(Optional.empty());

        assertThrows(ReceivingSessionNotFoundException.class,
                () -> receivingService.receiveItemsIntoStaging(unknownSessionId, request, "test-user"));
    }

    @Test
    void receiveItemsIntoStaging_unknownLineId_isSkippedGracefully() {
        UUID sessionId = UUID.randomUUID();
        UUID knownLineId = UUID.randomUUID();
        UUID unknownLineId = UUID.randomUUID();

        ReceivingLine knownLine = ReceivingLine.builder().lineId(knownLineId).expectedQuantity(new BigDecimal("10"))
                .build();
        ReceivingSession session = ReceivingSession.builder().sessionId(sessionId).lines(List.of(knownLine)).build();
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ReceiveLineRequest knownLineRequest = new ReceiveLineRequest(knownLineId, new BigDecimal("10"));
        ReceiveLineRequest unknownLineRequest = new ReceiveLineRequest(unknownLineId, new BigDecimal("5"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(knownLineRequest, unknownLineRequest));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getLinesProcessed()).isEqualTo(1);
        assertThat(response.getVariances()).isEmpty();
    }

    @Test
    void receiveItemsIntoStaging_mixedReceipt_processesAllLinesAndCreatesVariance() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId1 = UUID.randomUUID();
        UUID lineId2 = UUID.randomUUID();

        ReceivingLine line1 = ReceivingLine.builder().lineId(lineId1).productId("PROD-1")
                .expectedQuantity(new BigDecimal("10")).status(ReceivingLineStatus.EXPECTED).build();
        ReceivingLine line2 = ReceivingLine.builder().lineId(lineId2).productId("PROD-2")
                .expectedQuantity(new BigDecimal("20")).status(ReceivingLineStatus.EXPECTED).build();
        ReceivingSession session = ReceivingSession.builder().sessionId(sessionId).lines(List.of(line1, line2)).build();
        line1.setSession(session);
        line2.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(inventoryVarianceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveLineRequest req1 = new ReceiveLineRequest(lineId1, new BigDecimal("10")); // Exact
        ReceiveLineRequest req2 = new ReceiveLineRequest(lineId2, new BigDecimal("15")); // Short
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(req1, req2));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getLinesProcessed()).isEqualTo(2);
        assertThat(response.getVariances()).hasSize(1);
        assertThat(response.getVariances().get(0).getVarianceType()).isEqualTo("SHORTAGE");
        assertThat(response.getVariances().get(0).getVarianceQuantity()).isEqualByComparingTo("5");
    }

    @Test
    void receiveItemsIntoStaging_partialReceipt_setsStatusToInProgress() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId1 = UUID.randomUUID();
        UUID lineId2 = UUID.randomUUID();

        ReceivingLine line1 = ReceivingLine.builder().lineId(lineId1).expectedQuantity(new BigDecimal("10"))
                .status(ReceivingLineStatus.EXPECTED).build();
        ReceivingLine line2 = ReceivingLine.builder().lineId(lineId2).expectedQuantity(new BigDecimal("20"))
                .status(ReceivingLineStatus.EXPECTED).build();
        ReceivingSession session = ReceivingSession.builder().sessionId(sessionId).lines(List.of(line1, line2))
                .status(ReceivingSessionStatus.OPEN).build();
        line1.setSession(session);
        line2.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ReceiveLineRequest req1 = new ReceiveLineRequest(lineId1, new BigDecimal("10")); // Exact
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(req1));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getSessionStatus()).isEqualTo(ReceivingSessionStatus.IN_PROGRESS.name());
    }

    @Test
    void receiveItemsIntoStaging_fullReceipt_setsStatusToCompleted() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId1 = UUID.randomUUID();
        UUID lineId2 = UUID.randomUUID();

        ReceivingLine line1 = ReceivingLine.builder().lineId(lineId1).expectedQuantity(new BigDecimal("10"))
                .status(ReceivingLineStatus.EXPECTED).build();
        ReceivingLine line2 = ReceivingLine.builder().lineId(lineId2).expectedQuantity(new BigDecimal("20"))
                .status(ReceivingLineStatus.EXPECTED).build();
        ReceivingSession session = ReceivingSession.builder().sessionId(sessionId).lines(List.of(line1, line2))
                .status(ReceivingSessionStatus.OPEN).build();
        line1.setSession(session);
        line2.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ReceiveLineRequest req1 = new ReceiveLineRequest(lineId1, new BigDecimal("10"));
        ReceiveLineRequest req2 = new ReceiveLineRequest(lineId2, new BigDecimal("20"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(req1, req2));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getSessionStatus()).isEqualTo(ReceivingSessionStatus.COMPLETED.name());
    }

    @Test
    void createReceivingSession_withNullEntryMethod_defaultsToManual() {
        when(receivingSessionRepository.save(any(ReceivingSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-123", null);
        ReceivingSessionResponse response = receivingService.createReceivingSession(request, "test-user");

        assertThat(response.getEntryMethod()).isEqualTo("MANUAL");
    }

    @Test
    void receiveItemsIntoStaging_withCancelledLine_setsStatusToCompleted() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId1 = UUID.randomUUID();
        UUID lineId2 = UUID.randomUUID();

        ReceivingLine line1 = ReceivingLine.builder().lineId(lineId1).expectedQuantity(new BigDecimal("10"))
                .status(ReceivingLineStatus.EXPECTED).build();
        ReceivingLine line2 = ReceivingLine.builder().lineId(lineId2).expectedQuantity(new BigDecimal("20"))
                .status(ReceivingLineStatus.CANCELLED).build();
        ReceivingSession session = ReceivingSession.builder().sessionId(sessionId).lines(List.of(line1, line2))
                .status(ReceivingSessionStatus.OPEN).build();
        line1.setSession(session);
        line2.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ReceiveLineRequest req1 = new ReceiveLineRequest(lineId1, new BigDecimal("10"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(req1));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getSessionStatus()).isEqualTo(ReceivingSessionStatus.COMPLETED.name());
    }

    @Test
    void receiveItemsIntoStaging_exactMatch_noVariance() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .sourceDocumentId("PO-123")
                .status(ReceivingSessionStatus.OPEN)
                .lines(new java.util.ArrayList<>(List.of(line)))
                .build();
        line.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveLineRequest lineReq = new ReceiveLineRequest(lineId, new BigDecimal("10"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(lineReq));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getVariances()).isEmpty();
        assertThat(response.getLinesProcessed()).isEqualTo(1);
        assertThat(response.getSessionStatus()).isEqualTo("COMPLETED");
        assertThat(session.getLines().get(0).getStatus()).isEqualTo(ReceivingLineStatus.RECEIVED);
    }

    @Test
    void receiveItemsIntoStaging_shortReceipt_shortageVariance() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .sourceDocumentId("PO-123")
                .status(ReceivingSessionStatus.OPEN)
                .lines(new java.util.ArrayList<>(List.of(line)))
                .build();
        line.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryVarianceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveLineRequest lineReq = new ReceiveLineRequest(lineId, new BigDecimal("8"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(lineReq));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getVariances()).hasSize(1);
        assertThat(response.getVariances().get(0).getVarianceType()).isEqualTo("SHORTAGE");
        assertThat(response.getVariances().get(0).getVarianceQuantity()).isEqualByComparingTo("2");
        assertThat(response.getSessionStatus()).isEqualTo("COMPLETED");
        assertThat(session.getLines().get(0).getStatus()).isEqualTo(ReceivingLineStatus.RECEIVED_SHORT);
    }

    @Test
    void receiveItemsIntoStaging_overReceipt_overageVariance() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .sourceDocumentId("PO-123")
                .status(ReceivingSessionStatus.OPEN)
                .lines(new java.util.ArrayList<>(List.of(line)))
                .build();
        line.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryVarianceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveLineRequest lineReq = new ReceiveLineRequest(lineId, new BigDecimal("12"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(lineReq));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getVariances()).hasSize(1);
        assertThat(response.getVariances().get(0).getVarianceType()).isEqualTo("OVERAGE");
        assertThat(response.getVariances().get(0).getVarianceQuantity()).isEqualByComparingTo("2");
        assertThat(response.getSessionStatus()).isEqualTo("COMPLETED");
        assertThat(session.getLines().get(0).getStatus()).isEqualTo(ReceivingLineStatus.RECEIVED_OVER);
    }

    @Test
    void receiveItemsIntoStaging_allLinesReceived_sessionCompleted() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId1 = UUID.randomUUID();
        UUID lineId2 = UUID.randomUUID();
        ReceivingLine line1 = ReceivingLine.builder().lineId(lineId1).productId("PROD-001")
                .expectedQuantity(new BigDecimal("10")).status(ReceivingLineStatus.EXPECTED).build();
        ReceivingLine line2 = ReceivingLine.builder().lineId(lineId2).productId("PROD-002")
                .expectedQuantity(new BigDecimal("5")).status(ReceivingLineStatus.EXPECTED).build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .status(ReceivingSessionStatus.OPEN)
                .lines(new java.util.ArrayList<>(List.of(line1, line2)))
                .build();
        line1.setSession(session);
        line2.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(
                new ReceiveLineRequest(lineId1, new BigDecimal("10")),
                new ReceiveLineRequest(lineId2, new BigDecimal("5"))));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");
        assertThat(response.getSessionStatus()).isEqualTo("COMPLETED");
    }

    // ─── Story #33: crossDockLineToWorkorder service-layer tests ─────────────

    /**
     * AC-1: Full quantity cross-dock must return a CrossDockResponse with the
     * correct workorderId, crossDockedQuantity, and non-null sessionStatus.
     *
     * Issue: CAP-216 / Story #33
     */
    @Test
    void crossDockLineToWorkorder_fullQuantity_returnsCrossDockResponse() {
        // Arrange
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        java.util.ArrayList<ReceivingLine> lines = new java.util.ArrayList<>(List.of(line));
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .sourceDocumentId("PO-123")
                .status(ReceivingSessionStatus.OPEN)
                .lines(lines)
                .build();
        line.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CrossDockRequest request = new CrossDockRequest("WO-001", null, new BigDecimal("10"), null);

        // Act & Assert — RED: UnsupportedOperationException until Story #33 is
        // implemented
        CrossDockResponse result = receivingService.crossDockLineToWorkorder(
                sessionId, lineId, request, "actor-user");

        assertThat(result.getWorkorderId()).isEqualTo("WO-001");
        assertThat(result.getCrossDockedQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(result.getLineId()).isEqualTo(lineId);
        assertThat(result.getSessionStatus()).isNotNull();
    }

    /**
     * AC-2: Partial quantity cross-dock (qty < expectedQty) must return the
     * requested cross-docked quantity without marking the line as RECEIVED.
     *
     * Issue: CAP-216 / Story #33
     */
    @Test
    void crossDockLineToWorkorder_partialQuantity_returnsCrossDockResponse() {
        // Arrange
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        java.util.ArrayList<ReceivingLine> lines = new java.util.ArrayList<>(List.of(line));
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .sourceDocumentId("PO-123")
                .status(ReceivingSessionStatus.OPEN)
                .lines(lines)
                .build();
        line.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CrossDockRequest request = new CrossDockRequest("WO-001", null, new BigDecimal("3"), null);

        // Act & Assert — RED: UnsupportedOperationException until Story #33 is
        // implemented
        CrossDockResponse result = receivingService.crossDockLineToWorkorder(
                sessionId, lineId, request, "actor-user");

        assertThat(result.getCrossDockedQuantity()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(result.getLineId()).isEqualTo(lineId);
        // Partial cross-dock must not set line to RECEIVED
        assertThat(result.getLineStatus()).isNotEqualTo(ReceivingLineStatus.RECEIVED.name());
    }

    /**
     * AC-3: Cross-dock to a closed workorder must throw WorkorderClosedException.
     *
     * Issue: CAP-216 / Story #33
     */
    @Test
    void crossDockLineToWorkorder_closedWorkorder_throwsWorkorderClosedException() {
        // Arrange
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        java.util.ArrayList<ReceivingLine> lines = new java.util.ArrayList<>(List.of(line));
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .sourceDocumentId("PO-123")
                .status(ReceivingSessionStatus.OPEN)
                .lines(lines)
                .build();
        line.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // Issue CAP-216 / Story #33: workorderId suffix "-CLOSED" signals a closed
        // workorder
        CrossDockRequest request = new CrossDockRequest("WO-CLOSED", null, new BigDecimal("10"), null);

        // Act & Assert — RED: UnsupportedOperationException until Story #33 is
        // implemented
        assertThrows(WorkorderClosedException.class,
                () -> receivingService.crossDockLineToWorkorder(
                        sessionId, lineId, request, "actor-user"));
    }

    /**
     * Failure path: unknown sessionId must throw ReceivingSessionNotFoundException.
     *
     * Issue: CAP-216 / Story #33
     */
    @Test
    void crossDockLineToWorkorder_sessionNotFound_throwsNotFoundException() {
        // Arrange
        when(receivingSessionRepository.findById(any())).thenReturn(Optional.empty());
        CrossDockRequest request = new CrossDockRequest("WO-001", null, new BigDecimal("10"), null);

        // Act & Assert — RED: UnsupportedOperationException until Story #33 is
        // implemented
        assertThrows(ReceivingSessionNotFoundException.class,
                () -> receivingService.crossDockLineToWorkorder(
                        UUID.randomUUID(), UUID.randomUUID(), request, "actor-user"));
    }

    @Test
    void receiveItemsIntoStaging_partialLinesReceived_sessionInProgress() {
        UUID sessionId = UUID.randomUUID();
        UUID lineId1 = UUID.randomUUID();
        UUID lineId2 = UUID.randomUUID();
        ReceivingLine line1 = ReceivingLine.builder().lineId(lineId1).productId("PROD-001")
                .expectedQuantity(new BigDecimal("10")).status(ReceivingLineStatus.EXPECTED).build();
        ReceivingLine line2 = ReceivingLine.builder().lineId(lineId2).productId("PROD-002")
                .expectedQuantity(new BigDecimal("5")).status(ReceivingLineStatus.EXPECTED).build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .status(ReceivingSessionStatus.OPEN)
                .lines(new java.util.ArrayList<>(List.of(line1, line2)))
                .build();
        line1.setSession(session);
        line2.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(
                new ReceiveLineRequest(lineId1, new BigDecimal("10"))));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");
        assertThat(response.getSessionStatus()).isEqualTo("IN_PROGRESS");
    }
}
