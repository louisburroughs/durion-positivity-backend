package com.positivity.inventory.internal.receiving.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.receiving.CreateReceivingSessionRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockRequest;
import com.positivity.inventory.internal.dto.receiving.CrossDockResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsRequest;
import com.positivity.inventory.internal.dto.receiving.ReceiveItemsResponse;
import com.positivity.inventory.internal.dto.receiving.ReceiveLineRequest;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.ReceivingLine;
import com.positivity.inventory.internal.entity.ReceivingSession;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ReceivingLineStatus;
import com.positivity.inventory.internal.enums.ReceivingSessionStatus;
import com.positivity.inventory.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.inventory.internal.exception.PartMatchPermissionException;
import com.positivity.inventory.internal.exception.ReceivingSessionNotFoundException;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentLinesUnavailableException;
import com.positivity.inventory.internal.exception.WorkorderClosedException;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryVarianceRepository;
import com.positivity.inventory.internal.repository.ReceivingSessionRepository;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.inventory.internal.service.SourceDocumentResolver;
import com.positivity.inventory.internal.service.StagingLocationResolver;
import com.positivity.inventory.internal.service.UomConversionService;
import com.positivity.inventory.internal.service.UomConversionServiceImpl;
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
import org.junit.jupiter.api.BeforeEach;
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

