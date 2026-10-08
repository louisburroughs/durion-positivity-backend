package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.dto.AgedPayablesReport;
import com.positivity.accounting.internal.dto.AgedPayablesRow;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.enums.VendorBillCheckOutcome;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillDifferenceClass;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.enums.VendorBillStage;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.FinancialReportingService;
import com.positivity.accounting.internal.service.JournalEntryService;
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
 * The vendor-bill approval lifecycle and the posting at approval on the full Flyway chain (CAP:550 S12, #2509;
 * AW37-AW43): the template's VENDOR_BILL mappings, the entries of AC13 (a)-(e), the refusal that rolls the approval
 * back, the void dated on the void date, the once-only posting, the row lock between two deciders, the match
 * evidence under row-level security across two tenants, aged payables and the stage reads; and the review round
 * (AW45-AW47, A1, A4, A7, B-MAJ2, B-MAJ3): the receipt's void, the invoice date, the vendor's totals, the reversal
 * guard, the guided mapping refusal, the void mirror, the override, the credit note, two approvers and two writers.
 *
 * <p>Requires Docker.
 */
@DisplayName("Vendor-bill approval and posting at approval (#2509, real Postgres)")
class VendorBillApprovalPostgresIT extends PostgresTenancyTestBase {

    private static final String CLERK = "clerk.ana";
    private static final String CONTROLLER = "controller.cfo";
    private static final String[] CLERK_GRANTS = {"accounting:ap:view", "accounting:ap:approve", "accounting:ap:reject"
    };
    private static final String[] CONTROLLER_GRANTS = {
        "accounting:ap:view",
        "accounting:ap:pay",
        "accounting:ap:approve",
        "accounting:ap:reject",
        "accounting:ap:approve_over_limit",
        "accounting:je:post"
    };

    @Autowired
    private VendorBillService vendorBills;

    @Autowired
    private VendorBillApprovalService approvals;

    @Autowired
    private VendorBillRepository billRows;

    @Autowired
    private VendorBillMatchEvidenceRepository evidenceRows;

    @Autowired
    private FinancialReportingService reports;

