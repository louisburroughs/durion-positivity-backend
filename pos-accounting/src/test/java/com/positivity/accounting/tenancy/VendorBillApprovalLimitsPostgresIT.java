package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.ApApprovalPolicyRequest;
import com.positivity.accounting.internal.dto.ApApprovalPolicyResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.service.APPaymentService;
import com.positivity.accounting.internal.service.ApApprovalPolicyService;
import com.positivity.accounting.internal.service.VendorBillApprovalService;
import com.positivity.accounting.internal.service.VendorBillService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Approval limits and separation of duties on the full Flyway chain (CAP:550 S13, #2510): the policy PUT/GET with its
 * history and requestId replay under row-level security across two tenants, automatic approval on a HIGH match and a
 * refused one that keeps the match committed, the pay guard leaving no {@code ap_payment} row and calling no gateway,
 * and a limit change racing an approve.
 *
 * <p>Requires Docker.
 */
@DisplayName("Vendor-bill approval limits and separation of duties (#2510, real Postgres)")
class VendorBillApprovalLimitsPostgresIT extends PostgresTenancyTestBase {

    private static final String CLERK = "clerk.ana";
    private static final String CONTROLLER = "controller.cfo";
    private static final String[] CLERK_GRANTS = {
        "ROLE_ACCOUNTING_CLERK", "accounting:ap:view", "accounting:ap:approve", "accounting:ap:reject"
    };
    private static final String[] CONTROLLER_GRANTS = {
        "ROLE_CONTROLLER",
        "accounting:ap:view",
        "accounting:ap:pay",
        "accounting:ap:approve",
        "accounting:ap:reject",
        "accounting:ap:approve_over_limit",
        "accounting:ap_approval_policy:manage"
    };

    @Autowired
    private VendorBillService vendorBills;

    @Autowired
    private VendorBillApprovalService approvals;

    @Autowired
    private ApApprovalPolicyService policies;

    @Autowired
    private APPaymentService payments;

    @MockitoBean
    private PaymentGatewayProvider paymentGateway;

    @Autowired
    private Clock clock;

    private final List<UUID> tenants = new ArrayList<>();

    @AfterEach
    void removeTestTenants() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        List<String> scoped = owner.queryForList(
                "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND column_name = 'tenant_id' AND table_name NOT LIKE 'pg_%' ORDER BY table_name",
                String.class);
        for (UUID tenant : tenants) {
            owner.update(
                    "UPDATE journal_entry SET reversal_journal_entry_id = NULL, reversed_by_journal_entry_id = NULL"
                            + " WHERE tenant_id = ?",
                    tenant);
            owner.update("UPDATE vendor_bill SET journal_entry_id = NULL WHERE tenant_id = ?", tenant);
            for (int pass = 0; pass < 8; pass++) {
                boolean blocked = false;
                for (String table : scoped) {
                    try {
                        owner.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant);
                    } catch (RuntimeException stillReferenced) {
                        blocked = true;
                    }
                }
                if (!blocked) {
                    break;
                }
            }
        }
        tenants.clear();
    }

    @Test
    @DisplayName("AC8/AC9: PUT and GET on PostgreSQL: three changes in the history newest first, a requestId replay"
            + " writes nothing, and another tenant sees none of it (RLS)")
    void policyHistoryReplayAndRowLevelSecurity() {
        UUID tenant = tenant();
        UUID other = tenant();
        UUID first = UUIDv7Generator.generate();
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(tenant, () -> policies.set(limits("2500.00", "500.00", "Routine parts bills up to 2,500", first)));
        asTenant(
                tenant,
                () -> policies.set(new ApApprovalPolicyRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        "NET15",
                        "Vendors moved to 15 days",
                        UUIDv7Generator.generate())));
        ApApprovalPolicyResponse replay = asTenant(
                tenant, () -> policies.set(limits("2500.00", "500.00", "Routine parts bills up to 2,500", first)));

        assertThat(replay.clerkApprovalLimit()).isEqualByComparingTo("2500.00");
        assertThat(replay.defaultTerms()).isEqualTo("NET15");
        assertThat(replay.history())
                .extracting(ApApprovalPolicyResponse.HistoryRow::setting)
                .containsExactly("AP_DEFAULT_TERMS", "AP_AUTO_APPROVAL_LIMIT", "AP_CLERK_APPROVAL_LIMIT");
        assertThat(replay.history()).allSatisfy(row -> {
            assertThat(row.changedBy()).isEqualTo(CONTROLLER);
            assertThat(row.changedByRoles()).containsExactly("CONTROLLER");
            assertThat(row.changedAt()).isNotNull();
        });
        assertThat(count(tenant, "accounting_audit_log", "operation = 'AP_APPROVAL_POLICY_SET'"))
                .isEqualTo(3);

        ApApprovalPolicyResponse otherTenant = asTenant(other, () -> policies.get(0, 20));
        assertThat(otherTenant.clerkApprovalLimit()).isEqualByComparingTo("0.00");
        assertThat(otherTenant.defaultTerms()).isEqualTo("NET30");
        assertThat(otherTenant.history()).isEmpty();
    }

    @Test
    @DisplayName("AC5: automatic 500.00, clerk 300.00: a 250.00 HIGH match of stocked lines is APPROVED by SYSTEM and"
            + " posted on the invoice date; a 400.00 one waits for a person")
    void automaticApproval() {
        UUID tenant = tenant();
        LocalDate invoiced = today().minusDays(1);
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(tenant, () -> policies.set(limits("300.00", "500.00", "Small strong matches go through", null)));

        signIn("receiving.dock", "accounting:ap:pay");
        VendorBillResponse small = receiveAndMatch(tenant, "250.00", true, invoiced);
        VendorBillResponse large = receiveAndMatch(tenant, "400.00", true, invoiced);

        assertThat(small.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(small.getApproval().approvedBy()).isEqualTo("SYSTEM");
        assertThat(small.getApproval().approvedByKind()).isEqualTo(VendorBillApproverKind.SYSTEM);
        assertThat(small.getPosting().postingDate()).isEqualTo(invoiced);
        assertThat(small.getPosting().postingDateRule()).isEqualTo(VendorBillPostingDateRule.BILL_DATE);
        assertThat(lines(tenant, small.getPosting().journalEntryId()))
                .containsExactly("2100 D250.0000", "2000 C250.0000");
        assertThat(auditNewValue(tenant, small.getVendorBillId(), "VENDOR_BILL_AUTO_APPROVE"))
                .contains("limit=300.00", "autoLimit=500.00", "limitApplied=300.00");
        assertThat(kind(tenant, small.getVendorBillId())).isEqualTo("SYSTEM");

        assertThat(large.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        assertThat(large.getApproval().approvedBy()).isNull();
    }

    @Test
    @DisplayName("AC15: a non-stock line without a class, or a CLOSED period on both dates, skips: the match is"
            + " committed, AWAITING_APPROVAL, no entry, one VENDOR_BILL_AUTO_APPROVE_SKIPPED row with the code")
    void refusedAutomaticApprovalKeepsTheMatch() {
        UUID tenant = tenant();
        LocalDate invoiced = today().minusDays(1);
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(tenant, () -> policies.set(limits("500.00", "500.00", "Small strong matches go through", null)));

        signIn("receiving.dock", "accounting:ap:pay");
        VendorBillResponse unclassified = receiveAndMatch(tenant, "200.00", false, invoiced);
        assertSkipped(tenant, unclassified, "AP_BILL_UNCLASSIFIED");

        closePeriod(tenant, invoiced);
        if (!java.time.YearMonth.from(invoiced).equals(java.time.YearMonth.from(today()))) {
            closePeriod(tenant, today());
        }
        VendorBillResponse closed = receiveAndMatch(tenant, "200.00", true, invoiced);
        assertSkipped(tenant, closed, "PERIOD_CLOSED");
        assertThat(count(tenant, "journal_entry", "true")).isZero();
    }

    @Test
    @DisplayName("AC6/AC7: the person who approved a bill cannot pay it: 403, no ap_payment row, no gateway call, the"
            + " refusal audited; a SYSTEM-approved bill pays")
    void payGuard() {
        UUID tenant = tenant();
        LocalDate invoiced = today().minusDays(1);
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(tenant, () -> policies.set(limits("300.00", "300.00", "Small strong matches go through", null)));
        signIn("receiving.dock", "accounting:ap:pay");
        VendorBillResponse byPerson = receiveAndMatch(tenant, "400.00", true, invoiced);
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(
                tenant,
                () -> approvals.approve(
                        byPerson.getVendorBillId(),
                        new VendorBillCommands.Approve("Checked against the delivery", null, null, null)));

        ExecuteAPPaymentRequest pay = payment(byPerson, "400.00");
        assertThatThrownBy(() -> asTenant(tenant, () -> payments.executePayment(pay, CONTROLLER)))
                .isInstanceOfSatisfying(VendorBillException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo(VendorBillException.Code.AP_PAYMENT_SELF_APPROVED_BILL);
                    assertThat(refusal.getFieldErrors())
                            .extracting(VendorBillException.FieldError::message)
                            .containsExactly(byPerson.getBillNumber());
                });
        verify(paymentGateway, never()).executePayment(any());
        assertThat(count(tenant, "ap_payment", "true")).isZero();
        assertThat(count(tenant, "accounting_audit_log", "operation = 'VENDOR_BILL_PAYMENT_REFUSED'"))
                .as("the refusal survives the payment's rollback")
                .isEqualTo(1);

        signIn("receiving.dock", "accounting:ap:pay");
        VendorBillResponse bySystem = receiveAndMatch(tenant, "250.00", true, invoiced);
        assertThat(bySystem.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        when(paymentGateway.executePayment(any()))
                .thenReturn(GatewayPaymentResponse.builder()
                        .transactionId("txn-2510")
                        .status(PaymentGatewayProvider.GatewayPaymentStatus.SUCCEEDED)
                        .rawResponse("{}")
                        .build());
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(tenant, () -> payments.executePayment(payment(bySystem, "250.00"), CONTROLLER));
        assertThat(count(tenant, "ap_payment", "true")).isEqualTo(1);
    }

    @Test
    @DisplayName("A limit change racing a clerk's approve: the decision records the limit it was taken under, whichever"
            + " commits first (the policy rows are share-locked by the decision)")
    void limitChangeRacingAnApprove() throws Exception {
        UUID tenant = tenant();
        LocalDate invoiced = today().minusDays(1);
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(tenant, () -> policies.set(limits("2500.00", "0.00", "Routine parts bills up to 2,500", null)));
        signIn("receiving.dock", "accounting:ap:pay");
        UUID billId = receiveAndMatch(tenant, "400.00", true, invoiced).getVendorBillId();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> approve = pool.submit(inTenant(
                    start,
                    tenant,
                    CLERK,
                    CLERK_GRANTS,
                    () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null))));
            Future<Object> lower = pool.submit(inTenant(
                    start,
                    tenant,
                    CONTROLLER,
                    CONTROLLER_GRANTS,
                    () -> policies.set(limits("0.00", "0.00", "Every bill to a controller now", null))));
            start.countDown();
            Object decided = approve.get(60, TimeUnit.SECONDS);
            assertThat(lower.get(60, TimeUnit.SECONDS)).isInstanceOf(ApApprovalPolicyResponse.class);

            if (decided instanceof VendorBillResponse approved) {
                assertThat(approved.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
                assertThat(auditNewValue(tenant, billId, "VENDOR_BILL_APPROVE")).contains("limit=2500.00");
            } else {
                assertThat(decided)
                        .isInstanceOfSatisfying(
                                VendorBillException.class,
                                refusal -> assertThat(refusal.getCode())
                                        .isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED));
                assertThat(auditNewValue(tenant, billId, "VENDOR_BILL_APPROVE_REFUSED"))
                        .contains("limit=0.00");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static ApApprovalPolicyRequest limits(String clerk, String auto, String justification, UUID requestId) {
        return new ApApprovalPolicyRequest(
                new BigDecimal(clerk),
                new BigDecimal(auto),
                "USD",
                null,
                null,
                null,
                justification,
                requestId == null ? UUIDv7Generator.generate() : requestId);
    }

    private void assertSkipped(UUID tenant, VendorBillResponse bill, String code) {
        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        assertThat(bill.getApproval().submittedBy()).isEqualTo("SYSTEM");
        assertThat(bill.getApproval().approvedBy()).isNull();
        assertThat(bill.getPosting()).isNull();
        assertThat(status(tenant, bill.getVendorBillId()))
                .as("the match transaction committed")
                .isEqualTo("AWAITING_APPROVAL");
        assertThat(count(tenant, "vendor_bill_match_evidence", "vendor_bill_id = '" + bill.getVendorBillId() + "'"))
                .isEqualTo(1);
        assertThat(auditNewValue(tenant, bill.getVendorBillId(), "VENDOR_BILL_AUTO_APPROVE_SKIPPED"))
                .contains("code=" + code);
        assertThat(bill.getMatch().invoiceDate()).isEqualTo(bill.getBillDate());
    }

    /** A goods receipt of one line of {@code amount}, then a HIGH match of the same amount (stocked or not). */
    private VendorBillResponse receiveAndMatch(UUID tenant, String amount, boolean stocked, LocalDate invoiced) {
        UUID vendor = UUIDv7Generator.generate();
        UUID product = UUIDv7Generator.generate();
        asTenant(
                tenant,
                () -> vendorBills.handleGoodsReceivedEvent(GoodsReceivedEvent.builder()
                        .eventId(UUIDv7Generator.generate())
                        .organizationId(UUIDv7Generator.generate())
                        .purchaseOrderId(UUIDv7Generator.generate())
                        .vendorId(vendor)
                        .vendorName("Acme Parts Co")
                        .receivedDate(invoiced.atTime(9, 30))
                        .lineItems(List.of(GoodsReceivedEvent.ReceivedLineItem.builder()
                                .productId(product)
                                .description("Brake pads")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal(amount))
                                .isInventoryItem(stocked)
                                .build()))
                        .build()));
        return asTenant(
                tenant,
                () -> vendorBills.handleVendorInvoiceReceivedEvent(VendorInvoiceReceivedEvent.builder()
                        .eventId(UUIDv7Generator.generate())
                        .organizationId(UUIDv7Generator.generate())
                        .vendorId(vendor)
                        .invoiceReference("INV-" + vendor.toString().substring(24))
                        .invoiceDate(invoiced.atStartOfDay())
                        .dueDate(invoiced.plusDays(30).atStartOfDay())
                        .lineItems(List.of(VendorInvoiceReceivedEvent.InvoiceLineItem.builder()
                                .productId(product)
                                .description("Brake pads")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal(amount))
                                .build()))
                        .build()));
    }

    private static ExecuteAPPaymentRequest payment(VendorBillResponse bill, String amount) {
        ExecuteAPPaymentRequest request = ExecuteAPPaymentRequest.builder()
                .paymentRef("PAY-" + UUIDv7Generator.generate())
                .vendorId(bill.getVendorId())
                .grossAmount(new BigDecimal(amount))
                .currency("USD")
                .paymentMethod(PaymentMethod.ACH)
                .build();
        request.setAllocations(List.of(
                new ExecuteAPPaymentRequest.AllocationLineRequest(bill.getVendorBillId(), new BigDecimal(amount))));
        return request;
    }

    private Callable<Object> inTenant(
            CountDownLatch start, UUID tenant, String user, String[] grants, Callable<Object> command) {
        return () -> {
            signIn(user, grants);
            start.await();
            try {
                return asTenant(tenant, command);
            } catch (VendorBillException refused) {
                return refused;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private UUID tenant() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        provisionAccounting(tenant);
        return tenant;
    }

    private static void closePeriod(UUID tenant, LocalDate inPeriod) {
        LocalDate start = inPeriod.withDayOfMonth(1);
        new JdbcTemplate(ownerDataSource())
                .update(
                        "INSERT INTO accounting_period (tenant_id, period_id, period_code, start_date, end_date,"
                            + " status, created_at, created_by, modified_at, modified_by, version) VALUES (?, ?, ?, ?,"
                            + " ?, 'CLOSED', TIMESTAMPTZ '2026-09-01 00:00:00+00', 't', TIMESTAMPTZ '2026-09-01"
                            + " 00:00:00+00', 't', 0)",
                        tenant,
                        UUIDv7Generator.generate(),
                        start.toString().substring(0, 7),
                        start,
                        start.plusMonths(1).minusDays(1));
    }

    private static void signIn(String username, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private static String status(UUID tenant, UUID billId) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT status FROM vendor_bill WHERE tenant_id = ? AND vendor_bill_id = ?",
                        String.class,
                        tenant,
                        billId);
    }

    private static String kind(UUID tenant, UUID billId) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT approved_by_kind FROM vendor_bill WHERE tenant_id = ? AND vendor_bill_id = ?",
                        String.class,
                        tenant,
                        billId);
    }

    private static String auditNewValue(UUID tenant, UUID billId, String operation) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT new_value FROM accounting_audit_log WHERE tenant_id = ? AND entity_id = ? AND"
                                + " operation = ? ORDER BY timestamp DESC LIMIT 1",
                        String.class,
                        tenant,
                        billId,
                        operation);
    }

    /** "code D|C amount" for each line of the entry, in line order. */
    private static List<String> lines(UUID tenant, UUID entryId) {
        return new JdbcTemplate(ownerDataSource())
                .query(
                        "SELECT g.account_code, l.debit_amount, l.credit_amount FROM journal_entry_line l JOIN"
                                + " gl_account g ON g.tenant_id = l.tenant_id AND g.gl_account_id = l.gl_account_id"
                                + " WHERE l.tenant_id = ? AND l.journal_entry_id = ? ORDER BY l.line_number",
                        (rs, n) -> {
                            BigDecimal debit = rs.getBigDecimal(2);
                            return rs.getString(1) + " "
                                    + (debit != null && debit.signum() > 0
                                            ? "D" + debit.toPlainString()
                                            : "C" + rs.getBigDecimal(3).toPlainString());
                        },
                        tenant,
                        entryId);
    }

    private static int count(UUID tenant, String table, String where) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND " + where, Integer.class, tenant);
    }
}
