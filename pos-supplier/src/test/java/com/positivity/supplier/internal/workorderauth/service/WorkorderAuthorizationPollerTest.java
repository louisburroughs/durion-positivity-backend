package com.positivity.supplier.internal.workorderauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.adapter.michelins2s.MichelinS2SWorkorderAuthCodec;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierWorkorderAuthorizationEntity;
import com.positivity.supplier.internal.enums.WorkorderApprovalStatus;
import com.positivity.supplier.internal.enums.WorkorderAuthorizationStatus;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.repository.SupplierWorkorderAuthorizationRepository;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedPartyAccounts;
import com.positivity.supplier.internal.spi.ExchangeOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Limit;
import tools.jackson.databind.json.JsonMapper;

/**
 * Collecting an answer the vendor promised to give later (CAP-323 #1229).
 *
 * <p>Without this a 202 is indistinguishable from a request that vanished. The cases below are the
 * two ways that goes wrong anyway: waiting forever on a vendor that will never resolve, and
 * following a vendor-supplied address to somewhere nobody configured.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderAuthorizationPoller — collecting a promised answer (#1229)")
class WorkorderAuthorizationPollerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000c1");
    private static final Duration PENDING_TIMEOUT = Duration.ofHours(24);

    @Mock
    private SupplierWorkorderAuthorizationRepository authorizationRepository;

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private SupplierBaseClient baseClient;

    @Mock
    private WorkorderAuthorizationRunner runner;

    @Captor
    private ArgumentCaptor<SupplierHttpRequest> requestCaptor;

    private WorkorderAuthorizationPoller poller;

    @BeforeEach
    void setUp() {
        poller = new WorkorderAuthorizationPoller(
                authorizationRepository,
                profileResolver,
                adapterRegistry,
                baseClient,
                runner,
                Clock.fixed(NOW, ZoneOffset.UTC),
                50,
                PENDING_TIMEOUT);

        when(profileResolver.resolveBinding(any(), any())).thenReturn(binding());
        when(adapterRegistry.resolve(any(), any(), any()))
                .thenReturn(new AdapterResolution.Resolved(
                        new MichelinS2SWorkorderAuthCodec(JsonMapper.builder().build())));
        when(profileResolver.resolvePartyContext(any(), any())).thenReturn(accounts());
        when(authorizationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("a vendor that never decides stops being polled and reaches a human")
    void pendingPastTheTimeoutIsEscalated() {
        // Polling forever is cheaper to write and worse: the shop waiting on this would never be
        // told to stop waiting.
        SupplierWorkorderAuthorizationEntity row = pending("/api/v1/workOrders/WO-77");
        row.setRequestedAt(NOW.minus(PENDING_TIMEOUT).minus(Duration.ofMinutes(1)));
        givenPending(row);

        poller.pollPendingAuthorizations();

        verify(runner).parkForReview(any(), contains("did not decide within"));
        verify(baseClient, never()).exchange(any());
    }

    @Test
    @DisplayName("a pending authorization with nowhere to look reaches a human rather than spinning")
    void pendingWithoutALocationIsEscalated() {
        givenPending(pending(null));

        poller.pollPendingAuthorizations();

        verify(runner).parkForReview(any(), contains("no poll location"));
        verify(baseClient, never()).exchange(any());
    }

    @Test
    @DisplayName("an absolute Location is reduced to its path, so a vendor cannot redirect a credentialled call")
    void vendorSuppliedHostIsIgnored() {
        // The binding owns the base URL. Honouring a vendor-supplied host would send an
        // authenticated request bearing a fleet token to a host nobody configured.
        givenPending(pending("https://not-the-vendor.example.com/api/v1/workOrders/WO-77"));
        when(baseClient.exchange(any()))
                .thenReturn(new SupplierHttpResponse(
                        ExchangeOutcome.OK,
                        200,
                        "{\"id\":\"WO-77\",\"status\":\"granted\"}",
                        "corr",
                        1,
                        Duration.ofMillis(5),
                        null,
                        Map.of()));

        poller.pollPendingAuthorizations();

        verify(baseClient).exchange(requestCaptor.capture());
        assertThat(requestCaptor.getValue().pathSuffix()).isEqualTo("/api/v1/workOrders/WO-77");
    }

    @Test
    @DisplayName("a decision that arrives late is applied by the same code as one that arrived at once")
    void lateDecisionIsApplied() {
        SupplierWorkorderAuthorizationEntity row = pending("/api/v1/workOrders/WO-77");
        givenPending(row);
        when(baseClient.exchange(any()))
                .thenReturn(new SupplierHttpResponse(
                        ExchangeOutcome.OK,
                        200,
                        "{\"id\":\"WO-77\",\"status\":\"denied\",\"reasonCode\":\"NO_COVER\"}",
                        "corr",
                        1,
                        Duration.ofMillis(5),
                        null,
                        Map.of()));

        poller.pollPendingAuthorizations();

        verify(runner).applyDecision(any(), any());
    }

    @Test
    @DisplayName("a failed poll leaves the authorization pending, because it says nothing about the decision")
    void failedPollChangesNothing() {
        SupplierWorkorderAuthorizationEntity row = pending("/api/v1/workOrders/WO-77");
        givenPending(row);
        when(baseClient.exchange(any()))
                .thenReturn(new SupplierHttpResponse(
                        ExchangeOutcome.PRE_SEND_FAILURE, null, null, "corr", 1, Duration.ofMillis(5), "down"));

        poller.pollPendingAuthorizations();

        assertThat(row.getStatus()).isEqualTo(WorkorderAuthorizationStatus.PENDING);
        verify(runner, never()).applyDecision(any(), any());
        verify(runner, never()).parkForReview(any(), any());
    }

    @Test
    @DisplayName("a vendor with no billing account parks the row instead of polling with an empty partnerId")
    void missingPartnerIdParksRatherThanPolls() {
        // An empty partnerId is rejected by the vendor as an unidentified caller. Polling on with
        // one would ask once a tick until the pending timeout expired a day later, and would then
        // report a misconfiguration on this side as a vendor that never decided.
        givenPending(pending("/api/v1/workOrders/WO-77"));
        SupplierAccountEntity unconfigured = new SupplierAccountEntity();
        when(profileResolver.resolvePartyContext(any(), any()))
                .thenReturn(new ResolvedPartyAccounts(unconfigured, unconfigured));

        poller.pollPendingAuthorizations();

        verify(runner).parkForReview(any(), contains("partnerId"));
        verify(baseClient, never()).exchange(any());
    }

    private void givenPending(SupplierWorkorderAuthorizationEntity row) {
        when(authorizationRepository.findAwaitingPoll(WorkorderAuthorizationStatus.PENDING, Limit.of(50)))
                .thenReturn(List.of(row));
    }

    private static SupplierWorkorderAuthorizationEntity pending(String pollLocation) {
        return SupplierWorkorderAuthorizationEntity.builder()
                .supplierWorkorderAuthorizationId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"))
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-de")
                .workorderId(WORKORDER_ID)
                .status(WorkorderAuthorizationStatus.PENDING)
                .pollLocation(pollLocation)
                .approvalStatus(WorkorderApprovalStatus.NOT_REQUESTED)
                .requestedAt(NOW.minus(Duration.ofMinutes(10)))
                .build();
    }

    private static ResolvedBinding binding() {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-de");
        profile.setEnabled(true);

        SupplierEndpointBindingEntity endpointBinding = new SupplierEndpointBindingEntity();
        endpointBinding.setId(UUID.fromString("019200aa-0000-7000-8000-0000000000d1"));
        endpointBinding.setVendorProfileId(PROFILE_ID);
        endpointBinding.setCapability(SupplierCapability.WORKORDER_AUTHORIZATION);
        endpointBinding.setProtocolFamily(ProtocolFamily.MICHELIN_S2S);
        endpointBinding.setProtocolVersion("S2S_V1");
        endpointBinding.setEnabled(true);

        return new ResolvedBinding(
                profile,
                endpointBinding,
                new SupplierAuthConfigEntity(),
                SupplierCapability.WORKORDER_AUTHORIZATION,
                ProtocolFamily.MICHELIN_S2S,
                ProtocolVersion.S2S_V1);
    }

    private static ResolvedPartyAccounts accounts() {
        SupplierAccountEntity billing = new SupplierAccountEntity();
        billing.setAccountNumber("SP01234");
        return new ResolvedPartyAccounts(billing, billing);
    }
}
