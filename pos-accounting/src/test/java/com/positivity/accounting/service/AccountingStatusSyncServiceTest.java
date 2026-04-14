package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.AccountingStatusChangedEvent;
import com.positivity.accounting.internal.dto.AccountingStatusView;
import com.positivity.accounting.internal.entity.AccountingStatusSyncAudit;
import com.positivity.accounting.internal.entity.InvoiceStatusView;
import com.positivity.accounting.internal.enums.AccountingStatus;
import com.positivity.accounting.internal.repository.AccountingStatusSyncAuditRepository;
import com.positivity.accounting.internal.repository.InvoiceStatusViewRepository;
import com.positivity.accounting.internal.service.AccountingStatusSyncServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Unit tests for {@link AccountingStatusSyncServiceImpl} covering
 * AC1–AC8 of CAP-251 Story #5 — Reconcile POS Status with Accounting
 * Authoritative Status.
 *
 * <p>
 * Uses Mockito for dependencies; SecurityContextHolder is set up manually for
 * permission-gating assertions (AC3).
 * </p>
 *
 * Issue: CAP-251 #5
 */
@SpringJUnitConfig(AccountingStatusSyncServiceTest.TestConfig.class)
@ActiveProfiles("accounting-status-sync-unit")
@DisplayName("AccountingStatusSyncService Unit Tests — CAP-251 #5")
class AccountingStatusSyncServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @Autowired
    private InvoiceStatusViewRepository statusViewRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private AccountingStatusSyncAuditRepository auditRepository;

    @Autowired
    private AccountingStatusSyncService service;

    private InvoiceStatusView existingStatusView;

    @BeforeEach
    void setUp() {
        reset(statusViewRepository, idempotencyService, auditRepository);
        existingStatusView = new InvoiceStatusView();
        existingStatusView.setInvoiceId(INVOICE_ID);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC1 — Accounting status event received and processed
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC1 — Status event processed: accountingStatus and postingReference updated")
    class AccountingStatusEventProcessed {

        @Test
        @DisplayName("sets accountingStatus=POSTED, updates accountingStatusUpdatedAt and postingReference")
        void whenPostedEventReceived_thenStatusViewIsUpdatedWithPostingReference() {
            // Arrange
            // Issue CAP-251 #5: mock existing invoice record; impl will load and update it
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));

            AccountingStatusChangedEvent event = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.POSTED)
                    .timestamp(Instant.now(TEST_CLOCK))
                    .postingReference("GL-REF-001")
                    .eventId("evt-posted-001")
                    .build();

            // Act & Assert — RED: stub throws UnsupportedOperationException
            // GREEN: must update InvoiceStatusView.accountingStatus=POSTED,
            // accountingStatusUpdatedAt=event.timestamp, postingReference="GL-REF-001"
            assertThatCode(() -> service.processStatusEvent(event)).doesNotThrowAnyException();

            assertThat(existingStatusView.getAccountingStatus()).isEqualTo(AccountingStatus.POSTED);
            assertThat(existingStatusView.getPostingReference()).isEqualTo(event.getPostingReference());
            verify(statusViewRepository).save(existingStatusView);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC2 — Backward transition blocked (ordering protection)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC2 — Backward transition: POSTED → PENDING_POSTING is silently ignored")
    class BackwardTransitionBlocked {

        @Test
        @DisplayName("silently ignores backward transition: POSTED → PENDING_POSTING (no-op + warn)")
        void whenBackwardTransitionFromPosted_thenNoExceptionThrownAndSaveNotCalled() {
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));
            existingStatusView.setAccountingStatus(AccountingStatus.POSTED);
            when(idempotencyService.isKeyProcessed("evt-backward-001")).thenReturn(false);

            AccountingStatusChangedEvent backwardEvent = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.PENDING_POSTING)
                    .timestamp(Instant.now(TEST_CLOCK))
                    .postingReference(null)
                    .eventId("evt-backward-001")
                    .discrepancyReason(null)
                    .build();

            assertThatNoException().isThrownBy(() -> service.processStatusEvent(backwardEvent));
            verify(statusViewRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC4b: DISPUTED is interruptible — DISPUTED → PENDING_POSTING is allowed (not backward)")
        void whenTransitionFromDisputed_toPendingPosting_thenAllowed() {
            existingStatusView.setAccountingStatus(AccountingStatus.DISPUTED);
            AccountingStatusChangedEvent event = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.PENDING_POSTING)
                    .timestamp(Instant.now())
                    .postingReference("GL-REDISPATCH-001")
                    .eventId("evt-disputed-back")
                    .build();
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));
            when(idempotencyService.isKeyProcessed("evt-disputed-back")).thenReturn(false);

            assertThatNoException().isThrownBy(() -> service.processStatusEvent(event));
            verify(statusViewRepository).save(existingStatusView);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC3 — Duplicate event is idempotent (no-op)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Idempotency — Duplicate event: same eventId is a no-op")
    class DuplicateEventIdempotent {

        @Test
        @DisplayName("does not update status or save when eventId was previously processed")
        void whenDuplicateEventIdReceived_thenRepositorySaveIsNotCalled() {
            // Arrange
            // Issue CAP-251 #5: idempotency service reports this event was already
            // processed
            when(idempotencyService.isKeyProcessed("evt-1")).thenReturn(true);

            AccountingStatusChangedEvent duplicateEvent = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.POSTED)
                    .timestamp(Instant.now(TEST_CLOCK))
                    .postingReference("GL-REF-001")
                    .eventId("evt-1")
                    .build();

            // Act — RED: stub throws UnsupportedOperationException
            // GREEN: must return without calling statusViewRepository.save()
            service.processStatusEvent(duplicateEvent);

            // Assert
            verify(statusViewRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC4 — Stale indicator: accountingStatusUpdatedAt 2 hours ago → stale=true
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC6 — Stale indicator when status is 2 hours old")
    class StaleIndicatorWhenOld {

        @Test
        @DisplayName("returns stale=true when accountingStatusUpdatedAt is 2 hours before now")
        void whenAccountingStatusUpdatedAtIsTwoHoursAgo_thenStaleIsTrue() {
            // Arrange
            // Issue CAP-251 #5: InvoiceStatusView will expose accountingStatusUpdatedAt in
            // GREEN
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));
            setViewerSecurityContext();
            existingStatusView.setAccountingStatusUpdatedAt(
                    Instant.now(TEST_CLOCK).minus(2, ChronoUnit.HOURS));

            // Act — RED: stub throws UnsupportedOperationException
            // GREEN: must return AccountingStatusView with stale=true
            AccountingStatusView result = service.getInvoiceAccountingStatus(INVOICE_ID);

            assertThat(result.isStale()).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC5 — Non-stale indicator: accountingStatusUpdatedAt 30 min ago → stale=false
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC6 — Non-stale indicator when status is 30 minutes old")
    class NonStaleIndicatorWhenRecent {

        @Test
        @DisplayName("returns stale=false when accountingStatusUpdatedAt is 30 minutes before now")
        void whenAccountingStatusUpdatedAtIsThirtyMinutesAgo_thenStaleIsFalse() {
            // Arrange
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));
            setViewerSecurityContext();
            existingStatusView.setAccountingStatusUpdatedAt(
                    Instant.now(TEST_CLOCK).minus(30, ChronoUnit.MINUTES));

            // Act — RED: stub throws UnsupportedOperationException
            // GREEN: must return AccountingStatusView with stale=false
            AccountingStatusView result = service.getInvoiceAccountingStatus(INVOICE_ID);

            assertThat(result.isStale()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC6 — VIEW_ACCOUNTING_STATUS permission gating
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC3 — VIEW_ACCOUNTING_STATUS permission gating")
    class PermissionGating {

        @Test
        @DisplayName("throws AccessDeniedException when caller lacks VIEW_ACCOUNTING_STATUS authority")
        void whenCallerLacksViewAccountingStatusAuthority_thenAccessDeniedException() {
            // Arrange — Issue CAP-251 #5: caller only has ROLE_USER, not
            // VIEW_ACCOUNTING_STATUS
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                    "user", "password", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            SecurityContextHolder.setContext(ctx);

            // Act & Assert — RED: stub throws UnsupportedOperationException
            // GREEN: must throw AccessDeniedException for callers without
            // VIEW_ACCOUNTING_STATUS
            assertThatThrownBy(() -> service.getInvoiceAccountingStatus(INVOICE_ID))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("allows access when caller has VIEW_ACCOUNTING_STATUS authority")
        void whenCallerHasViewAccountingStatusAuthority_thenNoAccessDeniedException() {
            // Arrange — no repository stub needed; impl throws UOE before reaching repo in
            // RED
            setViewerSecurityContext();

            // Act & Assert — RED: stub throws UnsupportedOperationException (not
            // AccessDeniedException)
            // GREEN: must not throw AccessDeniedException
            assertThatThrownBy(() -> service.getInvoiceAccountingStatus(INVOICE_ID))
                    .isNotInstanceOf(AccessDeniedException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC7 — Archived invoice continues to receive status sync
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Archived Invoice — archived invoice receives status synchronization")
    class ArchivedInvoiceStatusSync {

        @Test
        @DisplayName("updates accountingStatus for archived invoice without error")
        void whenArchivedInvoiceReceivesStatusEvent_thenStatusUpdatedSuccessfully() {
            // Arrange
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));

            AccountingStatusChangedEvent event = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.POSTED)
                    .timestamp(Instant.now(TEST_CLOCK))
                    .postingReference("GL-ARCH-001")
                    .eventId("evt-archived-001")
                    .build();

            // Act & Assert — RED: stub throws UnsupportedOperationException
            // GREEN: must not throw; archived invoices are eligible for accounting status
            // sync
            assertThatCode(() -> service.processStatusEvent(event)).doesNotThrowAnyException();

            verify(statusViewRepository).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC8 — All v1 enum values accepted and stored without error
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC8 — All v1 AccountingStatus enum values are accepted")
    class AllV1EnumValuesAccepted {

        static List<AccountingStatus> v1StatusValues() {
            return Arrays.asList(
                    AccountingStatus.PENDING_POSTING,
                    AccountingStatus.POSTED,
                    AccountingStatus.RECONCILED,
                    AccountingStatus.REJECTED,
                    AccountingStatus.REVERSED,
                    AccountingStatus.VOIDED,
                    AccountingStatus.ON_HOLD,
                    AccountingStatus.DISPUTED);
        }

        @ParameterizedTest(name = "status={0}")
        @MethodSource("v1StatusValues")
        @DisplayName("processStatusEvent does not reject v1 status value")
        void whenV1StatusReceived_thenEventIsProcessedWithoutEnumMappingError(AccountingStatus status) {
            // Arrange
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));

            AccountingStatusChangedEvent event = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(status)
                    .timestamp(Instant.now(TEST_CLOCK))
                    .postingReference("GL-V1-001")
                    .eventId("evt-v1-" + status.name())
                    .build();

            // Act & Assert — RED: stub throws UnsupportedOperationException
            // GREEN: must accept all 8 v1 enum values without throwing
            assertThatCode(() -> service.processStatusEvent(event)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AccountingStatus enum contains all 8 v1 required values")
        void accountingStatusEnumContainsAllRequiredV1Values() {
            // This test passes immediately in RED — validates the enum scaffold is correct
            var values = java.util.EnumSet.allOf(AccountingStatus.class);
            assertThat(values)
                    .hasSize(8)
                    .containsExactlyInAnyOrder(
                            AccountingStatus.PENDING_POSTING,
                            AccountingStatus.POSTED,
                            AccountingStatus.RECONCILED,
                            AccountingStatus.REJECTED,
                            AccountingStatus.REVERSED,
                            AccountingStatus.VOIDED,
                            AccountingStatus.ON_HOLD,
                            AccountingStatus.DISPUTED);
        }
    }

    @Nested
    @DisplayName("AC4 — Discrepancy tracking: REJECTED sets discrepancyDetected=true")
    class AC4_DiscrepancyTracking {

        @Test
        @DisplayName("when REJECTED event arrives, discrepancyDetected=true and discrepancyReason set")
        void whenRejectedEvent_thenDiscrepancyDetectedTrueAndReasonSet() {
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));
            when(idempotencyService.isKeyProcessed("evt-rejected-001")).thenReturn(false);

            AccountingStatusChangedEvent event = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.REJECTED)
                    .timestamp(Instant.now(TEST_CLOCK))
                    .eventId("evt-rejected-001")
                    .discrepancyReason("GL validation failed: missing cost centre")
                    .build();

            service.processStatusEvent(event);

            assertThat(existingStatusView.isDiscrepancyDetected()).isTrue();
            assertThat(existingStatusView.getDiscrepancyReason())
                    .isEqualTo("GL validation failed: missing cost centre");
        }

        @Test
        @DisplayName("when non-REJECTED event arrives, discrepancyDetected=false and discrepancyReason null")
        void whenNonRejectedEvent_thenDiscrepancyDetectedFalseAndReasonNull() {
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));
            when(idempotencyService.isKeyProcessed("evt-posted-disc-001")).thenReturn(false);

            AccountingStatusChangedEvent event = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.POSTED)
                    .timestamp(Instant.now(TEST_CLOCK))
                    .eventId("evt-posted-disc-001")
                    .postingReference("GL-001")
                    .discrepancyReason(null)
                    .build();

            service.processStatusEvent(event);

            assertThat(existingStatusView.isDiscrepancyDetected()).isFalse();
            assertThat(existingStatusView.getDiscrepancyReason()).isNull();
        }
    }

    @Nested
    @DisplayName("AC5 — Audit trail: AccountingStatusSyncAudit persisted per processStatusEvent")
    class AC5_AuditTrail {

        @Test
        @DisplayName("persists an AccountingStatusSyncAudit record with required fields populated")
        void whenEventProcessed_thenAuditRecordIsSaved() {
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));
            when(idempotencyService.isKeyProcessed("evt-audit-001")).thenReturn(false);

            AccountingStatusChangedEvent event = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.POSTED)
                    .timestamp(Instant.now(TEST_CLOCK))
                    .eventId("evt-audit-001")
                    .postingReference("GL-AUDIT-001")
                    .discrepancyReason(null)
                    .build();

            service.processStatusEvent(event);

            ArgumentCaptor<AccountingStatusSyncAudit> captor = ArgumentCaptor.forClass(AccountingStatusSyncAudit.class);
            verify(auditRepository).save(captor.capture());
            AccountingStatusSyncAudit audit = captor.getValue();
            assertThat(audit.getInvoiceId()).isEqualTo(INVOICE_ID);
            assertThat(audit.getNewStatus()).isEqualTo(AccountingStatus.POSTED);
            assertThat(audit.getEventId()).isEqualTo("evt-audit-001");
            assertThat(audit.getSyncedAt()).isNotNull();
            assertThat(audit.getSyncSource()).isEqualTo("pos-accounting");
            assertThat(audit.getLatencyMs()).isNotNull();
            assertThat(audit.getOldStatus()).isNull(); // existingStatusView has no prior accountingStatus
        }
    }

    @Nested
    @DisplayName("AC7 — Out-of-order event guard: stale event timestamp is ignored")
    class AC7_OutOfOrderGuard {

        @Test
        @DisplayName("when event.timestamp < statusView.accountingStatusUpdatedAt, update is NOT applied")
        void whenEventTimestampIsBeforeCurrentUpdatedAt_thenUpdateIsIgnored() {
            Instant currentUpdatedAt = Instant.now(TEST_CLOCK);
            existingStatusView.setAccountingStatusUpdatedAt(currentUpdatedAt);
            existingStatusView.setAccountingStatus(AccountingStatus.POSTED);

            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));
            when(idempotencyService.isKeyProcessed("evt-stale-001")).thenReturn(false);

            AccountingStatusChangedEvent staleEvent = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.RECONCILED)
                    .timestamp(currentUpdatedAt.minus(5, ChronoUnit.MINUTES))
                    .eventId("evt-stale-001")
                    .discrepancyReason(null)
                    .build();

            service.processStatusEvent(staleEvent);

            assertThat(existingStatusView.getAccountingStatus()).isEqualTo(AccountingStatus.POSTED);
            verify(statusViewRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getInvoiceAccountingStatusDetail — VIEW_ACCOUNTING_DETAIL authority
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getInvoiceAccountingStatusDetail — VIEW_ACCOUNTING_DETAIL authority required")
    class GetInvoiceAccountingStatusDetail {

        @Test
        @DisplayName("returns stale=false when accountingStatusUpdatedAt is within 1 hour")
        void whenRecentlyUpdated_thenStaleIsFalse() {
            setDetailViewerSecurityContext();
            existingStatusView.setAccountingStatusUpdatedAt(
                    Instant.now(TEST_CLOCK).minus(30, ChronoUnit.MINUTES));
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));

            AccountingStatusView result = service.getInvoiceAccountingStatusDetail(INVOICE_ID);

            assertThat(result.isStale()).isFalse();
            assertThat(result.getInvoiceId()).isEqualTo(INVOICE_ID);
        }

        @Test
        @DisplayName("throws EntityNotFoundException when invoice not found")
        void whenInvoiceNotFound_thenEntityNotFoundException() {
            setDetailViewerSecurityContext();
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getInvoiceAccountingStatusDetail(INVOICE_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // refreshAccountingStatus — REFRESH_ACCOUNTING_STATUS authority
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refreshAccountingStatus — REFRESH_ACCOUNTING_STATUS authority required")
    class RefreshAccountingStatus {

        @Test
        @DisplayName("returns current status view for a known invoice")
        void whenInvoiceFound_thenReturnsStatusView() {
            setRefreshStatusSecurityContext();
            existingStatusView.setAccountingStatus(AccountingStatus.POSTED);
            existingStatusView.setAccountingStatusUpdatedAt(
                    Instant.now(TEST_CLOCK).minus(10, ChronoUnit.MINUTES));
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));

            AccountingStatusView result = service.refreshAccountingStatus(INVOICE_ID);

            assertThat(result.getAccountingStatus()).isEqualTo(AccountingStatus.POSTED);
            assertThat(result.isStale()).isFalse();
            assertThat(result.getInvoiceId()).isEqualTo(INVOICE_ID);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // recoverProcessStatusEvent — IllegalStateException after retry exhaustion
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("recoverProcessStatusEvent — throws IllegalStateException after retry exhaustion")
    class RecoverProcessStatusEvent {

        @Test
        @DisplayName("throws IllegalStateException wrapping the original cause")
        void whenRetryExhausted_thenIllegalStateExceptionWithOriginalCause() {
            AccountingStatusChangedEvent event = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.POSTED)
                    .timestamp(Instant.now(TEST_CLOCK))
                    .eventId("evt-recover-001")
                    .build();
            RuntimeException cause = new RuntimeException("DB unavailable");

            // Instantiate directly to bypass AOP proxy and invoke @Recover method
            AccountingStatusSyncServiceImpl impl = new AccountingStatusSyncServiceImpl(
                    statusViewRepository, idempotencyService, TEST_CLOCK, auditRepository);

            assertThatThrownBy(() -> impl.recoverProcessStatusEvent(cause, event))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("evt-recover-001")
                    .hasCause(cause);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // processStatusEvent — null timestamp: latencyMs is null in audit record
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("processStatusEvent — null event timestamp: latencyMs is null in audit")
    class NullTimestampAudit {

        @Test
        @DisplayName("when event.timestamp is null, audit latencyMs is null")
        void whenEventTimestampIsNull_thenAuditLatencyMsIsNull() {
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.of(existingStatusView));
            when(idempotencyService.isKeyProcessed("evt-null-ts-001")).thenReturn(false);

            AccountingStatusChangedEvent event = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.ON_HOLD)
                    .timestamp(null)
                    .eventId("evt-null-ts-001")
                    .build();

            service.processStatusEvent(event);

            ArgumentCaptor<AccountingStatusSyncAudit> captor = ArgumentCaptor.forClass(AccountingStatusSyncAudit.class);
            verify(auditRepository).save(captor.capture());
            assertThat(captor.getValue().getLatencyMs()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // processStatusEvent — invoice not found: EntityNotFoundException
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("processStatusEvent — invoice not found: EntityNotFoundException thrown")
    class ProcessStatusEventInvoiceNotFound {

        @Test
        @DisplayName("throws EntityNotFoundException when no InvoiceStatusView exists for invoiceId")
        void whenInvoiceNotFound_thenEntityNotFoundException() {
            when(statusViewRepository.findByInvoiceId(INVOICE_ID)).thenReturn(Optional.empty());
            when(idempotencyService.isKeyProcessed("evt-notfound-001")).thenReturn(false);

            AccountingStatusChangedEvent event = AccountingStatusChangedEvent.builder()
                    .invoiceId(INVOICE_ID)
                    .newStatus(AccountingStatus.POSTED)
                    .timestamp(Instant.now(TEST_CLOCK))
                    .eventId("evt-notfound-001")
                    .build();

            assertThatThrownBy(() -> service.processStatusEvent(event)).isInstanceOf(EntityNotFoundException.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity(prePostEnabled = true)
    @Profile("accounting-status-sync-unit")
    static class TestConfig {
        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }

        @Bean
        InvoiceStatusViewRepository statusViewRepository() {
            return mock(InvoiceStatusViewRepository.class);
        }

        @Bean
        IdempotencyService idempotencyService() {
            return mock(IdempotencyService.class);
        }

        @Bean
        AccountingStatusSyncAuditRepository auditRepository() {
            return mock(AccountingStatusSyncAuditRepository.class);
        }

        @Bean
        AccountingStatusSyncService accountingStatusSyncService(
                InvoiceStatusViewRepository statusViewRepository,
                IdempotencyService idempotencyService,
                Clock clock,
                AccountingStatusSyncAuditRepository auditRepository) {
            return new AccountingStatusSyncServiceImpl(
                    statusViewRepository, idempotencyService, clock, auditRepository);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets up a security context with {@code VIEW_ACCOUNTING_STATUS} authority,
     * as required by AC3 (permission gating) and AC6 (stale indicator) test
     * scenarios.
     */
    private void setViewerSecurityContext() {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                "viewer", "password", List.of(new SimpleGrantedAuthority("VIEW_ACCOUNTING_STATUS"))));
        SecurityContextHolder.setContext(ctx);
    }

    /**
     * Sets up a security context with {@code VIEW_ACCOUNTING_DETAIL} authority,
     * as required by {@code getInvoiceAccountingStatusDetail} permission gate.
     */
    private void setDetailViewerSecurityContext() {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                "detail-viewer", "password", List.of(new SimpleGrantedAuthority("VIEW_ACCOUNTING_DETAIL"))));
        SecurityContextHolder.setContext(ctx);
    }

    /**
     * Sets up a security context with {@code REFRESH_ACCOUNTING_STATUS} authority,
     * as required by {@code refreshAccountingStatus} permission gate.
     */
    private void setRefreshStatusSecurityContext() {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                "refresher", "password", List.of(new SimpleGrantedAuthority("REFRESH_ACCOUNTING_STATUS"))));
        SecurityContextHolder.setContext(ctx);
    }
}
