package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.workorder.internal.dto.OperationalContextOverrideRequest;
import com.positivity.workorder.internal.dto.OperationalContextResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

/**
 * Location scope on {@link WorkorderServiceImpl#overrideOperationalContext} (ADR-0061 §3, #1872).
 *
 * <p>The gate lives in the service rather than the controller because the check must follow the
 * existence check — a 403 for an id that does not exist would let a caller probe which workorder
 * ids are real — and the entity is loaded here. Both ends of the move are gated: the workorder's
 * current shop ({@code shopId}, the field the WIP detail gate reads) and then the body's
 * {@code locationId}, the site it is re-slotted to; both before any state change so a denied
 * override writes nothing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkorderServiceImpl.overrideOperationalContext — location scope")
class OperationalContextLocationScopeTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);

    private static final UUID WORKORDER_ID = UUID.fromString("019200cc-0000-7000-8000-000000000101");
    private static final UUID REGION_NODE = UUID.fromString("019200cc-0000-7000-8000-00000000a000");
    private static final UUID SHOP_A = UUID.fromString("019200cc-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("019200cc-0000-7000-8000-00000000000b");
    private static final UUID SHOP_C = UUID.fromString("019200cc-0000-7000-8000-00000000000c");

    /** Replica stand-in: SHOP_A and SHOP_C sit under REGION_NODE on the OTHER dimension; SHOP_B does not. */
    private static final Map<UUID, AncestorSets> REPLICA = Map.of(
            SHOP_A, new AncestorSets(Set.of(SHOP_A), Set.of(SHOP_A, REGION_NODE)),
            SHOP_B, new AncestorSets(Set.of(SHOP_B), Set.of(SHOP_B)),
            SHOP_C, new AncestorSets(Set.of(SHOP_C), Set.of(SHOP_C, REGION_NODE)));

    private static final LocationAncestorResolver RESOLVER =
            locationId -> REPLICA.getOrDefault(locationId, AncestorSets.EMPTY);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private EstimateRepository estimateRepository;

    @Mock
    private EstimateItemRepository estimateItemRepository;

    @Mock
    private WorkorderServiceRepository workorderServiceRepository;

    @Mock
    private WorkorderPartRepository workorderPartRepository;

    @Mock
    private RestClient restClient;

    @Mock
    private WorkorderStateMachine stateMachine;

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private PromotionValidationService promotionValidationService;

    @Mock
    private WorkorderFactPublisher workorderFactPublisher;

    @InjectMocks
    private WorkorderServiceImpl workorderService;

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------
    // Caller shapes
    // ---------------------------------------------------------------------------------------

    /** A post-rollout token whose override grant is OTHER-scoped to {@link #REGION_NODE}. */
    private static void scopedManager() {
        callerWith(LocationScope.of(
                Set.of(),
                Set.of(WorkorderPermissions.OPERATIONALCONTEXT_OVERRIDE),
                Optional.of(Set.of(REGION_NODE)),
                true,
                RESOLVER));
    }

    /** A pre-rollout token: no {@code loc_*} claims, so no scope detail is attached. */
    private static void preRolloutManager() {
        callerWith(null);
    }

    private static void callerWith(LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken("manager", null, List.of());
        token.setDetails(
                scope == null
                        ? Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "manager")
                        : Map.of(
                                GatewaySecurityConstants.DETAIL_USERNAME,
                                "manager",
                                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                                scope));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    /** An unstarted workorder whose current shop is {@code shopId}; its locationId starts at SHOP_B. */
    private static Workorder unstartedWorkorderAt(UUID shopId) {
        return Workorder.builder()
                .id(WORKORDER_ID)
                .status(WorkorderStatus.ASSIGNED)
                .operationalContextVersion("ctx-v1")
                .shopId(shopId)
                .locationId(SHOP_B)
                .workStartedAt(null)
                .build();
    }

    /** An unstarted workorder at a shop inside the scoped caller's reach. */
    private static Workorder unstartedWorkorder() {
        return unstartedWorkorderAt(SHOP_C);
    }

    private static OperationalContextOverrideRequest moveTo(UUID locationId) {
        return OperationalContextOverrideRequest.builder()
                .locationId(locationId)
                .build();
    }

    // ---------------------------------------------------------------------------------------
    // Cases
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("scoped caller moving a workorder from a shop in reach to a location in reach applies the override")
    void inReachApplies() {
        Workorder workorder = unstartedWorkorder();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(inv -> inv.getArgument(0));

        scopedManager();
        OperationalContextResponse response = workorderService.overrideOperationalContext(WORKORDER_ID, moveTo(SHOP_A));

        assertThat(response.getLocationId()).isEqualTo(SHOP_A);
        assertThat(workorder.getLocationId()).isEqualTo(SHOP_A);
        verify(workorderFactPublisher).markChanged(WORKORDER_ID);
    }

    @Test
    @DisplayName("scoped caller re-slotting to a location out of reach is refused and writes nothing")
    void outOfReachDeniesBeforeAnyWrite() {
        Workorder workorder = unstartedWorkorder();
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        scopedManager();
        assertThatThrownBy(() -> workorderService.overrideOperationalContext(WORKORDER_ID, moveTo(SHOP_B)))
                .isInstanceOf(LocationScopeDeniedException.class)
                .satisfies(ex -> assertThat(((LocationScopeDeniedException) ex).permission())
                        .isEqualTo(WorkorderPermissions.OPERATIONALCONTEXT_OVERRIDE));

        assertThat(workorder.getLocationId()).isEqualTo(SHOP_B);
        verify(workorderRepository, never()).save(any());
        verify(workorderFactPublisher, never()).markChanged(any());
    }

    @Test
    @DisplayName("source shop out of reach is refused even when the target is in reach, and writes nothing")
    void sourceOutOfReachDenies() {
        Workorder workorder = unstartedWorkorderAt(SHOP_B);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));

        scopedManager();
        assertThatThrownBy(() -> workorderService.overrideOperationalContext(WORKORDER_ID, moveTo(SHOP_A)))
                .isInstanceOf(LocationScopeDeniedException.class);

        assertThat(workorder.getLocationId()).isEqualTo(SHOP_B);
        verify(workorderRepository, never()).save(any());
        verify(workorderFactPublisher, never()).markChanged(any());
    }

    @Test
    @DisplayName("a workorder with no shop fails closed for a scoped caller")
    void nullSourceShopFailsClosed() {
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(unstartedWorkorderAt(null)));

        scopedManager();
        assertThatThrownBy(() -> workorderService.overrideOperationalContext(WORKORDER_ID, moveTo(SHOP_A)))
                .isInstanceOf(LocationScopeDeniedException.class);

        verify(workorderRepository, never()).save(any());
    }

    @Test
    @DisplayName("a missing workorder is still 404 for a scoped caller — existence is checked before scope")
    void missingWorkorderStaysNotFound() {
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

        scopedManager();
        assertThatThrownBy(() -> workorderService.overrideOperationalContext(WORKORDER_ID, moveTo(SHOP_B)))
                .isInstanceOf(WorkorderNotFoundException.class);
    }

    @Test
    @DisplayName("scope is decided before the locked-context rule: an out-of-reach target on a started job is 403")
    void scopePrecedesLockedRule() {
        Workorder started = unstartedWorkorder();
        started.setWorkStartedAt(Instant.parse("2026-09-07T08:00:00Z"));
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(started));

        scopedManager();
        assertThatThrownBy(() -> workorderService.overrideOperationalContext(WORKORDER_ID, moveTo(SHOP_B)))
                .isInstanceOf(LocationScopeDeniedException.class);
    }

    @Test
    @DisplayName("a null target fails closed for a scoped caller")
    void nullTargetFailsClosed() {
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(unstartedWorkorder()));

        scopedManager();
        assertThatThrownBy(() -> workorderService.overrideOperationalContext(WORKORDER_ID, moveTo(null)))
                .isInstanceOf(LocationScopeDeniedException.class);

        verify(workorderRepository, never()).save(any());
    }

    @Test
    @DisplayName("pre-rollout token without loc_* claims moves a workorder from any shop to any location as before")
    void preRolloutUnchanged() {
        Workorder workorder = unstartedWorkorderAt(SHOP_B);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
        when(workorderRepository.save(any(Workorder.class))).thenAnswer(inv -> inv.getArgument(0));

        preRolloutManager();
        OperationalContextResponse response = workorderService.overrideOperationalContext(WORKORDER_ID, moveTo(SHOP_B));

        assertThat(response.getLocationId()).isEqualTo(SHOP_B);
        verify(workorderRepository).save(workorder);
    }
}
