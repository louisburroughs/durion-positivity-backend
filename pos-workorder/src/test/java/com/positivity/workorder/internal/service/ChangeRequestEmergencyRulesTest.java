package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.CreateChangeRequestDTO;
import com.positivity.workorder.internal.entity.ChangeRequest;
import com.positivity.workorder.internal.entity.ChangeRequest.ChangeRequestStatus;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.ApprovalRecordRepository;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.service.IdempotencyService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Branch-coverage tests for {@link ChangeRequestServiceImpl}'s validation and
 * emergency-exception rules.
 *
 * <p>
 * A change request is how extra work gets added to a job the customer already
 * agreed to, so the rules here are the ones that decide whether a customer can
 * be billed for work they never approved. Two of them are worth stating
 * outright:
 *
 * <ul>
 * <li><b>Emergency items must carry evidence.</b> An "emergency/safety" item
 * bypasses the normal approval wait, so it needs either a photo or an explicit
 * "photo not possible" plus notes. The two checks overlap in a way that is easy
 * to collapse into one — the second fires for an item that claimed photo-not-
 * possible and then supplied nothing — and collapsing them would let a shop mark
 * anything as an emergency with no record of why.</li>
 * <li><b>A declined emergency blocks closing the workorder</b> until the customer
 * has acknowledged the denial, on every emergency item, on both services and
 * parts. That is the paper trail for "we told them it was unsafe and they said
 * no". The services and parts checks are separate methods with identical bodies,
 * so a workorder could close with an unacknowledged part while the service half
 * still worked.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChangeRequestServiceImpl — validation and emergency rules")
class ChangeRequestEmergencyRulesTest {

    private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CHANGE_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Mock
    private ChangeRequestRepository changeRequestRepository;

    @Mock
    private WorkorderRepository workOrderRepository;

    @Mock
    private WorkorderServiceRepository workOrderServiceRepository;

    @Mock
    private WorkorderPartRepository workOrderPartRepository;

    @Mock
    private ApprovalRecordRepository approvalRecordRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private RestClient documentServiceRestClient;

