package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationRow;
import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationsPage;
import com.positivity.accounting.internal.dto.AutomaticWorkItem;
import com.positivity.accounting.internal.dto.AutomaticWorkItemsResponse;
import com.positivity.accounting.internal.entity.ProcessorSettlementLine;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.ApplicationSource;
import com.positivity.accounting.internal.enums.AutomaticWorkItemKind;
import com.positivity.accounting.internal.enums.SettlementLineMatchStatus;
import com.positivity.accounting.internal.enums.SettlementLineType;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.WorkItemsCursorInvalidException;
import com.positivity.accounting.internal.repository.ProcessorSettlementLineRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Done automatically (#2515, CAP:550 S19; SPEC-accounting-workspace §7.1; AC 11; rulings 8 and 9 of 2026-10-17): the
 * payment applications with UNDO for the reverse permission only, the settlement lines and system-approved bills
 * without actions, the default and the bounds of {@code since}, and the cursor bound to it.
 */
@DisplayName("WorkItemAutomaticService (#2515)")
class WorkItemAutomaticServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final Instant START_OF_TODAY = Instant.parse("2026-10-17T00:00:00Z");

    private final AutomaticPaymentApplicationQueryService applications = mock();
    private final ProcessorSettlementLineRepository settlementLines = mock();
    private final VendorBillRepository vendorBills = mock();
    private final AccountingCalendarZoneResolver zoneResolver = mock();

    private final List<AutomaticPaymentApplicationRow> rows = new ArrayList<>();
    private WorkItemAutomaticServiceImpl service;

    @BeforeEach
    void setUp() {
        when(zoneResolver.today()).thenReturn(TODAY);
        when(zoneResolver.zone()).thenReturn(ZoneId.of("UTC"));
        when(zoneResolver.postingDate(any(Instant.class)))
                .thenAnswer(invocation -> LocalDate.ofInstant(invocation.getArgument(0), ZoneOffset.UTC));
        when(applications.listAutomatic(any(), anyInt(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> {
                    boolean canUndo = invocation.getArgument(3);
                    List<AutomaticPaymentApplicationRow> served = rows.stream()
                            .map(row -> AutomaticPaymentApplicationRow.builder()
                                    .paymentApplicationId(row.getPaymentApplicationId())
                                    .paymentId(row.getPaymentId())
                                    .invoiceId(row.getInvoiceId())
                                    .source(row.getSource())
                                    .appliedAt(row.getAppliedAt())
                                    .appliedAmount(row.getAppliedAmount())
                                    .invoiceNumber(row.getInvoiceNumber())
                                    .customerDisplayName(row.getCustomerDisplayName())
                                    .customerReference(row.getCustomerReference())
                                    .reversed(row.isReversed())
                                    .actions(!row.isReversed() && canUndo ? List.of("UNDO") : List.of())
                                    .build())
                            .toList();
                    return AutomaticPaymentApplicationsPage.builder()
                            .items(served)
                            .totalPages(1)
                            .build();
                });
        when(settlementLines.findByMatchStatusAndUpdatedAtGreaterThanEqual(any(), any()))
                .thenReturn(List.of());
        when(vendorBills.findByApprovedByKindAndApprovedAtGreaterThanEqualOrderByApprovedAtAscVendorBillIdAsc(
                        any(), any()))
                .thenReturn(List.of());
        service = new WorkItemAutomaticServiceImpl(
                applications,
                settlementLines,
                vendorBills,
                new WorkItemPaging("test-secret"),
                zoneResolver,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                "someone",
                "n/a",
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "someone"));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private AutomaticPaymentApplicationRow application(String appliedAt, boolean reversed) {
        AutomaticPaymentApplicationRow row = AutomaticPaymentApplicationRow.builder()
                .paymentApplicationId(UUID.randomUUID())
                .paymentId(UUID.randomUUID())
                .invoiceId(UUID.randomUUID())
                .source(ApplicationSource.PAYMENT_SETTLED)
                .appliedAt(Instant.parse(appliedAt))
                .appliedAmount(new BigDecimal("250.00"))
                .invoiceNumber("INV-5")
                .customerDisplayName("Pat Customer")
                .customerReference("REF-5")
                .reversed(reversed)
                .build();
        rows.add(row);
        return row;
    }

    // ---- the three kinds ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC11: a payment applied automatically today carries UNDO for a holder of payment:reverse only")
    void undoForTheReverseHolder() {
        AutomaticPaymentApplicationRow row = application("2026-10-17T09:00:00Z", false);

        signIn(AccountingPermissions.PAYMENT_REVERSE);
        AutomaticWorkItem withUndo = service.list(null, null, null).items().getFirst();
        assertThat(withUndo.itemId()).isEqualTo("PAYMENT_APPLIED_AUTOMATICALLY:" + row.getPaymentApplicationId());
        assertThat(withUndo.kind()).isEqualTo(AutomaticWorkItemKind.PAYMENT_APPLIED_AUTOMATICALLY);
        assertThat(withUndo.occurredOn()).isEqualTo(TODAY);
        assertThat(withUndo.amount()).isEqualByComparingTo("250.00");
        assertThat(withUndo.status()).isEqualTo("APPLIED");
        assertThat(withUndo.references())
                .containsEntry("customer", "Pat Customer")
                .containsEntry("invoiceNumber", "INV-5")
                .containsEntry("paymentReference", "REF-5");
        assertThat(withUndo.actions()).singleElement().satisfies(undo -> {
            assertThat(undo.action()).isEqualTo("UNDO");
            assertThat(undo.allowed()).isTrue();
        });
        assertThat(withUndo.routing()).containsKeys("paymentApplicationId", "paymentId", "invoiceId");

        signIn(AccountingPermissions.PAYMENT_APPLY);
        assertThat(service.list(null, null, null).items().getFirst().actions()).isEmpty();
    }

    @Test
    @DisplayName("a reversed application is listed flagged REVERSED and without UNDO")
    void reversedHasNoUndo() {
        application("2026-10-17T09:00:00Z", true);

        signIn(AccountingPermissions.PAYMENT_REVERSE);
        AutomaticWorkItem item = service.list(null, null, null).items().getFirst();

        assertThat(item.status()).isEqualTo("REVERSED");
        assertThat(item.actions()).isEmpty();
    }

    @Test
    @DisplayName("a matched settlement line and a system-approved bill are listed without actions")
    void settlementLineAndSystemBill() {
        ProcessorSettlementLine line = ProcessorSettlementLine.builder()
                .lineId(UUID.randomUUID())
                .settlementId("SET-9")
                .paymentReference("PAYREF-9")
                .lineType(SettlementLineType.CHARGE)
                .netAmount(new BigDecimal("97.10"))
                .matchStatus(SettlementLineMatchStatus.MATCHED)
                .matchedPaymentId(UUID.randomUUID())
                .updatedAt(Instant.parse("2026-10-17T11:00:00Z"))
                .build();
        when(settlementLines.findByMatchStatusAndUpdatedAtGreaterThanEqual(
                        SettlementLineMatchStatus.MATCHED, START_OF_TODAY))
                .thenReturn(List.of(line));
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setVendorId(UUID.randomUUID());
        bill.setVendorName("Acme Tires");
        bill.setBillNumber("INV-8");
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setTotalAmount(new BigDecimal("42.00"));
        bill.setApprovedAt(Instant.parse("2026-10-17T07:00:00Z"));
        bill.setApprovedByKind(VendorBillApproverKind.SYSTEM);
        when(vendorBills.findByApprovedByKindAndApprovedAtGreaterThanEqualOrderByApprovedAtAscVendorBillIdAsc(
                        VendorBillApproverKind.SYSTEM, START_OF_TODAY))
                .thenReturn(List.of(bill));

        signIn(AccountingPermissions.PAYMENT_REVERSE);
        List<AutomaticWorkItem> items = service.list(null, null, null).items();

        assertThat(items).extracting(AutomaticWorkItem::kind)
                .containsExactlyInAnyOrder(
                        AutomaticWorkItemKind.SETTLEMENT_LINE_MATCHED, AutomaticWorkItemKind.BILL_APPROVED_BY_SYSTEM);
        assertThat(items).allSatisfy(item -> assertThat(item.actions()).isEmpty());
        AutomaticWorkItem settlement = items.stream()
                .filter(item -> item.kind() == AutomaticWorkItemKind.SETTLEMENT_LINE_MATCHED)
                .findFirst()
                .orElseThrow();
        assertThat(settlement.references())
                .containsEntry("settlement", "SET-9")
                .containsEntry("paymentReference", "PAYREF-9");
        assertThat(settlement.amount()).isEqualByComparingTo("97.10");
        AutomaticWorkItem approved = items.stream()
                .filter(item -> item.kind() == AutomaticWorkItemKind.BILL_APPROVED_BY_SYSTEM)
                .findFirst()
                .orElseThrow();
        assertThat(approved.references()).containsEntry("vendor", "Acme Tires").containsEntry("billNumber", "INV-8");
        assertThat(approved.occurredOn()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("no reference is an identifier as text; identifiers are in routing only")
    void routingCarriesTheIds() {
        application("2026-10-17T09:00:00Z", false);

        signIn(AccountingPermissions.PAYMENT_REVERSE);
        AutomaticWorkItem item = service.list(null, null, null).items().getFirst();

        assertThat(item.references().values())
                .noneMatch(value -> value.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-.*"));
    }

    // ---- since -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("since defaults to the start of today in the tenant zone")
    void defaultSince() {
        signIn(AccountingPermissions.PAYMENT_REVERSE);

        service.list(null, null, null);

        verify(applications).listAutomatic(eq(START_OF_TODAY), eq(0), anyInt(), eq(true));
        verify(settlementLines)
                .findByMatchStatusAndUpdatedAtGreaterThanEqual(SettlementLineMatchStatus.MATCHED, START_OF_TODAY);
    }

    @Test
    @DisplayName("a local date reads as the start of that day in the tenant's zone")
    void dateInTheTenantZone() {
        when(zoneResolver.zone()).thenReturn(ZoneId.of("America/Chicago"));
        signIn(AccountingPermissions.PAYMENT_REVERSE);

        service.list("2026-10-15", null, null);

        verify(applications).listAutomatic(eq(Instant.parse("2026-10-15T05:00:00Z")), eq(0), anyInt(), eq(true));
    }

    @Test
    @DisplayName("an ISO instant is used as given")
    void instantSince() {
        signIn(AccountingPermissions.PAYMENT_REVERSE);

        service.list("2026-10-16T08:30:00Z", null, null);

        verify(applications).listAutomatic(eq(Instant.parse("2026-10-16T08:30:00Z")), eq(0), anyInt(), eq(true));
    }

    @Test
    @DisplayName("31 days back is allowed; 32 days back, the future and nonsense are VALIDATION_ERROR on since")
    void sinceBounds() {
        signIn(AccountingPermissions.PAYMENT_REVERSE);

        assertThat(service.list(TODAY.minusDays(31).toString(), null, null)).isNotNull();
        for (String bad : List.of(
                TODAY.minusDays(32).toString(),
                "2026-09-01T00:00:00Z",
                "2026-10-17T15:00:01Z",
                TODAY.plusDays(1).toString(),
                "yesterday")) {
            assertThatThrownBy(() -> service.list(bad, null, null))
                    .as("since=%s", bad)
                    .isInstanceOfSatisfying(
                            InvalidRequestParameterException.class,
                            e -> assertThat(e.getField()).isEqualTo("since"));
        }
    }

    // ---- paging ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("oldest first across the three sources, paged by a cursor bound to since")
    void pagingAndCursorScope() {
        application("2026-10-17T10:00:00Z", false);
        application("2026-10-16T10:00:00Z", false);
        application("2026-10-15T10:00:00Z", false);
        signIn(AccountingPermissions.PAYMENT_REVERSE);

        AutomaticWorkItemsResponse first = service.list("2026-10-14", null, 2);
        AutomaticWorkItemsResponse second = service.list("2026-10-14", first.nextCursor(), 2);

        assertThat(first.items()).extracting(AutomaticWorkItem::occurredOn)
                .containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertThat(second.items()).extracting(AutomaticWorkItem::occurredOn).containsExactly(TODAY);
        assertThat(second.nextCursor()).isNull();
        assertThat(first.asOf()).isEqualTo(NOW);

        assertThatThrownBy(() -> service.list("2026-10-13", first.nextCursor(), 2))
                .isInstanceOf(WorkItemsCursorInvalidException.class);
        assertThatThrownBy(() -> service.list("2026-10-14", first.nextCursor() + "x", 2))
                .isInstanceOf(WorkItemsCursorInvalidException.class);
        assertThatThrownBy(() -> service.list("2026-10-14", null, 201))
                .isInstanceOfSatisfying(
                        InvalidRequestParameterException.class, e -> assertThat(e.getField()).isEqualTo("limit"));
    }
}
