package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.client.SourceDocumentStubClient;
import com.positivity.inventory.internal.dto.receiving.CreateReceivingSessionRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveLineRequest;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ReceivingLine;
import com.positivity.inventory.internal.entity.ReceivingSession;
import com.positivity.inventory.internal.enums.ReceivingLineStatus;
import com.positivity.inventory.internal.enums.ReceivingSessionStatus;
import com.positivity.inventory.internal.exception.PartMatchPermissionException;
import com.positivity.inventory.internal.exception.ReceivingSessionNotFoundException;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.WorkorderClosedException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryVarianceRepository;
import com.positivity.inventory.internal.repository.ReceivingSessionRepository;
import com.positivity.inventory.internal.service.ReceivingServiceImpl;
import com.positivity.inventory.internal.service.SiteDefaultsService;
import com.positivity.inventory.internal.service.WorkorderValidationService;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReceivingServiceImplTest {

    private static final UUID STAGING_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CROSS_DOCK_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private ReceivingSessionRepository receivingSessionRepository;

    @Mock
    private InventoryVarianceRepository inventoryVarianceRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private SourceDocumentStubClient sourceDocumentStubClient;

    @Mock
    private SiteDefaultsService siteDefaultsService;

    @Mock
    private WorkorderValidationService workorderValidationService;

    @org.mockito.Mock
    private com.positivity.inventory.internal.service.InventoryFactPublisher inventoryFactPublisher;

    @InjectMocks
    private ReceivingServiceImpl receivingService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createReceivingSession_manualHappyPath() {
        stubSourceDocumentLines();
        when(receivingSessionRepository.save(any(ReceivingSession.class))).thenAnswer(inv -> inv.getArgument(0));

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
        stubSourceDocumentLines();
        when(receivingSessionRepository.save(any(ReceivingSession.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-123", "SCAN");
        ReceivingSessionResponse response = receivingService.createReceivingSession(request, "test-user");

        assertThat(response.getStatus()).isEqualTo("OPEN");
        assertThat(response.getEntryMethod()).isEqualTo("SCAN");
        assertThat(response.getLines()).hasSize(2);
        assertThat(response.getCreatedByUserId()).isEqualTo("test-user");
    }

    @Test
    void createReceivingSession_asnPrefix_setsSourceDocumentType() {
        stubSourceDocumentLines();
        when(receivingSessionRepository.save(any(ReceivingSession.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("ASN-ABC-789", "MANUAL");
        ReceivingSessionResponse response = receivingService.createReceivingSession(request, "test-user");

        assertThat(response.getSourceDocumentType()).isEqualTo("ASN");
    }

    @Test
    void createReceivingSession_sourceDocumentNotFound() {
        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-999", "MANUAL");

        assertThrows(
                SourceDocumentNotFoundException.class,
                () -> receivingService.createReceivingSession(request, "test-user"));
    }

    @Test
    void createReceivingSession_sourceDocumentAlreadyReceived() {
        SourceDocumentStubClient.SourceDocumentLinesResponse sourceDocument =
                new SourceDocumentStubClient.SourceDocumentLinesResponse();
        sourceDocument.setAlreadyReceived(true);
        sourceDocument.setLines(List.of());
        when(sourceDocumentStubClient.fetchDocument(any(), any(), any())).thenReturn(sourceDocument);

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-456", "MANUAL");

        assertThrows(
                SourceDocumentAlreadyReceivedException.class,
                () -> receivingService.createReceivingSession(request, "test-user"));
    }

    @Test
    void createReceivingSession_invalidEntryMethod() {
        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-123", "INVALID");

        assertThrows(
                IllegalArgumentException.class, () -> receivingService.createReceivingSession(request, "test-user"));
    }

    @Test
    void getReceivingSession_found() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReceivingSession session = new ReceivingSession();
        session.setSessionId(sessionId);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ReceivingSessionResponse response = receivingService.getReceivingSession(sessionId);

        assertThat(response.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    void getReceivingSession_notFound() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThrows(ReceivingSessionNotFoundException.class, () -> receivingService.getReceivingSession(sessionId));
    }

    // ─── Story #34: receiveItemsIntoStaging service-layer tests ──────────────

    // Issue #34: AC1 — exact receipt must return linesProcessed=1 and empty
    // variances
    @Test
    void receiveItemsIntoStaging_exactQuantity_returnsLinesProcessedAndEmptyVariances() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReceiveLineRequest line = new ReceiveLineRequest(lineId, new BigDecimal("10"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(line));

        // Mock the session repository to return a session
        ReceivingLine receivingLine = ReceivingLine.builder()
                .lineId(lineId)
                .expectedQuantity(new BigDecimal("10"))
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .lines(List.of(receivingLine))
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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReceiveLineRequest line = new ReceiveLineRequest(lineId, new BigDecimal("8"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(line));

        // Mock the session repository to return a session
        ReceivingLine receivingLine = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-1")
                .expectedQuantity(new BigDecimal("10"))
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .lines(List.of(receivingLine))
                .build();
        receivingLine.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(inventoryVarianceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getVariances()).hasSize(1);
        ReceiveItemsResponse.VarianceSummaryResponse variance =
                response.getVariances().get(0);
        assertThat(variance.getVarianceType()).isEqualTo("SHORTAGE");
        assertThat(variance.getVarianceQuantity()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(variance.getExpectedQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(variance.getReceivedQuantity()).isEqualByComparingTo(new BigDecimal("8"));
    }

    // Issue #34: AC3 — over receipt must yield an OVERAGE variance with correct
    // quantities
    @Test
    void receiveItemsIntoStaging_overQuantity_returnsOverageVariance() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReceiveLineRequest line = new ReceiveLineRequest(lineId, new BigDecimal("12"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(line));

        // Mock the session repository to return a session
        ReceivingLine receivingLine = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-1")
                .expectedQuantity(new BigDecimal("10"))
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .lines(List.of(receivingLine))
                .build();
        receivingLine.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(inventoryVarianceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        assertThat(response.getVariances()).hasSize(1);
        ReceiveItemsResponse.VarianceSummaryResponse variance =
                response.getVariances().get(0);
        assertThat(variance.getVarianceType()).isEqualTo("OVERAGE");
        assertThat(variance.getVarianceQuantity()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(variance.getExpectedQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(variance.getReceivedQuantity()).isEqualByComparingTo(new BigDecimal("12"));
    }

    // Issue #34: failure path — unknown session must throw
    // ReceivingSessionNotFoundException
    @Test
    void receiveItemsIntoStaging_sessionNotFound_throwsNotFoundException() {
        UUID unknownSessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReceiveLineRequest line =
                new ReceiveLineRequest(UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("5"));
        ReceiveItemsRequest request = new ReceiveItemsRequest(List.of(line));

        // We need to mock the repository to throw the exception
        when(receivingSessionRepository.findById(unknownSessionId)).thenReturn(Optional.empty());

        assertThrows(
                ReceivingSessionNotFoundException.class,
                () -> receivingService.receiveItemsIntoStaging(unknownSessionId, request, "test-user"));
    }

    @Test
    void receiveItemsIntoStaging_unknownLineId_isSkippedGracefully() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID knownLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID unknownLineId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        ReceivingLine knownLine = ReceivingLine.builder()
                .lineId(knownLineId)
                .expectedQuantity(new BigDecimal("10"))
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .lines(List.of(knownLine))
                .build();
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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        ReceivingLine line1 = ReceivingLine.builder()
                .lineId(lineId1)
                .productId("PROD-1")
                .expectedQuantity(new BigDecimal("10"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingLine line2 = ReceivingLine.builder()
                .lineId(lineId2)
                .productId("PROD-2")
                .expectedQuantity(new BigDecimal("20"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .lines(List.of(line1, line2))
                .build();
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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        ReceivingLine line1 = ReceivingLine.builder()
                .lineId(lineId1)
                .expectedQuantity(new BigDecimal("10"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingLine line2 = ReceivingLine.builder()
                .lineId(lineId2)
                .expectedQuantity(new BigDecimal("20"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .lines(List.of(line1, line2))
                .status(ReceivingSessionStatus.OPEN)
                .build();
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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        ReceivingLine line1 = ReceivingLine.builder()
                .lineId(lineId1)
                .expectedQuantity(new BigDecimal("10"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingLine line2 = ReceivingLine.builder()
                .lineId(lineId2)
                .expectedQuantity(new BigDecimal("20"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .lines(List.of(line1, line2))
                .status(ReceivingSessionStatus.OPEN)
                .build();
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
        stubSourceDocumentLines();
        when(receivingSessionRepository.save(any(ReceivingSession.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-123", null);
        ReceivingSessionResponse response = receivingService.createReceivingSession(request, "test-user");

        assertThat(response.getEntryMethod()).isEqualTo("MANUAL");
    }

    @Test
    void createReceivingSession_sourceDocumentWithNoLines_throwsNotFound() {
        SourceDocumentStubClient.SourceDocumentLinesResponse sourceDocument =
                new SourceDocumentStubClient.SourceDocumentLinesResponse();
        sourceDocument.setLines(List.of());
        when(sourceDocumentStubClient.fetchDocument(any(), any(), any())).thenReturn(sourceDocument);

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-123", "MANUAL");

        assertThrows(
                SourceDocumentNotFoundException.class,
                () -> receivingService.createReceivingSession(request, "test-user"));
    }

    @Test
    void createReceivingSession_sourceDocumentLineWithoutProduct_throwsIllegalState() {
        SourceDocumentStubClient.SourceDocumentLineDto invalidLine =
                new SourceDocumentStubClient.SourceDocumentLineDto();
        invalidLine.setProductId(" ");
        invalidLine.setExpectedQuantity(new BigDecimal("5"));
        SourceDocumentStubClient.SourceDocumentLinesResponse sourceDocument =
                new SourceDocumentStubClient.SourceDocumentLinesResponse();
        sourceDocument.setLines(List.of(invalidLine));
        when(sourceDocumentStubClient.fetchDocument(any(), any(), any())).thenReturn(sourceDocument);

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-123", "MANUAL");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> receivingService.createReceivingSession(request, "test-user"));

        assertThat(exception.getMessage()).contains("productId is required");
    }

    @Test
    void receiveItemsIntoStaging_withCancelledLine_setsStatusToCompleted() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        ReceivingLine line1 = ReceivingLine.builder()
                .lineId(lineId1)
                .expectedQuantity(new BigDecimal("10"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingLine line2 = ReceivingLine.builder()
                .lineId(lineId2)
                .expectedQuantity(new BigDecimal("20"))
                .status(ReceivingLineStatus.CANCELLED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .lines(List.of(line1, line2))
                .status(ReceivingSessionStatus.OPEN)
                .build();
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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
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
    void receiveItemsIntoStaging_ledgerEntryUsesStagingLocationAndOnHandQuantityAfter() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        when(inventoryLedgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation("PROD-001", STAGING_LOCATION_ID))
                .thenReturn(7);

        ReceiveItemsRequest request =
                new ReceiveItemsRequest(List.of(new ReceiveLineRequest(lineId, new BigDecimal("10"))));

        receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(inventoryLedgerEntryRepository).save(ledgerCaptor.capture());
        InventoryLedgerEntry savedLedgerEntry = ledgerCaptor.getValue();

        assertThat(savedLedgerEntry.getLocationId()).isEqualTo(STAGING_LOCATION_ID);
        assertThat(savedLedgerEntry.getToLocationId()).isEqualTo(STAGING_LOCATION_ID);
        assertThat(savedLedgerEntry.getQuantityAfter()).isEqualTo(17);
        assertThat(savedLedgerEntry.getChangeInQuantity()).isEqualTo(10);
    }

    @Test
    void receiveItemsIntoStaging_ledgerEntryUsesSiteDefaultStagingLocationWhenConfigured() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID siteDefaultStagingLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ReflectionTestUtils.setField(receivingService, "configuredSiteId", siteId.toString());

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
        when(siteDefaultsService.getDefaultStagingLocationId(siteId))
                .thenReturn(Optional.of(siteDefaultStagingLocationId));
        when(inventoryLedgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation("PROD-001", siteDefaultStagingLocationId))
                .thenReturn(7);

        ReceiveItemsRequest request =
                new ReceiveItemsRequest(List.of(new ReceiveLineRequest(lineId, new BigDecimal("10"))));

        receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(inventoryLedgerEntryRepository).save(ledgerCaptor.capture());
        InventoryLedgerEntry savedLedgerEntry = ledgerCaptor.getValue();

        assertThat(savedLedgerEntry.getLocationId()).isEqualTo(siteDefaultStagingLocationId);
        assertThat(savedLedgerEntry.getToLocationId()).isEqualTo(siteDefaultStagingLocationId);
        assertThat(savedLedgerEntry.getQuantityAfter()).isEqualTo(17);
    }

    @Test
    void receiveItemsIntoStaging_shortReceipt_shortageVariance() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        ReceivingLine line1 = ReceivingLine.builder()
                .lineId(lineId1)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingLine line2 = ReceivingLine.builder()
                .lineId(lineId2)
                .productId("PROD-002")
                .expectedQuantity(new BigDecimal("5"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("WORK_IN_PROGRESS", "PROD-001"));

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("10"), null);

        // Act & Assert — RED: UnsupportedOperationException until Story #33 is
        // implemented
        CrossDockResponse result = receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user");

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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("WORK_IN_PROGRESS", "PROD-001"));

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("3"), null);

        // Act & Assert — RED: UnsupportedOperationException until Story #33 is
        // implemented
        CrossDockResponse result = receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user");

        assertThat(result.getCrossDockedQuantity()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(result.getLineId()).isEqualTo(lineId);
        // Partial cross-dock must not set line to RECEIVED
        assertThat(result.getLineStatus()).isNotEqualTo(ReceivingLineStatus.RECEIVED.name());
    }

    @Test
    void crossDockLineToWorkorder_accumulatesReceivedQuantity() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(new BigDecimal("2"))
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
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("WORK_IN_PROGRESS", "PROD-001"));

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("3"), null);

        CrossDockResponse result = receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user");

        assertThat(line.getReceivedQuantity()).isEqualByComparingTo("5");
        assertThat(result.getLineStatus()).isEqualTo(ReceivingLineStatus.RECEIVED_SHORT.name());
    }

    @Test
    void crossDockLineToWorkorder_exceedsExpectedQuantity_throwsAndDoesNotCreateLedgerEntries() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(new BigDecimal("8"))
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
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("WORK_IN_PROGRESS", "PROD-001"));

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("3"), null);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user"));

        assertThat(exception.getMessage()).contains("exceeds expected quantity");
        verify(inventoryLedgerEntryRepository, never()).save(any());
        verify(receivingSessionRepository, never()).save(any(ReceivingSession.class));
    }

    /**
     * AC-3: Cross-dock to a closed workorder must throw WorkorderClosedException.
     *
     * Issue: CAP-216 / Story #33
     */
    @Test
    void crossDockLineToWorkorder_closedWorkorder_throwsWorkorderClosedException() {
        // Arrange
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

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

        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("CANCELLED", null));

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("10"), null);

        // Act & Assert — RED: UnsupportedOperationException until Story #33 is
        // implemented
        assertThrows(
                WorkorderClosedException.class,
                () -> receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user"));
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
        CrossDockRequest request = new CrossDockRequest(
                "WO-001",
                UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                new BigDecimal("10"),
                null);

        // Act & Assert — RED: UnsupportedOperationException until Story #33 is
        // implemented
        assertThrows(
                ReceivingSessionNotFoundException.class,
                () -> receivingService.crossDockLineToWorkorder(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        request,
                        "actor-user"));
    }

    @Test
    void crossDockLineToWorkorder_fractionalQuantity_throwsIllegalArgumentException() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("WORK_IN_PROGRESS", "PROD-001"));

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("1.5"), null);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user"));

        assertThat(exception.getMessage()).contains("whole number");
    }

    @Test
    void crossDockLineToWorkorder_partMismatchWithoutOverride_throwsPartMatchPermissionException() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation(
                        "WORK_IN_PROGRESS",
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString()));

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("2"), null);
        authenticateAs("receiver-user");

        PartMatchPermissionException exception = assertThrows(
                PartMatchPermissionException.class,
                () -> receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user"));

        assertThat(exception.getMessage()).contains("PART_MISMATCH_WITH_WORKORDER");
    }

    @Test
    void crossDockLineToWorkorder_partMismatchWithOverride_allowsCrossDock() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        when(receivingSessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation(
                        "WORK_IN_PROGRESS",
                        UUID.fromString("00000000-0000-0000-0000-000000000001").toString()));

        authenticateAs("override-user", "inventory:override:part-match");
        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("2"), null);

        CrossDockResponse result =
                receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "override-user");

        assertThat(result.getWorkorderId()).isEqualTo("WO-001");
        assertThat(result.getLineId()).isEqualTo(lineId);
        assertThat(result.getCrossDockedQuantity()).isEqualByComparingTo("2");
    }

    @Test
    void crossDockLineToWorkorder_ledgerEntriesUseCrossDockLocationAndOnHandQuantityAfter() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        when(receivingSessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryLedgerEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("WORK_IN_PROGRESS", "PROD-001"));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation("PROD-001", CROSS_DOCK_LOCATION_ID))
                .thenReturn(4, 14);

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("10"), null);

        receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user");

        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(inventoryLedgerEntryRepository, times(2)).save(ledgerCaptor.capture());
        List<InventoryLedgerEntry> savedEntries = ledgerCaptor.getAllValues();

        InventoryLedgerEntry receiptEntry = savedEntries.get(0);
        assertThat(receiptEntry.getLocationId()).isEqualTo(CROSS_DOCK_LOCATION_ID);
        assertThat(receiptEntry.getToLocationId()).isEqualTo(CROSS_DOCK_LOCATION_ID);
        assertThat(receiptEntry.getQuantityAfter()).isEqualTo(14);
        assertThat(receiptEntry.getChangeInQuantity()).isEqualTo(10);

        InventoryLedgerEntry issueEntry = savedEntries.get(1);
        assertThat(issueEntry.getLocationId()).isEqualTo(CROSS_DOCK_LOCATION_ID);
        assertThat(issueEntry.getFromLocationId()).isEqualTo(CROSS_DOCK_LOCATION_ID);
        assertThat(issueEntry.getQuantityAfter()).isEqualTo(4);
        assertThat(issueEntry.getChangeInQuantity()).isEqualTo(-10);
    }

    @Test
    void receiveItemsIntoStaging_partialLinesReceived_sessionInProgress() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        ReceivingLine line1 = ReceivingLine.builder()
                .lineId(lineId1)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingLine line2 = ReceivingLine.builder()
                .lineId(lineId2)
                .productId("PROD-002")
                .expectedQuantity(new BigDecimal("5"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .status(ReceivingSessionStatus.OPEN)
                .lines(new java.util.ArrayList<>(List.of(line1, line2)))
                .build();
        line1.setSession(session);
        line2.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveItemsRequest request =
                new ReceiveItemsRequest(List.of(new ReceiveLineRequest(lineId1, new BigDecimal("10"))));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");
        assertThat(response.getSessionStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void receiveItemsIntoStaging_fractionalQuantity_throwsIllegalArgumentException() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .lines(List.of(line))
                .status(ReceivingSessionStatus.OPEN)
                .build();
        line.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ReceiveItemsRequest request =
                new ReceiveItemsRequest(List.of(new ReceiveLineRequest(lineId, new BigDecimal("2.75"))));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> receivingService.receiveItemsIntoStaging(sessionId, request, "test-user"));

        assertThat(exception.getMessage()).contains("whole number");
    }

    private void stubSourceDocumentLines() {
        SourceDocumentStubClient.SourceDocumentLineDto line1 = new SourceDocumentStubClient.SourceDocumentLineDto();
        line1.setProductId("PROD-001");
        line1.setExpectedQuantity(new BigDecimal("5"));

        SourceDocumentStubClient.SourceDocumentLineDto line2 = new SourceDocumentStubClient.SourceDocumentLineDto();
        line2.setProductId("PROD-002");
        line2.setExpectedQuantity(new BigDecimal("10"));

        SourceDocumentStubClient.SourceDocumentLinesResponse sourceDocument =
                new SourceDocumentStubClient.SourceDocumentLinesResponse();
        sourceDocument.setLines(List.of(line1, line2));
        when(sourceDocumentStubClient.fetchDocument(any(), any(), any())).thenReturn(sourceDocument);
    }

    private void authenticateAs(String username, String... authorities) {
        var authentication = new UsernamePasswordAuthenticationToken(
                username,
                "N/A",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
