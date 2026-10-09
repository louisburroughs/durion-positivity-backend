package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.VendorBillApprovalService;
import com.positivity.accounting.internal.service.VendorBillStatedTax;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CAP:550 S32d item 10 on the full Flyway chain (ACs 1, 8, 9, 10): a tenant that recovers input tax and one that does
 * not, side by side. The recovering tenant holds a registration for a fixture regime in a fixture country whose
 * configured currency (pos-tax, mocked here) is the functional currency; its template-free fixture maps {@code
 * TAX_RECOVERABLE_<regime>} under {@code VENDOR_BILL}. No country, regime or tax type is named in the code under test.
 *
 * <p>Requires Docker.
 */
@DisplayName("Vendor-bill tax split and input-tax recovery (CAP:550 S32d, real Postgres)")
class VendorBillTaxRecoveryPostgresIT extends PostgresTenancyTestBase {

    private static final String COUNTRY = "ZZ";
    private static final String REGIME = "R_1";
    private static final String RECOVERABLE_CODE = "1259";
    private static final String CONTROLLER = "controller.cfo";
    private static final String[] CONTROLLER_GRANTS = {
        "accounting:ap:view",
        "accounting:ap:approve",
        "accounting:ap:reject",
        "accounting:ap:approve_over_limit",
        "accounting:je:post"
    };
    private static final VendorBillReview.Classification EXPENSE =
            new VendorBillReview.Classification(VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES");

    /** pos-tax's profiles: an internal-only service outside this test. */
    @MockitoBean
    private TaxProfileClient taxProfiles;

    @Autowired
    private VendorBillApprovalService approvals;

    @Autowired
    private VendorBillRepository billRows;

    @Autowired
    private VendorBillStatedTax statedTax;

    @Autowired
    private LedgerCurrency ledgerCurrency;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    private final List<UUID> tenants = new ArrayList<>();

    @BeforeEach
    void profile() {
        when(taxProfiles.taxTypes(COUNTRY))
                .thenReturn(new TaxProfileClient.TaxTypes(
                        COUNTRY,
                        ledgerCurrency.code(),
                        List.of(
                                new TaxProfileClient.TaxType("TT_RECOVERABLE", REGIME, "FEDERAL", true),
                                new TaxProfileClient.TaxType("TT_COST", null, "STATE", false)),
                        List.of(new TaxProfileClient.Regime(REGIME, List.of()))));
        when(taxProfiles.evidenceRules(anyString(), any()))
                .thenReturn(
                        new TaxProfileClient.EvidenceRules(COUNTRY, today(), ledgerCurrency.code(), List.of(), null));
    }

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
    @DisplayName("AC 8 / AC 1: the same bill recovers its recoverable type in one tenant and books the gross in the"
            + " other; both store the tax by type (AC 9)")
    void recoveringAndNonRecoveringTenantsSideBySide() {
        UUID recovering = recoveringTenant();
        UUID plain = tenant();
        UUID recoveringBill = billWithTypes(recovering);
        UUID plainBill = billWithTypes(plain);

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        VendorBillResponse recovered = approve(recovering, recoveringBill, null);
        VendorBillResponse gross = approve(plain, plainBill, null);

        String expense = expenseAccount(recovering);
        assertThat(lines(recovering, recovered.getPosting().journalEntryId()))
                .containsExactlyInAnyOrder(expense + " D1070.0000", RECOVERABLE_CODE + " D50.0000", "2000 C1120.0000");
        assertThat(recovered.getInputTaxRecovery())
                .extracting(
                        VendorBillReview.InputTaxRecovery::taxType,
                        VendorBillReview.InputTaxRecovery::accountCode,
                        VendorBillReview.InputTaxRecovery::recoveryWithheldReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("TT_COST", null, "NOT_RECOVERABLE"),
                        org.assertj.core.groups.Tuple.tuple("TT_RECOVERABLE", RECOVERABLE_CODE, null));
        assertThat(recovered.getTaxByType())
                .extracting(VendorBillReview.TaxByType::taxType)
                .containsExactly("TT_COST", "TT_RECOVERABLE");

        assertThat(lines(plain, gross.getPosting().journalEntryId()))
                .containsExactlyInAnyOrder(expenseAccount(plain) + " D1120.0000", "2000 C1120.0000");
        assertThat(gross.getInputTaxRecovery())
                .as("a tenant without recovery records none")
                .isNull();
        assertThat(gross.getTaxByType()).hasSize(2);
        assertThat(count(plain, "vendor_bill_tax_recovery")).isZero();
    }

    @Test
    @DisplayName("AC 10: a recovering tenant's bill with no split recovers nothing (TAX_SPLIT_MISSING) and is not held")
    void unsplitRecoversNothing() {
        UUID tenant = recoveringTenant();
        UUID bill = bill(tenant);

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        VendorBillResponse approved = approve(tenant, bill, null);

        assertThat(approved.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(lines(tenant, approved.getPosting().journalEntryId()))
                .containsExactlyInAnyOrder(expenseAccount(tenant) + " D1120.0000", "2000 C1120.0000");
        assertThat(approved.getInputTaxRecovery()).singleElement().satisfies(row -> {
            assertThat(row.taxType()).isNull();
            assertThat(row.recoveryWithheldReason()).isEqualTo("TAX_SPLIT_MISSING");
            assertThat(row.statedAmount()).isEqualByComparingTo("120.00");
        });
    }

    @Test
    @DisplayName("AC 10: the approval's taxByType[] copied from the document splits an unsplit bill, and the audit"
            + " records the split and the recovery")
    void approvalTaxByTypeSplits() {
        UUID tenant = recoveringTenant();
        UUID bill = bill(tenant);

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        VendorBillResponse approved = approve(
                tenant,
                bill,
                List.of(
                        new VendorBillCommands.TaxAmount("TT_RECOVERABLE", new BigDecimal("50.00")),
                        new VendorBillCommands.TaxAmount("TT_COST", new BigDecimal("70.00"))));

        assertThat(lines(tenant, approved.getPosting().journalEntryId()))
                .contains(RECOVERABLE_CODE + " D50.0000", "2000 C1120.0000");
        assertThat(approved.getTaxByType())
                .extracting(VendorBillReview.TaxByType::source)
                .containsOnly("APPROVAL");
        assertThat(audit(tenant, bill))
                .contains("taxByType=TT_RECOVERABLE:50.00,TT_COST:70.00")
                .contains("inputTaxRecovery=TT_COST:70.00:NOT_RECOVERABLE|TT_RECOVERABLE:50.00->TAX_RECOVERABLE_"
                        + REGIME);
    }

    @Test
    @DisplayName("AC 10: a taxByType[] that does not add up to the stated tax -> 422 AP_BILL_TAX_SPLIT_MISMATCH, the"
            + " bill left awaiting approval and nothing posted")
    void mismatchIsRefused() {
        UUID tenant = recoveringTenant();
        UUID bill = bill(tenant);

        signIn(CONTROLLER, CONTROLLER_GRANTS);
        assertThatThrownBy(() -> approve(
                        tenant,
                        bill,
                        List.of(new VendorBillCommands.TaxAmount("TT_RECOVERABLE", new BigDecimal("50.00")))))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        refused -> assertThat(refused.getCode())
                                .isEqualTo(VendorBillException.Code.AP_BILL_TAX_SPLIT_MISMATCH));

        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT status FROM vendor_bill WHERE tenant_id = ? AND vendor_bill_id = ?",
                                String.class,
                                tenant,
                                bill))
                .isEqualTo("AWAITING_APPROVAL");
        assertThat(count(tenant, "journal_entry")).isZero();
        assertThat(count(tenant, "vendor_bill_tax")).isZero();
    }

    @Test
    @DisplayName("the database refuses a recovery row that says it recovered nothing without a reason")
    void recoveryRowConstraint() {
        UUID tenant = tenant();
        assertThatThrownBy(() -> new JdbcTemplate(ownerDataSource())
                        .update(
                                "INSERT INTO vendor_bill_tax_recovery (tenant_id, vendor_bill_tax_recovery_id,"
                                        + " vendor_bill_id, vendor_bill_gl_posting_id, stated_amount, recovered_amount,"
                                        + " created_at) VALUES (?, ?, ?, ?, 10, 0, now())",
                                tenant,
                                UUIDv7Generator.generate(),
                                UUIDv7Generator.generate(),
                                UUIDv7Generator.generate()))
                .hasMessageContaining("vendor_bill_tax_recovery");
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private UUID tenant() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        provisionAccounting(tenant);
        return tenant;
    }

    /** A tenant registered for {@link #REGIME}, its recovered tax mapped to {@link #RECOVERABLE_CODE}. */
    private UUID recoveringTenant() {
        UUID tenant = tenant();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        Timestamp now = Timestamp.from(Instant.now(clock));
        owner.update(
                "INSERT INTO ext_tax_registration (tenant_id, registration_id, country_code, regime,"
                        + " registration_number, jurisdiction_code, effective_from, effective_to, aggregate_version,"
                        + " changed_at, synced_at) VALUES (?, ?, ?, ?, ?, ?, DATE '2020-01-01', NULL, 1, ?, ?)",
                tenant,
                UUIDv7Generator.generate(),
                COUNTRY,
                REGIME,
                "000000000",
                COUNTRY,
                now,
                now);
        UUID account = UUIDv7Generator.generate();
        owner.update(
                "INSERT INTO gl_account (tenant_id, gl_account_id, account_code, account_name, account_type,"
                        + " account_subtype, reconcilable, activation_date, version, created_at, created_by, modified_at,"
                        + " modified_by) VALUES (?, ?, ?, 'Recoverable tax (fixture)', 'ASSET', 'CURRENT_ASSET', FALSE,"
                        + " TIMESTAMP '2020-01-01 00:00:00', 0, now(), 'test', now(), 'test')",
                tenant,
                account,
                RECOVERABLE_CODE);
        UUID category = owner.queryForObject(
                "SELECT posting_category_id FROM posting_category WHERE tenant_id = ? AND category_name = 'VENDOR_BILL'",
                UUID.class,
                tenant);
        UUID key = UUIDv7Generator.generate();
        owner.update(
                "INSERT INTO mapping_key (tenant_id, mapping_key_id, posting_category_id, key_name, description,"
                        + " is_active, created_at, created_by, modified_at, modified_by) VALUES (?, ?, ?, ?,"
                        + " 'Recoverable tax (fixture)', TRUE, now(), 'test', now(), 'test')",
                tenant,
                key,
                category,
                "TAX_RECOVERABLE_" + REGIME);
        owner.update(
                "INSERT INTO gl_mapping (tenant_id, gl_mapping_id, source_system, external_code, posting_category_id,"
                        + " mapping_key_id, gl_account_id, effective_start_date, created_at, created_by) VALUES (?, ?,"
                        + " 'ACCOUNTING', 'VENDOR_BILL_TAX_RECOVERABLE', ?, ?, ?, TIMESTAMP '2020-01-01 00:00:00',"
                        + " now(), 'test')",
                tenant,
                UUIDv7Generator.generate(),
                category,
                key,
                account);
        return tenant;
    }

    /** An EDI bill awaiting approval: net 1,000.00, tax 120.00, gross 1,120.00, no tax by type. */
    private UUID bill(UUID tenant) {
        return asTenant(
                tenant,
                () -> new TransactionTemplate(transactionManager).execute(_ -> {
                    VendorBill bill = new VendorBill();
                    bill.setVendorId(UUIDv7Generator.generate());
                    bill.setVendorName("Supply House");
                    bill.setBillNumber(
                            "INV-" + UUIDv7Generator.generate().toString().substring(24));
                    bill.setBillDate(today().minusDays(1).atStartOfDay());
                    bill.setTotalAmount(new BigDecimal("1120.00"));
                    bill.setNetAmount(new BigDecimal("1000.00"));
                    bill.setTaxAmount(new BigDecimal("120.00"));
                    bill.setStatedLineCount(1);
                    bill.setCurrency(ledgerCurrency.code());
                    bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
                    bill.setOriginEventId(UUIDv7Generator.generate());
                    bill.setOriginEventType("SUPPLIER_INVOICE_RECEIVED");
                    bill.setCreatedBy("supplier");
                    bill.setModifiedBy("supplier");
                    return billRows.saveAndFlush(bill).getVendorBillId();
                }));
    }

    /** {@link #bill} with its document's tax by type stored as the EDI listener stores it. */
    private UUID billWithTypes(UUID tenant) {
        UUID billId = bill(tenant);
        asTenant(
                tenant,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(_ -> {
                    Map<String, BigDecimal> byType = new LinkedHashMap<>();
                    byType.put("TT_RECOVERABLE", new BigDecimal("50.00"));
                    byType.put("TT_COST", new BigDecimal("70.00"));
                    statedTax.storeFromDocument(billRows.findById(billId).orElseThrow(), byType);
                }));
        return billId;
    }

    private VendorBillResponse approve(UUID tenant, UUID billId, List<VendorBillCommands.TaxAmount> taxByType) {
        return asTenant(
                tenant,
                () -> approvals.approve(
                        billId,
                        new VendorBillCommands.Approve(
                                "Checked against the document", EXPENSE, null, null, taxByType)));
    }

    private static String expenseAccount(UUID tenant) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT g.account_code FROM gl_mapping m JOIN mapping_key k ON k.tenant_id = m.tenant_id AND"
                                + " k.mapping_key_id = m.mapping_key_id JOIN posting_category c ON c.tenant_id ="
                                + " k.tenant_id AND c.posting_category_id = k.posting_category_id JOIN gl_account g ON"
                                + " g.tenant_id = m.tenant_id AND g.gl_account_id = m.gl_account_id WHERE m.tenant_id"
                                + " = ? AND c.category_name = 'VENDOR_BILL' AND k.key_name = 'EXPENSE_SHOP_SUPPLIES'",
                        String.class,
                        tenant);
    }

    private static String audit(UUID tenant, UUID billId) {
        return String.join(
                "\n",
                new JdbcTemplate(ownerDataSource())
                        .queryForList(
                                "SELECT new_value FROM accounting_audit_log WHERE tenant_id = ? AND entity_type ="
                                        + " 'VENDOR_BILL' AND entity_id = ?",
                                String.class,
                                tenant,
                                billId));
    }

    private static void signIn(String username, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    /** "code D|C amount" for each line of the entry. */
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
