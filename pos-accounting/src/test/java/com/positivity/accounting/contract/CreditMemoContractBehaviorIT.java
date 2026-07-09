package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.dto.CreateCreditMemoRequest;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.service.InvoiceBalanceCalculator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Contract Behavioral Integration Tests for Credit Memo (CAP-052)
 *
 * This test suite validates the behavioral contracts defined in:
 * durion/domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md
 * Section: Credit Memo endpoints (v1.1)
 *
 * Each test maps to a scenario defined in the contract guide's "Examples"
 * section for Credit Memo operations.
 * Tests run against the actual service in test mode (not mocked).
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@DisplayName("Credit Memo Backend Contract Behavioral Tests (CAP-052)")
public class CreditMemoContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private CreditMemoRepository creditMemoRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private JournalEntryLineRepository journalEntryLineRepository;

    @MockitoBean
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    private static final String API_V1_CREDIT_MEMOS = "/v1/accounting/credit-memos";
    private static final UUID REVENUE_ACCOUNT_ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    private static final UUID TAX_PAYABLE_ACCOUNT_ID = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");
    private static final UUID AR_ACCOUNT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        // Clean up any existing test data
        journalEntryLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        creditMemoRepository.deleteAll();
        glAccountRepository.deleteAll();

        // Default replica state (ADR-0044 #842): a FINALIZED $110 invoice with a $110
        // derived balance, matching the previous invoice-service stubs.
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ExtInvoice defaultInvoice = ExtInvoice.builder()
                .invoiceId(invoiceId)
                .workorderId(UUID.fromString("00000000-0000-0000-0000-0000000000ff"))
                .partyId(customerId.toString())
                .status("FINALIZED")
                .total(new BigDecimal("110.00"))
                .finalizedAt(Instant.now(Clock.systemUTC()))
                .aggregateVersion(1L)
                .updatedAt(Instant.now(Clock.systemUTC()))
                .build();
        when(invoiceBalanceCalculator.findInvoice(any(UUID.class))).thenReturn(java.util.Optional.of(defaultInvoice));
        when(invoiceBalanceCalculator.isArEligible(any(ExtInvoice.class))).thenReturn(true);
        when(invoiceBalanceCalculator.balanceDue(any(ExtInvoice.class))).thenReturn(new BigDecimal("110.00"));

        seedConfiguredGLAccounts();
    }

    @AfterEach
    void tearDown() {
        // Clean up test data after each test
        journalEntryLineRepository.deleteAll();
        journalEntryRepository.deleteAll();
        creditMemoRepository.deleteAll();
        glAccountRepository.deleteAll();
    }

    private void seedConfiguredGLAccounts() {
        glAccountRepository.save(createGlAccount(REVENUE_ACCOUNT_ID, "4000-000", "Revenue", AccountType.REVENUE));
        glAccountRepository.save(
                createGlAccount(TAX_PAYABLE_ACCOUNT_ID, "2200-000", "Tax Payable", AccountType.LIABILITY));
        glAccountRepository.save(createGlAccount(AR_ACCOUNT_ID, "1200-000", "Accounts Receivable", AccountType.ASSET));
    }

    private GLAccount createGlAccount(UUID id, String code, String name, AccountType accountType) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(id);
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(accountType);
        account.setActivationDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));
        account.setCreatedBy(TEST_USER);
        account.setModifiedBy(TEST_USER);
        return account;
    }

    // ===============================================
    // HAPPY PATH SCENARIOS (from Contract Guide Examples)
    // ===============================================

    @Test
    @DisplayName("CP-CM-001: Create full credit memo (Contract Example 1)")
    void testCreateFullCreditMemo_Success() throws Exception {
        // Arrange: Full credit for returned goods (per contract guide example)
        UUID testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateCreditMemoRequest request = new CreateCreditMemoRequest();
        request.setOriginalInvoiceId(testInvoiceId);
        request.setCreditAmount(new BigDecimal("100.00"));
        request.setReasonCode("RETURNED_GOODS");
        request.setJustificationNote("Customer returned all items due to shipping damage");

        // Act: POST to Credit Memo endpoint
        MvcResult result = mockMvc.perform(withAuth(post(API_V1_CREDIT_MEMOS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditMemoId").exists())
                .andExpect(jsonPath("$.originalInvoiceId").value(testInvoiceId.toString()))
                .andExpect(jsonPath("$.creditAmount").value(100.00))
                .andExpect(jsonPath("$.taxAmountReversed").exists()) // Calculated proportionally
                .andExpect(jsonPath("$.totalAmount").exists()) // Credit + tax
                .andExpect(jsonPath("$.reasonCode").value("RETURNED_GOODS"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.priorPeriodAdjustment").value(false))
                .andExpect(jsonPath("$.creationTimestamp").exists())
                .andExpect(jsonPath("$.postedTimestamp").exists())
                .andReturn();

        // Assert: Verify entity persisted with correct values
        String responseBody = result.getResponse().getContentAsString();
        var responseMap = objectMapper.readValue(responseBody, java.util.Map.class);
        UUID creditMemoId = UUID.fromString((String) responseMap.get("creditMemoId"));

        CreditMemo saved = creditMemoRepository.findById(creditMemoId).orElseThrow();
        assertThat(saved.getOriginalInvoiceId()).isEqualTo(testInvoiceId);
        assertThat(saved.getCreditAmount()).isEqualByComparingTo("100.00");
        assertThat(saved.getReasonCode()).isEqualTo("RETURNED_GOODS");
        assertThat(saved.getStatus()).isEqualTo(CreditMemoStatus.POSTED);
    }

    @Test
    @DisplayName("CP-CM-002: Create partial credit memo (Contract Example 2)")
    void testCreatePartialCreditMemo_Success() throws Exception {
        // Arrange: Partial credit for pricing error
        UUID testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateCreditMemoRequest request = new CreateCreditMemoRequest();
        request.setOriginalInvoiceId(testInvoiceId);
        request.setCreditAmount(new BigDecimal("25.00")); // Partial amount
        request.setReasonCode("PRICING_ERROR");
        request.setJustificationNote("Incorrect pricing applied to line item #3");

        // Act: POST to Credit Memo endpoint
        mockMvc.perform(withAuth(post(API_V1_CREDIT_MEMOS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditAmount").value(25.00))
                .andExpect(jsonPath("$.reasonCode").value("PRICING_ERROR"))
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    @Test
    @DisplayName("CP-CM-003: List credit memos with pagination")
    void testListCreditMemos_Paginated() throws Exception {
        // Arrange: Create multiple credit memos
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        for (int i = 0; i < 5; i++) {
            CreditMemo cm = new CreditMemo();
            cm.setOriginalInvoiceId(invoiceId);
            cm.setCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            cm.setCreditAmount(new BigDecimal("10.00"));
            cm.setTaxAmountReversed(new BigDecimal("1.00"));
            // totalAmount is now calculated: 10.00 + 1.00 = 11.00
            cm.setReasonCode("SERVICE_CREDIT");
            cm.setStatus(CreditMemoStatus.POSTED);
            cm.setCreatedByUserId(TEST_USER);
            cm.setCurrency("USD");
            cm.setPriorPeriodAdjustment(false);
            creditMemoRepository.save(cm);
        }

        // Act: GET list with pagination
        mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS)).param("page", "0").param("size", "3"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    @DisplayName("CP-CM-004: Get credit memo by ID")
    void testGetCreditMemo_Success() throws Exception {
        // Arrange: Create a credit memo
        CreditMemo cm = new CreditMemo();
        UUID invoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        cm.setOriginalInvoiceId(invoiceId);
        cm.setCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        cm.setCreditAmount(new BigDecimal("50.00"));
        cm.setTaxAmountReversed(new BigDecimal("5.00"));
        // totalAmount is now calculated: 50.00 + 5.00 = 55.00
        cm.setReasonCode("BILLING_ERROR");
        cm.setJustificationNote("Duplicate charge on invoice");
        cm.setStatus(CreditMemoStatus.POSTED);
        cm.setCreatedByUserId(TEST_USER);
        cm.setCurrency("USD");
        cm.setPriorPeriodAdjustment(false);
        CreditMemo saved = creditMemoRepository.save(cm);

        // Derived balance after credit applied
        when(invoiceBalanceCalculator.balanceDue(any(ExtInvoice.class))).thenReturn(new BigDecimal("55.00"));

        // Act: GET by ID
        mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS + "/" + saved.getCreditMemoId())))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.creditMemoId").value(saved.getCreditMemoId().toString()))
                .andExpect(jsonPath("$.creditAmount").value(50.00))
                .andExpect(jsonPath("$.reasonCode").value("BILLING_ERROR"));
    }

    @Test
    @DisplayName("CP-CM-005: Filter credit memos by invoice ID")
    void testListCreditMemos_FilterByInvoice() throws Exception {
        // Arrange: Create credit memos for different invoices
        UUID targetInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID otherInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        for (int i = 0; i < 3; i++) {
            CreditMemo cm = new CreditMemo();
            cm.setOriginalInvoiceId(i < 2 ? targetInvoiceId : otherInvoiceId);
            cm.setCustomerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            cm.setCreditAmount(new BigDecimal("10.00"));
            cm.setTaxAmountReversed(new BigDecimal("1.00"));
            // totalAmount is now calculated: 10.00 + 1.00 = 11.00
            cm.setReasonCode("SERVICE_CREDIT");
            cm.setStatus(CreditMemoStatus.POSTED);
            cm.setCreatedByUserId(TEST_USER);
            cm.setCurrency("USD");
            cm.setPriorPeriodAdjustment(false);
            creditMemoRepository.save(cm);
        }

        // Act: GET list filtered by invoice ID
        mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS)).param("originalInvoiceId", targetInvoiceId.toString()))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].originalInvoiceId").value(targetInvoiceId.toString()));
    }

    // ===============================================
    // ERROR SCENARIOS (from Contract Guide Examples)
    // ===============================================

    @Test
    @DisplayName("CE-CM-001: Amount exceeds balance - 409 Conflict (Contract Example)")
    void testCreateCreditMemo_AmountExceedsBalance() throws Exception {
        // Arrange: Credit amount exceeds stubbed invoice balance ($110)
        UUID testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateCreditMemoRequest request = new CreateCreditMemoRequest();
        request.setOriginalInvoiceId(testInvoiceId);
        request.setCreditAmount(new BigDecimal("200.00")); // Exceeds balance
        request.setReasonCode("RETURNED_GOODS");

        // Act & Assert: Expect 409 Conflict
        mockMvc.perform(withAuth(post(API_V1_CREDIT_MEMOS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("exceeds invoice outstanding balance")));
    }

    @Test
    @DisplayName("CE-CM-002: Missing reason code - 400 Bad Request (Contract Example)")
    void testCreateCreditMemo_MissingReasonCode() throws Exception {
        // Arrange: No reason code provided
        UUID testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateCreditMemoRequest request = new CreateCreditMemoRequest();
        request.setOriginalInvoiceId(testInvoiceId);
        request.setCreditAmount(new BigDecimal("50.00"));
        // request.setReasonCode(null); // Intentionally omitted

        // Act & Assert: Expect 400 Bad Request with field errors
        mockMvc.perform(withAuth(post(API_V1_CREDIT_MEMOS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isBadRequest());
        // Note: Specific field error structure depends on validation framework config
    }

    @Test
    @DisplayName("CE-CM-003: Credit memo not found - 404 Not Found")
    void testGetCreditMemo_NotFound() throws Exception {
        // Arrange: Non-existent credit memo ID
        UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // Act & Assert: Expect 404 Not Found
        mockMvc.perform(withAuth(get(API_V1_CREDIT_MEMOS + "/" + nonExistentId)))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Credit Memo not found: " + nonExistentId));
    }

    @Test
    @DisplayName("CE-CM-004: Invalid credit amount - 400 Bad Request")
    void testCreateCreditMemo_InvalidAmount() throws Exception {
        // Arrange: Negative credit amount
        UUID testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateCreditMemoRequest request = new CreateCreditMemoRequest();
        request.setOriginalInvoiceId(testInvoiceId);
        request.setCreditAmount(new BigDecimal("-10.00")); // Invalid
        request.setReasonCode("RETURNED_GOODS");

        // Act & Assert: Expect 400 Bad Request
        mockMvc.perform(withAuth(post(API_V1_CREDIT_MEMOS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CE-CM-005: Fully paid invoice - 409 Conflict")
    void testCreateCreditMemo_FullyPaidInvoice() throws Exception {
        // Arrange: Invoice is fully paid (zero balance remaining)
        UUID testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // Derived balance is zero: the invoice is fully paid
        when(invoiceBalanceCalculator.balanceDue(any(ExtInvoice.class))).thenReturn(BigDecimal.ZERO);

        CreateCreditMemoRequest request = new CreateCreditMemoRequest();
        request.setOriginalInvoiceId(testInvoiceId);
        request.setCreditAmount(new BigDecimal("50.00"));
        request.setReasonCode("RETURNED_GOODS");
        request.setJustificationNote("Customer returned items after full payment");

        // Act & Assert: Expect 409 Conflict with meaningful message
        mockMvc.perform(withAuth(post(API_V1_CREDIT_MEMOS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Invoice has no remaining balance to credit")));
    }

    // ===============================================
    // AUTHORIZATION SCENARIOS
    // ===============================================

    @Test
    @DisplayName("AUTH-CM-001: Missing authorities - 401 Unauthorized")
    void testCreateCreditMemo_NoAuthorities() throws Exception {
        // Arrange: Request without authorities
        UUID testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateCreditMemoRequest request = new CreateCreditMemoRequest();
        request.setOriginalInvoiceId(testInvoiceId);
        request.setCreditAmount(new BigDecimal("50.00"));
        request.setReasonCode("RETURNED_GOODS");

        // Act & Assert: Expect 401 Unauthorized.
        // GatewayAuthoritiesFilter clears the security context when the
        // X-Authorities header is absent, leaving an unauthenticated request.
        mockMvc.perform(post(API_V1_CREDIT_MEMOS)
                        .header("X-User", TEST_USER) // User present but no authorities
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isUnauthorized());
    }
}