/**
 * Unit tests for {@code ReceivingServiceImpl}, including characterisation coverage for
 * {@code crossDockLineToWorkorder} (S3776 remediation, cognitive complexity 18) added ahead of
 * splitting it into named helpers.
 *
 * <p><b>Deliberately-uncovered branches in {@code crossDockLineToWorkorder}</b>, left untested
 * rather than faked because no reachable state produces them:
 *
 * <ul>
 *   <li>The {@code RECEIVED_OVER} arm of its status three-way branch is dead code: the method's
 *       own earlier guard throws {@code IllegalArgumentException} whenever {@code
 *       cumulativeReceivedQuantity > expectedQuantity}, so by the time the status is computed
 *       {@code cumulativeReceivedQuantity <= expectedQuantity} always holds and the comparison
 *       can only be zero or negative. (The refactor delegates this comparison to the existing
 *       {@code statusFor} helper, which legitimately reaches {@code RECEIVED_OVER} from {@code
 *       receiveItemsIntoStaging}'s unguarded call site — see {@code
 *       receiveItemsIntoStaging_overReceipt_overageVariance} — so the arm stays exercised
 *       there even though this method can never select it.)</li>
 *   <li>The {@code null} fallbacks for {@code existingReceivedQuantity} and {@code
 *       expectedQuantity} (defensive {@code != null ? … : …} ternaries) are unreachable through
 *       this service: every {@code ReceivingLine} it hands out is built by {@code
 *       buildLinesFromDocument} with {@code receivedQuantity(BigDecimal.ZERO)}, and {@code
 *       requireUsableLine} rejects any source document line whose {@code expectedQuantity} is
 *       null or non-positive before a {@code ReceivingLine} is ever created from it. Constructing
 *       a line with a null quantity by hand would exercise a state the production code never
 *       produces, not the guard's real job.</li>
 * </ul>
 */
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
    private LedgerPostingService ledgerPostingService;

    @Mock
    private SourceDocumentResolver sourceDocumentResolver;

    @Mock
    private StagingLocationResolver stagingLocationResolver;

    @Mock
    private WorkorderValidationService workorderValidationService;

    @org.mockito.Mock
    private com.positivity.inventory.internal.service.InventoryFactPublisher inventoryFactPublisher;

    @Spy
    private com.positivity.inventory.internal.service.DocumentQuantityConverter documentQuantityConverter =
            new com.positivity.inventory.internal.service.DocumentQuantityConverter(
                    org.mockito.Mockito.mock(com.positivity.inventory.internal.service.UomConversionService.class));

    // Lot gate answers "untracked" for every SKU in these unit tests (E1 #1038): the mock's
    // resolveReceiptLot returns null by default.
    @Mock
    private com.positivity.inventory.internal.service.InventoryLotCaptureService lotCaptureService;

    /**
     * The real divisibility guard over a stubbable conversion service (ADR-0055, #1414). A mocked
     * guard would answer null and NPE; a real one over a mocked conversion service answers "scale
     * 0" for every product by default, which is the whole-units behaviour every SKU has today —
     * so the existing vectors are unchanged and a divisible product can be stubbed in explicitly.
     */
    private final com.positivity.inventory.internal.service.UomConversionService uomConversionService =
            org.mockito.Mockito.mock(com.positivity.inventory.internal.service.UomConversionService.class);

    @Spy
    private com.positivity.inventory.internal.service.QuantityScaleGuard quantityScaleGuard =
            new com.positivity.inventory.internal.service.QuantityScaleGuard(uomConversionService);

    @InjectMocks
    private ReceivingServiceImpl receivingService;

    @BeforeEach
    void stubDefaultStagingLocation() {
        lenient().when(stagingLocationResolver.resolveStagingLocationId()).thenReturn(STAGING_LOCATION_ID);
    }

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
    void createReceivingSession_sourceDocumentLinesUnavailable() {
        when(sourceDocumentResolver.resolve(any(), any()))
                .thenThrow(new SourceDocumentLinesUnavailableException(
                        "Purchase order PO-999 has not replicated its lines into pos-inventory yet"));

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-999", "MANUAL");

        assertThrows(
                SourceDocumentLinesUnavailableException.class,
                () -> receivingService.createReceivingSession(request, "test-user"));
    }

    @Test
    void createReceivingSession_sourceDocumentAlreadyReceived() {
        when(sourceDocumentResolver.resolve(any(), any()))
                .thenThrow(new SourceDocumentAlreadyReceivedException("PO-456 has already been fully received"));

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
        ReceiveLineRequest line = new ReceiveLineRequest(lineId, new BigDecimal("10"), null, null, null);
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
        ReceiveLineRequest line = new ReceiveLineRequest(lineId, new BigDecimal("8"), null, null, null);
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
        ReceiveLineRequest line = new ReceiveLineRequest(lineId, new BigDecimal("12"), null, null, null);
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
        ReceiveLineRequest line = new ReceiveLineRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("5"), null, null, null);
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

        ReceiveLineRequest knownLineRequest =
                new ReceiveLineRequest(knownLineId, new BigDecimal("10"), null, null, null);
        ReceiveLineRequest unknownLineRequest =
                new ReceiveLineRequest(unknownLineId, new BigDecimal("5"), null, null, null);
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

        ReceiveLineRequest req1 = new ReceiveLineRequest(lineId1, new BigDecimal("10"), null, null, null); // Exact
        ReceiveLineRequest req2 = new ReceiveLineRequest(lineId2, new BigDecimal("15"), null, null, null); // Short
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

        ReceiveLineRequest req1 = new ReceiveLineRequest(lineId1, new BigDecimal("10"), null, null, null); // Exact
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

        ReceiveLineRequest req1 = new ReceiveLineRequest(lineId1, new BigDecimal("10"), null, null, null);
        ReceiveLineRequest req2 = new ReceiveLineRequest(lineId2, new BigDecimal("20"), null, null, null);
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
    void createReceivingSession_sourceDocumentNotProjected_throwsLinesUnavailable() {
        when(sourceDocumentResolver.resolve(any(), any()))
                .thenThrow(new SourceDocumentLinesUnavailableException(
                        "Purchase order PO-123 has not replicated its lines into pos-inventory yet"));

        CreateReceivingSessionRequest request = new CreateReceivingSessionRequest("PO-123", "MANUAL");

        assertThrows(
                SourceDocumentLinesUnavailableException.class,
                () -> receivingService.createReceivingSession(request, "test-user"));
    }

    @Test
    void createReceivingSession_sourceDocumentLineWithoutProduct_throwsIllegalState() {
        when(sourceDocumentResolver.resolve(any(), any()))
                .thenReturn(
                        new SourceDocumentResolver.SourceDocument("PO-123", "APPROVED", List.of(sourceLine(" ", "5"))));

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

        ReceiveLineRequest req1 = new ReceiveLineRequest(lineId1, new BigDecimal("10"), null, null, null);
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

        ReceiveLineRequest lineReq = new ReceiveLineRequest(lineId, new BigDecimal("10"), null, null, null);
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
        when(ledgerPostingService.post(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation("PROD-001", STAGING_LOCATION_ID))
                .thenReturn(new BigDecimal("7"));

        ReceiveItemsRequest request = new ReceiveItemsRequest(
                List.of(new ReceiveLineRequest(lineId, new BigDecimal("10"), null, null, null)));

        receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService).post(ledgerCaptor.capture());
        InventoryLedgerEntry savedLedgerEntry = ledgerCaptor.getValue();

        assertThat(savedLedgerEntry.getLocationId()).isEqualTo(STAGING_LOCATION_ID);
        assertThat(savedLedgerEntry.getToLocationId()).isEqualTo(STAGING_LOCATION_ID);
        assertThat(savedLedgerEntry.getQuantityAfter()).isEqualByComparingTo("17");
        assertThat(savedLedgerEntry.getChangeInQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void receiveItemsIntoStaging_ledgerEntryUsesSiteDefaultStagingLocationWhenConfigured() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID siteDefaultStagingLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(stagingLocationResolver.resolveStagingLocationId()).thenReturn(siteDefaultStagingLocationId);

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
        when(ledgerPostingService.post(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation("PROD-001", siteDefaultStagingLocationId))
                .thenReturn(new BigDecimal("7"));

        ReceiveItemsRequest request = new ReceiveItemsRequest(
                List.of(new ReceiveLineRequest(lineId, new BigDecimal("10"), null, null, null)));

        receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService).post(ledgerCaptor.capture());
        InventoryLedgerEntry savedLedgerEntry = ledgerCaptor.getValue();

        assertThat(savedLedgerEntry.getLocationId()).isEqualTo(siteDefaultStagingLocationId);
        assertThat(savedLedgerEntry.getToLocationId()).isEqualTo(siteDefaultStagingLocationId);
        assertThat(savedLedgerEntry.getQuantityAfter()).isEqualByComparingTo("17");
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

        ReceiveLineRequest lineReq = new ReceiveLineRequest(lineId, new BigDecimal("8"), null, null, null);
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

        ReceiveLineRequest lineReq = new ReceiveLineRequest(lineId, new BigDecimal("12"), null, null, null);
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
                new ReceiveLineRequest(lineId1, new BigDecimal("10"), null, null, null),
                new ReceiveLineRequest(lineId2, new BigDecimal("5"), null, null, null)));

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
        verify(ledgerPostingService, never()).post(any());
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
    void crossDockLineToWorkorder_fractionalQuantityForAWholeUnitsProduct_isRefused() {
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

        // ADR-0055 (#1414): the refusal survives the widening, but it is now read from the
        // product's catalog declaration rather than hardcoded — this product declares nothing,
        // so it is stocked in whole units and 1.5 is refused with the same code the demand side
        // raises (#1413).
        FractionalQuantityNotAllowedException exception = assertThrows(
                FractionalQuantityNotAllowedException.class,
                () -> receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user"));

        assertThat(exception.getMessage()).contains(FractionalQuantityNotAllowedException.ERROR_CODE);
        assertThat(exception.getMessage()).contains("whole units");
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
        when(ledgerPostingService.post(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("WORK_IN_PROGRESS", "PROD-001"));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation("PROD-001", CROSS_DOCK_LOCATION_ID))
                .thenReturn(new BigDecimal("4"), new BigDecimal("14"));

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("10"), null);

        receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user");

        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService, times(2)).post(ledgerCaptor.capture());
        List<InventoryLedgerEntry> savedEntries = ledgerCaptor.getAllValues();

        InventoryLedgerEntry receiptEntry = savedEntries.get(0);
        assertThat(receiptEntry.getLocationId()).isEqualTo(CROSS_DOCK_LOCATION_ID);
        assertThat(receiptEntry.getToLocationId()).isEqualTo(CROSS_DOCK_LOCATION_ID);
        assertThat(receiptEntry.getQuantityAfter()).isEqualByComparingTo("14");
        assertThat(receiptEntry.getChangeInQuantity()).isEqualByComparingTo("10");

        InventoryLedgerEntry issueEntry = savedEntries.get(1);
        assertThat(issueEntry.getLocationId()).isEqualTo(CROSS_DOCK_LOCATION_ID);
        assertThat(issueEntry.getFromLocationId()).isEqualTo(CROSS_DOCK_LOCATION_ID);
        assertThat(issueEntry.getQuantityAfter()).isEqualByComparingTo("4");
        assertThat(issueEntry.getChangeInQuantity()).isEqualByComparingTo("-10");
    }

    /**
     * The request can name a lineId this session does not have (a stale UI, a typo'd id); that
     * must 404 rather than post ledger entries against a line that does not exist.
     */
    @Test
    void crossDockLineToWorkorder_lineNotInSession_throwsNotFoundException() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID otherLineId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .status(ReceivingSessionStatus.OPEN)
                .lines(new java.util.ArrayList<>(List.of(line)))
                .build();
        line.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        CrossDockRequest request = new CrossDockRequest("WO-001", "wol-1", new BigDecimal("1"), null);

        ReceivingSessionNotFoundException exception = assertThrows(
                ReceivingSessionNotFoundException.class,
                () -> receivingService.crossDockLineToWorkorder(sessionId, otherLineId, request, "actor-user"));

        assertThat(exception.getMessage()).contains("Receiving line not found in session");
        verify(workorderValidationService, never()).getWorkorderLineValidation(any(), any());
    }

    /**
     * A session settles only once EVERY line has; cross-docking one line of a multi-line session
     * must leave the session IN_PROGRESS while its sibling line is still EXPECTED.
     */
    @Test
    void crossDockLineToWorkorder_otherLineStillExpected_sessionStaysInProgress() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID otherLineId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingLine otherLine = ReceivingLine.builder()
                .lineId(otherLineId)
                .productId("PROD-002")
                .expectedQuantity(new BigDecimal("5"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .status(ReceivingSessionStatus.OPEN)
                .lines(new java.util.ArrayList<>(List.of(line, otherLine)))
                .build();
        line.setSession(session);
        otherLine.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("WORK_IN_PROGRESS", "PROD-001"));

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("10"), null);

        CrossDockResponse result = receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user");

        assertThat(result.getSessionStatus()).isEqualTo(ReceivingSessionStatus.IN_PROGRESS.name());
        assertThat(session.getStatus()).isEqualTo(ReceivingSessionStatus.IN_PROGRESS);
    }

    /**
     * odoo-parity E2 (#1042): a request-supplied lot number is the one stamped on BOTH paired
     * ledger entries and (trimmed) keyed back onto the line, so the funnel's per-lot row nets to
     * zero and the lot's status reconciler can mark it CONSUMED once the workorder consumes it.
     */
    @Test
    void crossDockLineToWorkorder_requestLotNumber_stampsTrimmedLotOnLineAndBothLedgerEntries() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lotId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .status(ReceivingSessionStatus.OPEN)
                .lines(new java.util.ArrayList<>(List.of(line)))
                .build();
        line.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerPostingService.post(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("WORK_IN_PROGRESS", "PROD-001"));
        when(lotCaptureService.resolveReceiptLot(eq("PROD-001"), eq("  LOT-A  "), any()))
                .thenReturn(lotId);

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("10"), null, "  LOT-A  ");

        receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user");

        assertThat(line.getLotNumber()).isEqualTo("LOT-A");
        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService, times(2)).post(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getAllValues())
                .allSatisfy(entry -> assertThat(entry.getLotId()).isEqualTo(lotId));
    }

    /**
     * A blank (not null) request lot number is treated as absent — the fallback is the lot
     * already keyed on the receiving line from its original receipt, not a refusal.
     */
    @Test
    void crossDockLineToWorkorder_blankRequestLotNumber_fallsBackToLinesExistingLotNumber() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID workorderLineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lotId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId("PROD-001")
                .expectedQuantity(new BigDecimal("10"))
                .receivedQuantity(BigDecimal.ZERO)
                .lotNumber("LOT-ORIGINAL")
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .status(ReceivingSessionStatus.OPEN)
                .lines(new java.util.ArrayList<>(List.of(line)))
                .build();
        line.setSession(session);

        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerPostingService.post(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workorderValidationService.getWorkorderLineValidation("WO-001", workorderLineId.toString()))
                .thenReturn(new WorkorderValidationService.WorkorderLineValidation("WORK_IN_PROGRESS", "PROD-001"));
        when(lotCaptureService.resolveReceiptLot(eq("PROD-001"), eq("LOT-ORIGINAL"), any()))
                .thenReturn(lotId);

        CrossDockRequest request =
                new CrossDockRequest("WO-001", workorderLineId.toString(), new BigDecimal("10"), null, "   ");

        receivingService.crossDockLineToWorkorder(sessionId, lineId, request, "actor-user");

        assertThat(line.getLotNumber()).isEqualTo("LOT-ORIGINAL");
        verify(lotCaptureService).resolveReceiptLot("PROD-001", "LOT-ORIGINAL", null);
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

        ReceiveItemsRequest request = new ReceiveItemsRequest(
                List.of(new ReceiveLineRequest(lineId1, new BigDecimal("10"), null, null, null)));

        ReceiveItemsResponse response = receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");
        assertThat(response.getSessionStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void receiveItemsIntoStaging_fractionalQuantityForAWholeUnitsProduct_isRefused() {
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

        ReceiveItemsRequest request = new ReceiveItemsRequest(
                List.of(new ReceiveLineRequest(lineId, new BigDecimal("2.75"), null, null, null)));

        FractionalQuantityNotAllowedException exception = assertThrows(
                FractionalQuantityNotAllowedException.class,
                () -> receivingService.receiveItemsIntoStaging(sessionId, request, "test-user"));

        assertThat(exception.getMessage()).contains(FractionalQuantityNotAllowedException.ERROR_CODE);
        assertThat(exception.getMessage()).contains("whole units");
    }

    /**
     * The ADR-0055 sentinel (#1414 AC). {@code UomConversionServiceImplTest} carries a product
     * whose base UoM is {@code LB} at {@code precision_scale = 2}, and asserts that {@code 1 BAG}
     * converts to {@code 1.01 LB}. Feed that same quantity through receiving before this stage and
     * {@code toWholeLedgerQuantity}'s {@code intValueExact()} threw: the conversion subsystem and
     * the ledger could not both be right about the same product. This is the single clearest proof
     * that the contradiction is resolved — the posting round-trips into the ledger at its real
     * quantity, and the guard is still there, just reading the declaration.
     */
    @Test
    void receiveItemsIntoStaging_scaleTwoProduct_postsItsFractionalQuantityToTheLedger() {
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID productId = UUID.fromString("018f0000-0000-7000-8000-000000000d02");

        // Not a stubbed scale: the real conversion service reading the real fixture. This is the
        // same product UomConversionServiceImplTest seeds — base LB at precision_scale 2, BAG at
        // factor 1.005 — the one whose 1 BAG -> 1.01 LB vector receiving used to reject. Deriving
        // the declaration here rather than stubbing it is what makes the two sides provably agree
        // instead of coincidentally agreeing.
        ExtProductReplicaRepository products = mock(ExtProductReplicaRepository.class);
        ExtProductUomReplicaRepository productUoms = mock(ExtProductUomReplicaRepository.class);
        when(products.findById(productId))
                .thenReturn(Optional.of(ExtProductReplica.builder()
                        .productId(productId)
                        .baseUom("LB")
                        .trackingLevel("NONE")
                        .aggregateVersion(1L)
                        .updatedAt(Instant.parse("2026-07-20T00:00:00Z"))
                        .build()));
        when(products.existsById(productId)).thenReturn(true);
        when(productUoms.findByProductIdAndUomCode(productId, "LB"))
                .thenReturn(Optional.of(ExtProductUomReplica.builder()
                        .productId(productId)
                        .uomCode("LB")
                        .uomType("BASE")
                        .factorToBase(BigDecimal.ONE)
                        .precisionScale(2)
                        .build()));
        UomConversionService realConversion = new UomConversionServiceImpl(products, productUoms);
        int declaredScale = realConversion.declaredBaseScale(productId);
        assertThat(declaredScale).isEqualTo(2);
        // Resolved before stubbing, not inside the when(...) argument: a real call that itself
        // touches mocks would leave Mockito mid-stubbing.
        when(uomConversionService.declaredBaseScale(productId)).thenReturn(declaredScale);

        ReceivingLine line = ReceivingLine.builder()
                .lineId(lineId)
                .productId(productId.toString())
                .expectedQuantity(new BigDecimal("1.01"))
                .receivedQuantity(BigDecimal.ZERO)
                .status(ReceivingLineStatus.EXPECTED)
                .build();
        ReceivingSession session = ReceivingSession.builder()
                .sessionId(sessionId)
                .sourceDocumentId("PO-BULK")
                .lines(new java.util.ArrayList<>(List.of(line)))
                .status(ReceivingSessionStatus.OPEN)
                .build();
        line.setSession(session);
        when(receivingSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(receivingSessionRepository.save(any(ReceivingSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerPostingService.post(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceiveItemsRequest request = new ReceiveItemsRequest(
                List.of(new ReceiveLineRequest(lineId, new BigDecimal("1.01"), null, null, null)));

        receivingService.receiveItemsIntoStaging(sessionId, request, "test-user");

        ArgumentCaptor<InventoryLedgerEntry> posted = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService).post(posted.capture());
        assertThat(posted.getValue().getChangeInQuantity()).isEqualByComparingTo("1.01");
        assertThat(posted.getValue().getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_RECEIPT);
    }

    private void stubSourceDocumentLines() {
        when(sourceDocumentResolver.resolve(any(), any()))
                .thenReturn(new SourceDocumentResolver.SourceDocument(
                        "PO-123", "APPROVED", List.of(sourceLine("PROD-001", "5"), sourceLine("PROD-002", "10"))));
    }

    private static SourceDocumentResolver.SourceDocumentLine sourceLine(String productId, String expectedQuantity) {
        SourceDocumentResolver.SourceDocumentLine line = new SourceDocumentResolver.SourceDocumentLine();
        line.setProductId(productId);
        line.setExpectedQuantity(new BigDecimal(expectedQuantity));
        return line;
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