    @Autowired
    private JournalEntryService journalEntries;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    @DisplayName("AC13(a)/(b), AC2, AC4: a receipt of 4 x 100.00 billed 4 x 103.00 matches HIGH to AWAITING_APPROVAL"
            + " with nothing posted; approval posts Dr 2100 400.00 / Dr 5050 12.00 / Cr 2000 412.00 once")
    void matchedBillPostsAtApprovalOnce() {
        UUID tenant = tenant();
        LocalDate today = today();
        signIn("receiving.dock", "accounting:ap:pay");
        UUID vendor = UUIDv7Generator.generate();
        UUID product = UUIDv7Generator.generate();
        VendorBillResponse created = asTenant(
                tenant, () -> vendorBills.handleGoodsReceivedEvent(receipt(vendor, product, today.minusDays(2))));
        assertThat(created.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
        assertThat(created.getChannel()).isEqualTo(VendorBillReview.Channel.GOODS_RECEIPT);

        VendorBillResponse matched = asTenant(
                tenant,
                () -> vendorBills.handleVendorInvoiceReceivedEvent(invoice(vendor, product, today.minusDays(2))));

        assertThat(matched.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        assertThat(matched.getTotalAmount()).isEqualByComparingTo("412.00");
        assertThat(matched.getApproval().submittedBy()).isEqualTo("SYSTEM");
        assertThat(matched.getApproval().approvedBy()).isNull();
        assertThat(matched.getApproval().requiredTier()).isEqualTo(VendorBillReview.RequiredTier.OVER_LIMIT);
        assertThat(matched.getMatch().score()).isEqualTo(95);
        assertThat(matched.getMatch().points()).isEqualTo(new VendorBillReview.Points(40, 30, 20, 5));
        assertThat(matched.getLines()).singleElement().satisfies(line -> {
            assertThat(line.receivedUnitPrice()).isEqualByComparingTo("100.00");
            assertThat(line.billedUnitPrice()).isEqualByComparingTo("103.00");
            assertThat(line.billedQuantity()).isEqualByComparingTo("4");
        });
        assertThat(count(tenant, "journal_entry"))
                .as("AC13(b): creation and match post nothing")
                .isZero();
        assertThat(count(tenant, "accounting_event"))
                .as("AW37: no vendor-bill accounting event")
                .isZero();

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        VendorBillResponse approved = asTenant(
                tenant,
                () -> approvals.approve(
                        created.getVendorBillId(),
                        new VendorBillCommands.Approve("Checked the delivery", null, null, null)));

        assertThat(approved.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(approved.getApproval().approvedBy()).isEqualTo(CONTROLLER);
        assertThat(approved.getPosting().postingDate()).isEqualTo(today.minusDays(2));
        assertThat(approved.getPosting().postingDateRule()).isEqualTo(VendorBillPostingDateRule.BILL_DATE);
        assertThat(approved.getPosting().journalEntryReference()).startsWith("JE-");
        assertThat(lines(tenant, approved.getPosting().journalEntryId()))
                .containsExactly("2100 D400.0000", "5050 D12.0000", "2000 C412.0000");
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForMap(
                                "SELECT source_event_type, source_event_id FROM journal_entry WHERE tenant_id = ? AND"
                                        + " journal_entry_id = ?",
                                tenant,
                                approved.getPosting().journalEntryId()))
                .containsEntry("source_event_type", "VENDOR_BILL")
                .containsEntry(
                        "source_event_id",
                        UUID.nameUUIDFromBytes(("VENDOR_BILL:" + created.getVendorBillId()).getBytes()));
        assertThat(approved.getJournalEntryId()).isEqualTo(approved.getPosting().journalEntryId());

        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.approve(
                                created.getVendorBillId(), new VendorBillCommands.Approve(null, null, null, null))))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_NOT_APPROVABLE));
        assertThat(count(tenant, "journal_entry"))
                .as("a second approve posts nothing")
                .isEqualTo(1);
        assertThat(count(tenant, "vendor_bill_gl_posting")).isEqualTo(1);
        assertThat(auditOperations(tenant, created.getVendorBillId()))
                .containsExactly("VENDOR_BILL_MATCH_ROUTED", "VENDOR_BILL_APPROVE");
    }

    @Test
    @DisplayName("AC13(e), AC3, AC13(b), AC13(d): an EDI expense bill (net 200.00, tax 14.00) is sent by a clerk and"
            + " approved by a controller: Dr 6340 214.00 / Cr 2000 214.00; voided, its mirror is dated today")
    void ediExpenseBillPostsAndVoids() {
        UUID tenant = tenant();
        UUID billId = ediBill(tenant, "INV-200", today().minusDays(1), "214.00", "200.00", "14.00");

        signIn(CLERK, CLERK_GRANTS);
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.submitForApproval(billId, new VendorBillCommands.Submit("short", null, null))))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED));
        VendorBillResponse submitted = asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId,
                        new VendorBillCommands.Submit(
                                "No delivery!",
                                new VendorBillReview.Classification(
                                        VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES"),
                                null)));
        assertThat(submitted.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        assertThat(submitted.getApproval().submittedBy()).isEqualTo(CLERK);
        assertThat(submitted.getAvailableActions())
                .extracting(VendorBillReview.AvailableAction::action, VendorBillReview.AvailableAction::allowed)
                .as("AC12: the clerk may reject; approve is listed blocked by the default limit of 0 (S13)")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.APPROVE, false),
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.REJECT, true),
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.SET_DUE_DATE, true));
        assertThat(count(tenant, "journal_entry")).isZero();

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        VendorBillResponse approved = asTenant(
                tenant, () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null)));
        assertThat(lines(tenant, approved.getPosting().journalEntryId()))
                .containsExactly("6340 D214.0000", "2000 C214.0000");
        assertThat(approved.getAvailableActions())
                .extracting(VendorBillReview.AvailableAction::action)
                .containsExactly(VendorBillAction.VOID_APPROVED);

        VendorBillResponse voided = asTenant(
                tenant,
                () -> approvals.voidBill(
                        billId, new VendorBillCommands.VoidBill("Billed twice, the vendor confirmed", null)));
        assertThat(voided.getStatus()).isEqualTo(VendorBillStatus.VOIDED);
        assertThat(voided.getRejection().reason()).isEqualTo("Billed twice, the vendor confirmed");
        assertThat(voided.getPosting().reversalDate()).isEqualTo(today());
        assertThat(voided.getPosting().reversalReference()).startsWith("JE-");
        UUID reversal = new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT reversal_journal_entry_id FROM vendor_bill_gl_posting WHERE tenant_id = ? AND"
                                + " vendor_bill_id = ?",
                        UUID.class,
                        tenant,
                        billId);
        assertThat(lines(tenant, reversal)).containsExactly("6340 C214.0000", "2000 D214.0000");
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForMap(
                                "SELECT transaction_date::date AS dated, reversal_journal_entry_id AS reverses FROM"
                                        + " journal_entry WHERE tenant_id = ? AND journal_entry_id = ?",
                                tenant,
                                reversal))
                .containsEntry("dated", java.sql.Date.valueOf(today()))
                .containsEntry("reverses", approved.getPosting().journalEntryId());
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT status FROM journal_entry WHERE tenant_id = ? AND journal_entry_id = ?",
                                String.class,
                                tenant,
                                approved.getPosting().journalEntryId()))
                .isEqualTo("REVERSED");
    }

    @Test
    @DisplayName("AC13(c), ruling Q7 AC2: a hard-locked approval date is 422 with no approval fields, no entry and one"
            + " refusal audit row")
    void hardLockedApprovalRollsBack() {
        UUID tenant = tenant();
        UUID billId = ediBill(tenant, "INV-300", today().minusDays(1), "100.00", null, null);
        hardLock(tenant, today().plusDays(1));
        signIn(CLERK, CLERK_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId, new VendorBillCommands.Submit("Sent without a match", null, null)));

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.approve(
                                billId,
                                new VendorBillCommands.Approve(
                                        null,
                                        new VendorBillReview.Classification(VendorBillDebitClass.GOODS, null),
                                        null,
                                        null))))
                .isInstanceOf(AccountingPeriodHardLockedException.class);

        Map<String, Object> row = new JdbcTemplate(ownerDataSource())
                .queryForMap(
                        "SELECT status, approved_by, approved_at, approval_justification, journal_entry_id FROM"
                                + " vendor_bill WHERE tenant_id = ? AND vendor_bill_id = ?",
                        tenant,
                        billId);
        assertThat(row).containsEntry("status", "AWAITING_APPROVAL");
        assertThat(row.get("approved_by")).isNull();
        assertThat(row.get("approved_at")).isNull();
        assertThat(row.get("approval_justification")).isNull();
        assertThat(row.get("journal_entry_id")).isNull();
        assertThat(count(tenant, "journal_entry")).isZero();
        assertThat(count(tenant, "vendor_bill_gl_posting")).isZero();
        assertThat(auditOperations(tenant, billId))
                .containsExactly("VENDOR_BILL_SUBMIT", "VENDOR_BILL_APPROVE_REFUSED");
    }

    @Test
    @DisplayName("Ruling Q7 AC1: a bill dated in a closed period posts on the approval date,"
            + " APPROVAL_DATE_BILL_PERIOD_NOT_OPEN; Q2 AC4: an EDI GOODS bill with US tax posts the tax to 5050")
    void closedBillPeriodPostsOnTheApprovalDate() {
        UUID tenant = tenant();
        LocalDate lastMonth = today().minusMonths(1).withDayOfMonth(15);
        closePeriod(tenant, lastMonth);
        UUID billId = ediBill(tenant, "INV-400", lastMonth, "1070.00", "1000.00", "70.00");
        signIn(CLERK, CLERK_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId,
                        new VendorBillCommands.Submit(
                                "Stock bought outside a PO",
                                new VendorBillReview.Classification(VendorBillDebitClass.GOODS, null),
                                null)));

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        VendorBillResponse approved = asTenant(
                tenant, () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null)));

        assertThat(approved.getPosting().postingDate()).isEqualTo(today());
        assertThat(approved.getPosting().postingDateRule())
                .isEqualTo(VendorBillPostingDateRule.APPROVAL_DATE_BILL_PERIOD_NOT_OPEN);
        assertThat(lines(tenant, approved.getPosting().journalEntryId()))
                .containsExactly("2100 D1000.0000", "5050 D70.0000", "2000 C1070.0000");
    }

    @Test
    @DisplayName("Ruling Q2 AC7: a bill with no class and no vendor default is 422 AP_BILL_UNCLASSIFIED; no entry")
    void unclassifiedBillIsRefused() {
        UUID tenant = tenant();
        UUID billId = ediBill(tenant, "INV-500", today(), "50.00", null, null);
        signIn(CLERK, CLERK_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(billId, new VendorBillCommands.Submit("Shop supplies", null, null)));
        signIn(CONTROLLER, CONTROLLER_GRANTS);

        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null))))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_UNCLASSIFIED));
        assertThat(count(tenant, "journal_entry")).isZero();
        assertThat(status(tenant, billId)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    @DisplayName("AC5, AC13(b): reject writes the rejection and posts nothing")
    void rejectPostsNothing() {
        UUID tenant = tenant();
        UUID billId = ediBill(tenant, "INV-600", today(), "80.00", null, null);
        signIn(CLERK, CLERK_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId, new VendorBillCommands.Submit("Cleaning service", null, null)));

        VendorBillResponse rejected =
                asTenant(tenant, () -> approvals.reject(billId, new VendorBillCommands.Reject("Not our order")));

        assertThat(rejected.getStatus()).isEqualTo(VendorBillStatus.REJECTED);
        assertThat(rejected.getRejection().rejectedBy()).isEqualTo(CLERK);
        assertThat(rejected.getApproval().approvedBy()).isNull();
        assertThat(count(tenant, "journal_entry")).isZero();
    }

    @Test
    @DisplayName("Row lock: an approve and a reject of the same bill at once: one wins, the other is 409 naming the"
            + " status it found")
    void concurrentDecisionsSerialize() throws Exception {
        UUID tenant = tenant();
        UUID billId = ediBill(tenant, "INV-700", today(), "60.00", null, null);
        signIn(CLERK, CLERK_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId,
                        new VendorBillCommands.Submit(
                                "Small tools",
                                new VendorBillReview.Classification(
                                        VendorBillDebitClass.EXPENSE, "EXPENSE_SMALL_TOOLS"),
                                null)));
        SecurityContextHolder.clearContext();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> approve = pool.submit(decision(
                    start,
                    tenant,
                    CONTROLLER,
                    CONTROLLER_GRANTS,
                    () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null))));
            Future<Object> reject = pool.submit(decision(
                    start,
                    tenant,
                    CLERK,
                    CLERK_GRANTS,
                    () -> approvals.reject(billId, new VendorBillCommands.Reject("Not ordered by us"))));
            start.countDown();
            List<Object> outcomes = List.of(approve.get(60, TimeUnit.SECONDS), reject.get(60, TimeUnit.SECONDS));

            assertThat(outcomes)
                    .filteredOn(VendorBillResponse.class::isInstance)
                    .hasSize(1);
            assertThat(outcomes)
                    .filteredOn(VendorBillException.class::isInstance)
                    .singleElement()
                    .satisfies(refused -> assertThat(((VendorBillException) refused).getCode())
                            .isEqualTo(VendorBillException.Code.AP_BILL_NOT_APPROVABLE));
        } finally {
            pool.shutdownNow();
        }
        String finalStatus = status(tenant, billId);
        assertThat(finalStatus).isIn("APPROVED", "REJECTED");
        assertThat(count(tenant, "journal_entry")).isEqualTo("APPROVED".equals(finalStatus) ? 1 : 0);
    }

    @Test
    @DisplayName("ADR-0062: match evidence is invisible across tenants; AC11: AWAITING_APPROVAL bills without a due"
            + " date count in the stages and in aged payables as unapproved")
    void evidenceIsolationStagesAndAgedPayables() {
        UUID tenantA = tenant();
        UUID tenantB = tenant();
        LocalDate today = today();
        signIn("receiving.dock", "accounting:ap:pay");
        UUID vendor = UUIDv7Generator.generate();
        UUID product = UUIDv7Generator.generate();
        UUID billId = asTenant(
                        tenantA,
                        () -> vendorBills.handleGoodsReceivedEvent(receipt(vendor, product, today.minusDays(1))))
                .getVendorBillId();
        asTenant(
                tenantA,
                () -> vendorBills.handleVendorInvoiceReceivedEvent(invoice(vendor, product, today.minusDays(1))));
        assertThat(asTenant(
                        tenantA,
                        () -> evidenceRows.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(billId)))
                .isPresent();
        assertThat(asTenant(
                        tenantB,
                        () -> evidenceRows.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(billId)))
                .as("tenant B cannot see tenant A's evidence")
                .isEmpty();
        assertThat(asTenant(tenantB, () -> evidenceRows.count())).isZero();

        UUID edi = ediBill(tenantA, "INV-800", today, "90.00", null, null);
        signIn(CLERK, CLERK_GRANTS);
        VendorBillReview.StageCounts counts = asTenant(tenantA, () -> approvals.stageCounts());
        assertThat(counts.approve()).as("the HIGH match").isEqualTo(1);
        assertThat(counts.check()).as("the EDI bill without a due date").isEqualTo(1);
        assertThat(asTenant(tenantA, () -> approvals.listByStage(VendorBillStage.CHECK, 0, 20))
                        .getContent())
                .extracting(VendorBillReview.StageRow::vendorBillId)
                .containsExactly(edi);
        assertThat(asTenant(tenantA, () -> approvals.listByStage(VendorBillStage.APPROVE, 0, 20))
                        .getContent())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.vendorBillId()).isEqualTo(billId);
                    assertThat(row.dueDate()).isNotNull();
                    assertThat(row.openAmount()).isEqualByComparingTo("412.00");
                    assertThat(row.currencyCode()).isEqualTo("USD");
                });
        assertThat(asTenant(tenantB, () -> approvals.stageCounts()).approve()).isZero();

        AgedPayablesReport aged = asTenant(tenantA, () -> reports.generateAgedPayables(today));
        assertThat(aged.getRows())
                .filteredOn(row -> vendor.equals(row.getVendorId()))
                .singleElement()
                .satisfies((AgedPayablesRow row) -> {
                    assertThat(row.getUnapproved()).isEqualByComparingTo("412.00");
                    assertThat(row.getUnapprovedBillCount()).isEqualTo(1);
                    assertThat(row.getTotalOutstanding()).isEqualByComparingTo("0");
                });
    }

    // ---- #2509 review round: AW45-AW47, A1, A4, A7, B-MAJ2, B-MAJ3 -----------------------------------------

    private UUID approvedMatchedBill(UUID tenant, UUID vendor, UUID product) {
        signIn("receiving.dock", "accounting:ap:pay");
        UUID billId = asTenant(
                        tenant,
                        () -> vendorBills.handleGoodsReceivedEvent(receipt(vendor, product, today().minusDays(2))))
                .getVendorBillId();
        asTenant(
                tenant,
                () -> vendorBills.handleVendorInvoiceReceivedEvent(invoice(vendor, product, today().minusDays(2))));
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(tenant, () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null)));
        return billId;
    }

    @Test
    @DisplayName("A7: a matched bill's void reverses Dr 2000 412.00 / Cr 2100 400.00 / Cr 5050 12.00 on today")
    void matchedBillVoidMirrorsItsEntry() {
        UUID tenant = tenant();
        UUID billId = approvedMatchedBill(tenant, UUIDv7Generator.generate(), UUIDv7Generator.generate());

        VendorBillResponse voided = asTenant(
                tenant,
                () -> approvals.voidBill(billId, new VendorBillCommands.VoidBill("Wrong vendor billed us", null)));

        assertThat(voided.getStatus()).isEqualTo(VendorBillStatus.VOIDED);
        assertThat(lines(tenant, reversalOf(tenant, billId)))
                .containsExactlyInAnyOrder("2000 D412.0000", "2100 C400.0000", "5050 C12.0000");
        assertThat(voided.getPosting().reversalDate()).isEqualTo(today());
    }

    @Test
    @DisplayName("A7: a partly paid approved bill is 409 AP_BILL_NOT_VOIDABLE; nothing is reversed")
    void partlyPaidBillIsNotVoidable() {
        UUID tenant = tenant();
        UUID billId = ediBill(tenant, "INV-910", today(), "214.00", "200.00", "14.00");
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId,
                        new VendorBillCommands.Submit(
                                "Shop supplies bill",
                                new VendorBillReview.Classification(
                                        VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES"),
                                null)));
        asTenant(tenant, () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null)));
        allocate(tenant, billId, "100.00");

        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.voidBill(
                                billId, new VendorBillCommands.VoidBill("Billed twice by mistake", null))))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_NOT_VOIDABLE));
        assertThat(status(tenant, billId)).isEqualTo("APPROVED");
        assertThat(count(tenant, "journal_entry")).isEqualTo(1);
    }

    @Test
    @DisplayName("A7: approval into a CLOSED period posts with accounting:period:override and its justification,"
            + " audited PERIOD_OVERRIDE_POST; without the override it is 422 PERIOD_CLOSED")
    void closedPeriodWithOverride() {
        UUID tenant = tenant();
        closePeriod(tenant, today());
        UUID billId = ediBill(tenant, "INV-920", today(), "214.00", "200.00", "14.00");
        signIn(CLERK, CLERK_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId,
                        new VendorBillCommands.Submit(
                                "Shop supplies bill",
                                new VendorBillReview.Classification(
                                        VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES"),
                                null)));

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null))))
                .isInstanceOf(com.positivity.accounting.internal.exception.AccountingPeriodClosedException.class);
        assertThat(count(tenant, "journal_entry")).isZero();

        signIn(
                CONTROLLER,
                Stream.concat(Stream.of(CONTROLLER_GRANTS), Stream.of("accounting:period:override"))
                        .toArray(String[]::new));
        VendorBillResponse approved = asTenant(
                tenant,
                () -> approvals.approve(
                        billId,
                        new VendorBillCommands.Approve(null, null, "Late bill, agreed with the accountant", null)));

        assertThat(approved.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(approved.getPosting().postingDate()).isEqualTo(today());
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT count(*) FROM accounting_audit_log WHERE tenant_id = ? AND operation ="
                                        + " 'PERIOD_OVERRIDE_POST'",
                                Integer.class,
                                tenant))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A7: an EDI credit note of -50.00, class EXPENSE, posts Dr 2000 50.00 / Cr 6340 50.00")
    void creditNotePostsTheMirror() {
        UUID tenant = tenant();
        UUID billId = ediBill(tenant, "CN-930", today(), "-50.00", "-50.00", "0.00");
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId,
                        new VendorBillCommands.Submit(
                                "Credit for returned supplies",
                                new VendorBillReview.Classification(
                                        VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES"),
                                null)));

        VendorBillResponse approved = asTenant(
                tenant, () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null)));

        assertThat(lines(tenant, approved.getPosting().journalEntryId()))
                .containsExactlyInAnyOrder("6340 C50.0000", "2000 D50.0000");
    }

    @Test
    @DisplayName("A7: two approves of the same bill at once: one posts, the other is 409; one entry")
    void concurrentApprovesPostOnce() throws Exception {
        UUID tenant = tenant();
        UUID billId = ediBill(tenant, "INV-940", today(), "214.00", "200.00", "14.00");
        signIn(CLERK, CLERK_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId,
                        new VendorBillCommands.Submit(
                                "Shop supplies bill",
                                new VendorBillReview.Classification(
                                        VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES"),
                                null)));
        SecurityContextHolder.clearContext();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<VendorBillResponse> approve =
                    () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null));
            Future<Object> first = pool.submit(decision(start, tenant, CONTROLLER, CONTROLLER_GRANTS, approve));
            Future<Object> second = pool.submit(decision(start, tenant, "controller.two", CONTROLLER_GRANTS, approve));
            start.countDown();
            List<Object> outcomes = List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));

            assertThat(outcomes)
                    .filteredOn(VendorBillResponse.class::isInstance)
                    .hasSize(1);
            assertThat(outcomes)
                    .filteredOn(VendorBillException.class::isInstance)
                    .singleElement()
                    .satisfies(refused -> assertThat(((VendorBillException) refused).getCode())
                            .isEqualTo(VendorBillException.Code.AP_BILL_NOT_APPROVABLE));
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(tenant, "journal_entry")).isEqualTo(1);
        assertThat(count(tenant, "vendor_bill_gl_posting")).isEqualTo(1);
    }

    @Test
    @DisplayName("A1: the journal-entry reversal of a bill's entry, and of its void's reversal, is 409"
            + " AP_BILL_ENTRY_NOT_REVERSIBLE; the ledger is unchanged both times")
    void billEntriesAreReversedByTheVoidOnly() {
        UUID tenant = tenant();
        UUID billId = approvedMatchedBill(tenant, UUIDv7Generator.generate(), UUIDv7Generator.generate());
        UUID entryId = new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT journal_entry_id FROM vendor_bill_gl_posting WHERE tenant_id = ? AND vendor_bill_id ="
                                + " ?",
                        UUID.class,
                        tenant,
                        billId);

        assertThatThrownBy(() -> asTenant(
                        tenant, () -> journalEntries.reverseJournalEntry(entryId, "Reverse it by hand", today())))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_ENTRY_NOT_REVERSIBLE));
        assertThat(count(tenant, "journal_entry")).isEqualTo(1);
        assertThat(entryStatus(tenant, entryId)).isEqualTo("POSTED");
        assertThat(status(tenant, billId)).isEqualTo("APPROVED");

        asTenant(
                tenant,
                () -> approvals.voidBill(billId, new VendorBillCommands.VoidBill("Wrong vendor billed us", null)));
        UUID reversal = reversalOf(tenant, billId);
        assertThatThrownBy(() ->
                        asTenant(tenant, () -> journalEntries.reverseJournalEntry(reversal, "Undo the void", today())))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_ENTRY_NOT_REVERSIBLE));
        assertThat(count(tenant, "journal_entry")).isEqualTo(2);
        assertThat(entryStatus(tenant, reversal)).isEqualTo("POSTED");
        assertThat(status(tenant, billId)).isEqualTo("VOIDED");
    }

    @Test
    @DisplayName("A4, AC13(f): a key without an active mapping is 422 GL_MAPPING_NOT_CONFIGURED naming"
            + " VENDOR_BILL/EXPENSE_SHOP_SUPPLIES; the bill is unchanged and the refusal audited")
    void missingMappingRefusesTheApproval() {
        UUID tenant = tenant();
        new JdbcTemplate(ownerDataSource())
                .update(
                        "DELETE FROM gl_mapping WHERE tenant_id = ? AND mapping_key_id IN (SELECT k.mapping_key_id FROM"
                                + " mapping_key k JOIN posting_category c ON c.tenant_id = k.tenant_id AND"
                                + " c.posting_category_id = k.posting_category_id WHERE k.tenant_id = ? AND"
                                + " c.category_name = 'VENDOR_BILL' AND k.key_name = 'EXPENSE_SHOP_SUPPLIES')",
                        tenant,
                        tenant);
        UUID billId = ediBill(tenant, "INV-950", today(), "214.00", "200.00", "14.00");
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId,
                        new VendorBillCommands.Submit(
                                "Shop supplies bill",
                                new VendorBillReview.Classification(
                                        VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES"),
                                null)));

        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null))))
                .isInstanceOfSatisfying(GLMappingNotConfiguredException.class, e -> {
                    assertThat(e.getReferenceId()).isEqualTo("VENDOR_BILL/EXPENSE_SHOP_SUPPLIES");
                    assertThat(e.getNextAction()).contains("Map VENDOR_BILL / EXPENSE_SHOP_SUPPLIES");
                });
        assertThat(status(tenant, billId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(count(tenant, "journal_entry")).isZero();
        assertThat(auditOperations(tenant, billId))
                .containsExactly("VENDOR_BILL_SUBMIT", "VENDOR_BILL_APPROVE_REFUSED");
    }

    @Test
    @DisplayName("#2601: a mapping whose account is deactivated is the same 422 GL_MAPPING_NOT_CONFIGURED naming"
            + " category, key and posting date; the bill is unchanged")
    void inactiveMappedAccountRefusesTheApproval() {
        UUID tenant = tenant();
        new JdbcTemplate(ownerDataSource())
                .update(
                        "UPDATE gl_account SET deactivation_date = TIMESTAMP '2000-01-01 00:00:00' WHERE tenant_id = ?"
                                + " AND account_code = '6340'",
                        tenant);
        UUID billId = ediBill(tenant, "INV-955", today(), "214.00", "200.00", "14.00");
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId,
                        new VendorBillCommands.Submit(
                                "Shop supplies bill",
                                new VendorBillReview.Classification(
                                        VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES"),
                                null)));

        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null))))
                .isInstanceOfSatisfying(GLMappingNotConfiguredException.class, e -> {
                    assertThat(e.getReferenceId()).isEqualTo("VENDOR_BILL/EXPENSE_SHOP_SUPPLIES");
                    assertThat(e.getMessage()).contains("EXPENSE_SHOP_SUPPLIES", today().toString());
                });
        assertThat(status(tenant, billId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(count(tenant, "journal_entry")).isZero();
    }

    @Test
    @DisplayName("B-MAJ2: a discrepancy CORRECTed goes back to its receipt; the next match compares with what was"
            + " received and goes to approval at 412.00")
    void correctThenRematch() {
        UUID tenant = tenant();
        UUID vendor = UUIDv7Generator.generate();
        UUID product = UUIDv7Generator.generate();
        LocalDate received = today().minusDays(3);
        signIn("receiving.dock", "accounting:ap:pay");
        VendorBillResponse receipt =
                asTenant(tenant, () -> vendorBills.handleGoodsReceivedEvent(receipt(vendor, product, received)));
        UUID billId = receipt.getVendorBillId();
        VendorInvoiceReceivedEvent overpriced = invoice(vendor, product, today().minusDays(1));
        overpriced.getLineItems().get(0).setUnitPrice(new BigDecimal("110.00"));
        VendorBillResponse exception = asTenant(tenant, () -> vendorBills.handleVendorInvoiceReceivedEvent(overpriced));
        assertThat(exception.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
        assertThat(exception.getTotalAmount()).isEqualByComparingTo("440.00");

        signIn(CLERK, CLERK_GRANTS);
        VendorBillResponse corrected = asTenant(
                tenant,
                () -> approvals.resolveException(
                        billId,
                        new VendorBillCommands.ResolveException(
                                "CORRECT", "Vendor sends a new invoice", null, null, null)));
        assertThat(corrected.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
        assertThat(corrected.getTotalAmount()).isEqualByComparingTo("400.00");
        assertThat(corrected.getBillDate()).isEqualTo(received.atTime(9, 30));
        assertThat(corrected.getBillNumber())
                .as("L-new-1: the receipt's own number again; the rejected invoice number no longer names it")
                .isEqualTo(receipt.getBillNumber());
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT count(*) FROM vendor_bill WHERE tenant_id = ? AND bill_number = ?",
                                Integer.class,
                                tenant,
                                overpriced.getInvoiceReference()))
                .isZero();
        assertThat(corrected.getLines()).singleElement().satisfies(line -> {
            assertThat(line.billedQuantity()).isNull();
            assertThat(line.billedUnitPrice()).isNull();
        });
        assertThat(corrected.getAvailableActions())
                .extracting(VendorBillReview.AvailableAction::action)
                .as("AW45: back to waiting for its invoice")
                .containsExactly(VendorBillAction.VOID_UNMATCHED, VendorBillAction.SET_DUE_DATE);

        signIn("receiving.dock", "accounting:ap:pay");
        VendorBillResponse rematched = asTenant(
                tenant,
                () -> vendorBills.handleVendorInvoiceReceivedEvent(invoice(vendor, product, today().minusDays(1))));
        assertThat(rematched.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        assertThat(rematched.getTotalAmount()).isEqualByComparingTo("412.00");
        assertThat(rematched.getMatch().receivedTotal()).isEqualByComparingTo("400.00");
        assertThat(rematched.getMatch().receivedDate()).isEqualTo(received.atTime(9, 30));
        assertThat(rematched.getLines()).hasSize(1);
    }

    @Test
    @DisplayName("AW45(a)/(b): an unmatched goods-receipt bill is 409 AP_BILL_AWAITING_INVOICE on submit; an ap:reject"
            + " holder voids it with a 12-character reason, posting nothing; without the permission it is 403")
    void unmatchedReceiptAwaitsItsInvoiceOrIsVoided() {
        UUID tenant = tenant();
        signIn("receiving.dock", "accounting:ap:pay");
        UUID billId = asTenant(
                        tenant,
                        () -> vendorBills.handleGoodsReceivedEvent(
                                receipt(UUIDv7Generator.generate(), UUIDv7Generator.generate(), today())))
                .getVendorBillId();

        signIn(CLERK, CLERK_GRANTS);
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.submitForApproval(
                                billId, new VendorBillCommands.Submit("Send it without the invoice", null, null))))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_AWAITING_INVOICE));

        signIn("payer.pat", "accounting:ap:view", "accounting:ap:approve", "accounting:ap:approve_over_limit");
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.voidBill(billId, new VendorBillCommands.VoidBill("No invoice ever", null))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        signIn(CLERK, CLERK_GRANTS);
        VendorBillResponse voided = asTenant(
                tenant, () -> approvals.voidBill(billId, new VendorBillCommands.VoidBill("No invoice ever", null)));
        assertThat(voided.getStatus()).isEqualTo(VendorBillStatus.VOIDED);
        assertThat(voided.getPosting()).isNull();
        assertThat(count(tenant, "journal_entry")).isZero();
        assertThat(count(tenant, "vendor_bill_gl_posting")).isZero();
        assertThat(auditOperations(tenant, billId)).containsExactly("VENDOR_BILL_VOID");
    }

    @Test
    @DisplayName("AW45(c): an EDI GOODS bill of a vendor with one open goods-receipt bill shows"
            + " OPEN_DELIVERIES_FROM_VENDOR FAIL with count 1; its approval still succeeds")
    void openDeliveriesFromVendorIsInformational() {
        UUID tenant = tenant();
        UUID vendor = UUIDv7Generator.generate();
        signIn("receiving.dock", "accounting:ap:pay");
        String receiptNumber = asTenant(
                        tenant,
                        () -> vendorBills.handleGoodsReceivedEvent(
                                receipt(vendor, UUIDv7Generator.generate(), today())))
                .getBillNumber();
        UUID edi = ediBill(
                tenant,
                vendor,
                "INV-960",
                today(),
                "1070.00",
                "1000.00",
                "70.00",
                VendorBillStatus.PENDING_RECEIPT_MATCH,
                null);

        signIn(CLERK, CLERK_GRANTS);
        VendorBillResponse submitted = asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        edi,
                        new VendorBillCommands.Submit(
                                "Stock bought outside a PO",
                                new VendorBillReview.Classification(VendorBillDebitClass.GOODS, null),
                                null)));
        assertThat(submitted.getChecks())
                .filteredOn(check -> check.code().equals("OPEN_DELIVERIES_FROM_VENDOR"))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.outcome()).isEqualTo(VendorBillCheckOutcome.FAIL);
                    assertThat(check.args()).containsEntry("count", "1").containsEntry("billNumbers", receiptNumber);
                });

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        VendorBillResponse approved =
                asTenant(tenant, () -> approvals.approve(edi, new VendorBillCommands.Approve(null, null, null, null)));
        assertThat(approved.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
    }

    @Test
    @DisplayName("AW46(a): a receipt matched to an invoice dated later takes the invoice date; the evidence keeps"
            + " the receipt date; it posts on the invoice date")
    void matchedBillTakesTheInvoiceDate() {
        UUID tenant = tenant();
        UUID vendor = UUIDv7Generator.generate();
        UUID product = UUIDv7Generator.generate();
        LocalDate received = today().minusDays(4);
        LocalDate invoiced = today().minusDays(1);
        signIn("receiving.dock", "accounting:ap:pay");
        UUID billId = asTenant(tenant, () -> vendorBills.handleGoodsReceivedEvent(receipt(vendor, product, received)))
                .getVendorBillId();
        VendorBillResponse matched = asTenant(
                tenant, () -> vendorBills.handleVendorInvoiceReceivedEvent(invoice(vendor, product, invoiced)));

        assertThat(matched.getBillDate()).isEqualTo(invoiced.atStartOfDay());
        assertThat(matched.getMatch().receivedDate()).isEqualTo(received.atTime(9, 30));
        assertThat(matched.getMatch().invoiceDate()).isEqualTo(invoiced.atStartOfDay());

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        VendorBillResponse approved = asTenant(
                tenant, () -> approvals.approve(billId, new VendorBillCommands.Approve(null, null, null, null)));
        assertThat(approved.getPosting().postingDate()).isEqualTo(invoiced);
        assertThat(approved.getPosting().postingDateRule()).isEqualTo(VendorBillPostingDateRule.BILL_DATE);
    }

    @Test
    @DisplayName("AW46(b): /match onto an EDI bill's number and invoice date is 409 AP_BILL_DUPLICATE; the receipt"
            + " bill is untouched")
    void matchOntoAnEdiBillsNumberIsRefused() {
        UUID tenant = tenant();
        UUID vendor = UUIDv7Generator.generate();
        UUID product = UUIDv7Generator.generate();
        LocalDate invoiced = today().minusDays(1);
        VendorInvoiceReceivedEvent invoice = invoice(vendor, product, invoiced);
        ediBill(
                tenant,
                vendor,
                invoice.getInvoiceReference(),
                invoiced,
                "412.00",
                "412.00",
                "0.00",
                VendorBillStatus.PENDING_RECEIPT_MATCH,
                null);
        signIn("receiving.dock", "accounting:ap:pay");
        VendorBillResponse receipt = asTenant(
                tenant, () -> vendorBills.handleGoodsReceivedEvent(receipt(vendor, product, today().minusDays(3))));

        assertThatThrownBy(() -> asTenant(tenant, () -> vendorBills.handleVendorInvoiceReceivedEvent(invoice)))
                .isInstanceOf(com.positivity.accounting.internal.exception.VendorBillDuplicateException.class);

        Map<String, Object> row = new JdbcTemplate(ownerDataSource())
                .queryForMap(
                        "SELECT status, bill_number, bill_date, total_amount FROM vendor_bill WHERE tenant_id = ? AND"
                                + " vendor_bill_id = ?",
                        tenant,
                        receipt.getVendorBillId());
        assertThat(row).containsEntry("status", "PENDING_RECEIPT_MATCH");
        assertThat(row).containsEntry("bill_number", receipt.getBillNumber());
        assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("400.00");
        assertThat(count(tenant, "vendor_bill_match_evidence")).isZero();
    }

    @Test
    @DisplayName("AW47(a)/(b): totals 15.00 apart: approve without a difference is 422 AP_BILL_TOTALS_UNRECONCILED;"
            + " with FREIGHT it posts Dr 2100 1,000.00 / Dr 5050 70.00 / Dr 5060 15.00 / Cr 2000 1,085.00")
    void unreconciledTotalsPostWithTheirDifference() {
        UUID tenant = tenant();
        UUID billId = ediBill(
                tenant,
                UUIDv7Generator.generate(),
                "INV-970",
                today(),
                "1085.00",
                "1000.00",
                "70.00",
                VendorBillStatus.MATCH_EXCEPTION,
                "The vendor's totals don't add up: net 1000.00 + tax 70.00 ≠ total 1085.00");
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        VendorBillResponse held = asTenant(tenant, () -> approvals.getBill(billId));
        assertThat(held.getStatusExplanation()).startsWith("The vendor's totals don't add up");
        assertThat(held.getChecks())
                .filteredOn(check -> check.code().equals("TOTALS_ADD_UP"))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.outcome()).isEqualTo(VendorBillCheckOutcome.FAIL);
                    assertThat(check.args()).containsEntry("difference", "15.00");
                });
        VendorBillReview.Classification goods = new VendorBillReview.Classification(VendorBillDebitClass.GOODS, null);

        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> approvals.resolveException(
                                billId,
                                new VendorBillCommands.ResolveException(
                                        "ACCEPT", "Totals checked", goods, null, null))))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_TOTALS_UNRECONCILED));
        assertThat(status(tenant, billId)).isEqualTo("MATCH_EXCEPTION");
        assertThat(auditOperations(tenant, billId)).isEmpty();

        VendorBillResponse approved = asTenant(
                tenant,
                () -> approvals.resolveException(
                        billId,
                        new VendorBillCommands.ResolveException(
                                "ACCEPT",
                                "Totals checked",
                                goods,
                                null,
                                new VendorBillReview.Difference(
                                        VendorBillDifferenceClass.FREIGHT,
                                        null,
                                        "Freight on the invoice, not stated"))));
        assertThat(lines(tenant, approved.getPosting().journalEntryId()))
                .containsExactlyInAnyOrder("2100 D1000.0000", "5050 D70.0000", "5060 D15.0000", "2000 C1085.0000");
        assertThat(approved.getPosting().differenceClass()).isEqualTo(VendorBillDifferenceClass.FREIGHT);
        assertThat(approved.getPosting().differenceAmount()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("AW47(c)/(d): gross 1,070.01 posts Dr 2100 1,000.01 with roundingAdjustment 0.01; gross 1,060.00"
            + " with PRICE_DIFFERENCE posts Dr 5050 60.00")
    void roundingAndNegativeDifference() {
        UUID tenant = tenant();
        UUID rounded = ediBill(tenant, "INV-980", today(), "1070.01", "1000.00", "70.00");
        UUID short10 = ediBill(
                tenant,
                UUIDv7Generator.generate(),
                "INV-981",
                today(),
                "1060.00",
                "1000.00",
                "70.00",
                VendorBillStatus.MATCH_EXCEPTION,
                "The vendor's totals don't add up: net 1000.00 + tax 70.00 ≠ total 1060.00");
        VendorBillReview.Classification goods = new VendorBillReview.Classification(VendorBillDebitClass.GOODS, null);
        signIn(CONTROLLER, CONTROLLER_GRANTS);

        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        rounded, new VendorBillCommands.Submit("Stock bought outside a PO", goods, null)));
        VendorBillResponse roundedApproved = asTenant(
                tenant, () -> approvals.approve(rounded, new VendorBillCommands.Approve(null, null, null, null)));
        assertThat(lines(tenant, roundedApproved.getPosting().journalEntryId()))
                .containsExactlyInAnyOrder("2100 D1000.0100", "5050 D70.0000", "2000 C1070.0100");
        assertThat(roundedApproved.getPosting().roundingAdjustment()).isEqualByComparingTo("0.01");

        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        short10,
                        new VendorBillCommands.Submit(
                                "Vendor gave a discount",
                                goods,
                                new VendorBillReview.Difference(
                                        VendorBillDifferenceClass.PRICE_DIFFERENCE, null, "Discount on the total"))));
        VendorBillResponse shortApproved = asTenant(
                tenant, () -> approvals.approve(short10, new VendorBillCommands.Approve(null, null, null, null)));
        assertThat(lines(tenant, shortApproved.getPosting().journalEntryId()))
                .containsExactlyInAnyOrder("2100 D1000.0000", "5050 D60.0000", "2000 C1060.0000");
    }

    @Test
    @DisplayName("B-MAJ3: a void and a match of the same receipt at once: exactly one wins")
    void voidAndMatchSerialize() throws Exception {
        UUID tenant = tenant();
        UUID vendor = UUIDv7Generator.generate();
        UUID product = UUIDv7Generator.generate();
        signIn("receiving.dock", "accounting:ap:pay");
        UUID billId = asTenant(
                        tenant,
                        () -> vendorBills.handleGoodsReceivedEvent(receipt(vendor, product, today().minusDays(1))))
                .getVendorBillId();
        SecurityContextHolder.clearContext();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> voiding = pool.submit(anyOutcome(
                    start,
                    tenant,
                    CLERK,
                    CLERK_GRANTS,
                    () -> approvals.voidBill(billId, new VendorBillCommands.VoidBill("No invoice ever", null))));
            Future<Object> matching = pool.submit(anyOutcome(
                    start,
                    tenant,
                    "receiving.dock",
                    new String[] {"accounting:ap:pay"},
                    () -> vendorBills.handleVendorInvoiceReceivedEvent(
                            invoice(vendor, product, today().minusDays(1)))));
            start.countDown();
            List<Object> outcomes = List.of(voiding.get(60, TimeUnit.SECONDS), matching.get(60, TimeUnit.SECONDS));

            assertThat(outcomes)
                    .filteredOn(VendorBillResponse.class::isInstance)
                    .hasSize(1);
            assertThat(outcomes).filteredOn(RuntimeException.class::isInstance).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        String finalStatus = status(tenant, billId);
        assertThat(finalStatus).isIn("VOIDED", "AWAITING_APPROVAL");
        assertThat(count(tenant, "vendor_bill_match_evidence")).isEqualTo("VOIDED".equals(finalStatus) ? 0 : 1);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private LocalDate today() {
        // The tenants of this test take the provisioning seed's UTC accounting zone.
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private UUID tenant() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        provisionAccounting(tenant);
        return tenant;
    }

    /** A goods receipt of the vendor, put in the bound tenant's vendor copy first (S24). */
    private static GoodsReceivedEvent receipt(UUID vendor, UUID product, LocalDate received) {
        return GoodsReceivedEvent.builder()
                .eventId(UUIDv7Generator.generate())
                .organizationId(UUIDv7Generator.generate())
                .purchaseOrderId(UUIDv7Generator.generate())
                .vendorId(copiedVendor(vendor))
                .vendorName("Acme Parts Co")
                .receivedDate(received.atTime(9, 30))
                .lineItems(List.of(GoodsReceivedEvent.ReceivedLineItem.builder()
                        .productId(product)
                        .description("Brake pads")
                        .quantity(new BigDecimal("4"))
                        .unitPrice(new BigDecimal("100.00"))
                        .isInventoryItem(true)
                        .build()))
                .build();
    }

    private static VendorInvoiceReceivedEvent invoice(UUID vendor, UUID product, LocalDate invoiced) {
        return VendorInvoiceReceivedEvent.builder()
                .eventId(UUIDv7Generator.generate())
                .organizationId(UUIDv7Generator.generate())
                .vendorId(vendor)
                .invoiceReference("INV-" + vendor.toString().substring(24))
                .invoiceDate(invoiced.atStartOfDay())
                .dueDate(invoiced.plusDays(30).atStartOfDay())
                .lineItems(List.of(VendorInvoiceReceivedEvent.InvoiceLineItem.builder()
                        .productId(product)
                        .description("Brake pads")
                        .quantity(new BigDecimal("4"))
                        .unitPrice(new BigDecimal("103.00"))
                        .build()))
                .build();
    }

    /** An EDI bill as the supplier listener writes it: no lines, the stated net and tax, PENDING_RECEIPT_MATCH. */
    private UUID ediBill(UUID tenant, String number, LocalDate billDate, String gross, String net, String tax) {
        return ediBill(
                tenant,
                UUIDv7Generator.generate(),
                number,
                billDate,
                gross,
                net,
                tax,
                VendorBillStatus.PENDING_RECEIPT_MATCH,
                null);
    }

    /** An EDI bill of {@code vendor} in {@code status}, with the listener's explanation when it is held. */
    private UUID ediBill(
            UUID tenant,
            UUID vendor,
            String number,
            LocalDate billDate,
            String gross,
            String net,
            String tax,
            VendorBillStatus status,
            String explanation) {
        return asTenant(
                tenant,
                () -> new TransactionTemplate(transactionManager).execute(_ -> {
                    VendorBill bill = new VendorBill();
                    bill.setVendorId(vendor);
                    bill.setVendorName("Supply House");
                    bill.setBillNumber(number);
                    bill.setBillDate(billDate.atStartOfDay());
                    bill.setTotalAmount(new BigDecimal(gross));
                    bill.setNetAmount(net == null ? null : new BigDecimal(net));
                    bill.setTaxAmount(tax == null ? null : new BigDecimal(tax));
                    bill.setStatedLineCount(net == null ? null : 1);
                    bill.setCurrency("USD");
                    bill.setStatus(status);
                    bill.setRejectionReason(explanation);
                    bill.setOriginEventId(UUIDv7Generator.generate());
                    bill.setOriginEventType("SUPPLIER_INVOICE_RECEIVED");
                    bill.setCreatedBy("supplier");
                    bill.setModifiedBy("supplier");
                    return billRows.saveAndFlush(bill).getVendorBillId();
                }));
    }

    private static void hardLock(UUID tenant, LocalDate lockDate) {
        new JdbcTemplate(ownerDataSource())
                .update(
                        "INSERT INTO accounting_configuration (tenant_id, config_id, config_key, config_value,"
                                + " created_at, created_by, modified_at, modified_by) VALUES (?, ?, 'HARD_LOCK_DATE', ?,"
                                + " TIMESTAMPTZ '2026-09-01 00:00:00+00', 't', TIMESTAMPTZ '2026-09-01 00:00:00+00', 't')",
                        tenant,
                        UUIDv7Generator.generate(),
                        lockDate.toString());
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

    private Callable<Object> decision(
            CountDownLatch start, UUID tenant, String user, String[] grants, Callable<VendorBillResponse> command) {
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

    private Callable<Object> anyOutcome(
            CountDownLatch start, UUID tenant, String user, String[] grants, Callable<VendorBillResponse> command) {
        return () -> {
            signIn(user, grants);
            start.await();
            try {
                return asTenant(tenant, command);
            } catch (RuntimeException refused) {
                return refused;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private static void allocate(UUID tenant, UUID billId, String amount) {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        UUID payment = UUIDv7Generator.generate();
        UUID vendor = owner.queryForObject(
                "SELECT vendor_id FROM vendor_bill WHERE tenant_id = ? AND vendor_bill_id = ?",
                UUID.class,
                tenant,
                billId);
        owner.update(
                "INSERT INTO ap_payment (tenant_id, currency, gross_amount, created_at, payment_id, vendor_id, status,"
                        + " created_by, payment_ref) VALUES (?, 'USD', ?, now(), ?, ?, 'GL_POSTED', 't', ?)",
                tenant,
                new BigDecimal(amount),
                payment,
                vendor,
                "PAY-" + payment);
        owner.update(
                "INSERT INTO ap_payment_allocation (tenant_id, allocation_sequence, applied_amount, created_at,"
                        + " allocation_id, payment_id, vendor_bill_id) VALUES (?, 1, ?, now(), ?, ?, ?)",
                tenant,
                new BigDecimal(amount),
                UUIDv7Generator.generate(),
                payment,
                billId);
    }

    private static UUID reversalOf(UUID tenant, UUID billId) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT reversal_journal_entry_id FROM vendor_bill_gl_posting WHERE tenant_id = ? AND"
                                + " vendor_bill_id = ?",
                        UUID.class,
                        tenant,
                        billId);
    }

    private static String entryStatus(UUID tenant, UUID entryId) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT status FROM journal_entry WHERE tenant_id = ? AND journal_entry_id = ?",
                        String.class,
                        tenant,
                        entryId);
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

    private static List<String> auditOperations(UUID tenant, UUID billId) {
        return new JdbcTemplate(ownerDataSource())
                .queryForList(
                        "SELECT operation FROM accounting_audit_log WHERE tenant_id = ? AND entity_type = 'VENDOR_BILL'"
                                + " AND entity_id = ? ORDER BY timestamp, audit_log_id",
                        String.class,
                        tenant,
                        billId);
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

    private static int count(UUID tenant, String table) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }
}
