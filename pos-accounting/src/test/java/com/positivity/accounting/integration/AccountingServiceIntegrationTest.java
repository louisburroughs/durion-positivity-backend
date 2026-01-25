package com.positivity.accounting.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.entity.GLAccount;
import com.positivity.accounting.repository.GLAccountRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Phase 3 Integration Tests - Accounting Service Wrappers")
class AccountingServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GLAccountRepository glAccountRepository;

    private static final String ORG_ID = "org-test-001";
    private static final String JWT_TOKEN = "Bearer test-jwt-token-valid";
    private static final String BASE_URL = "/v1/accounting";

    @BeforeEach
    void setup() {
        // Clear data before each test
        glAccountRepository.deleteAll();
    }

    // ============================================
    // GL ACCOUNT SERVICE WRAPPER TESTS
    // ============================================

    @Test
    @DisplayName("Should create GL account via REST wrapper")
    void testCreateGLAccount() throws Exception {
        String payload = """
                {
                  "organizationId": "org-test-001",
                  "accountNumber": "1000",
                  "description": "Cash - Operating Account",
                  "accountType": "ASSET",
                  "postingCategory": "OPERATING"
                }
                """;

        mockMvc.perform(post(BASE_URL + "/gl-accounts")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.glAccountId", notNullValue()))
                .andExpect(jsonPath("$.accountNumber").value("1000"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @DisplayName("Should list GL accounts with pagination")
    void testListGLAccounts() throws Exception {
        mockMvc.perform(get(BASE_URL + "/gl-accounts")
                .header("Authorization", JWT_TOKEN)
                .param("organizationId", ORG_ID)
                .param("page", "0")
                .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.glAccounts", isA(java.util.List.class)))
                .andExpect(jsonPath("$.totalElements", isA(Number.class)))
                .andExpect(jsonPath("$.totalPages", isA(Number.class)));
    }

    @Test
    @DisplayName("Should activate GL account (DRAFT → ACTIVE)")
    void testActivateGLAccount() throws Exception {
        // Create account first
        GLAccount account = new GLAccount();
        account.setOrganizationId(ORG_ID);
        account.setAccountNumber("2000");
        account.setDescription("Test Liability");
        account.setAccountType("LIABILITY");
        account.setStatus("DRAFT");
        account = glAccountRepository.save(account);

        String payload = """
                {
                  "effectiveDate": "2025-01-01"
                }
                """;

        mockMvc.perform(post(BASE_URL + "/gl-accounts/" + account.getGlAccountId() + "/activate")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Should return 400 when activating without effective date")
    void testActivateGLAccountMissingDate() throws Exception {
        GLAccount account = new GLAccount();
        account.setOrganizationId(ORG_ID);
        account.setAccountNumber("3000");
        account.setStatus("DRAFT");
        account = glAccountRepository.save(account);

        mockMvc.perform(post(BASE_URL + "/gl-accounts/" + account.getGlAccountId() + "/activate")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
    }

    @Test
    @DisplayName("Should return 403 when user lacks accounting:coa:create permission")
    void testCreateGLAccountUnauthorized() throws Exception {
        String invalidToken = "Bearer invalid-token-no-permissions";
        String payload = """
                {
                  "organizationId": "org-test-001",
                  "accountNumber": "4000",
                  "accountType": "ASSET"
                }
                """;

        mockMvc.perform(post(BASE_URL + "/gl-accounts")
                .header("Authorization", invalidToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_PERMISSIONS"));
    }

    // ============================================
    // JOURNAL ENTRY SERVICE WRAPPER TESTS
    // ============================================

    @Test
    @DisplayName("Should create journal entry with balanced lines")
    void testCreateBalancedJournalEntry() throws Exception {
        // Setup GL accounts
        GLAccount cashAccount = new GLAccount();
        cashAccount.setOrganizationId(ORG_ID);
        cashAccount.setAccountNumber("1000");
        cashAccount.setAccountType("ASSET");
        cashAccount.setStatus("ACTIVE");
        cashAccount = glAccountRepository.save(cashAccount);

        GLAccount revenueAccount = new GLAccount();
        revenueAccount.setOrganizationId(ORG_ID);
        revenueAccount.setAccountNumber("4000");
        revenueAccount.setAccountType("REVENUE");
        revenueAccount.setStatus("ACTIVE");
        revenueAccount = glAccountRepository.save(revenueAccount);

        String payload = """
                {
                  "organizationId": "org-test-001",
                  "transactionDate": "2025-01-01T10:00:00Z",
                  "description": "Invoice received",
                  "lines": [
                    {
                      "glAccountId": "%s",
                      "debitAmount": 1000.00
                    },
                    {
                      "glAccountId": "%s",
                      "creditAmount": 1000.00
                    }
                  ]
                }
                """.formatted(cashAccount.getGlAccountId(), revenueAccount.getGlAccountId());

        mockMvc.perform(post(BASE_URL + "/journal-entries")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.journalEntry.status").value("DRAFT"))
                .andExpect(jsonPath("$.journalEntry.totalDebit").value(1000.00))
                .andExpect(jsonPath("$.journalEntry.totalCredit").value(1000.00));
    }

    @Test
    @DisplayName("Should return 422 when journal entry is unbalanced")
    void testCreateUnbalancedJournalEntry() throws Exception {
        GLAccount account1 = new GLAccount();
        account1.setOrganizationId(ORG_ID);
        account1.setAccountNumber("1000");
        account1.setStatus("ACTIVE");
        account1 = glAccountRepository.save(account1);

        String payload = """
                {
                  "organizationId": "org-test-001",
                  "transactionDate": "2025-01-01T10:00:00Z",
                  "lines": [
                    {
                      "glAccountId": "%s",
                      "debitAmount": 1000.00
                    },
                    {
                      "glAccountId": "%s",
                      "creditAmount": 500.00
                    }
                  ]
                }
                """.formatted(account1.getGlAccountId(), account1.getGlAccountId());

        mockMvc.perform(post(BASE_URL + "/journal-entries")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNBALANCED_ENTRY"));
    }

    @Test
    @DisplayName("Should post journal entry (DRAFT → POSTED)")
    void testPostJournalEntry() throws Exception {
        // Create GL accounts
        GLAccount account1 = new GLAccount();
        account1.setOrganizationId(ORG_ID);
        account1.setAccountNumber("1000");
        account1.setStatus("ACTIVE");
        account1 = glAccountRepository.save(account1);

        GLAccount account2 = new GLAccount();
        account2.setOrganizationId(ORG_ID);
        account2.setAccountNumber("2000");
        account2.setStatus("ACTIVE");
        account2 = glAccountRepository.save(account2);

        // Create entry
        String createPayload = """
                {
                  "organizationId": "org-test-001",
                  "transactionDate": "2025-01-01T10:00:00Z",
                  "lines": [
                    {"glAccountId": "%s", "debitAmount": 100.00},
                    {"glAccountId": "%s", "creditAmount": 100.00}
                  ]
                }
                """.formatted(account1.getGlAccountId(), account2.getGlAccountId());

        MvcResult createResult = mockMvc.perform(post(BASE_URL + "/journal-entries")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload))
                .andExpect(status().isCreated())
                .andReturn();

        String entryId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("journalEntry").get("journalEntryId").asText();

        // Post entry
        mockMvc.perform(post(BASE_URL + "/journal-entries/" + entryId + "/post")
                .header("Authorization", JWT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    @Test
    @DisplayName("Should return 409 when posting already-posted entry")
    void testPostAlreadyPostedEntry() throws Exception {
        // Create and post entry
        GLAccount account = new GLAccount();
        account.setOrganizationId(ORG_ID);
        account.setAccountNumber("1000");
        account.setStatus("ACTIVE");
        account = glAccountRepository.save(account);

        String payload = """
                {
                  "organizationId": "org-test-001",
                  "transactionDate": "2025-01-01T10:00:00Z",
                  "lines": [
                    {"glAccountId": "%s", "debitAmount": 100.00, "creditAmount": 0},
                    {"glAccountId": "%s", "debitAmount": 0, "creditAmount": 100.00}
                  ]
                }
                """.formatted(account.getGlAccountId(), account.getGlAccountId());

        MvcResult result = mockMvc.perform(post(BASE_URL + "/journal-entries")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andReturn();

        String entryId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("journalEntry").get("journalEntryId").asText();

        // Post it
        mockMvc.perform(post(BASE_URL + "/journal-entries/" + entryId + "/post")
                .header("Authorization", JWT_TOKEN))
                .andExpect(status().isOk());

        // Try to post again
        mockMvc.perform(post(BASE_URL + "/journal-entries/" + entryId + "/post")
                .header("Authorization", JWT_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTRY_ALREADY_POSTED"));
    }

    // ============================================
    // POSTING RULE SET SERVICE WRAPPER TESTS
    // ============================================

    @Test
    @DisplayName("Should create and publish posting rule set")
    void testCreateAndPublishRuleSet() throws Exception {
        String createPayload = """
                {
                  "organizationId": "org-test-001",
                  "name": "AR Auto-Post v1",
                  "description": "Automatic posting rules for AR invoices",
                  "rules": [
                    {
                      "glAccountId": "gl-ar-001",
                      "dimension": "BUSINESS_UNIT",
                      "priority": 100,
                      "postingCategory": "OPERATING"
                    }
                  ]
                }
                """;

        MvcResult createResult = mockMvc.perform(post(BASE_URL + "/posting-rules")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleSet.status").value("DRAFT"))
                .andReturn();

        String ruleSetId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("ruleSet").get("ruleSetId").asText();

        // Publish rule set
        mockMvc.perform(post(BASE_URL + "/posting-rules/" + ruleSetId + "/publish")
                .header("Authorization", JWT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedDate", notNullValue()));
    }

    @Test
    @DisplayName("Should return 409 when modifying published rule set")
    void testModifyPublishedRuleSetFails() throws Exception {
        String createPayload = """
                {
                  "organizationId": "org-test-001",
                  "name": "Test Rules",
                  "rules": []
                }
                """;

        MvcResult createResult = mockMvc.perform(post(BASE_URL + "/posting-rules")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload))
                .andReturn();

        String ruleSetId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("ruleSet").get("ruleSetId").asText();

        // Publish
        mockMvc.perform(post(BASE_URL + "/posting-rules/" + ruleSetId + "/publish")
                .header("Authorization", JWT_TOKEN))
                .andExpect(status().isOk());

        // Try to update published set
        String updatePayload = """
                {
                  "name": "Modified Name"
                }
                """;

        mockMvc.perform(put(BASE_URL + "/posting-rules/" + ruleSetId)
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RULE_SET_IMMUTABLE"));
    }

    // ============================================
    // GL MAPPING SERVICE WRAPPER TESTS
    // ============================================

    @Test
    @DisplayName("Should create GL mapping with dimension matching")
    void testCreateGLMappingWithDimensions() throws Exception {
        GLAccount account = new GLAccount();
        account.setOrganizationId(ORG_ID);
        account.setAccountNumber("1000");
        account.setStatus("ACTIVE");
        account = glAccountRepository.save(account);

        String payload = """
                {
                  "organizationId": "org-test-001",
                  "sourceSystem": "ERP_LEGACY",
                  "externalCode": "1000-COGS",
                  "glAccountId": "%s",
                  "effectiveStartDate": "2025-01-01",
                  "effectiveEndDate": "2025-12-31",
                  "dimensions": {
                    "businessUnitId": "BU-001",
                    "locationId": "NYC"
                  }
                }
                """.formatted(account.getGlAccountId());

        mockMvc.perform(post(BASE_URL + "/mappings")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mapping.sourceSystem").value("ERP_LEGACY"))
                .andExpect(jsonPath("$.mapping.priority").value(0));
    }

    @Test
    @DisplayName("Should resolve GL mapping by external code with temporal awareness")
    void testResolveGLMapping() throws Exception {
        GLAccount account = new GLAccount();
        account.setOrganizationId(ORG_ID);
        account.setAccountNumber("1000");
        account.setStatus("ACTIVE");
        account = glAccountRepository.save(account);

        String resolvePayload = """
                {
                  "organizationId": "org-test-001",
                  "sourceSystem": "ERP_LEGACY",
                  "externalCode": "1000-COGS",
                  "transactionDate": "2025-06-15T10:00:00Z"
                }
                """;

        mockMvc.perform(post(BASE_URL + "/mappings/resolve")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resolvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.glAccountId", notNullValue()));
    }

    // ============================================
    // EVENT INGESTION SERVICE WRAPPER TESTS
    // ============================================

    @Test
    @DisplayName("Should submit accounting event successfully")
    void testSubmitAccountingEvent() throws Exception {
        String eventPayload = """
                {
                  "organizationId": "org-test-001",
                  "sourceSystem": "billing-service",
                  "eventType": "billing.invoicePosted",
                  "transactionDate": "2025-01-01T10:00:00Z",
                  "payload": {
                    "invoiceId": "inv-001",
                    "customerId": "cust-001",
                    "totalAmount": 1000.00
                  }
                }
                """;

        mockMvc.perform(post(BASE_URL + "/events")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventPayload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId", notNullValue()))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    @DisplayName("Should detect and reject duplicate events (idempotency)")
    void testDuplicateEventRejection() throws Exception {
        String eventPayload = """
                {
                  "organizationId": "org-test-001",
                  "sourceSystem": "billing-service",
                  "eventType": "billing.invoicePosted",
                  "transactionDate": "2025-01-01T10:00:00Z",
                  "payload": {"invoiceId": "inv-001"}
                }
                """;

        // Submit first
        MvcResult firstResult = mockMvc.perform(post(BASE_URL + "/events")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventPayload))
                .andExpect(status().isAccepted())
                .andReturn();

        String firstEventId = objectMapper.readTree(firstResult.getResponse().getContentAsString())
                .get("eventId").asText();

        // Submit duplicate
        mockMvc.perform(post(BASE_URL + "/events")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EVENT"));
    }

    // ============================================
    // VENDOR BILL (AP) SERVICE WRAPPER TESTS
    // ============================================

    @Test
    @DisplayName("Should approve vendor bill")
    void testApproveVendorBill() throws Exception {
        String approvePayload = """
                {
                  "approvalDate": "2025-01-10"
                }
                """;

        // Assume bill created via event; test approval
        mockMvc.perform(post(BASE_URL + "/vendor-bills/bill-001/approve")
                .header("Authorization", JWT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvePayload))
                .andExpect(status().isOk());
    }

}
