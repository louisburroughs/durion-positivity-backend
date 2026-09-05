package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.ExtCustomerParty;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.service.InvoiceBalanceCalculator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Contract behavior for the credit-memo display references added by issue #1779.
 *
 * <p>List and detail responses must carry a memo reference, the original invoice's number and the
 * customer's name/number alongside — never instead of — the UUIDs that commands, links and audit
 * traceability depend on. Each display value is proved in both states the issue calls out:
 * populated when accounting can resolve it, and null when it cannot. The "unavailable" cases are
 * the ones that matter most, since the defect being fixed is a frontend that would otherwise show
 * a UUID: nothing here may ever answer a UUID string in a display field.
 *
 * <p>Display values resolve from data accounting already holds — the {@code ext_invoice} and
 * {@code ext_customer_party} replicas, both fed by the owners' domain events (ADR-0044) — so
 * these tests seed those replicas rather than stubbing a cross-service call.
 */
@DisplayName("Credit Memo Display Reference Contract Tests (issue #1779)")
public class CreditMemoDisplayReferenceContractBehaviorIT extends BaseContractIntegrationTest {

    private static final String API_V1_CREDIT_MEMOS = "/v1/accounting/credit-memos";

    private static final UUID NAMED_INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0001");
    private static final UUID UNNAMED_INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0002");
    private static final UUID NAMED_CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0003");
    private static final UUID UNKNOWN_CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0004");

    /** Any JSON key that names display text, paired with its string value. */
    private static final Pattern DISPLAY_FIELD_WITH_STRING_VALUE =
            Pattern.compile("\"(\\w*(?:[Rr]eference|DisplayName|[Nn]ame))\"\\s*:\\s*\"([^\"]*)\"");

    private static final Pattern UUID_SHAPED =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Matches the credit-memo GL accounts configured by the `test` profile. */
    private static final UUID REVENUE_ACCOUNT_ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    private static final UUID TAX_PAYABLE_ACCOUNT_ID = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");
    private static final UUID AR_ACCOUNT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String INVOICE_NUMBER = "INV-2026-004417";
    private static final String CUSTOMER_NAME = "Northside Fleet Services";
    private static final String CUSTOMER_NUMBER = "C-10427";

    @Autowired
    private CreditMemoRepository creditMemoRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private ExtCustomerPartyRepository extCustomerPartyRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private JournalEntryLineRepository journalEntryLineRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private DefaultGLMappingRepository defaultGLMappingRepository;

    @MockitoBean
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    @BeforeEach
    void setUp() {
        journalEntryLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        creditMemoRepository.deleteAll();
        extInvoiceRepository.deleteAll();
        extCustomerPartyRepository.deleteAll();
        // Children before parents: default_gl_mapping FKs gl_account.
        defaultGLMappingRepository.deleteAll();
        glAccountRepository.deleteAll();

        // The balance calculator is not what this test is about; keep the read paths quiet.
        when(invoiceBalanceCalculator.findInvoice(any(UUID.class))).thenReturn(Optional.empty());

        // Replica invoice WITH a number, and one the owner has published no number for.
        extInvoiceRepository.save(replicaInvoice(NAMED_INVOICE_ID, INVOICE_NUMBER));
        extInvoiceRepository.save(replicaInvoice(UNNAMED_INVOICE_ID, null));

        // Replica party WITH a name and number. UNKNOWN_CUSTOMER_ID is deliberately absent — a
        // party the replica has never seen.
        extCustomerPartyRepository.save(ExtCustomerParty.builder()
                .partyId(NAMED_CUSTOMER_ID)
                .partyType("COMMERCIAL")
                .displayName(CUSTOMER_NAME)
                .customerNumber(CUSTOMER_NUMBER)
                .status("ACTIVE")
                .aggregateVersion(1L)
                .updatedAt(Instant.now(Clock.systemUTC()))
                .build());
    }