    private ChangeRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChangeRequestServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                changeRequestRepository,
                workOrderRepository,
                workOrderServiceRepository,
                workOrderPartRepository,
                approvalRecordRepository,
                idempotencyService,
                documentServiceRestClient,
                new ObjectMapper());

        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        when(workOrderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
    }

    private static CreateChangeRequestDTO.WorkorderItemDTO item() {
        CreateChangeRequestDTO.WorkorderItemDTO item = new CreateChangeRequestDTO.WorkorderItemDTO();
        item.setQuantity(1);
        return item;
    }

    private static CreateChangeRequestDTO dto(CreateChangeRequestDTO.WorkorderItemDTO... services) {
        CreateChangeRequestDTO dto = new CreateChangeRequestDTO();
        dto.setWorkorderId(WORKORDER_ID);
        dto.setDescription("Corroded brake line found during inspection");
        dto.setServices(List.of(services));
        return dto;
    }

    // ─── request-level validation ────────────────────────────────────────────

    @ParameterizedTest(name = "description=[{0}] is rejected")
    @CsvSource(
            value = {"NULL", "''", "'   '"},
            nullValues = "NULL")
    @DisplayName("a change request with no meaningful description is rejected")
    void descriptionIsRequired(String description) {
        CreateChangeRequestDTO dto = dto(item());
        dto.setDescription(description);

        // Whitespace is included deliberately: the description is what the customer is shown
        // when asked to approve extra work, so a blank one is the same as none.
        assertThatThrownBy(() -> service.createChangeRequest(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Description is required");

        verify(changeRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("a change request with neither services nor parts is rejected")
    void atLeastOneItemIsRequired() {
        CreateChangeRequestDTO dto = dto();
        dto.setServices(List.of());
        dto.setParts(List.of());

        assertThatThrownBy(() -> service.createChangeRequest(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one service or part");
    }

    @Test
    @DisplayName("null lists count as empty rather than throwing")
    void nullItemListsAreTreatedAsEmpty() {
        CreateChangeRequestDTO dto = dto();
        dto.setServices(null);
        dto.setParts(null);

        // The two collections are checked with a null guard before isEmpty; dropping it turns a
        // malformed request into a NullPointerException instead of a clear rejection.
        assertThatThrownBy(() -> service.createChangeRequest(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one service or part");
    }

    @Test
    @DisplayName("a request naming an unknown workorder is rejected")
    void unknownWorkorderIsRejected() {
        when(workOrderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createChangeRequest(dto(item())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Work order not found");
    }

    @ParameterizedTest(name = "workorder in {0} cannot take a change request")
    @EnumSource(
            value = WorkorderStatus.class,
            names = {"WORK_IN_PROGRESS"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("only a workorder actually in progress can take a change request")
    void onlyWorkInProgressAcceptsChangeRequests(WorkorderStatus status) {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setStatus(status);
        when(workOrderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        // Every other status is checked rather than one sample: adding extra work to a job that
        // is finished, invoiced or cancelled is precisely the case that produces a bill the
        // customer never agreed to.
        assertThatThrownBy(() -> service.createChangeRequest(dto(item())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORK_IN_PROGRESS");

        verify(changeRequestRepository, never()).save(any());
    }

    // ─── emergency documentation ─────────────────────────────────────────────

    @Test
    @DisplayName("claiming an emergency exception with no emergency item is rejected")
    void emergencyExceptionNeedsAnEmergencyItem() {
        CreateChangeRequestDTO dto = dto(item());
        dto.setIsEmergencyException(true);

        // The flag and the items must agree. A request that claims the exception but marks
        // nothing as emergency would take the fast path with no item to justify it.
        assertThatThrownBy(() -> service.createChangeRequest(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no items are marked as emergency");
    }

    @Test
    @DisplayName("an emergency item with no photo and no photo-not-possible flag is rejected")
    void emergencyItemNeedsPhotoOrAnExplicitReasonItCannotBeTaken() {
        CreateChangeRequestDTO.WorkorderItemDTO emergency = item();
        emergency.setIsEmergencySafety(true);
        CreateChangeRequestDTO dto = dto(emergency);
        dto.setIsEmergencyException(true);

        assertThatThrownBy(() -> service.createChangeRequest(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("photo evidence");
    }

    @Test
    @DisplayName("an emergency item claiming photo-not-possible but supplying no notes is rejected")
    void photoNotPossibleStillNeedsNotes() {
        CreateChangeRequestDTO.WorkorderItemDTO emergency = item();
        emergency.setIsEmergencySafety(true);
        emergency.setPhotoNotPossible(true);
        CreateChangeRequestDTO dto = dto(emergency);
        dto.setIsEmergencyException(true);

        // This is the case that the two overlapping checks exist for. Collapsing them into one
        // would let an item pass with the flag set and nothing written down, which is the same
        // as no evidence at all.
        assertThatThrownBy(() -> service.createChangeRequest(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notes");
    }

    @ParameterizedTest(name = "photo={0} photoNotPossible={1} notes={2} accepted={3}")
    @CsvSource({
        "https://img/1.jpg, false, ,                    true",
        "https://img/1.jpg, false, 'seized fitting',    true",
        "'',                true,  'seized fitting',    true",
        "'   ',             true,  'seized fitting',    true",
        "'',                true,  '   ',               false",
        "'',                false, 'seized fitting',    false",
    })
    @DisplayName("the evidence rule is photo, or photo-not-possible with notes")
    void evidenceTruthTable(String photoUrl, boolean photoNotPossible, String notes, boolean accepted) {
        CreateChangeRequestDTO.WorkorderItemDTO emergency = item();
        emergency.setIsEmergencySafety(true);
        emergency.setPhotoEvidenceUrl(photoUrl);
        emergency.setPhotoNotPossible(photoNotPossible);
        emergency.setEmergencyNotes(notes);
        CreateChangeRequestDTO dto = dto(emergency);
        dto.setIsEmergencyException(true);
        when(changeRequestRepository.save(any())).thenAnswer(invocation -> {
            ChangeRequest saved = invocation.getArgument(0);
            saved.setId(CHANGE_REQUEST_ID);
            return saved;
        });

        // Whitespace-only values appear on both sides of the table: a photo url of spaces is
        // not a photo, and notes of spaces are not notes.
        if (accepted) {
            assertThat(service.createChangeRequest(dto)).isNotNull();
        } else {
            assertThatThrownBy(() -> service.createChangeRequest(dto)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("an emergency part is validated on the same terms as an emergency service")
    void emergencyPartsAreValidatedToo() {
        CreateChangeRequestDTO.WorkorderItemDTO part = item();
        part.setIsEmergencySafety(true);
        CreateChangeRequestDTO dto = new CreateChangeRequestDTO();
        dto.setWorkorderId(WORKORDER_ID);
        dto.setDescription("Corroded brake line found during inspection");
        dto.setParts(List.of(part));
        dto.setIsEmergencyException(true);

        // Services and parts are validated by two separate loops over the same helper. A part
        // that skipped the check would be the cheaper half of the bill going undocumented.
        assertThatThrownBy(() -> service.createChangeRequest(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("photo evidence");
    }

    @Test
    @DisplayName("a non-emergency request skips the evidence rules entirely")
    void nonEmergencyRequestSkipsEvidenceRules() {
        when(changeRequestRepository.save(any())).thenAnswer(invocation -> {
            ChangeRequest saved = invocation.getArgument(0);
            saved.setId(CHANGE_REQUEST_ID);
            return saved;
        });

        // The ordinary path must not demand photos — this is the branch that keeps the
        // emergency rules from applying to every change request in the shop.
        assertThat(service.createChangeRequest(dto(item()))).isNotNull();
    }

    // ─── closing the workorder ───────────────────────────────────────────────

    @Test
    @DisplayName("a workorder with no declined emergency requests can close")
    void noDeclinedEmergencyMeansCloseable() {
        when(changeRequestRepository.findByWorkorder_IdAndStatus(WORKORDER_ID, ChangeRequestStatus.DECLINED))
                .thenReturn(List.of());

        assertThat(service.canCloseWorkorder(WORKORDER_ID)).isTrue();
    }

    @Test
    @DisplayName("a declined request that was never an emergency does not block closing")
    void declinedNonEmergencyDoesNotBlockClosing() {
        ChangeRequest declined = new ChangeRequest();
        declined.setId(CHANGE_REQUEST_ID);
        declined.setIsEmergencyException(false);
        when(changeRequestRepository.findByWorkorder_IdAndStatus(WORKORDER_ID, ChangeRequestStatus.DECLINED))
                .thenReturn(List.of(declined));

        // Only emergency denials need an acknowledgment. Blocking on ordinary declines would
        // stop every workorder where the customer simply said no to an upsell.
        assertThat(service.canCloseWorkorder(WORKORDER_ID)).isTrue();
        verify(workOrderServiceRepository, never()).findByChangeRequest_Id(any());
    }

    @ParameterizedTest(name = "unacknowledged emergency {0} blocks closing")
    @CsvSource({"service", "part"})
    @DisplayName("an unacknowledged emergency item blocks closing, on services and on parts alike")
    void unacknowledgedEmergencyItemBlocksClosing(String kind) {
        declinedEmergencyExists();

        if ("service".equals(kind)) {
            WorkorderServiceLine line = new WorkorderServiceLine();
            line.setIsEmergencySafety(true);
            line.setCustomerDenialAcknowledged(false);
            when(workOrderServiceRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of(line));
            when(workOrderPartRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of());
        } else {
            WorkorderPart part = new WorkorderPart();
            part.setIsEmergencySafety(true);
            part.setCustomerDenialAcknowledged(false);
            when(workOrderServiceRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of());
            when(workOrderPartRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                    .thenReturn(List.of(part));
        }

        // Both halves are asserted because they are separate methods with identical bodies:
        // the parts check could be dropped and the services one would keep the test green.
        assertThat(service.canCloseWorkorder(WORKORDER_ID)).isFalse();
    }

    @Test
    @DisplayName("once every emergency item is acknowledged, the workorder can close")
    void acknowledgedEmergencyItemsUnblockClosing() {
        declinedEmergencyExists();
        WorkorderServiceLine line = new WorkorderServiceLine();
        line.setIsEmergencySafety(true);
        line.setCustomerDenialAcknowledged(true);
        WorkorderPart part = new WorkorderPart();
        part.setIsEmergencySafety(true);
        part.setCustomerDenialAcknowledged(true);
        when(workOrderServiceRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                .thenReturn(List.of(line));
        when(workOrderPartRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID)).thenReturn(List.of(part));

        assertThat(service.canCloseWorkorder(WORKORDER_ID)).isTrue();
    }

    @Test
    @DisplayName("a non-emergency item on a declined emergency request needs no acknowledgment")
    void nonEmergencyItemsNeedNoAcknowledgment() {
        declinedEmergencyExists();
        WorkorderServiceLine ordinary = new WorkorderServiceLine();
        ordinary.setIsEmergencySafety(false);
        ordinary.setCustomerDenialAcknowledged(false);
        when(workOrderServiceRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID))
                .thenReturn(List.of(ordinary));
        when(workOrderPartRepository.findByChangeRequest_Id(CHANGE_REQUEST_ID)).thenReturn(List.of());

        // The acknowledgment requirement is per item, not per request: an ordinary line riding
        // along on an emergency request must not hold the workorder open forever.
        assertThat(service.canCloseWorkorder(WORKORDER_ID)).isTrue();
    }

    private void declinedEmergencyExists() {
        ChangeRequest declined = new ChangeRequest();
        declined.setId(CHANGE_REQUEST_ID);
        declined.setIsEmergencyException(true);
        declined.setStatus(ChangeRequestStatus.DECLINED);
        when(changeRequestRepository.findByWorkorder_IdAndStatus(WORKORDER_ID, ChangeRequestStatus.DECLINED))
                .thenReturn(List.of(declined));
    }
}
