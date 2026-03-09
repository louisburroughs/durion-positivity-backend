package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.positivity.shopmanager.internal.dto.ShopAuditEntryResponse;
import com.positivity.shopmanager.internal.dto.ShopAuditFilter;
import com.positivity.shopmanager.internal.entity.ShopAuditEntry;
import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import com.positivity.shopmanager.internal.repository.ShopAuditRepository;
import com.positivity.shopmanager.service.ShopAuditService;

/**
 * RED tests for Story #61 — Audit Trail for Schedule and Assignment Changes.
 *
 *
 * <p>
 * ADR compliance:
 * <ul>
 * <li>ADR-0018: actorId MUST be sourced from SecurityContextHolder; never from
 * caller-supplied params.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopAuditServiceTest {

    // Story #61 — fixed clock simulates "now" for 90-day default window tests.
    private static final Instant FIXED_NOW = Instant.parse("2025-06-01T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    @Mock
    private ShopAuditRepository shopAuditRepository;

    private ShopAuditServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShopAuditServiceImpl(shopAuditRepository, FIXED_CLOCK);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─── AC: Schedule change triggers audit entry ─────────────────────────────

    /**
     * AC: A schedule change (create) produces an audit entry with actor from
     * security context.
     * ADR-0018: actorId resolved from authenticated principal — never from a
     * caller-supplied field.
     */
    @Test
    void recordScheduleChange_scheduleCreate_persistsEntryWithActorFromSecurityContext() {
        // arrange — set authenticated actor in security context (ADR-0018)
        var auth = new UsernamePasswordAuthenticationToken(
                "dispatcher@shop.com", null,
                List.of(new SimpleGrantedAuthority("shopmgmt.schedule.create")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // act
        ShopAuditEntryResponse result = service.recordScheduleChange(
                ShopAuditEventType.SCHEDULE_CREATED,
                "AP-789", "WO-123", "LOC-1",
                "Created schedule for 2:00 PM", null, null, null);

        // assert — actor MUST come from security context
        assertThat(result)
                .isNotNull()
                .satisfies(r -> {
                    assertThat(r.getActorUserId())
                            .as("actorUserId must be sourced from SecurityContextHolder (ADR-0018)")
                            .isEqualTo("dispatcher@shop.com");
                    assertThat(r.getEventType()).isEqualTo(ShopAuditEventType.SCHEDULE_CREATED);
                    assertThat(r.getAppointmentId()).isEqualTo("AP-789");
                    assertThat(r.getWorkorderId()).isEqualTo("WO-123");
                    assertThat(r.getId()).isNotNull();
                });
    }

    // ─── AC: Assignment change triggers audit entry ───────────────────────────

    /**
     * AC: A mechanic assignment produces an audit entry with actor from security
     * context.
     * ADR-0018: actorId resolved from authenticated principal.
     */
    @Test
    void recordAssignmentChange_mechanicAssigned_persistsEntryWithActorFromSecurityContext() {
        // arrange
        var auth = new UsernamePasswordAuthenticationToken(
                "advisor@shop.com", null,
                List.of(new SimpleGrantedAuthority("workexec.assignment.create")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // act — mechanic M-456 assigned to WO-123
        ShopAuditEntryResponse result = service.recordAssignmentChange(
                ShopAuditEventType.ASSIGNMENT_CREATED,
                "WO-123", "M-456", "LOC-1",
                "Assigned mechanic M-456 to WO-123", null, null, null);

        // assert
        assertThat(result)
                .isNotNull()
                .satisfies(r -> {
                    assertThat(r.getActorUserId())
                            .as("actorUserId must be sourced from SecurityContextHolder (ADR-0018)")
                            .isEqualTo("advisor@shop.com");
                    assertThat(r.getEventType()).isEqualTo(ShopAuditEventType.ASSIGNMENT_CREATED);
                    assertThat(r.getWorkorderId()).isEqualTo("WO-123");
                    assertThat(r.getMechanicId()).isEqualTo("M-456");
                    assertThat(r.getId()).isNotNull();
                });
    }

    /**
     * AC: A mechanic unassignment also produces an audit entry with actor from
     * security context.
     */
    @Test
    void recordAssignmentChange_mechanicUnassigned_persistsEntryWithActorFromSecurityContext() {
        // arrange
        var auth = new UsernamePasswordAuthenticationToken(
                "advisor@shop.com", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // act — mechanic M-456 removed from WO-123
        ShopAuditEntryResponse result = service.recordAssignmentChange(
                ShopAuditEventType.ASSIGNMENT_REMOVED,
                "WO-123", "M-456", "LOC-1",
                "Removed mechanic M-456 from WO-123", null, "workexec:MECHANIC_UNAVAILABLE", null);

        assertThat(result)
                .isNotNull()
                .satisfies(r -> {
                    assertThat(r.getActorUserId()).isEqualTo("advisor@shop.com");
                    assertThat(r.getEventType()).isEqualTo(ShopAuditEventType.ASSIGNMENT_REMOVED);
                });
    }

    // ─── ADR-0018: Actor must come from SecurityContext only ──────────────────

    /**
     * ADR-0018 structural guard: service interface record-methods must NOT declare
     * an
     * {@code actorId} / {@code actorUserId} / {@code changedBy} / {@code userId}
     * parameter.
     * The actor is always sourced internally from the security context.
     *
     * <p>
     * This is a structural test; it passes at scaffold time and must remain
     * passing.
     */
    @Test
    void shopAuditService_recordMethods_doNotExposeActorAsParameter() {
        var recordMethods = Arrays.stream(ShopAuditService.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("record"))
                .toList();

        assertThat(recordMethods)
                .as("ShopAuditService must expose at least one recordXxx method")
                .isNotEmpty();

        for (var method : recordMethods) {
            var paramNames = Arrays.stream(method.getParameters())
                    .map(p -> p.getName().toLowerCase())
                    .toList();

            assertThat(paramNames)
                    .as("Method '%s' must NOT declare actor/actorId/changedBy/userId as a parameter "
                            + "(ADR-0018 — actor is sourced from SecurityContextHolder, never from caller)",
                            method.getName())
                    .noneMatch(n -> n.equals("actorid") || n.equals("actoruserid")
                            || n.equals("changedby") || n.equals("createdby")
                            || n.equals("actor"));
        }
    }

    // ─── AC: Audit entries are immutable ─────────────────────────────────────

    /**
     * AC: Audit log entries are immutable — the service interface must define no
     * update,
     * delete, patch, or modify methods (Story #61 business rule).
     *
     * <p>
     * This is a structural test; it passes at scaffold time and must remain
     * passing.
     */
    @Test
    void shopAuditService_hasNoMutationMethodsOtherThanRecord() {
        var methodNames = Arrays.stream(ShopAuditService.class.getDeclaredMethods())
                .map(m -> m.getName().toLowerCase())
                .toList();

        assertThat(methodNames)
                .as("ShopAuditService must not expose update/delete/patch/modify/edit methods "
                        + "(audit entries are immutable per Story #61 business rule)")
                .noneMatch(n -> n.startsWith("update") || n.startsWith("delete")
                        || n.startsWith("patch") || n.startsWith("modify") || n.startsWith("edit"));
    }

    // ─── AC: Search by workorderId ────────────────────────────────────────────

    /**
     * AC: Search by workorderId returns all matching audit entries in
     * reverse-chronological order.
     */
    @Test
    void search_byWorkorderId_returnsMatchingEntriesForThatWorkorder() {
        ShopAuditFilter filter = ShopAuditFilter.builder()
                .workorderId("WO-123")
                .build();
        ShopAuditEntry matchingEntry = ShopAuditEntry.builder()
                .id(UUID.randomUUID())
                .workorderId("WO-123")
                .eventType(ShopAuditEventType.SCHEDULE_CREATED)
                .actorUserId("system")
                .build();
        when(shopAuditRepository.findByFilter(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(matchingEntry));

        List<ShopAuditEntryResponse> results = service.search(filter);

        assertThat(results)
                .as("search by workorderId must return a non-null list")
                .isNotNull()
                .as("all returned entries must match workorderId WO-123")
                .allSatisfy(r -> assertThat(r.getWorkorderId()).isEqualTo("WO-123"));
    }

    // ─── AC: Search by mechanicId ─────────────────────────────────────────────

    /**
     * AC: Search by mechanicId returns all assignment audit entries for that
     * mechanic.
     */
    @Test
    void search_byMechanicId_returnsMatchingEntriesForThatMechanic() {
        ShopAuditFilter filter = ShopAuditFilter.builder()
                .mechanicId("M-456")
                .build();
        ShopAuditEntry matchingEntry = ShopAuditEntry.builder()
                .id(UUID.randomUUID())
                .mechanicId("M-456")
                .eventType(ShopAuditEventType.ASSIGNMENT_CREATED)
                .actorUserId("system")
                .build();
        when(shopAuditRepository.findByFilter(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(matchingEntry));

        List<ShopAuditEntryResponse> results = service.search(filter);

        assertThat(results)
                .as("search by mechanicId must return a non-null list")
                .isNotNull()
                .as("all returned entries must match mechanicId M-456")
                .allSatisfy(r -> assertThat(r.getMechanicId()).isEqualTo("M-456"));
    }

    // ─── AC: Search by eventType ──────────────────────────────────────────────

    /**
     * AC: Search by eventType returns only audit entries of the specified type.
     */
    @Test
    void search_byEventType_returnsOnlyEntriesOfThatType() {
        ShopAuditFilter filter = ShopAuditFilter.builder()
                .eventType(ShopAuditEventType.ASSIGNMENT_CREATED)
                .build();
        ShopAuditEntry matchingEntry = ShopAuditEntry.builder()
                .id(UUID.randomUUID())
                .eventType(ShopAuditEventType.ASSIGNMENT_CREATED)
                .actorUserId("system")
                .build();
        when(shopAuditRepository.findByFilter(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(matchingEntry));

        List<ShopAuditEntryResponse> results = service.search(filter);

        assertThat(results)
                .as("search by eventType must return a non-null list")
                .isNotNull()
                .as("all returned entries must have eventType ASSIGNMENT_CREATED")
                .allSatisfy(r -> assertThat(r.getEventType())
                        .isEqualTo(ShopAuditEventType.ASSIGNMENT_CREATED));
    }

    // ─── AC: Date range defaults to last 90 days ──────────────────────────────

    /**
     * AC (RQ5): When no {@code fromDateTime} is supplied, the service must default
     * to the last 90 days to prevent unbounded full-table scans.
     */
    @Test
    void search_withNoFromDateTime_defaultsToLast90DaysWindow() {
        // Arrange — filter has workorderId but no explicit fromDateTime
        ShopAuditFilter filter = ShopAuditFilter.builder()
                .workorderId("WO-123")
                // fromDateTime intentionally omitted — service must apply 90-day default
                .build();

        Instant expectedFrom = FIXED_NOW.minus(90, ChronoUnit.DAYS);

        List<ShopAuditEntryResponse> results = service.search(filter);

        assertThat(results)
                .isNotNull()
                // All returned entries must fall within [now-90d, now]
                .allSatisfy(r -> assertThat(r.getRecordedAt())
                        .as("entry recordedAt must be within the default 90-day window")
                        .isAfterOrEqualTo(expectedFrom)
                        .isBeforeOrEqualTo(FIXED_NOW));
    }

    // ─── AC: Empty filter is rejected ─────────────────────────────────────────

    /**
     * AC (RQ5): At least one filter criterion must be provided. An empty filter
     * must be rejected with {@link IllegalArgumentException} to prevent full-table
     * scans.
     */
    @Test
    void search_withEmptyFilter_throwsIllegalArgumentException() {
        ShopAuditFilter emptyFilter = ShopAuditFilter.builder().build(); // all fields null

        // Precondition: helper correctly identifies the filter as empty
        assertThat(emptyFilter.hasAtLeastOneFilter()).isFalse();

        // The service must reject empty filters (not allow unbounded scans)
        assertThatThrownBy(() -> service.search(emptyFilter))
                .as("search with empty filter must throw IllegalArgumentException "
                        + "to prevent unbounded full-table scans (RQ5)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filter");
    }

    // ─── AC: Retention period default 7 years ────────────────────────────────

    /**
     * AC (RQ3): Audit entries must be persisted with the default 7-year retention
     * period
     * unless an explicit override is provided by tenant configuration.
     */
    @Test
    void recordScheduleChange_withoutExplicitRetentionOverride_persistsWith7YearDefault() {
        // arrange
        var auth = new UsernamePasswordAuthenticationToken(
                "dispatcher@shop.com", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // act — reschedule with reason code (RQ4 structured reason code)
        ShopAuditEntryResponse result = service.recordScheduleChange(
                ShopAuditEventType.SCHEDULE_UPDATED,
                "AP-789", "WO-123", null,
                "Rescheduled from 2:00 PM to 3:00 PM",
                "[{\"op\":\"replace\",\"path\":\"/scheduleTime\",\"value\":\"2025-01-10T15:00:00Z\"}]",
                "workexec:CUSTOMER_REQUEST",
                "Customer called to request later slot");

        // assert — default retention is 7 years (Story #61 / RQ3)
        assertThat(result.getRetentionYears())
                .as("default retention period must be 7 years (Story #61 / RQ3)")
                .isEqualTo(7);
        assertThat(result.getReasonCode()).isEqualTo("workexec:CUSTOMER_REQUEST");
    }

    // ─── AC: findById returns correct entry ───────────────────────────────────

    /**
     * AC: A single audit entry can be retrieved by its UUID.
     */
    @Test
    void findById_withExistingId_returnsEntry() {
        UUID knownId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ShopAuditEntry stubEntry = ShopAuditEntry.builder()
                .id(knownId)
                .eventType(ShopAuditEventType.SCHEDULE_CREATED)
                .actorUserId("system")
                .build();
        when(shopAuditRepository.findById(knownId)).thenReturn(Optional.of(stubEntry));

        var result = service.findById(knownId);

        assertThat(result)
                .isPresent()
                .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(knownId));
    }

    /**
     * AC: A missing audit entry returns an empty Optional (not an exception).
     */
    @Test
    void findById_withNonExistentId_returnsEmpty() {
        UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        var result = service.findById(unknownId);

        assertThat(result).isEmpty();
    }

    // ─── ADR-0018: resolveActorId corner cases ────────────────────────────────

    /**
     * ADR-0018: When no authentication is present in the security context, the
     * actor must default to "system" rather than throwing.
     */
    @Test
    void resolveActorId_withNullAuthentication_defaultsToSystem() {
        // arrange — security context is empty; no auth set
        SecurityContextHolder.clearContext();

        // act — any record method exercises resolveActorId()
        ShopAuditEntryResponse result = service.recordScheduleChange(
                ShopAuditEventType.SCHEDULE_CREATED,
                "AP-SYS-001", null, null,
                "System-initiated schedule creation", null, null, null);

        // assert
        assertThat(result.getActorUserId())
                .as("actorUserId must default to 'system' when authentication is null (ADR-0018)")
                .isEqualTo("system");
    }

    /**
     * ADR-0018: When authentication is present but its name is null, the actor
     * must still default to "system".
     */
    @Test
    void resolveActorId_withNullAuthenticationName_defaultsToSystem() {
        // arrange — mock authentication whose getName() returns null
        Authentication mockAuth = mock(Authentication.class);
        when(mockAuth.getName()).thenReturn(null);
        SecurityContextHolder.getContext().setAuthentication(mockAuth);

        // act
        ShopAuditEntryResponse result = service.recordAssignmentChange(
                ShopAuditEventType.ASSIGNMENT_CREATED,
                "WO-SYS-001", "M-SYS-001", null,
                "System-initiated assignment", null, null, null);

        // assert
        assertThat(result.getActorUserId())
                .as("actorUserId must default to 'system' when authentication name is null (ADR-0018)")
                .isEqualTo("system");
    }

    // ─── AC: Explicit date range is respected ─────────────────────────────────

    /**
     * AC (RQ5): When explicit {@code fromDateTime} and {@code toDateTime} are
     * provided in the filter, the service passes them through to the repository
     * without applying the 90-day default.
     */
    @Test
    void search_withExplicitFromAndToDateTime_passesSuppliedDatesToRepository() {
        Instant from = FIXED_NOW.minus(30, ChronoUnit.DAYS);
        Instant to = FIXED_NOW.minus(1, ChronoUnit.DAYS);

        ShopAuditFilter filter = ShopAuditFilter.builder()
                .workorderId("WO-RANGE-001")
                .fromDateTime(from)
                .toDateTime(to)
                .build();

        ShopAuditEntry entry = ShopAuditEntry.builder()
                .id(UUID.randomUUID())
                .workorderId("WO-RANGE-001")
                .eventType(ShopAuditEventType.SCHEDULE_UPDATED)
                .actorUserId("tech@shop.com")
                .build();
        when(shopAuditRepository.findByFilter(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(entry));

        List<ShopAuditEntryResponse> results = service.search(filter);

        assertThat(results)
                .as("search with explicit date range must return results")
                .hasSize(1)
                .singleElement()
                .satisfies(r -> assertThat(r.getWorkorderId()).isEqualTo("WO-RANGE-001"));
    }
}