    @AfterEach
    void tearDown() {
        journalEntryLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        creditMemoRepository.deleteAll();
        extInvoiceRepository.deleteAll();
        extCustomerPartyRepository.deleteAll();
        // Children before parents: default_gl_mapping FKs gl_account.
        defaultGLMappingRepository.deleteAll();
        glAccountRepository.deleteAll();
    }

    @Test
    @DisplayName("Detail response carries the memo, invoice and customer display values")
    void detailResponseCarriesDisplayValues() throws Exception {
        CreditMemo memo = creditMemoRepository.save(creditMemo(NAMED_INVOICE_ID, NAMED_CUSTOMER_ID, "CM-202401-1"));

        mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS + "/" + memo.getCreditMemoId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditMemoReference").value("CM-202401-1"))
                .andExpect(jsonPath("$.originalInvoiceReference").value(INVOICE_NUMBER))
                .andExpect(jsonPath("$.customerDisplayName").value(CUSTOMER_NAME))
                .andExpect(jsonPath("$.customerReference").value(CUSTOMER_NUMBER))
                // The identifiers stay in the contract: display values are additive, and commands,
                // links and audit still key off the UUIDs.
                .andExpect(
                        jsonPath("$.creditMemoId").value(memo.getCreditMemoId().toString()))
                .andExpect(jsonPath("$.originalInvoiceId").value(NAMED_INVOICE_ID.toString()))
                .andExpect(jsonPath("$.customerId").value(NAMED_CUSTOMER_ID.toString()));
    }

