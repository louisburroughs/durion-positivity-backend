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
 * Telling the fleet the work is done (CAP-323 #1229).
 *
 * <p>This is the step that gets a shop paid, so the cases that matter are the ones where it stops:
 * an approval that can never succeed must reach a human quickly, and one that merely failed must be
 * retried a bounded number of times and then also reach a human. Retrying silently forever is the
 * failure mode that looks like success.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderCompletionApprover — the step that gets a shop paid (#1229)")
class WorkorderCompletionApproverTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000c1");
    private static final int MAX_ATTEMPTS = 3;

    @Mock
    private SupplierWorkorderAuthorizationRepository authorizationRepository;

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private SupplierBaseClient baseClient;

    private WorkorderCompletionApprover approver;

    @BeforeEach
    void setUp() {
        approver = new WorkorderCompletionApprover(
                authorizationRepository,
                profileResolver,
                adapterRegistry,
                baseClient,
                Clock.fixed(NOW, ZoneOffset.UTC),
                50,
                MAX_ATTEMPTS);

        when(profileResolver.resolveBinding(any(), any())).thenReturn(binding());
        when(adapterRegistry.resolve(any(), any(), any()))
                .thenReturn(new AdapterResolution.Resolved(
                        new MichelinS2SWorkorderAuthCodec(JsonMapper.builder().build())));
        when(profileResolver.resolvePartyContext(any(), any())).thenReturn(accounts());
        when(authorizationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("a completed fleet job is queued for sign-off")
    void completionQueuesApproval() {
        SupplierWorkorderAuthorizationEntity row = granted();
        when(authorizationRepository.findByWorkorderIdOrderByRequestedAtDesc(WORKORDER_ID))
                .thenReturn(List.of(row));

        Optional<SupplierWorkorderAuthorizationEntity> queued = approver.queueApproval(WORKORDER_ID);

        assertThat(queued).isPresent();
        assertThat(queued.get().getApprovalStatus()).isEqualTo(WorkorderApprovalStatus.PENDING);
    }

    @Test
    @DisplayName("an ordinary retail job queues nothing")
    void nonFleetCompletionQueuesNothing() {
        // The common case by a wide margin. It must cost one indexed read and no vendor call.
        when(authorizationRepository.findByWorkorderIdOrderByRequestedAtDesc(WORKORDER_ID))
                .thenReturn(List.of());

        assertThat(approver.queueApproval(WORKORDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("a redelivered completion does not reopen an approval already accepted")
    void redeliveredCompletionDoesNotReopenAnApproval() {
        SupplierWorkorderAuthorizationEntity row = granted();
        row.setApprovalStatus(WorkorderApprovalStatus.APPROVED);
        when(authorizationRepository.findByWorkorderIdOrderByRequestedAtDesc(WORKORDER_ID))
                .thenReturn(List.of(row));

        assertThat(approver.queueApproval(WORKORDER_ID))
                .get()
                .extracting(SupplierWorkorderAuthorizationEntity::getApprovalStatus)
                .isEqualTo(WorkorderApprovalStatus.APPROVED);
    }

    @Test
    @DisplayName("an authorization that never got an id goes to a human at once, without burning attempts")
    void grantWithoutAnIdCannotBeApproved() {
        // No amount of retrying invents an identifier. Spending the retry budget on it would only
        // delay the moment somebody finds out.
        SupplierWorkorderAuthorizationEntity row = granted();
        row.setVendorAuthorizationId(null);
        row.setApprovalStatus(WorkorderApprovalStatus.PENDING);
        when(authorizationRepository.findByApprovalStatusOrderByUpdatedAtAsc(
                        WorkorderApprovalStatus.PENDING, Limit.of(50)))
                .thenReturn(List.of(row));
        when(authorizationRepository.findById(any())).thenReturn(Optional.of(row));

        approver.approveOutstanding();

        assertThat(row.getApprovalStatus()).isEqualTo(WorkorderApprovalStatus.MANUAL_REVIEW);
        assertThat(row.getApprovalAttempts()).isZero();
        verify(baseClient, never()).exchange(any());
    }

    @Test
    @DisplayName("a failed approval is retried, and escalates once the budget is spent")
    void failedApprovalEscalatesAfterTheBudget() {
        SupplierWorkorderAuthorizationEntity row = granted();
        row.setApprovalStatus(WorkorderApprovalStatus.PENDING);
        when(authorizationRepository.findByApprovalStatusOrderByUpdatedAtAsc(
                        WorkorderApprovalStatus.PENDING, Limit.of(50)))
                .thenReturn(List.of(row));
        when(authorizationRepository.findById(any())).thenReturn(Optional.of(row));
        when(baseClient.exchange(any()))
                .thenReturn(new SupplierHttpResponse(
                        ExchangeOutcome.PRE_SEND_FAILURE, null, null, "corr", 1, Duration.ofMillis(5), "vendor down"));

        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            approver.approveOutstanding();
            assertThat(row.getApprovalStatus())
                    .as("still retrying after attempt %s", attempt)
                    .isEqualTo(WorkorderApprovalStatus.PENDING);
        }

        approver.approveOutstanding();

        // An approval that retried forever would look exactly like one that was working.
        assertThat(row.getApprovalStatus()).isEqualTo(WorkorderApprovalStatus.MANUAL_REVIEW);
        assertThat(row.getReviewReason()).contains("vendor down");
    }

    @Test
    @DisplayName("a vendor that accepts the sign-off closes the row")
    void acceptedApprovalIsRecorded() {
        SupplierWorkorderAuthorizationEntity row = granted();
        row.setApprovalStatus(WorkorderApprovalStatus.PENDING);
        when(authorizationRepository.findByApprovalStatusOrderByUpdatedAtAsc(
                        WorkorderApprovalStatus.PENDING, Limit.of(50)))
                .thenReturn(List.of(row));
        when(authorizationRepository.findById(any())).thenReturn(Optional.of(row));
        when(baseClient.exchange(any()))
                .thenReturn(
                        new SupplierHttpResponse(ExchangeOutcome.OK, 200, "{}", "corr", 1, Duration.ofMillis(5), null));

        approver.approveOutstanding();

        assertThat(row.getApprovalStatus()).isEqualTo(WorkorderApprovalStatus.APPROVED);
        assertThat(row.getApprovedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a vendor with no billing account parks at once, without spending the retry budget")
    void missingPartnerIdParksWithoutBurningAttempts() {
        // No number of attempts supplies a missing account number. Retrying would escalate a
        // money-critical approval with a review reason blaming the vendor for a defect on this side.
        SupplierWorkorderAuthorizationEntity row = granted();
        row.setApprovalStatus(WorkorderApprovalStatus.PENDING);
        when(authorizationRepository.findByApprovalStatusOrderByUpdatedAtAsc(
                        WorkorderApprovalStatus.PENDING, Limit.of(50)))
                .thenReturn(List.of(row));
        when(authorizationRepository.findById(any())).thenReturn(Optional.of(row));
        SupplierAccountEntity unconfigured = new SupplierAccountEntity();
        when(profileResolver.resolvePartyContext(any(), any()))
                .thenReturn(new ResolvedPartyAccounts(unconfigured, unconfigured));

        approver.approveOutstanding();

        assertThat(row.getApprovalStatus()).isEqualTo(WorkorderApprovalStatus.MANUAL_REVIEW);
        assertThat(row.getApprovalAttempts()).isZero();
        assertThat(row.getReviewReason()).contains("partnerId");
        verify(baseClient, never()).exchange(any());
    }

    private static SupplierWorkorderAuthorizationEntity granted() {
        return SupplierWorkorderAuthorizationEntity.builder()
                .supplierWorkorderAuthorizationId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"))
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-de")
                .workorderId(WORKORDER_ID)
                .status(WorkorderAuthorizationStatus.GRANTED)
                .vendorAuthorizationId("WO-42")
                .approvalStatus(WorkorderApprovalStatus.NOT_REQUESTED)
                .approvalAttempts(0)
                .requestedAt(NOW.minus(Duration.ofHours(3)))
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
