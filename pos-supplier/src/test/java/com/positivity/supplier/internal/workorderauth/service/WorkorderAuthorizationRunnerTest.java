package com.positivity.supplier.internal.workorderauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.adapter.michelins2s.MichelinS2SWorkorderAuthCodec;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.WorkorderAuthorizationRequest;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Limit;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asking a fleet program, and what happens when it does not answer (CAP-323 #1229).
 *
 * <p>The cases below are all versions of the same question: what is recorded when the vendor's
 * position is unknown. Getting that wrong either tells a shop the fleet refused when nobody asked,
 * or lets an unanswered request disappear.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderAuthorizationRunner — unreachable is never denied (#1229)")
class WorkorderAuthorizationRunnerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000c1");

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private SupplierBaseClient baseClient;

    @Mock
    private SupplierWorkorderAuthorizationRepository authorizationRepository;

    @Mock
    private WorkorderAuthorizationPublisher publisher;

    private WorkorderAuthorizationRunner runner;

    @BeforeEach
    void setUp() {
        // A real WorkorderAuthorizationTransactions, not a mock: it holds the row-status logic these
        // tests exercise, and it is a separate bean only so @Transactional applies (self-invocation
        // from within WorkorderAuthorizationRunner used to bypass it -- SonarCloud java:S2229).
        WorkorderAuthorizationTransactions transactions = new WorkorderAuthorizationTransactions(
                authorizationRepository, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
        runner = new WorkorderAuthorizationRunner(
                profileResolver, adapterRegistry, baseClient, authorizationRepository, transactions);

        when(profileResolver.resolveBinding(any(), any())).thenReturn(binding());
        when(adapterRegistry.resolve(any(), any(), any()))
                .thenReturn(new AdapterResolution.Resolved(
                        new MichelinS2SWorkorderAuthCodec(JsonMapper.builder().build())));
        when(profileResolver.resolvePartyContext(any(), any())).thenReturn(accounts());
        when(authorizationRepository.findByVendorProfileIdAndWorkorderId(any(), any()))
                .thenReturn(Optional.empty());
        when(authorizationRepository.save(any())).thenAnswer(invocation -> {
            SupplierWorkorderAuthorizationEntity row = invocation.getArgument(0);
            if (row.getSupplierWorkorderAuthorizationId() == null) {
                row.setSupplierWorkorderAuthorizationId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"));
            }
            return row;
        });
        when(authorizationRepository.findById(any())).thenAnswer(invocation -> Optional.empty());
    }

    @Test
    @DisplayName("an unreachable vendor is recorded for review, never as a refusal")
    void unreachableVendorIsNotADenial() {
        when(baseClient.exchange(any())).thenReturn(failed(ExchangeOutcome.PRE_SEND_FAILURE, "connect timed out"));

        SupplierWorkorderAuthorizationEntity row =
                runner.requestAuthorizationRow(new SupplierRef("michelin-de"), request());

        assertThat(row.getStatus()).isEqualTo(WorkorderAuthorizationStatus.MANUAL_REVIEW);
        assertThat(row.getReviewReason()).contains("connect timed out");
        // Nothing published: a shop told "the fleet said no" would go and argue about a decision
        // that was never made.
        verify(publisher, never()).publishIfTerminal(any(), any());
    }

    @Test
    @DisplayName("an answer nobody can read is recorded for review, not guessed at")
    void unreadableAnswerIsNotADecision() {
        when(baseClient.exchange(any())).thenReturn(ok(200, "{\"id\":\"WO-1\",\"status\":\"ESCALATED\"}", Map.of()));

        SupplierWorkorderAuthorizationEntity row =
                runner.requestAuthorizationRow(new SupplierRef("michelin-de"), request());

        assertThat(row.getStatus()).isEqualTo(WorkorderAuthorizationStatus.MANUAL_REVIEW);
        assertThat(row.getReviewReason()).contains("ESCALATED");
        verify(publisher, never()).publishIfTerminal(any(), any());
    }

    @Test
    @DisplayName("a grant is recorded with its ceiling and published")
    void grantIsRecordedAndPublished() {
        when(baseClient.exchange(any()))
                .thenReturn(ok(
                        201,
                        "{\"id\":\"WO-42\",\"status\":\"granted\",\"contractId\":\"C-1\",\"authorizedAmount\":\"400,00\",\"currency\":\"EUR\"}",
                        Map.of()));

        SupplierWorkorderAuthorizationEntity row =
                runner.requestAuthorizationRow(new SupplierRef("michelin-de"), request());

        assertThat(row.getStatus()).isEqualTo(WorkorderAuthorizationStatus.GRANTED);
        assertThat(row.getVendorAuthorizationId()).isEqualTo("WO-42");
        assertThat(row.getAuthorizedAmount()).isEqualByComparingTo("400.00");
        assertThat(row.getDecidedAt()).isEqualTo(NOW);
        verify(publisher).publishIfTerminal(any(), any());
    }

    @Test
    @DisplayName("a 202 is recorded as pending with the location to come back to, and publishes nothing")
    void acceptedRequestWaits() {
        when(baseClient.exchange(any())).thenReturn(ok(202, null, Map.of("location", "/api/v1/workOrders/WO-77")));

        SupplierWorkorderAuthorizationEntity row =
                runner.requestAuthorizationRow(new SupplierRef("michelin-de"), request());

        assertThat(row.getStatus()).isEqualTo(WorkorderAuthorizationStatus.PENDING);
        assertThat(row.getPollLocation()).isEqualTo("/api/v1/workOrders/WO-77");
        verify(publisher).publishIfTerminal(any(), any());
    }

    @Test
    @DisplayName("re-requesting updates the one row, and clears the previous attempt's reasons")
    void reRequestUpdatesTheSameRow() {
        SupplierWorkorderAuthorizationEntity existing = SupplierWorkorderAuthorizationEntity.builder()
                .supplierWorkorderAuthorizationId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"))
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-de")
                .workorderId(WORKORDER_ID)
                .status(WorkorderAuthorizationStatus.MANUAL_REVIEW)
                .reviewReason("vendor exchange failed last time")
                .reasonCode("OLD")
                .reasonText("old refusal")
                .approvalStatus(WorkorderApprovalStatus.NOT_REQUESTED)
                .requestedAt(NOW.minus(Duration.ofDays(1)))
                .build();
        when(authorizationRepository.findByVendorProfileIdAndWorkorderId(any(), any()))
                .thenReturn(Optional.of(existing));
        when(baseClient.exchange(any())).thenReturn(ok(201, "{\"id\":\"WO-9\",\"status\":\"granted\"}", Map.of()));
        when(authorizationRepository.findById(any())).thenReturn(Optional.of(existing));

        SupplierWorkorderAuthorizationEntity row =
                runner.requestAuthorizationRow(new SupplierRef("michelin-de"), request());

        // One row, not two: a denial and a grant coexisting for one workorder leaves nothing to say
        // which the shop should act on.
        assertThat(row.getSupplierWorkorderAuthorizationId()).isEqualTo(existing.getSupplierWorkorderAuthorizationId());
        assertThat(row.getStatus()).isEqualTo(WorkorderAuthorizationStatus.GRANTED);
        // A stale reason left in place would read as if it described this attempt.
        assertThat(row.getReviewReason()).isNull();
        assertThat(row.getReasonCode()).isNull();
    }

    @Test
    @DisplayName("the review queue projects both stuck kinds, with the reason a person needs")
    void reviewQueueIsVisible() {
        // MANUAL_REVIEW only earns its place if something can list it. A state nothing surfaces is
        // a state nobody acts on, and these rows are work already done that will not be paid for.
        SupplierWorkorderAuthorizationEntity stuckAuthorization = SupplierWorkorderAuthorizationEntity.builder()
                .supplierWorkorderAuthorizationId(UUID.fromString("019200aa-0000-7000-8000-0000000000a2"))
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-de")
                .workorderId(WORKORDER_ID)
                .status(WorkorderAuthorizationStatus.MANUAL_REVIEW)
                .reviewReason("vendor exchange failed: PRE_SEND_FAILURE")
                .approvalStatus(WorkorderApprovalStatus.NOT_REQUESTED)
                .requestedAt(NOW)
                .build();
        SupplierWorkorderAuthorizationEntity stuckApproval = SupplierWorkorderAuthorizationEntity.builder()
                .supplierWorkorderAuthorizationId(UUID.fromString("019200aa-0000-7000-8000-0000000000a3"))
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-de")
                .workorderId(UUID.fromString("019200aa-0000-7000-8000-0000000000c2"))
                .status(WorkorderAuthorizationStatus.GRANTED)
                .approvalStatus(WorkorderApprovalStatus.MANUAL_REVIEW)
                .reviewReason("completion approval gave up after 6 attempts")
                .requestedAt(NOW)
                .build();
        when(authorizationRepository.findNeedingReview(Limit.of(100)))
                .thenReturn(List.of(stuckAuthorization, stuckApproval));

        var queue = runner.findNeedingReview(100);

        assertThat(queue).hasSize(2);
        assertThat(queue.getFirst().reviewReason()).contains("PRE_SEND_FAILURE");
        // The two kinds are told apart by approvalStatus, so an operator can see which is which.
        assertThat(queue.get(1).status()).isEqualTo("GRANTED");
        assertThat(queue.get(1).approvalStatus()).isEqualTo("MANUAL_REVIEW");
    }

    private static WorkorderAuthorizationRequest request() {
        return new WorkorderAuthorizationRequest(WORKORDER_ID, null, "ABC-123", null, null, null, null, List.of());
    }

    private static SupplierHttpResponse ok(int status, String body, Map<String, String> headers) {
        return new SupplierHttpResponse(
                ExchangeOutcome.OK, status, body, "corr-1", 1, Duration.ofMillis(10), null, headers);
    }

    private static SupplierHttpResponse failed(ExchangeOutcome outcome, String detail) {
        return new SupplierHttpResponse(outcome, null, null, "corr-1", 1, Duration.ofMillis(10), detail);
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
