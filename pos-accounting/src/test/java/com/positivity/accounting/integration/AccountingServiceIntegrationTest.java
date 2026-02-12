package com.positivity.accounting.integration;

import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.GLAccountRepository;

@Transactional
@DisplayName("Phase 3 Integration Tests - Accounting Service Wrappers")
class AccountingServiceIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private GLAccountRepository glAccountRepository;

  private static final UUID ORG_ID = UUID.fromString("00000000-0000-4000-a000-000000000010");
  private static final String BASE_URL = "/v1/accounting";

  // UUID constants for entity IDs used in test payloads
  private static final UUID GL_AR_ID = UUID.fromString("00000000-0000-4000-a000-000000000001");
  private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-4000-a000-000000000002");
  private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-4000-a000-000000000003");
  private static final UUID BILL_ID = UUID.fromString("00000000-0000-4000-a000-000000000004");

  @BeforeEach
  void setUp() {
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
          "organizationId": "%s",
          "accountCode": "1000",
          "description": "Cash - Operating Account",
          "accountType": "ASSET",
          "postingCategory": "OPERATING"
        }
        """.formatted(ORG_ID);

    mockMvc.perform(withAuth(post(BASE_URL + "/gl-accounts"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.glAccountId", notNullValue()))
        .andExpect(jsonPath("$.accountCode").value("1000"));
  }

  @Test
  @DisplayName("Should list GL accounts with pagination")

  void testListGLAccounts() throws Exception {
    mockMvc.perform(withAuth(get(BASE_URL + "/gl-accounts"))
        .param("organizationId", ORG_ID.toString())
        .param("page", "0")
        .param("pageSize", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.glAccounts", isA(java.util.List.class)))
        .andExpect(jsonPath("$.totalElements", isA(Number.class)))
        .andExpect(jsonPath("$.totalPages", isA(Number.class)));
  }

  @Test
  @DisplayName("Should activate GL account (inactive → active)")

  void testActivateGLAccount() throws Exception {
    // Create account first with activation date in future
    GLAccount account = new GLAccount();
    account.setGlAccountId(UUID.randomUUID());
    account.setAccountCode("2000");
    account.setAccountName("Test Liability");
    account.setAccountType(AccountType.LIABILITY);
    account.setDescription("Test Liability Account");
    account.setActivationDate(LocalDateTime.now().plusDays(1)); // Not yet active
    account = glAccountRepository.save(account);

    String payload = """
        {
          "effectiveDate": "2025-01-01"
        }
        """;

    mockMvc.perform(withAuth(post(BASE_URL + "/gl-accounts/" + account.getGlAccountId() + "/activate"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.glAccountId").value(account.getGlAccountId().toString()));
  }

  @Test
  @DisplayName("Should return 400 when activating without effective date")

  void testActivateGLAccountMissingDate() throws Exception {
    GLAccount account = new GLAccount();
    account.setGlAccountId(UUID.randomUUID());
    account.setAccountCode("3000");
    account.setAccountName("Test Account");
    account.setAccountType(AccountType.ASSET);
    account.setActivationDate(LocalDateTime.now().plusDays(1));
    account = glAccountRepository.save(account);

    mockMvc.perform(withAuth(post(BASE_URL + "/gl-accounts/" + account.getGlAccountId() + "/activate"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
  }

  @Test
  @DisplayName("Should return 403 when user lacks accounting:coa:create permission")

  void testCreateGLAccountUnauthorized() throws Exception {
    // Send X-Authorities WITHOUT accounting:coa:create so @PreAuthorize rejects
    String insufficientAuthorities = "accounting:je:view";
    String payload = """
        {
          "organizationId": "%s",
          "accountCode": "4000",
          "accountType": "ASSET"
        }
        """.formatted(ORG_ID);

    mockMvc.perform(post(BASE_URL + "/gl-accounts")
        .header("X-Authorities", insufficientAuthorities)
        .header("X-User", "unauthorized-user")
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isForbidden());
  }

  // ============================================
  // JOURNAL ENTRY SERVICE WRAPPER TESTS
  // ============================================

  @Test
  @DisplayName("Should create journal entry with balanced lines")

  void testCreateBalancedJournalEntry() throws Exception {
    // Setup GL accounts
    GLAccount cashAccount = new GLAccount();
    cashAccount.setGlAccountId(UUID.randomUUID());
    cashAccount.setAccountCode("1000");
    cashAccount.setAccountName("Cash");
    cashAccount.setAccountType(AccountType.ASSET);
    cashAccount.setActivationDate(LocalDateTime.now());
    cashAccount = glAccountRepository.save(cashAccount);

    GLAccount revenueAccount = new GLAccount();
    revenueAccount.setGlAccountId(UUID.randomUUID());
    revenueAccount.setAccountCode("4000");
    revenueAccount.setAccountName("Revenue");
    revenueAccount.setAccountType(AccountType.REVENUE);
    revenueAccount.setActivationDate(LocalDateTime.now());
    revenueAccount = glAccountRepository.save(revenueAccount);

    String payload = """
        {
          "organizationId": "%s",
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
        """.formatted(ORG_ID, cashAccount.getGlAccountId(), revenueAccount.getGlAccountId());

    mockMvc.perform(withAuth(post(BASE_URL + "/journal-entries"))
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
    account1.setGlAccountId(UUID.randomUUID());
    account1.setAccountCode("1000");
    account1.setAccountName("Test Account");
    account1.setAccountType(AccountType.ASSET);
    account1.setActivationDate(LocalDateTime.now());
    account1 = glAccountRepository.save(account1);

    String payload = """
        {
          "organizationId": "%s",
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
        """.formatted(ORG_ID, account1.getGlAccountId(), account1.getGlAccountId());

    mockMvc.perform(withAuth(post(BASE_URL + "/journal-entries"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.error.code").value("UNBALANCED_ENTRY"));
  }

  @Test
  @DisplayName("Should post journal entry (DRAFT → POSTED)")

  void testPostJournalEntry() throws Exception {
    // Create GL accounts
    GLAccount account1 = new GLAccount();
    account1.setGlAccountId(UUID.randomUUID());
    account1.setAccountCode("1000");
    account1.setAccountName("Account 1");
    account1.setAccountType(AccountType.ASSET);
    account1.setActivationDate(LocalDateTime.now());
    account1 = glAccountRepository.save(account1);

    GLAccount account2 = new GLAccount();
    account2.setGlAccountId(UUID.randomUUID());
    account2.setAccountCode("2000");
    account2.setAccountName("Account 2");
    account2.setAccountType(AccountType.LIABILITY);
    account2.setActivationDate(LocalDateTime.now());
    account2 = glAccountRepository.save(account2);

    // Create entry
    String createPayload = """
        {
          "organizationId": "%s",
          "transactionDate": "2025-01-01T10:00:00Z",
          "lines": [
            {"glAccountId": "%s", "debitAmount": 100.00},
            {"glAccountId": "%s", "creditAmount": 100.00}
          ]
        }
        """.formatted(ORG_ID, account1.getGlAccountId(), account2.getGlAccountId());

    MvcResult createResult = mockMvc.perform(withAuth(post(BASE_URL + "/journal-entries"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(createPayload))
        .andExpect(status().isCreated())
        .andReturn();

    UUID entryId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString())
        .get("journalEntry").get("journalEntryId").asString());

    // Post entry
    mockMvc.perform(withAuth(post(BASE_URL + "/journal-entries/" + entryId.toString() + "/post")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("POSTED"));
  }

  @Test
  @DisplayName("Should return 409 when posting already-posted entry")

  void testPostAlreadyPostedEntry() throws Exception {
    // Create and post entry
    GLAccount account = new GLAccount();
    account.setGlAccountId(UUID.randomUUID());
    account.setAccountCode("1000");
    account.setAccountName("Test Account");
    account.setAccountType(AccountType.ASSET);
    account.setActivationDate(LocalDateTime.now());
    account = glAccountRepository.save(account);

    String payload = """
        {
          "organizationId": "%s",
          "transactionDate": "2025-01-01T10:00:00Z",
          "lines": [
            {"glAccountId": "%s", "debitAmount": 100.00},
            {"glAccountId": "%s", "creditAmount": 100.00}
          ]
        }
        """.formatted(ORG_ID, account.getGlAccountId(), account.getGlAccountId());

    MvcResult result = mockMvc.perform(withAuth(post(BASE_URL + "/journal-entries"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andReturn();

    UUID entryId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
        .get("journalEntry").get("journalEntryId").asString());

    // Post it
    mockMvc.perform(withAuth(post(BASE_URL + "/journal-entries/" + entryId.toString() + "/post")))
        .andExpect(status().isOk());

    // Try to post again
    mockMvc.perform(withAuth(post(BASE_URL + "/journal-entries/" + entryId.toString() + "/post")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ENTRY_ALREADY_POSTED"));
  }

  // ============================================
  // POSTING RULE SET SERVICE WRAPPER TESTS
  // ============================================

  @Test
  @Disabled("Posting rule endpoints are stubs returning 501 — implement PostingRuleController first")
  @DisplayName("Should create and publish posting rule set")

  void testCreateAndPublishRuleSet() throws Exception {
    String createPayload = """
        {
          "organizationId": "%s",
          "name": "AR Auto-Post v1",
          "description": "Automatic posting rules for AR invoices",
          "rules": [
            {
              "glAccountId": "%s",
              "dimension": "BUSINESS_UNIT",
              "priority": 100,
              "postingCategory": "OPERATING"
            }
          ]
        }
        """.formatted(ORG_ID, GL_AR_ID);

    MvcResult createResult = mockMvc.perform(withAuth(post(BASE_URL + "/posting-rules"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ruleSet.status").value("DRAFT"))
        .andReturn();

    UUID ruleSetId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString())
        .get("ruleSet").get("ruleSetId").asString());

    // Publish rule set
    mockMvc.perform(withAuth(post(BASE_URL + "/posting-rules/" + ruleSetId.toString() + "/publish")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
  }

  @Test
  @Disabled("Posting rule endpoints are stubs returning 501 — implement PostingRuleController first")
  @DisplayName("Should return 409 when modifying published rule set")

  void testModifyPublishedRuleSetFails() throws Exception {
    String createPayload = """
        {
          "organizationId": "%s",
          "name": "Test Rules",
          "rules": []
        }
        """.formatted(ORG_ID);

    MvcResult createResult = mockMvc.perform(withAuth(post(BASE_URL + "/posting-rules"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(createPayload))
        .andReturn();

    UUID ruleSetId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString())
        .get("ruleSet").get("ruleSetId").asString());

    // Publish
    mockMvc.perform(withAuth(post(BASE_URL + "/posting-rules/" + ruleSetId.toString() + "/publish")))
        .andExpect(status().isOk());

    // Try to update published set
    String updatePayload = """
        {
          "name": "Modified Name"
        }
        """;

    mockMvc.perform(withAuth(put(BASE_URL + "/posting-rules/" + ruleSetId.toString()))
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
    account.setGlAccountId(UUID.randomUUID());
    account.setAccountCode("1000");
    account.setAccountName("Test Account");
    account.setAccountType(AccountType.ASSET);
    account.setActivationDate(LocalDateTime.now());
    account = glAccountRepository.save(account);

    String payload = """
        {
          "organizationId": "%s",
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
        """.formatted(ORG_ID, account.getGlAccountId());

    mockMvc.perform(withAuth(post(BASE_URL + "/mappings"))
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
    account.setGlAccountId(UUID.randomUUID());
    account.setAccountCode("1000");
    account.setAccountName("Test Account");
    account.setAccountType(AccountType.ASSET);
    account.setActivationDate(LocalDateTime.now());
    account = glAccountRepository.save(account);

    String resolvePayload = """
        {
          "organizationId": "%s",
          "sourceSystem": "ERP_LEGACY",
          "externalCode": "1000-COGS",
          "transactionDate": "2025-06-15T10:00:00Z"
        }
        """.formatted(ORG_ID);

    mockMvc.perform(withAuth(post(BASE_URL + "/mappings/resolve"))
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
          "organizationId": "%s",
          "sourceSystem": "billing-service",
          "eventType": "billing.invoicePosted",
          "transactionDate": "2025-01-01T10:00:00Z",
          "payload": {
            "invoiceId": "%s",
            "customerId": "%s",
            "totalAmount": 1000.00
          }
        }
        """.formatted(ORG_ID, INVOICE_ID, CUSTOMER_ID);

    mockMvc.perform(withAuth(post(BASE_URL + "/events"))
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
          "organizationId": "%s",
          "sourceSystem": "billing-service",
          "eventType": "billing.invoicePosted",
          "transactionDate": "2025-01-01T10:00:00Z",
          "payload": {"invoiceId": "%s"}
        }
        """.formatted(ORG_ID, INVOICE_ID);

    // Submit first
    mockMvc.perform(withAuth(post(BASE_URL + "/events"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(eventPayload))
        .andExpect(status().isAccepted())
        .andReturn();

    // Submit duplicate
    mockMvc.perform(withAuth(post(BASE_URL + "/events"))
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
    mockMvc.perform(withAuth(post(BASE_URL + "/vendor-bills/" + BILL_ID + "/approve"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(approvePayload))
        .andExpect(status().isOk());
  }

}