    @Test
    @DisplayName("Detail response leaves unresolvable display values null, never a UUID")
    void detailResponseLeavesUnavailableDisplayValuesNull() throws Exception {
        CreditMemo memo = creditMemoRepository.save(creditMemo(UNNAMED_INVOICE_ID, UNKNOWN_CUSTOMER_ID, null));

        String body = mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS + "/" + memo.getCreditMemoId())))
                .andExpect(status().isOk())
                // An invoice the replica knows but holds no number for, and a party it has never
                // seen at all: three display values, none of them resolvable.
                .andExpect(jsonPath("$.creditMemoReference").doesNotExist())
                .andExpect(jsonPath("$.originalInvoiceReference").doesNotExist())
                .andExpect(jsonPath("$.customerDisplayName").doesNotExist())
                .andExpect(jsonPath("$.customerReference").doesNotExist())
                .andExpect(jsonPath("$.originalInvoiceId").value(UNNAMED_INVOICE_ID.toString()))
                .andExpect(jsonPath("$.customerId").value(UNKNOWN_CUSTOMER_ID.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The specific failure #1779 guards against: a UUID smuggled into a display field. Assert
        // it structurally — no display key anywhere in the body may hold one of these ids.
        assertNoUuidInDisplayFields(body);
    }

    @Test
    @DisplayName("List response carries the same display values as detail")
    void listResponseCarriesDisplayValues() throws Exception {
        creditMemoRepository.save(creditMemo(NAMED_INVOICE_ID, NAMED_CUSTOMER_ID, "CM-202401-1"));

        mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS).param("customerId", NAMED_CUSTOMER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].creditMemoReference").value("CM-202401-1"))
                .andExpect(jsonPath("$.content[0].originalInvoiceReference").value(INVOICE_NUMBER))
                .andExpect(jsonPath("$.content[0].customerDisplayName").value(CUSTOMER_NAME))
                .andExpect(jsonPath("$.content[0].customerReference").value(CUSTOMER_NUMBER))
                .andExpect(jsonPath("$.content[0].creditMemoId").exists())
                .andExpect(jsonPath("$.content[0].originalInvoiceId").value(NAMED_INVOICE_ID.toString()));
    }

    @Test
    @DisplayName("List response leaves unresolvable display values null, never a UUID")
    void listResponseLeavesUnavailableDisplayValuesNull() throws Exception {
        creditMemoRepository.save(creditMemo(UNNAMED_INVOICE_ID, UNKNOWN_CUSTOMER_ID, null));

        String body = mockMvc.perform(
                        withAuth(get(API_V1_CREDIT_MEMOS).param("customerId", UNKNOWN_CUSTOMER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].creditMemoReference").doesNotExist())
                .andExpect(jsonPath("$.content[0].originalInvoiceReference").doesNotExist())
                .andExpect(jsonPath("$.content[0].customerDisplayName").doesNotExist())
                .andExpect(jsonPath("$.content[0].customerReference").doesNotExist())
                .andExpect(jsonPath("$.content[0].customerId").value(UNKNOWN_CUSTOMER_ID.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertNoUuidInDisplayFields(body);
    }

    @Test
    @DisplayName("A page of memos resolves display values for every row, not just the first")
    void listResolvesDisplayValuesForEveryRow() throws Exception {
        // Both rows share a customer but differ on invoice: one resolvable, one not. Proves the
        // batch lookup keys per row rather than reusing one row's resolution for the page.
        creditMemoRepository.save(creditMemo(NAMED_INVOICE_ID, NAMED_CUSTOMER_ID, "CM-202401-1"));
        creditMemoRepository.save(creditMemo(UNNAMED_INVOICE_ID, NAMED_CUSTOMER_ID, "CM-202401-2"));

        mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS)
                        .param("customerId", NAMED_CUSTOMER_ID.toString())
                        .param("sort", "creditMemoReference,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].creditMemoReference").value("CM-202401-1"))
                .andExpect(jsonPath("$.content[0].originalInvoiceReference").value(INVOICE_NUMBER))
                .andExpect(jsonPath("$.content[0].customerDisplayName").value(CUSTOMER_NAME))
                .andExpect(jsonPath("$.content[1].creditMemoReference").value("CM-202401-2"))
                .andExpect(jsonPath("$.content[1].originalInvoiceReference").doesNotExist())
                .andExpect(jsonPath("$.content[1].customerDisplayName").value(CUSTOMER_NAME));
    }

    @Test
    @DisplayName("Creating a memo assigns a real CM-{YYYYMM}-{n} reference, and the next one increments")
    void createAssignsAndIncrementsTheReference() throws Exception {
        // Everything above persists CreditMemo rows with a literal reference, which proves only
        // that a stored string round-trips. This exercises the assignment path for real: scope-key
        // derivation, sequence bootstrap on first use of the month, the locked read/increment, and
        // the uq_credit_memo_reference uniqueness the migration adds.
        seedInvoiceForCreation();

        String first = createCreditMemo("25.00");
        String second = createCreditMemo("25.00");

        assertThat(first).matches("CM-\\d{6}-\\d+");
        assertThat(second).matches("CM-\\d{6}-\\d+");
        assertThat(second).isNotEqualTo(first);

        String scope = first.substring(0, first.lastIndexOf('-'));
        assertThat(second).startsWith(scope + "-");
        long firstN = Long.parseLong(first.substring(first.lastIndexOf('-') + 1));
        long secondN = Long.parseLong(second.substring(second.lastIndexOf('-') + 1));
        assertThat(secondN).isEqualTo(firstN + 1);

        // Both persisted, both distinct: the unique index is satisfied by real assignment, not by
        // the test handing out its own values.
        assertThat(creditMemoRepository.findAll())
                .extracting(CreditMemo::getCreditMemoReference)
                .containsExactlyInAnyOrder(first, second);
    }

    /**
     * Put the creation path in a workable state: the configured GL accounts must exist, and the
     * balance calculator must answer a finalized invoice with room to credit. Everything else in
     * this class is a read-path test and needs neither.
     */
    private void seedInvoiceForCreation() {
        glAccountRepository.save(glAccount(REVENUE_ACCOUNT_ID, "4000-000", "Revenue", AccountType.REVENUE));
        glAccountRepository.save(glAccount(TAX_PAYABLE_ACCOUNT_ID, "2200-000", "Tax Payable", AccountType.LIABILITY));
        glAccountRepository.save(glAccount(AR_ACCOUNT_ID, "1200-000", "Accounts Receivable", AccountType.ASSET));

        ExtInvoice invoice = replicaInvoice(NAMED_INVOICE_ID, INVOICE_NUMBER);
        when(invoiceBalanceCalculator.findInvoice(NAMED_INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceBalanceCalculator.isArEligible(any(ExtInvoice.class))).thenReturn(true);
        when(invoiceBalanceCalculator.balanceDue(any(ExtInvoice.class))).thenReturn(new BigDecimal("110.00"));
    }

    private GLAccount glAccount(UUID id, String code, String name, AccountType accountType) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(id);
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(accountType);
        account.setActivationDate(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        account.setCreatedBy(TEST_USER);
        account.setModifiedBy(TEST_USER);
        return account;
    }

    /**
     * POST a credit memo against the seeded invoice and return the assigned display reference.
     */
    private String createCreditMemo(String creditAmount) throws Exception {
        String request = """
                {"originalInvoiceId":"%s","creditAmount":%s,"reasonCode":"RETURNED_GOODS",
                 "justificationNote":"contract test"}
                """.formatted(NAMED_INVOICE_ID, creditAmount);

        String body = mockMvc.perform(withAuth(post(API_V1_CREDIT_MEMOS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditMemoReference").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(body).get("creditMemoReference").asString();
    }

    /**
     * No display field anywhere in the response may carry a UUID (issue #1779).
     *
     * <p>Scans every key of the serialized body whose name marks it as display text and fails if
     * its value is UUID-shaped, rather than enumerating the four fields and four fixture ids this
     * test happens to use. That distinction matters: the enumerated form passed for a fifth
     * display field added later, or for a UUID sourced from anywhere but those four constants —
     * a memo's own {@code creditMemoId} leaking into {@code creditMemoReference}, say.
     */
    private static void assertNoUuidInDisplayFields(String body) {
        Matcher displayValues = DISPLAY_FIELD_WITH_STRING_VALUE.matcher(body);
        while (displayValues.find()) {
            String field = displayValues.group(1);
            String value = displayValues.group(2);
            assertThat(UUID_SHAPED.matcher(value).matches())
                    .as(
                            "display field \"%s\" answered a UUID (%s); a display value must be null when "
                                    + "accounting cannot resolve it, never the identifier as text",
                            field, value)
                    .isFalse();
        }
    }

    private static ExtInvoice replicaInvoice(UUID invoiceId, String invoiceNumber) {
        return ExtInvoice.builder()
                .invoiceId(invoiceId)
                .invoiceNumber(invoiceNumber)
                .partyId(NAMED_CUSTOMER_ID.toString())
                .status("FINALIZED")
                .total(new BigDecimal("110.00"))
                .tax(new BigDecimal("10.00"))
                .finalizedAt(Instant.now(Clock.systemUTC()))
                .aggregateVersion(1L)
                .updatedAt(Instant.now(Clock.systemUTC()))
                .build();
    }

    private static CreditMemo creditMemo(UUID invoiceId, UUID customerId, String reference) {
        CreditMemo memo = new CreditMemo();
        memo.setCreditMemoReference(reference);
        memo.setOriginalInvoiceId(invoiceId);
        memo.setCustomerId(customerId);
        memo.setCreditAmount(new BigDecimal("100.00"));
        memo.setTaxAmountReversed(new BigDecimal("10.00"));
        memo.setReasonCode("RETURNED_GOODS");
        memo.setStatus(CreditMemoStatus.POSTED);
        memo.setCreatedByUserId(TEST_USER);
        memo.setCurrency("USD");
        memo.setCreationTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        memo.setPostedTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        return memo;
    }
}
