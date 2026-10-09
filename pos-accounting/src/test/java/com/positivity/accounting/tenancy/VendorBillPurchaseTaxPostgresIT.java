package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.config.TaxCountry;
import com.positivity.accounting.internal.dto.TaxPurchaseRules;
import com.positivity.accounting.internal.dto.TaxUseQuote;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.TaxOnResaleOverrideSource;
import com.positivity.accounting.internal.enums.VendorBillCheckOutcome;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.VendorBillApprovalService;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CAP:550 S43 (#2604, AW44) on the full Flyway chain: the hold for tax on goods for resale and its two overrides, the
 * self-assessed (use) tax accrual to 2240 and its void mirror, the rules switched off, pos-tax down, the mapping
 * pre-check, V24's check constraint and the seed's 2240 / {@code USE_TAX_PAYABLE} on a provisioned tenant. pos-tax is
 * the base class's mocked {@code TaxReferenceClient}; the rule fixture is HOLD / self-assess, and the fixture use-tax
 * answer is 8.5 % of the line (7.25 % + 1 % + 0.25 %, not tax law).
 *
 * <p>Requires Docker.
 */
@DisplayName("S43 purchase-tax rules: the tax-on-resale hold and the use-tax accrual (#2604, real Postgres)")
class VendorBillPurchaseTaxPostgresIT extends PostgresTenancyTestBase {

    private static final String CONTROLLER = "controller.cfo";
    private static final String[] CONTROLLER_GRANTS = {
        "accounting:ap:view",
        "accounting:ap:approve",
        "accounting:ap:reject",
        "accounting:ap:approve_over_limit",
        "accounting:je:post"
    };
    private static final String JUSTIFICATION = "Vendor resale certificate pending";
    private static final VendorBillReview.Classification GOODS =
            new VendorBillReview.Classification(VendorBillDebitClass.GOODS, null);
    private static final VendorBillReview.Classification SHOP_SUPPLIES =
            new VendorBillReview.Classification(VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES");

    @Autowired
    private VendorBillApprovalService approvals;

    @Autowired
    private VendorBillRepository billRows;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TaxCountry taxCountry;

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

    // ---- AC 1, AC 2: the hold and its overrides ---------------------------------------------------------------

    @Test
    @DisplayName("AC1: a GOODS bill, net 400.00 with tax 28.00, vendor setting off, approved without an override:"
            + " 422 AP_BILL_TAX_ON_RESALE_GOODS, no approval fields, no entry, one VENDOR_BILL_APPROVE_REFUSED row")
    void holdRefusesTheApproval() {
        UUID tenant = tenant();
        rules(true, false);
        UUID billId = submitted(tenant, goodsBill(tenant, "INV-4300"), GOODS);

        assertThatThrownBy(() -> approve(tenant, billId, null)).isInstanceOfSatisfying(VendorBillException.class, e -> {
            assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_TAX_ON_RESALE_GOODS);
            assertThat(e.getMessage()).contains("INV-4300", "28.00");
        });

        assertThat(status(tenant, billId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(column(tenant, billId, "approved_by")).isNull();
        assertThat(column(tenant, billId, "tax_on_resale_override")).isNull();
        assertThat(count(tenant, "journal_entry")).isZero();
        assertThat(auditOperations(tenant, billId))
                .containsExactly("VENDOR_BILL_SUBMIT", "VENDOR_BILL_APPROVE_REFUSED");
        assertThat(lastAudit(tenant, billId)).contains("code=AP_BILL_TAX_ON_RESALE_GOODS", "taxAmount=28.00");
    }

    @Test
    @DisplayName("AC1 [M]: the same bill unclassified answers AP_BILL_UNCLASSIFIED, the hold coming after"
            + " requireClassified")
    void unclassifiedBeforeTheHold() {
        UUID tenant = tenant();
        rules(true, false);
        UUID billId = submitted(tenant, goodsBill(tenant, "INV-4301"), null);

        assertThatThrownBy(() -> approve(tenant, billId, null))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_UNCLASSIFIED));
        assertThat(lastAudit(tenant, billId)).contains("code=AP_BILL_UNCLASSIFIED");
    }

    @Test
    @DisplayName("AC2: with taxOnResaleOverrideJustification the bill is approved and posts Dr 2100 400.00 / Dr 5050"
            + " 28.00 / Cr 2000 428.00, override BILL stored and read back, the audit row naming the source only")
    void perBillOverride() {
        UUID tenant = tenant();
        rules(true, false);
        UUID billId = submitted(tenant, goodsBill(tenant, "INV-4302"), GOODS);

        VendorBillResponse approved = approve(tenant, billId, "  " + JUSTIFICATION + "  ");

        assertThat(approved.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(lines(tenant, approved.getPosting().journalEntryId()))
                .containsExactly("2100 D400.0000", "5050 D28.0000", "2000 C428.0000");
        assertThat(column(tenant, billId, "tax_on_resale_override")).isEqualTo("BILL");
        assertThat(column(tenant, billId, "tax_on_resale_override_justification"))
                .isEqualTo(JUSTIFICATION);
        assertThat(approved.getTaxOnResaleOverride())
                .isEqualTo(new VendorBillReview.TaxOnResaleOverride(TaxOnResaleOverrideSource.BILL, JUSTIFICATION));
        assertThat(check(approved)).satisfies(check -> {
            assertThat(check.outcome()).isEqualTo(VendorBillCheckOutcome.PASS);
            assertThat(check.args()).containsEntry("acceptedBy", "BILL");
        });
        assertThat(lastAudit(tenant, billId))
                .contains("taxOnResaleOverride=BILL")
                .doesNotContain(JUSTIFICATION);
    }

    @Test
    @DisplayName("AC2: with the vendor's acceptTaxOnResaleGoods on, the bill is approved without a justification,"
            + " override VENDOR_SETTING; before it, the read's check is PASS acceptedBy VENDOR_SETTING")
    void vendorSettingOverride() {
        UUID tenant = tenant();
        rules(true, false);
        UUID vendor = UUIDv7Generator.generate();
        acceptsTaxOnResaleGoods(tenant, vendor);
        UUID billId = submitted(tenant, goodsBill(tenant, vendor, "INV-4303", "428.00", "400.00", "28.00"), GOODS);

        VendorBillResponse read = asTenant(tenant, () -> approvals.getBill(billId));
        assertThat(check(read).outcome()).isEqualTo(VendorBillCheckOutcome.PASS);
        assertThat(check(read).args()).containsEntry("acceptedBy", "VENDOR_SETTING");

        VendorBillResponse approved = approve(tenant, billId, null);

        assertThat(approved.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(column(tenant, billId, "tax_on_resale_override")).isEqualTo("VENDOR_SETTING");
        assertThat(column(tenant, billId, "tax_on_resale_override_justification"))
                .isNull();
        assertThat(lastAudit(tenant, billId)).contains("taxOnResaleOverride=VENDOR_SETTING");
    }

    @Test
    @DisplayName("AC2: a justification of 9 characters is 400 JUSTIFICATION_REQUIRED naming the field; nothing is"
            + " written; the bill's read FAIL check carries taxAmount and currencyCode")
    void shortJustification() {
        UUID tenant = tenant();
        rules(true, false);
        UUID billId = submitted(tenant, goodsBill(tenant, "INV-4304"), GOODS);

        VendorBillResponse read = asTenant(tenant, () -> approvals.getBill(billId));
        assertThat(check(read).outcome()).isEqualTo(VendorBillCheckOutcome.FAIL);
        assertThat(check(read).args()).containsEntry("taxAmount", "28.00").containsEntry("currencyCode", "USD");
        assertThat(read.getAvailableActions())
                .filteredOn(a -> a.action() == com.positivity.accounting.internal.enums.VendorBillAction.APPROVE)
                .singleElement()
                .satisfies(a -> assertThat(a.allowed()).isTrue());

        assertThatThrownBy(() -> approve(tenant, billId, "123456789"))
                .isInstanceOfSatisfying(VendorBillException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED);
                    assertThat(e.getMessage()).contains("taxOnResaleOverrideJustification");
                });
        assertThat(status(tenant, billId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(auditOperations(tenant, billId)).containsExactly("VENDOR_BILL_SUBMIT");
    }

    // ---- AC 4, AC 5: the accrual ------------------------------------------------------------------------------

    @Test
    @DisplayName("AC4: an EXPENSE bill, net 200.00 with no tax, posts Dr 6340 217.00 / Cr 2000 200.00 / Cr 2240 17.00"
            + " from one USE quote (ledger currency, posting date, the bill, not committable); its void mirrors all"
            + " three lines")
    void useTaxAccruesAndTheVoidMirrorsIt() {
        UUID tenant = tenant();
        rules(false, true);
        quoteAtFixtureRate();
        UUID billId = submitted(tenant, expenseBill(tenant, "INV-4400", "200.00", "200.00", "0.00"), SHOP_SUPPLIES);

        VendorBillResponse approved = approve(tenant, billId, null);

        assertThat(lines(tenant, approved.getPosting().journalEntryId()))
                .containsExactly("6340 D217.0000", "2000 C200.0000", "2240 C17.0000");
        ArgumentCaptor<TaxUseQuote.Request> quote = ArgumentCaptor.forClass(TaxUseQuote.Request.class);
        verify(taxReferenceClient).useTax(quote.capture());
        assertThat(quote.getValue().calculationType()).isEqualTo("USE");
        assertThat(quote.getValue().currencyCode()).isEqualTo("USD");
        assertThat(quote.getValue().committable()).isFalse();
        assertThat(quote.getValue().referenceId()).isEqualTo(billId);
        assertThat(quote.getValue().transactionDate())
                .isEqualTo(approved.getPosting().postingDate().toString());
        assertThat(quote.getValue().destinationAddress().countryCode()).isEqualTo(taxCountry.code());
        assertThat(quote.getValue().lineItems())
                .singleElement()
                .satisfies(line -> assertThat(line.unitPrice()).isEqualByComparingTo("200.00"));
        assertThat(lastAudit(tenant, billId)).contains("useTaxAmount=17.00");

        VendorBillResponse voided = asTenant(
                tenant,
                () -> approvals.voidBill(billId, new VendorBillCommands.VoidBill("Billed twice, confirmed", null)));
        assertThat(voided.getStatus()).isEqualTo(VendorBillStatus.VOIDED);
        assertThat(lines(tenant, reversalOf(tenant, billId)))
                .containsExactly("6340 C217.0000", "2000 D200.0000", "2240 D17.0000");
    }

    @Test
    @DisplayName("AC5: an EXPENSE bill with tax 5.00, a GOODS bill with no tax and an EXPENSE credit note post no 2240"
            + " leg, and pos-tax USE is never called")
    void noAccrual() {
        UUID tenant = tenant();
        rules(true, true);
        quoteAtFixtureRate();
        UUID taxed = submitted(tenant, expenseBill(tenant, "INV-4500", "205.00", "200.00", "5.00"), SHOP_SUPPLIES);
        UUID goods = submitted(
                tenant, goodsBill(tenant, UUIDv7Generator.generate(), "INV-4501", "300.00", "300.00", "0.00"), GOODS);
        UUID credit = submitted(tenant, expenseBill(tenant, "CN-4502", "-50.00", "-50.00", "0.00"), SHOP_SUPPLIES);

        assertThat(lines(tenant, approve(tenant, taxed, null).getPosting().journalEntryId()))
                .containsExactly("6340 D205.0000", "2000 C205.0000");
        assertThat(lines(tenant, approve(tenant, goods, null).getPosting().journalEntryId()))
                .containsExactly("2100 D300.0000", "2000 C300.0000");
        assertThat(lines(tenant, approve(tenant, credit, null).getPosting().journalEntryId()))
                .containsExactly("6340 C50.0000", "2000 D50.0000");
        verify(taxReferenceClient, never()).useTax(any());
    }

    // ---- AC 6, AC 7, AC 9 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC6: the stub answers configured false: AC1's bill approves with its tax in 5050 and AC4's bill posts"
            + " Dr 6340 200.00 / Cr 2000 200.00, no override stored, no USE call")
    void rulesNotConfigured() {
        UUID tenant = tenant();
        // The base class's default: configured false, ALLOW, false.
        UUID goods = submitted(tenant, goodsBill(tenant, "INV-4600"), GOODS);
        UUID expense = submitted(tenant, expenseBill(tenant, "INV-4601", "200.00", "200.00", "0.00"), SHOP_SUPPLIES);

        assertThat(lines(tenant, approve(tenant, goods, null).getPosting().journalEntryId()))
                .containsExactly("2100 D400.0000", "5050 D28.0000", "2000 C428.0000");
        assertThat(column(tenant, goods, "tax_on_resale_override")).isNull();
        assertThat(lines(tenant, approve(tenant, expense, null).getPosting().journalEntryId()))
                .containsExactly("6340 D200.0000", "2000 C200.0000");
        verify(taxReferenceClient, never()).useTax(any());
    }

    @Test
    @DisplayName("AC7 [M]: pos-tax unreachable: approving AC4's bill answers 503 SERVICE_UNAVAILABLE and writes"
            + " nothing; the read of a held bill succeeds with TAX_ON_RESALE_GOODS NOT_APPLICABLE rulesUnavailable")
    void posTaxDown() {
        UUID tenant = tenant();
        when(taxReferenceClient.purchaseRules(any(), any()))
                .thenThrow(new TaxServiceUnavailableException("The tax service is unavailable"));
        UUID expense = submitted(tenant, expenseBill(tenant, "INV-4700", "200.00", "200.00", "0.00"), SHOP_SUPPLIES);
        UUID goods = submitted(tenant, goodsBill(tenant, "INV-4701"), GOODS);

        assertThatThrownBy(() -> approve(tenant, expense, null)).isInstanceOf(TaxServiceUnavailableException.class);
        assertThat(status(tenant, expense)).isEqualTo("AWAITING_APPROVAL");
        assertThat(count(tenant, "journal_entry")).isZero();
        assertThat(auditOperations(tenant, expense))
                .as("a pos-tax failure writes no refusal row")
                .containsExactly("VENDOR_BILL_SUBMIT");

        VendorBillResponse read = asTenant(tenant, () -> approvals.getBill(goods));
        assertThat(check(read).outcome()).isEqualTo(VendorBillCheckOutcome.NOT_APPLICABLE);
        assertThat(check(read).args()).containsEntry("rulesUnavailable", "true");
    }

    @Test
    @DisplayName("AC7: the USE quote failing after the rules answered is the same 503, nothing written")
    void quoteDown() {
        UUID tenant = tenant();
        rules(false, true);
        when(taxReferenceClient.useTax(any())).thenThrow(new TaxServiceUnavailableException("unavailable"));
        UUID expense = submitted(tenant, expenseBill(tenant, "INV-4702", "200.00", "200.00", "0.00"), SHOP_SUPPLIES);

        assertThatThrownBy(() -> approve(tenant, expense, null)).isInstanceOf(TaxServiceUnavailableException.class);
        assertThat(count(tenant, "journal_entry")).isZero();
        assertThat(auditOperations(tenant, expense)).containsExactly("VENDOR_BILL_SUBMIT");
    }

    @Test
    @DisplayName("AC9: without the USE_TAX_PAYABLE mapping, approving AC4's bill is 422 GL_MAPPING_NOT_CONFIGURED"
            + " naming VENDOR_BILL/USE_TAX_PAYABLE, the bill unchanged")
    void useTaxMappingMissing() {
        UUID tenant = tenant();
        rules(false, true);
        quoteAtFixtureRate();
        new JdbcTemplate(ownerDataSource())
                .update(
                        "DELETE FROM gl_mapping WHERE tenant_id = ? AND mapping_key_id IN (SELECT k.mapping_key_id FROM"
                                + " mapping_key k JOIN posting_category c ON c.tenant_id = k.tenant_id AND"
                                + " c.posting_category_id = k.posting_category_id WHERE k.tenant_id = ? AND"
                                + " c.category_name = 'VENDOR_BILL' AND k.key_name = 'USE_TAX_PAYABLE')",
                        tenant,
                        tenant);
        UUID billId = submitted(tenant, expenseBill(tenant, "INV-4900", "200.00", "200.00", "0.00"), SHOP_SUPPLIES);

        assertThatThrownBy(() -> approve(tenant, billId, null))
                .isInstanceOfSatisfying(
                        GLMappingNotConfiguredException.class,
                        e -> assertThat(e.getReferenceId()).isEqualTo("VENDOR_BILL/USE_TAX_PAYABLE"));
        assertThat(status(tenant, billId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(count(tenant, "journal_entry")).isZero();
    }

    // ---- data -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Seed: a provisioned tenant has 2240 Use Tax Payable (LIABILITY, TAX_PAYABLE) and VENDOR_BILL /"
            + " USE_TAX_PAYABLE mapped to it")
    void seedOnAProvisionedTenant() {
        UUID tenant = tenant();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(owner.queryForMap(
                        "SELECT account_name, account_type, account_subtype FROM gl_account WHERE tenant_id = ? AND"
                                + " account_code = '2240'",
                        tenant))
                .containsEntry("account_name", "Use Tax Payable")
                .containsEntry("account_type", "LIABILITY")
                .containsEntry("account_subtype", "TAX_PAYABLE");
        assertThat(owner.queryForObject(
                        "SELECT g.account_code FROM gl_mapping m JOIN mapping_key k ON k.tenant_id = m.tenant_id AND"
                                + " k.mapping_key_id = m.mapping_key_id JOIN posting_category c ON c.tenant_id ="
                                + " k.tenant_id AND c.posting_category_id = k.posting_category_id JOIN gl_account g ON"
                                + " g.tenant_id = m.tenant_id AND g.gl_account_id = m.gl_account_id WHERE m.tenant_id ="
                                + " ? AND c.category_name = 'VENDOR_BILL' AND k.key_name = 'USE_TAX_PAYABLE'",
                        String.class,
                        tenant))
                .isEqualTo("2240");
    }

    @Test
    @DisplayName("V24: the check constraint refuses BILL without a justification of 10 characters, VENDOR_SETTING with"
            + " one, and a justification without a source")
    void overrideCheckConstraint() {
        UUID tenant = tenant();
        UUID billId = expenseBill(tenant, "INV-4800", "200.00", "200.00", "0.00");
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        String update = "UPDATE vendor_bill SET tax_on_resale_override = ?, tax_on_resale_override_justification = ?"
                + " WHERE tenant_id = ? AND vendor_bill_id = ?";
        assertThatThrownBy(() -> owner.update(update, "BILL", null, tenant, billId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> owner.update(update, "BILL", "  short   ", tenant, billId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> owner.update(update, "VENDOR_SETTING", JUSTIFICATION, tenant, billId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> owner.update(update, null, JUSTIFICATION, tenant, billId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(owner.update(update, "BILL", JUSTIFICATION, tenant, billId)).isEqualTo(1);
        assertThat(owner.update(update, "VENDOR_SETTING", null, tenant, billId)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-0072: the override justification never appears in a log line of the approval")
    void justificationNeverLogged() {
        UUID tenant = tenant();
        rules(true, false);
        UUID billId = submitted(tenant, goodsBill(tenant, "INV-4305"), GOODS);
        String secret = "Resale certificate RC-77-SECRET pending";
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            approve(tenant, billId, secret);
            assertThatThrownBy(() -> approve(tenant, billId, secret)).isInstanceOf(VendorBillException.class);
        } finally {
            root.detachAppender(appender);
        }
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("RC-77-SECRET"));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private void rules(boolean hold, boolean selfAssess) {
        when(taxReferenceClient.purchaseRules(any(), any()))
                .thenAnswer(inv -> new TaxPurchaseRules(
                        inv.getArgument(0), inv.getArgument(1), "STUB", true, hold ? "HOLD" : "ALLOW", selfAssess));
    }

    /** The fixture use-tax answer: 8.5 % of each line (7.25 % + 1 % + 0.25 %), rounded to the cent; not tax law. */
    private void quoteAtFixtureRate() {
        when(taxReferenceClient.useTax(any())).thenAnswer(inv -> {
            TaxUseQuote.Request request = inv.getArgument(0);
            List<TaxUseQuote.LineTax> taxes = request.lineItems().stream()
                    .map(line -> new TaxUseQuote.LineTax(
                            line.lineItemId(),
                            line.unitPrice()
                                    .multiply(new BigDecimal("0.085"))
                                    .setScale(2, java.math.RoundingMode.HALF_UP)))
                    .toList();
            return new TaxUseQuote.Response(
                    taxes.stream().map(TaxUseQuote.LineTax::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add), taxes);
        });
    }

    private VendorBillResponse approve(UUID tenant, UUID billId, String taxOnResaleOverrideJustification) {
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        return asTenant(
                tenant,
                () -> approvals.approve(
                        billId,
                        new VendorBillCommands.Approve(null, null, null, null, taxOnResaleOverrideJustification)));
    }

    private UUID submitted(UUID tenant, UUID billId, VendorBillReview.Classification classification) {
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        asTenant(
                tenant,
                () -> approvals.submitForApproval(
                        billId, new VendorBillCommands.Submit("Checked the vendor's bill", classification, null)));
        return billId;
    }

    private static VendorBillReview.Check check(VendorBillResponse read) {
        return read.getChecks().stream()
                .filter(c -> "TAX_ON_RESALE_GOODS".equals(c.code()))
                .findFirst()
                .orElseThrow();
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

    private UUID goodsBill(UUID tenant, String number) {
        return goodsBill(tenant, UUIDv7Generator.generate(), number, "428.00", "400.00", "28.00");
    }

    private UUID goodsBill(UUID tenant, UUID vendor, String number, String gross, String net, String tax) {
        return ediBill(tenant, vendor, number, gross, net, tax);
    }

    private UUID expenseBill(UUID tenant, String number, String gross, String net, String tax) {
        return ediBill(tenant, UUIDv7Generator.generate(), number, gross, net, tax);
    }

    /** An EDI bill as the supplier listener writes it: no lines, the stated net and tax, PENDING_RECEIPT_MATCH. */
    private UUID ediBill(UUID tenant, UUID vendor, String number, String gross, String net, String tax) {
        LocalDate billDate = today().minusDays(1);
        return asTenant(
                tenant,
                () -> new TransactionTemplate(transactionManager).execute(_ -> {
                    VendorBill bill = new VendorBill();
                    bill.setVendorId(vendor);
                    bill.setVendorName("Supply House");
                    bill.setBillNumber(number);
                    bill.setBillDate(billDate.atStartOfDay());
                    bill.setTotalAmount(new BigDecimal(gross));
                    bill.setNetAmount(new BigDecimal(net));
                    bill.setTaxAmount(new BigDecimal(tax));
                    bill.setStatedLineCount(1);
                    bill.setCurrency("USD");
                    bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
                    bill.setOriginEventId(UUIDv7Generator.generate());
                    bill.setOriginEventType("SUPPLIER_INVOICE_RECEIVED");
                    bill.setCreatedBy("supplier");
                    bill.setModifiedBy("supplier");
                    return billRows.saveAndFlush(bill).getVendorBillId();
                }));
    }

    /** The vendor's AP settings row with acceptTaxOnResaleGoods on, as the settings PUT leaves it. */
    private static void acceptsTaxOnResaleGoods(UUID tenant, UUID vendor) {
        new JdbcTemplate(ownerDataSource())
                .update(
                        "INSERT INTO ap_vendor_settings (tenant_id, ap_vendor_settings_id, vendor_id, ap_hold,"
                                + " information_return_reportable, accept_tax_on_resale_goods, version, created_at,"
                                + " updated_at) VALUES (?, ?, ?, false, false, true, 0, now(), now())",
                        tenant,
                        UUIDv7Generator.generate(),
                        vendor);
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

    private static void signIn(String username, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private static String status(UUID tenant, UUID billId) {
        return column(tenant, billId, "status");
    }

    private static String column(UUID tenant, UUID billId, String column) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT " + column + "::text FROM vendor_bill WHERE tenant_id = ? AND vendor_bill_id = ?",
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

    private static String lastAudit(UUID tenant, UUID billId) {
        List<String> rows = new JdbcTemplate(ownerDataSource())
                .queryForList(
                        "SELECT new_value FROM accounting_audit_log WHERE tenant_id = ? AND entity_type = 'VENDOR_BILL'"
                                + " AND entity_id = ? ORDER BY timestamp, audit_log_id",
                        String.class,
                        tenant,
                        billId);
        return rows.getLast();
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
