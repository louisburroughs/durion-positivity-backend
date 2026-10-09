package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.dto.ApApprovalPolicyRequest;
import com.positivity.accounting.internal.dto.ApApprovalPolicyResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillDifferenceClass;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    private Clock clock;

    @Autowired
    private VendorBillRepository billRows;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    @DisplayName("Two first PUTs on a fresh tenant serialize on the tenant's policy lock: both succeed, no duplicate"
            + " setting row")
    void concurrentFirstPuts() throws Exception {
        UUID tenant = tenant();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> one = pool.submit(inTenant(
                    start,
                    tenant,
                    CONTROLLER,
                    CONTROLLER_GRANTS,
                    () -> policies.set(limits("1000.00", "0.00", "First manager sets the limit", null))));
            Future<Object> two = pool.submit(inTenant(
                    start,
                    tenant,
                    "gm.gary",
                    CONTROLLER_GRANTS,
                    () -> policies.set(limits("2000.00", "0.00", "Second manager sets the limit", null))));
            start.countDown();
            assertThat(one.get(60, TimeUnit.SECONDS)).isInstanceOf(ApApprovalPolicyResponse.class);
            assertThat(two.get(60, TimeUnit.SECONDS)).isInstanceOf(ApApprovalPolicyResponse.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(tenant, "accounting_configuration", "config_key = 'AP_CLERK_APPROVAL_LIMIT'"))
                .isEqualTo(1);
        assertThat(count(tenant, "accounting_audit_log", "operation = 'AP_APPROVAL_POLICY_SET'"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("AC5: automatic 500.00, clerk 300.00: a 250.00 HIGH match of stocked lines is APPROVED by SYSTEM and"
            + " posted on the invoice date; a 400.00 one waits for a person")
    void automaticApproval() {
        UUID tenant = tenant();
        LocalDate invoiced = today().minusDays(1);
        // AC5's setup, automatic 500.00 above clerk 300.00, is a stored state the PUT refuses to write (the automatic
        // limit never exceeds the clerk limit); item 9 applies min(automatic, clerk) to it.
        setting(tenant, "AP_CLERK_APPROVAL_LIMIT", "300.00");
        setting(tenant, "AP_AUTO_APPROVAL_LIMIT", "500.00");

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
    @DisplayName("AC6: the person who approved a bill cannot pay it: 403 before any payment row or gateway call, no"
            + " ap_payment row, the refusal audited in its own transaction")
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
        assertThat(count(tenant, "ap_payment", "true")).isZero();
        assertThat(count(tenant, "accounting_audit_log", "operation = 'VENDOR_BILL_PAYMENT_REFUSED'"))
                .as("the refusal survives the payment's rollback")
                .isEqualTo(1);
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

    @Test
    @DisplayName("AC13 (AW47): within a 1,100.00 clerk limit a clerk ACCEPTs an EDI bill of gross 1,085.00 with"
            + " difference FREIGHT: Dr 2100 1,000.00 / Dr 5050 70.00 / Dr 5060 15.00 / Cr 2000 1,085.00, tier CLERK")
    void ediAcceptWithFreight() {
        UUID tenant = tenant();
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(tenant, () -> policies.set(limits("1100.00", "0.00", "Clerks approve up to 1,100", null)));
        UUID billId = ediBill(tenant, "INV-AW47", today().minusDays(1), VendorBillStatus.MATCH_EXCEPTION);

        signIn(CLERK, CLERK_GRANTS);
        VendorBillResponse accepted = asTenant(
                tenant,
                () -> approvals.resolveException(
                        billId,
                        new VendorBillCommands.ResolveException(
                                "ACCEPT",
                                "Freight on the invoice, agreed",
                                new VendorBillReview.Classification(VendorBillDebitClass.GOODS, null),
                                null,
                                new VendorBillReview.Difference(
                                        VendorBillDifferenceClass.FREIGHT, null, "Freight not stated separately"))));

        assertThat(accepted.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(accepted.getApproval().approvedBy()).isEqualTo(CLERK);
        assertThat(lines(tenant, accepted.getPosting().journalEntryId()))
                .containsExactlyInAnyOrder("2100 D1000.0000", "5050 D70.0000", "5060 D15.0000", "2000 C1085.0000");
        assertThat(auditNewValue(tenant, billId, "VENDOR_BILL_MATCH_EXCEPTION_RESOLVE"))
                .contains("tier=CLERK");
    }

    @Test
    @DisplayName("AC2: a clerk's approve over the limit is 403 AP_APPROVAL_LIMIT_EXCEEDED, and its"
            + " VENDOR_BILL_APPROVE_REFUSED row survives the rollback")
    void limitRefusalIsAudited() {
        UUID tenant = tenant();
        UUID billId = ediBill(tenant, "INV-OVER", today().minusDays(1), VendorBillStatus.AWAITING_APPROVAL);

        signIn(CLERK, CLERK_GRANTS);
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.approve(
                                billId,
                                new VendorBillCommands.Approve(
                                        null,
                                        new VendorBillReview.Classification(VendorBillDebitClass.GOODS, null),
                                        null,
                                        new VendorBillReview.Difference(
                                                VendorBillDifferenceClass.FREIGHT,
                                                null,
                                                "Freight not stated separately")))))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        refusal -> assertThat(refusal.getCode())
                                .isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED));
        assertThat(status(tenant, billId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(count(tenant, "accounting_audit_log", "operation = 'VENDOR_BILL_APPROVE_REFUSED'"))
                .isEqualTo(1);
        assertThat(auditNewValue(tenant, billId, "VENDOR_BILL_APPROVE_REFUSED"))
                .contains("code=AP_APPROVAL_LIMIT_EXCEEDED", "tier=OVER_LIMIT", "limit=0.00");
        assertThat(count(tenant, "journal_entry", "true")).isZero();
    }

    /** An EDI bill as the supplier listener writes it: gross 1,085.00, net 1,000.00, tax 70.00, in {@code status}. */
    private UUID ediBill(UUID tenant, String number, LocalDate billDate, VendorBillStatus status) {
        return asTenant(
                tenant,
                () -> new TransactionTemplate(transactionManager).execute(_ -> {
                    VendorBill bill = new VendorBill();
                    bill.setVendorId(UUIDv7Generator.generate());
                    bill.setVendorName("Supply House");
                    bill.setBillNumber(number);
                    bill.setBillDate(billDate.atStartOfDay());
                    bill.setTotalAmount(new BigDecimal("1085.00"));
                    bill.setNetAmount(new BigDecimal("1000.00"));
                    bill.setTaxAmount(new BigDecimal("70.00"));
                    bill.setStatedLineCount(1);
                    bill.setCurrency("USD");
                    bill.setStatus(status);
                    bill.setOriginEventId(UUIDv7Generator.generate());
                    bill.setOriginEventType("SUPPLIER_INVOICE_RECEIVED");
                    bill.setCreatedBy("supplier");
                    bill.setModifiedBy("supplier");
                    return billRows.saveAndFlush(bill).getVendorBillId();
                }));
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
        assertThat(count(
                        tenant,
                        "accounting_audit_log",
                        "entity_id = '" + bill.getVendorBillId()
                                + "' AND operation = 'VENDOR_BILL_AUTO_APPROVE_SKIPPED'"))
                .as("exactly one skip row")
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
                        .vendorId(copiedVendor(vendor)) // S24: in the tenant's vendor copy
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

    private static void setting(UUID tenant, String key, String value) {
        new JdbcTemplate(ownerDataSource())
                .update(
                        "INSERT INTO accounting_configuration (tenant_id, config_id, config_key, config_value,"
                                + " created_at, created_by, modified_at, modified_by) VALUES (?, ?, ?, ?,"
                                + " TIMESTAMPTZ '2026-10-08 00:00:00+00', 't', TIMESTAMPTZ '2026-10-08 00:00:00+00', 't')",
                        tenant,
                        UUIDv7Generator.generate(),
                        key,
                        value);
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
