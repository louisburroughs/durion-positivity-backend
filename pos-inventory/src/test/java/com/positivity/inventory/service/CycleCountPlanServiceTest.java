package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.internal.service.CycleCountPlanServiceImpl;

/**
 * Unit tests for CycleCountPlanServiceImpl.
 *
 * <p>
 * Covers plan creation validation (past date, empty zones, null location),
 * happy path creation with PLANNED status, and plan retrieval (found /
 * not-found) behavior.
 *
 * Issue: #176
 */
@ExtendWith(MockitoExtension.class)
class CycleCountPlanServiceTest {

        private static final Clock FIXED_CLOCK = Clock.fixed(java.time.Instant.parse("2024-01-01T00:00:00Z"),
                        java.time.ZoneOffset.UTC);

        @Mock
        private CycleCountPlanRepository cycleCountPlanRepository;

        private CycleCountPlanServiceImpl service;

        private static final String ACTOR_USER_ID = "test-user-001";

        @BeforeEach
        void setUp() {
                service = new CycleCountPlanServiceImpl(cycleCountPlanRepository, FIXED_CLOCK);
        }

        // ─── createPlan ────────────────────────────────────────────────────────────

        /**
         * Verifies that a valid request with a future scheduledDate and non-empty
         * zoneIds creates a plan with PLANNED status.
         */
        @Test
        void createPlan_validRequest_returnsPlanResponseWithPlannedStatus() {
                // Issue #176: happy path — future date, valid zones → PLANNED status
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                List<UUID> zoneIds = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));
                LocalDate scheduledDate = LocalDate.now(FIXED_CLOCK).plusDays(7);
                UUID generatedPlanId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                CreateCycleCountPlanRequest request = CreateCycleCountPlanRequest.builder()
                                .locationId(locationId)
                                .zoneIds(zoneIds)
                                .planName("Annual Audit Q1")
                                .scheduledDate(scheduledDate)
                                .build();

                when(cycleCountPlanRepository.save(any(CycleCountPlan.class))).thenAnswer(inv -> {
                        CycleCountPlan plan = inv.getArgument(0);
                        plan.setPlanId(generatedPlanId);
                        return plan;
                });

                CycleCountPlanResponse response = service.createPlan(request, ACTOR_USER_ID);

                assertThat(response).isNotNull();
                assertThat(response.getPlanId()).isEqualTo(generatedPlanId);
                assertThat(response.getStatus()).isEqualTo("PLANNED");
                assertThat(response.getLocationId()).isEqualTo(locationId);
                assertThat(response.getZoneIds()).containsExactlyElementsOf(zoneIds);
                verify(cycleCountPlanRepository).save(any(CycleCountPlan.class));
        }

        /**
         * Verifies that a scheduledDate in the past raises an
         * {@link IllegalArgumentException} and no plan is persisted.
         */
        @Test
        void createPlan_pastScheduledDate_throwsIllegalArgumentException() {
                // Issue #176: scheduledDate yesterday → IAE → becomes 400 in controller
                CreateCycleCountPlanRequest request = CreateCycleCountPlanRequest.builder()
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .zoneIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                                .scheduledDate(LocalDate.now(FIXED_CLOCK).minusDays(1))
                                .build();

                assertThatThrownBy(() -> service.createPlan(request, ACTOR_USER_ID))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("past");

                verify(cycleCountPlanRepository, never()).save(any());
        }

        /**
         * Verifies that an empty zoneIds list raises an
         * {@link IllegalArgumentException} and no plan is persisted.
         */
        @Test
        void createPlan_emptyZoneIds_throwsIllegalArgumentException() {
                // Issue #176: no zones → IAE → becomes 400 in controller
                CreateCycleCountPlanRequest request = CreateCycleCountPlanRequest.builder()
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .zoneIds(List.of())
                                .scheduledDate(LocalDate.now(FIXED_CLOCK).plusDays(1))
                                .build();

                assertThatThrownBy(() -> service.createPlan(request, ACTOR_USER_ID))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("zone");

                verify(cycleCountPlanRepository, never()).save(any());
        }

        /**
         * Verifies that a null locationId raises an {@link IllegalArgumentException}
         * and no plan is persisted.
         */
        @Test
        void createPlan_nullLocationId_throwsIllegalArgumentException() {
                CreateCycleCountPlanRequest request = CreateCycleCountPlanRequest.builder()
                                .locationId(null) // Test case: null locationId
                                .zoneIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                                .scheduledDate(LocalDate.now(FIXED_CLOCK).plusDays(1))
                                .build();

                assertThatThrownBy(() -> service.createPlan(request, ACTOR_USER_ID))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("locationId");

                verify(cycleCountPlanRepository, never()).save(any());
        }

        /**
         * Verifies that a null zoneIds list raises an
         * {@link IllegalArgumentException} and no plan is persisted.
         */
        @Test
        void createPlan_nullZoneIds_throwsIllegalArgumentException() {
                CreateCycleCountPlanRequest request = CreateCycleCountPlanRequest.builder()
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .zoneIds(null) // Test case: null zoneIds
                                .scheduledDate(LocalDate.now(FIXED_CLOCK).plusDays(1))
                                .build();

                assertThatThrownBy(() -> service.createPlan(request, ACTOR_USER_ID))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("zoneIds");

                verify(cycleCountPlanRepository, never()).save(any());
        }

        /**
         * Verifies that a null scheduledDate raises an
         * {@link IllegalArgumentException} and no plan is persisted.
         */
        @Test
        void createPlan_nullScheduledDate_throwsIllegalArgumentException() {
                CreateCycleCountPlanRequest request = CreateCycleCountPlanRequest.builder()
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .zoneIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                                .scheduledDate(null) // Test case: null scheduledDate
                                .build();

                assertThatThrownBy(() -> service.createPlan(request, ACTOR_USER_ID))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("scheduledDate");

                verify(cycleCountPlanRepository, never()).save(any());
        }

        /**
         * Verifies that a scheduledDate of today raises an
         * {@link IllegalArgumentException} as it must be in the future.
         */
        @Test
        void createPlan_todaysScheduledDate_throwsIllegalArgumentException() {
                CreateCycleCountPlanRequest request = CreateCycleCountPlanRequest.builder()
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .zoneIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                                .scheduledDate(LocalDate.now(FIXED_CLOCK)) // Test case: today's date
                                .build();

                assertThatThrownBy(() -> service.createPlan(request, ACTOR_USER_ID))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("scheduledDate");

                verify(cycleCountPlanRepository, never()).save(any());
        }

        // ─── getPlan ───────────────────────────────────────────────────────────────

        /**
         * Verifies that an existing plan is returned correctly when found by ID.
         */
        @Test
        void getPlan_existingId_returnsPlanResponse() {
                // Issue #176: known planId → entity found → response populated
                UUID planId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                List<UUID> zoneIds = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));

                CycleCountPlan entity = CycleCountPlan.builder()
                                .planId(planId)
                                .locationId(locationId)
                                .zoneIds(zoneIds)
                                .planName("Test Plan")
                                .scheduledDate(LocalDate.now(FIXED_CLOCK).plusDays(5))
                                .status(CycleCountPlanStatus.PLANNED)
                                .createdBy(ACTOR_USER_ID)
                                .build();

                when(cycleCountPlanRepository.findById(planId)).thenReturn(Optional.of(entity));

                CycleCountPlanResponse response = service.getPlan(planId);

                assertThat(response).isNotNull();
                assertThat(response.getPlanId()).isEqualTo(planId);
                assertThat(response.getStatus()).isEqualTo("PLANNED");
                assertThat(response.getCreatedBy()).isEqualTo(ACTOR_USER_ID);
        }

        /**
         * Verifies that a non-existent planId causes an exception that will
         * propagate to a 404 response.
         */
        @Test
        void getPlan_nonExistentId_throwsException() {
                // Issue #176: unknown planId → empty → CycleCountPlanNotFoundException → 404
                UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                when(cycleCountPlanRepository.findById(unknownId)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.getPlan(unknownId))
                                .isInstanceOf(CycleCountPlanNotFoundException.class);
        }
}
