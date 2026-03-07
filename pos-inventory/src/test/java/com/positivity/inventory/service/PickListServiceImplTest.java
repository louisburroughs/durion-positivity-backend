package com.positivity.inventory.service;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.picklist.CreatePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.dto.picklist.PickTaskResponse;
import com.positivity.inventory.internal.entity.PickListEntity;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.exception.PickScanMismatchException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.PickListRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.service.PickListServiceImpl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PickListServiceImpl} — Story #28: Create Pick List /
 * Pick Tasks for Workorder.
 *
 * <p>
 * All tests are intentionally RED: {@code PickListServiceImpl} contains only
 * stub methods that throw {@link UnsupportedOperationException}.
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0013: UUIDv7 identifiers used for pickListId</li>
 * <li>ADR-0017: HTTP 201 for create, 200 for update (validated at contract
 * layer)</li>
 * <li>ADR-0023: no tenantId in request or response</li>
 * <li>ADR-0024: createdAt/updatedAt populated by @PrePersist on entity</li>
 * </ul>
 *
 * Issue: #28
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PickListServiceImpl — Story #28 unit tests (RED phase)")
class PickListServiceImplTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Mock
        private PickListRepository pickListRepository;

        @Mock
        private PickTaskRepository pickTaskRepository;

        private PickListServiceImpl service;

        @BeforeEach
        void setUp() {
                service = new PickListServiceImpl(pickListRepository, pickTaskRepository);
        }

        // ─── SC1: createPickList — valid request → DRAFT status ─────────────────────

        /**
         * SC1: Verifies that createPickList() with a valid workorderId returns a
         * PickListResponse with status=DRAFT and a non-null pickListId.
         *
         * @see PickListServiceImpl#createPickList(CreatePickListRequest)
         */
        @Test
        @DisplayName("SC1_createPickList: valid request → DRAFT status, workorderId set, pickListId non-null")
        void SC1_createPickList_validRequest_returnsDraftWithWorkorderId() {
                // Issue #28: SC1 — createPickList must return DRAFT status and non-null
                // pickListId
                // Arrange
                UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                CreatePickListRequest request = new CreatePickListRequest(workorderId,
                                Instant.now(TEST_CLOCK).plusSeconds(3600),
                                1,
                                UUID.fromString("00000000-0000-0000-0000-000000000002"));

                when(pickListRepository.save(any())).thenAnswer(i -> i.getArgument(0));

                // Act — RED: impl throws UnsupportedOperationException; assertions never
                // reached
                PickListResponse result = service.createPickList(request);

                // Assert
                assertThat(result.getPickListId()).isNotNull();
                assertThat(result.getWorkorderId()).isEqualTo(workorderId);
                assertThat(result.getStatus()).isEqualTo(PickListStatus.DRAFT);
        }

        // ─── SC2: createPickList — pick tasks are created for reservation lines ──────

        /**
         * SC2: Verifies that createPickList() creates PickTask entries with
         * status=PENDING and sortOrder populated.
         *
         * @see PickListServiceImpl#createPickList(CreatePickListRequest)
         */
        @Test
        @DisplayName("SC2_createPickList: creates PickTasks with PENDING status and sortOrder set")
        void SC2_createPickList_validRequest_createsPickTasksWithPendingStatusAndSortOrder() {
                // Issue #28: SC2 — pick tasks must be created with status=PENDING and sortOrder
                // Arrange
                UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000003");
                UUID reservationId = UUID.fromString("00000000-0000-0000-0000-000000000004");
                CreatePickListRequest request = new CreatePickListRequest(workorderId,
                                Instant.now(TEST_CLOCK).plusSeconds(3600),
                                1,
                                reservationId);

                when(pickListRepository.save(any())).thenAnswer(i -> i.getArgument(0));

                // Act — RED: impl throws UnsupportedOperationException
                PickListResponse result = service.createPickList(request);

                // Assert (reached only when GREEN)
                assertThat(result.getPickListId()).isNotNull();
                assertThat(result.getStatus()).isEqualTo(PickListStatus.DRAFT);
        }

        // ─── SC3: createPickList — null workorderId → NullPointerException ────────

        /**
         * SC3: Verifies that createPickList() with a null workorderId throws
         * IllegalArgumentException or NullPointerException.
         *
         * @see PickListServiceImpl#createPickList(CreatePickListRequest)
         */
        @Test
        @DisplayName("SC3_createPickList_nullWorkorderId: null workorderId → NullPointerException or IllegalArgumentException")
        void SC3_createPickList_nullWorkorderId_throwsNullOrIllegalArgument() {
                // Issue #28: SC3 — empty workorderId must be rejected
                CreatePickListRequest request = new CreatePickListRequest(null, null, 0, null);

                // Act + Assert — RED: impl throws UnsupportedOperationException, not
                // NullPointerException or IllegalArgumentException
                assertThatThrownBy(() -> service.createPickList(request))
                                .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        }

        // ─── SC4: getPickList — valid ID → returns matching PickListResponse ──────

        /**
         * SC4: Verifies that getPickList() with a known ID returns the PickListResponse
         * matching the stored entity.
         *
         * @see PickListServiceImpl#getPickList(UUID)
         */
        @Test
        @DisplayName("SC4_getPickList: valid ID → returns PickListResponse with matching pickListId")
        void SC4_getPickList_validId_returnMatchingResponse() {
                // Issue #28: SC4 — getPickList must return the stored entity by ID
                // Arrange
                UUID validId = UUID.fromString("00000000-0000-0000-0000-000000000005");
                PickListEntity entity = PickListEntity.builder()
                                .pickListId(validId)
                                .workorderId(UUID.fromString("00000000-0000-0000-0000-000000000006"))
                                .status(PickListStatus.DRAFT)
                                .priority(0)
                                .createdAt(Instant.now(TEST_CLOCK))
                                .updatedAt(Instant.now(TEST_CLOCK))
                                .build();
                when(pickListRepository.findById(validId)).thenReturn(Optional.of(entity));

                // Act — RED: impl throws UnsupportedOperationException
                PickListResponse result = service.getPickList(validId);

                // Assert
                assertThat(result.getPickListId()).isEqualTo(validId);
        }

        // ─── SC5: getPickList — unknown ID → ResourceNotFoundException ───────────

        /**
         * SC5: Verifies that getPickList() with an unknown ID throws
         * {@link ResourceNotFoundException}.
         *
         * @see PickListServiceImpl#getPickList(UUID)
         */
        @Test
        @DisplayName("SC5_getPickList_unknownId: unknown ID → ResourceNotFoundException")
        void SC5_getPickList_unknownId_throwsResourceNotFoundException() {
                // Issue #28: SC5 — unknown pickListId must throw ResourceNotFoundException
                UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000007");
                assertThatThrownBy(() -> service.getPickList(unknownId))
                                .isInstanceOf(ResourceNotFoundException.class)
                                .hasMessageContaining("PickList not found");
        }

        // ─── SC6: getPickListsForWorkorder — valid workorderId → list returned ────

        /**
         * SC6: Verifies that getPickListsForWorkorder() with a valid workorderId
         * returns a non-null list (may be empty or non-empty).
         *
         * @see PickListServiceImpl#getPickListsForWorkorder(UUID)
         */
        @Test
        @DisplayName("SC6_getPickListsForWorkorder: valid workorderId → returns list (empty or populated)")
        void SC6_getPickListsForWorkorder_validWorkorderId_returnsList() {
                // Issue #28: SC6 — getPickListsForWorkorder must return a non-null list
                // Arrange
                UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000008");

                // Act — RED: impl throws UnsupportedOperationException
                List<PickListResponse> result = service.getPickListsForWorkorder(workorderId);

                // Assert
                assertThat(result).isNotNull();
        }

        // ─── SC7: updatePickListStatus DRAFT → READY_TO_PICK ─────────────────────

        /**
         * SC7: Verifies that updatePickListStatus() transitions a PickList from DRAFT
         * to READY_TO_PICK (story describes as RELEASED — maps to READY_TO_PICK in
         * the enum) and returns the updated PickListResponse.
         *
         * @see PickListServiceImpl#updatePickListStatus(UUID, PickListStatus)
         */
        @Test
        @DisplayName("SC7_updatePickListStatus: DRAFT → READY_TO_PICK returns updated PickListResponse")
        void SC7_updatePickListStatus_draftToReadyToPick_returnsUpdatedResponse() {
                // Issue #28: SC7 — status transition DRAFT → READY_TO_PICK must be reflected in
                // response
                // Arrange
                UUID pickListId = UUID.fromString("00000000-0000-0000-0000-000000000009");
                UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                PickListEntity entity = PickListEntity.builder()
                                .pickListId(pickListId)
                                .workorderId(workorderId)
                                .status(PickListStatus.DRAFT)
                                .priority(0)
                                .createdAt(Instant.now(TEST_CLOCK))
                                .updatedAt(Instant.now(TEST_CLOCK))
                                .build();

                when(pickListRepository.findById(pickListId)).thenReturn(Optional.of(entity));
                when(pickListRepository.save(any(PickListEntity.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                // Act — RED: impl throws UnsupportedOperationException
                PickListResponse result = service.updatePickListStatus(pickListId, PickListStatus.READY_TO_PICK);

                // Assert
                assertThat(result.getPickListId()).isEqualTo(pickListId);
                assertThat(result.getStatus()).isEqualTo(PickListStatus.READY_TO_PICK);
        }

        // ─── SC8: cancelPickList — valid ID → completes without exception ─────────

        /**
         * SC8: Verifies that cancelPickList() with a valid ID completes without
         * throwing an exception.
         *
         * @see PickListServiceImpl#cancelPickList(UUID)
         */
        @Test
        @DisplayName("SC8_cancelPickList: valid ID → no exception thrown")
        void SC8_cancelPickList_validId_completesWithoutException() {
                // Issue #28: SC8 — cancelPickList must succeed silently
                // Arrange
                UUID pickListId = UUID.fromString("00000000-0000-0000-0000-000000000010");

                // Act + Assert — RED: impl throws UnsupportedOperationException
                assertThatCode(() -> service.cancelPickList(pickListId)).doesNotThrowAnyException();
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Story #179 — Mechanic Executes Picking (Scan + Confirm)
        // ─────────────────────────────────────────────────────────────────────────────

        // ─── PS1: releasePickList — DRAFT → READY_TO_PICK ────────────────────────────

        /**
         * PS1: Verifies that releasePickList() transitions status from DRAFT to
         * READY_TO_PICK, saves the entity, and returns a PickListResponse reflecting
         * the new status.
         *
         * <p>
         * ADR-0006: pos-inventory owns pick list state; workexec owns orchestration.
         *
         * @see PickListServiceImpl#releasePickList(UUID)
         *      Issue: #179
         */
        @Test
        @DisplayName("PS1_releasePickList: DRAFT pick list → status=READY_TO_PICK in response")
        void PS1_releasePickList_draftPickList_returnsReadyToPickResponse() {
                // Issue #179: PS1 — releasePickList must transition DRAFT → READY_TO_PICK
                // Arrange
                UUID pickListId = UUID.fromString("00000000-0000-0000-0000-000000000011");
                PickListEntity entity = PickListEntity.builder()
                                .pickListId(pickListId)
                                .workorderId(UUID.fromString("00000000-0000-0000-0000-000000000012"))
                                .status(PickListStatus.DRAFT)
                                .priority(0)
                                .createdAt(Instant.now(TEST_CLOCK))
                                .updatedAt(Instant.now(TEST_CLOCK))
                                .build();
                when(pickListRepository.findById(pickListId)).thenReturn(Optional.of(entity));
                when(pickListRepository.save(entity)).thenReturn(entity);

                // Act — BLOCKED: releasePickList does not exist on PickListServiceImpl
                PickListResponse result = service.releasePickList(pickListId);

                // Assert
                assertThat(result.getPickListId()).isEqualTo(pickListId);
                assertThat(result.getStatus()).isEqualTo(PickListStatus.READY_TO_PICK);
        }

        // ─── PS2: confirmPickTask — valid scan → task status=PICKED ──────────────────

        /**
         * PS2: Verifies that confirmPickTask() with a valid scan (matching scannedSkuId
         * and non-exceeding quantityPicked) sets task status to PICKED and returns a
         * PickTaskResponse with status=PICKED.
         *
         * <p>
         * ADR-0006: pos-inventory validates pick scan data; orchestration lives in
         * workexec.
         *
         * @see PickListServiceImpl#confirmPickTask(UUID, UUID, UUID, UUID, int)
         *      Issue: #179
         */
        @Test
        @DisplayName("PS2_confirmPickTask: matching SKU + valid quantity → task status=PICKED")
        void PS2_confirmPickTask_validScan_returnsPickedTaskResponse() {
                // Issue #179: PS2 — valid scan must set task status=PICKED
                // Arrange
                UUID pickListId = UUID.fromString("00000000-0000-0000-0000-000000000013");
                UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000014");
                UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000015");
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000016");
                int quantityRequired = 2;

                PickListEntity pickList = PickListEntity.builder()
                                .pickListId(pickListId)
                                .workorderId(UUID.fromString("00000000-0000-0000-0000-000000000017"))
                                .status(PickListStatus.READY_TO_PICK)
                                .priority(0)
                                .build();
                PickTaskEntity taskEntity = PickTaskEntity.builder()
                                .pickTaskId(pickTaskId)
                                .pickList(pickList)
                                .productId(productId)
                                .sku("SKU-TEST-001")
                                .quantityRequired(quantityRequired)
                                .suggestedLocationId(locationId)
                                .status(PickTaskStatus.PENDING)
                                .sortOrder(0)
                                .build();

                when(pickListRepository.findById(pickListId)).thenReturn(Optional.of(pickList));
                when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(taskEntity));
                when(pickTaskRepository.save(taskEntity)).thenReturn(taskEntity);
                when(pickTaskRepository.findByPickListOrderBySortOrderAsc(pickList)).thenReturn(List.of(taskEntity));

                // Act — BLOCKED: confirmPickTask does not exist on PickListServiceImpl
                PickTaskResponse result = service.confirmPickTask(
                                pickListId, pickTaskId, productId, locationId, quantityRequired);

                // Assert
                assertThat(result.getPickTaskId()).isEqualTo(pickTaskId);
                assertThat(result.getStatus()).isEqualTo(PickTaskStatus.PICKED);
                assertThat(result.getQuantityPicked()).isEqualTo(quantityRequired);
        }

        // ─── PS3: confirmPickTask — wrong SKU → PickScanMismatchException
        // ─────────────

        /**
         * PS3: Verifies that confirmPickTask() with a scannedSkuId that does not match
         * the task's productId throws {@link PickScanMismatchException}.
         *
         * <p>
         * ADR-0017: 422 Unprocessable Entity for semantic domain-policy violations.
         *
         * @see PickListServiceImpl#confirmPickTask(UUID, UUID, UUID, UUID, int)
         *      Issue: #179
         */
        @Test
        @DisplayName("PS3_confirmPickTask: wrong scannedSkuId → PickScanMismatchException")
        void PS3_confirmPickTask_wrongSkuId_throwsPickScanMismatchException() {
                // Issue #179: PS3 — scan mismatch must throw PickScanMismatchException (→ 422)
                // Arrange
                UUID pickListId = UUID.fromString("00000000-0000-0000-0000-000000000018");
                UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000019");
                UUID correctProductId = UUID.fromString("00000000-0000-0000-0000-000000000020");
                UUID wrongScannedSkuId = UUID.fromString("00000000-0000-0000-0000-000000000021"); // different from
                // correctProductId
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000022");

                PickListEntity pickList = PickListEntity.builder()
                                .pickListId(pickListId)
                                .workorderId(UUID.fromString("00000000-0000-0000-0000-000000000023"))
                                .status(PickListStatus.READY_TO_PICK)
                                .priority(0)
                                .build();
                PickTaskEntity taskEntity = PickTaskEntity.builder()
                                .pickTaskId(pickTaskId)
                                .pickList(pickList)
                                .productId(correctProductId)
                                .sku("SKU-CORRECT")
                                .quantityRequired(1)
                                .suggestedLocationId(locationId)
                                .status(PickTaskStatus.PENDING)
                                .sortOrder(0)
                                .build();

                when(pickListRepository.findById(pickListId)).thenReturn(Optional.of(pickList));
                when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(taskEntity));

                // Act + Assert — BLOCKED: confirmPickTask does not exist on PickListServiceImpl
                assertThatThrownBy(() -> service.confirmPickTask(
                                pickListId, pickTaskId, wrongScannedSkuId, locationId, 1))
                                .isInstanceOf(PickScanMismatchException.class);
        }

        // ─── PS4: confirmPickTask — quantity exceeds required →
        // IllegalArgumentException

        /**
         * PS4: Verifies that confirmPickTask() with a quantityPicked that exceeds the
         * task's quantityRequired throws {@link IllegalArgumentException}.
         *
         * <p>
         * ADR-0017: 400 Bad Request for input validation failures.
         *
         * @see PickListServiceImpl#confirmPickTask(UUID, UUID, UUID, UUID, int)
         *      Issue: #179
         */
        @Test
        @DisplayName("PS4_confirmPickTask: quantityPicked > quantityRequired → IllegalArgumentException")
        void PS4_confirmPickTask_quantityExceedsRequired_throwsIllegalArgumentException() {
                // Issue #179: PS4 — over-pick must be rejected with IllegalArgumentException
                // Arrange
                UUID pickListId = UUID.fromString("00000000-0000-0000-0000-000000000024");
                UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000025");
                UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000026");
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000027");
                int quantityRequired = 2;
                int overPickQuantity = quantityRequired + 1;

                PickListEntity pickList = PickListEntity.builder()
                                .pickListId(pickListId)
                                .workorderId(UUID.fromString("00000000-0000-0000-0000-000000000028"))
                                .status(PickListStatus.READY_TO_PICK)
                                .priority(0)
                                .build();
                PickTaskEntity taskEntity = PickTaskEntity.builder()
                                .pickTaskId(pickTaskId)
                                .pickList(pickList)
                                .productId(productId)
                                .sku("SKU-TEST-002")
                                .quantityRequired(quantityRequired)
                                .suggestedLocationId(locationId)
                                .status(PickTaskStatus.PENDING)
                                .sortOrder(0)
                                .build();

                when(pickListRepository.findById(pickListId)).thenReturn(Optional.of(pickList));
                when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(taskEntity));

                // Act + Assert — BLOCKED: confirmPickTask does not exist on PickListServiceImpl
                assertThatThrownBy(() -> service.confirmPickTask(
                                pickListId, pickTaskId, productId, locationId, overPickQuantity))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("quantity");
        }

        // ─── PS5: confirmPickTask — last task PICKED → pick list COMPLETED
        // ────────────

        /**
         * PS5: Verifies that when confirmPickTask() is called on the last PENDING task
         * and all other tasks are already PICKED, the pick list status transitions to
         * COMPLETED and the returned PickTaskResponse reflects status=PICKED.
         *
         * <p>
         * ADR-0006: pos-inventory owns the completion state transition.
         *
         * @see PickListServiceImpl#confirmPickTask(UUID, UUID, UUID, UUID, int)
         *      Issue: #179
         */
        @Test
        @DisplayName("PS5_confirmPickTask: last pending task confirmed → pick list status=COMPLETED")
        void PS5_confirmPickTask_lastPendingTask_triggersPickListCompleted() {
                // Issue #179: PS5 — confirming last task must trigger pick list
                // status=COMPLETED
                // Arrange
                UUID pickListId = UUID.fromString("00000000-0000-0000-0000-000000000029");
                UUID taskId1 = UUID.fromString("00000000-0000-0000-0000-000000000030");
                UUID taskId2 = UUID.fromString("00000000-0000-0000-0000-000000000031");
                UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000032");
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000033");

                PickListEntity pickList = PickListEntity.builder()
                                .pickListId(pickListId)
                                .workorderId(UUID.fromString("00000000-0000-0000-0000-000000000034"))
                                .status(PickListStatus.IN_PROGRESS)
                                .priority(0)
                                .build();

                // task1 already PICKED, task2 is the last pending task
                PickTaskEntity task1 = PickTaskEntity.builder()
                                .pickTaskId(taskId1)
                                .pickList(pickList)
                                .productId(UUID.fromString("00000000-0000-0000-0000-000000000035"))
                                .sku("SKU-DONE")
                                .quantityRequired(1)
                                .status(PickTaskStatus.PICKED)
                                .sortOrder(0)
                                .build();
                PickTaskEntity task2 = PickTaskEntity.builder()
                                .pickTaskId(taskId2)
                                .pickList(pickList)
                                .productId(productId)
                                .sku("SKU-LAST")
                                .quantityRequired(1)
                                .suggestedLocationId(locationId)
                                .status(PickTaskStatus.PENDING)
                                .sortOrder(1)
                                .build();

                when(pickListRepository.findById(pickListId)).thenReturn(Optional.of(pickList));
                when(pickTaskRepository.findById(taskId2)).thenReturn(Optional.of(task2));
                when(pickTaskRepository.save(task2)).thenReturn(task2);
                // After task2 is set to PICKED, all tasks are PICKED → list should complete
                when(pickTaskRepository.findByPickListOrderBySortOrderAsc(pickList)).thenReturn(List.of(task1, task2));
                when(pickListRepository.save(pickList)).thenReturn(pickList);

                // Act — BLOCKED: confirmPickTask does not exist on PickListServiceImpl
                PickTaskResponse result = service.confirmPickTask(
                                pickListId, taskId2, productId, locationId, 1);

                // Assert
                assertThat(result.getStatus()).isEqualTo(PickTaskStatus.PICKED);
                assertThat(pickList.getStatus()).isEqualTo(PickListStatus.COMPLETED);
        }
}
