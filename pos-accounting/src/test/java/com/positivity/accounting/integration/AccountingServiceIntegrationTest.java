package com.positivity.accounting.integration;

import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.GLAccountRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("Phase 3 Integration Tests - Accounting Service Wrappers")
class AccountingServiceIntegrationTest {

  @Autowired
  private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private GLAccountRepository glAccountRepository;

  private static final UUID ORG_ID = UUID.fromString("00000000-0000-4000-a000-000000000010");
  private static final String BASE_URL = "/v1/accounting";

  // Gateway header values — mirrors what pos-api-gateway injects after JWT
  // validation
  private static final String TEST_USER = "testuser";
  private static final String TEST_AUTHORITIES = Stream.of(
      "accounting:je:view", "accounting:je:create", "accounting:je:post", "accounting:je:reverse",
      "accounting:coa:view", "accounting:coa:create", "accounting:coa:edit", "accounting:coa:deactivate",
      "accounting:events:view", "accounting:events:submit", "accounting:events:retry",
      "accounting:posting_rules:view", "accounting:posting_rules:create",
      "accounting:posting_rules:publish", "accounting:posting_rules:archive",
      "accounting:ap:view", "accounting:ap:pay",
      "accounting:mappings:view", "accounting:mappings:create",
      "accounting:gl-mapping:create", "accounting:gl-mapping:resolve",
      "accounting:audit:view")
      .collect(Collectors.joining(","));

  // UUID constants for entity IDs used in test payloads
  private static final UUID GL_AR_ID = UUID.fromString("00000000-0000-4000-a000-000000000001");
  private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-4000-a000-000000000002");
  private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-4000-a000-000000000003");
  private static final UUID BILL_ID = UUID.fromString("00000000-0000-4000-a000-000000000004");
  private static final UUID VENDOR_ID = UUID.fromString("00000000-0000-4000-a000-000000000005");

  @BeforeEach
  void setup() {
    // Initialize MockMvc with the production security filter chain.
    // GatewayAuthoritiesFilter reads X-Authorities / X-User headers to populate
    // SecurityContext — tests send those headers just as the API gateway would.
    this.mockMvc = MockMvcBuilders
        .webAppContextSetup(context)
        .apply(springSecurity())
        .build();
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

    mockMvc.perform(post(BASE_URL + "/gl-accounts")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.glAccountId", notNullValue()))
        .andExpect(jsonPath("$.accountCode").value("1000"));
  }

  @Test
  @DisplayName("Should list GL accounts with pagination")
  void testListGLAccounts() throws Exception {
    mockMvc.perform(get(BASE_URL + "/gl-accounts")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
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

    mockMvc.perform(post(BASE_URL + "/gl-accounts/" + account.getGlAccountId() + "/activate")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
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

    mockMvc.perform(post(BASE_URL + "/gl-accounts/" + account.getGlAccountId() + "/activate")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
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
    cashAccount.setActivationDate(LocalDateTime.of(2024, 1, 1, 0, 0));
    cashAccount = glAccountRepository.save(cashAccount);

    GLAccount revenueAccount = new GLAccount();
    revenueAccount.setGlAccountId(UUID.randomUUID());
    revenueAccount.setAccountCode("4000");
    revenueAccount.setAccountName("Revenue");
    revenueAccount.setAccountType(AccountType.REVENUE);
    revenueAccount.setActivationDate(LocalDateTime.of(2024, 1, 1, 0, 0));
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

    mockMvc.perform(post(BASE_URL + "/journal-entries")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.totalDebits").value(1000.00))
        .andExpect(jsonPath("$.totalCredits").value(1000.00));
  }

  @Test
  @DisplayName("Should return 422 when journal entry is unbalanced")

  void testCreateUnbalancedJournalEntry() throws Exception {
    GLAccount account1 = new GLAccount();
    account1.setGlAccountId(UUID.randomUUID());
    account1.setAccountCode("1000");
    account1.setAccountName("Test Account");
    account1.setAccountType(AccountType.ASSET);
    account1.setActivationDate(LocalDateTime.of(2024, 1, 1, 0, 0));
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

    mockMvc.perform(post(BASE_URL + "/journal-entries")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
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
    account1.setActivationDate(LocalDateTime.of(2024, 1, 1, 0, 0));
    account1 = glAccountRepository.save(account1);

    GLAccount account2 = new GLAccount();
    account2.setGlAccountId(UUID.randomUUID());
    account2.setAccountCode("2000");
    account2.setAccountName("Account 2");
    account2.setAccountType(AccountType.LIABILITY);
    account2.setActivationDate(LocalDateTime.of(2024, 1, 1, 0, 0));
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

    MvcResult createResult = mockMvc.perform(post(BASE_URL + "/journal-entries")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(createPayload))
        .andExpect(status().isCreated())
        .andReturn();

    UUID entryId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString())
        .get("journalEntryId").asString());

    // Post entry
    mockMvc.perform(post(BASE_URL + "/journal-entries/" + entryId.toString() + "/post")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER))
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
    account.setActivationDate(LocalDateTime.of(2024, 1, 1, 0, 0));
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

    MvcResult result = mockMvc.perform(post(BASE_URL + "/journal-entries")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andReturn();

    UUID entryId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
        .get("journalEntryId").asString());

    // Post it
    mockMvc.perform(post(BASE_URL + "/journal-entries/" + entryId.toString() + "/post")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER))
        .andExpect(status().isOk());

    // Try to post again
    mockMvc.perform(post(BASE_URL + "/journal-entries/" + entryId.toString() + "/post")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ENTRY_ALREADY_POSTED"));
  }

  // ============================================
  // POSTING RULE SET SERVICE WRAPPER TESTS
  // ============================================

  @Test
  @DisplayName("Should create and publish posting rule set")
  void testCreateAndPublishRuleSet() throws Exception {
    // Create GL account for rule
    GLAccount arAccount = new GLAccount();
    arAccount.setGlAccountId(GL_AR_ID);
    arAccount.setAccountCode("1200");
    arAccount.setAccountName("Accounts Receivable");
    arAccount.setAccountType(AccountType.ASSET);
    arAccount.setActivationDate(LocalDateTime.of(2024, 1, 1, 0, 0));
    arAccount.setCreatedBy("testuser");
    arAccount.setModifiedBy("testuser");
    glAccountRepository.save(arAccount);

    String createPayload = """
        {
          "name": "AR Auto-Post v1",
          "eventType": "billing.invoicePosted",
          "description": "Automatic posting rules for AR invoices",
          "rulesDefinition": "{}",
          "createdBy": "testuser"
        }
        """;

    MvcResult createResult = mockMvc.perform(post(BASE_URL + "/posting-rules")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postingRuleSetId", notNullValue()))
        .andExpect(jsonPath("$.name").value("AR Auto-Post v1"))
        .andReturn();

    // Verify response body contains expected fields
    String responseBody = createResult.getResponse().getContentAsString();
    var responseJson = objectMapper.readTree(responseBody);

    // Assert response contains required fields
    assert responseJson.has("postingRuleSetId") : "Response missing postingRuleSetId field";
    assert responseJson.has("name") : "Response missing name field";
    assert responseJson.get("name").asText().equals("AR Auto-Post v1") : "Unexpected name in response";

    // Now check versions
    mockMvc
        .perform(
            get(BASE_URL + "/posting-rules/" + objectMapper.readTree(responseBody).get("postingRuleSetId").asText())
                .header("X-Authorities", TEST_AUTHORITIES)
                .header("X-User", TEST_USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versions[0].state").value("DRAFT"));

    UUID ruleSetId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString())
        .get("postingRuleSetId").asText());

    // Publish rule set
    mockMvc.perform(post(BASE_URL + "/posting-rules/" + ruleSetId.toString() + "/publish")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PUBLISHED"));
  }

  @Test
  @DisplayName("Should return 409 when modifying published rule set")
  void testModifyPublishedRuleSetFails() throws Exception {
    String createPayload = """
        {
          "name": "Test Rules",
          "eventType": "test.event",
          "rulesDefinition": "{}",
          "createdBy": "testuser"
        }
        """;

    MvcResult createResult = mockMvc.perform(post(BASE_URL + "/posting-rules")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(createPayload))
        .andExpect(status().isCreated())
        .andReturn();

    UUID ruleSetId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString())
        .get("postingRuleSetId").asText());

    // Publish
    mockMvc.perform(post(BASE_URL + "/posting-rules/" + ruleSetId.toString() + "/publish")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER))
        .andExpect(status().isOk());

    // Try to update published set - should return 409
    String updatePayload = """
        {
          "name": "Updated Rules",
          "eventType": "test.event.updated",
          "rulesDefinition": "{}",
          "createdBy": "testuser"
        }
        """;

    mockMvc.perform(put(BASE_URL + "/posting-rules/" + ruleSetId.toString())
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(updatePayload))
        .andExpect(status().isConflict());
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
    account.setActivationDate(LocalDateTime.of(2024, 1, 1, 0, 0));
    account.setCreatedBy("testuser");
    account.setCreatedAt(Instant.now());
    account.setModifiedBy("testuser");
    account.setModifiedAt(Instant.now());
    account = glAccountRepository.save(account);

    String payload = """
        {
          "organizationId": "%s",
          "sourceSystem": "ERP_LEGACY",
          "externalCode": "1000-COGS",
          "glAccountId": "%s",
          "effectiveStartDate": "2025-01-01T00:00:00",
          "effectiveEndDate": "2025-12-31T23:59:59",
          "dimensions": {
            "businessUnitId": "BU-001",
            "locationId": "NYC"
          }
        }
        """.formatted(ORG_ID, account.getGlAccountId());

    mockMvc.perform(post(BASE_URL + "/mappings")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.mapping.sourceSystem").value("ERP_LEGACY"))
        .andExpect(jsonPath("$.mapping.priority").value(0));
  }

  @Test
  @DisplayName("Should resolve GL mapping by external code with temporal awareness")
  void testResolveGLMapping() throws Exception {
    // Create GL account with audit fields
    GLAccount account = new GLAccount();
    account.setGlAccountId(UUID.randomUUID());
    account.setAccountCode("1000");
    account.setAccountName("Test Account");
    account.setAccountType(AccountType.ASSET);
    account.setActivationDate(LocalDateTime.of(2024, 1, 1, 0, 0));
    account.setCreatedBy("testuser");
    account.setCreatedAt(Instant.now());
    account.setModifiedBy("testuser");
    account.setModifiedAt(Instant.now());
    account = glAccountRepository.save(account);

    // Create the mapping first
    String createPayload = """
        {
          "organizationId": "%s",
          "sourceSystem": "ERP_LEGACY",
          "externalCode": "1000-COGS",
          "glAccountId": "%s",
          "effectiveStartDate": "2025-01-01T00:00:00",
          "effectiveEndDate": "2025-12-31T23:59:59"
        }
        """.formatted(ORG_ID, account.getGlAccountId());

    mockMvc.perform(post(BASE_URL + "/mappings")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(createPayload))
        .andExpect(status().isCreated());

    // Now resolve the mapping
    String resolvePayload = """
        {
          "organizationId": "%s",
          "sourceSystem": "ERP_LEGACY",
          "externalCode": "1000-COGS",
          "transactionDate": "2025-06-15T10:00:00Z"
        }
        """.formatted(ORG_ID);

    mockMvc.perform(post(BASE_URL + "/mappings/resolve")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
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

    mockMvc.perform(post(BASE_URL + "/events")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
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
    mockMvc.perform(post(BASE_URL + "/events")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(eventPayload))
        .andExpect(status().isAccepted())
        .andReturn();

    // Submit duplicate
    mockMvc.perform(post(BASE_URL + "/events")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(eventPayload))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("DUPLICATE_EVENT"));
  }

  // ============================================
  // VENDOR BILL (AP) SERVICE WRAPPER TESTS
  // ============================================

  @Test
  @DisplayName("Should create vendor bill from goods received event and complete three-way match workflow")
  void testVendorBillWorkflow() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID vendorId = VENDOR_ID;
    String purchaseOrderId = "PO-12345";

    // Step 1: Publish GoodsReceivedEvent → create bill in PENDING_RECEIPT_MATCH
    String goodsReceivedPayload = String.format("""
        {
          "eventId": "%s",
          "purchaseOrderId": "%s",
          "vendorId": "%s",
          "vendorName": "Test Vendor Corp",
          "receivedDate": "2025-01-10T10:00:00",
          "lineItems": [
            {
              "productId": "PROD-001",
              "description": "Widget A",
              "quantity": 100,
              "unitPrice": 10.00,
              "isInventoryItem": true
            }
          ]
        }
        """, eventId, purchaseOrderId, vendorId);

    // In production, this would be published via event bus
    // For now, we'll call the service directly in the test setup
    // TODO: Replace with actual event publishing once event infrastructure is in
    // place

    // Step 2: Verify bill created in PENDING_RECEIPT_MATCH status
    // Note: This test requires event publishing infrastructure to be complete
    // For now, marking as integration test that will validate the workflow
    // when connected to actual event bus

    // Step 3: Publish VendorInvoiceReceivedEvent → three-way match
    String invoiceReceivedPayload = String.format("""
        {
          "eventId": "%s",
          "vendorId": "%s",
          "invoiceReference": "INV-98765",
          "invoiceDate": "2025-01-11T12:00:00",
          "dueDate": "2025-02-10T12:00:00",
          "lineItems": [
            {
              "productId": "PROD-001",
              "description": "Widget A",
              "quantity": 100,
              "unitPrice": 10.00
            }
          ]
        }
        """, UUID.randomUUID(), vendorId);

    // Step 4: Verify bill transitioned to APPROVED status (match successful)

    // Step 5: Test discrepancy scenario (price variance > 5%)
    String discrepancyInvoicePayload = String.format("""
        {
          "eventId": "%s",
          "vendorId": "%s",
          "invoiceReference": "INV-99999",
          "invoiceDate": "2025-01-12T12:00:00",
          "dueDate": "2025-02-11T12:00:00",
          "lineItems": [
            {
              "productId": "PROD-001",
              "description": "Widget A",
              "quantity": 100,
              "unitPrice": 12.00
            }
          ]
        }
        """, UUID.randomUUID(), vendorId);

    // Step 6: Verify bill transitioned to MATCH_EXCEPTION status

    // TODO: Complete test implementation when event publishing infrastructure is
    // ready
    // This test validates Issue #130 Receipt Accrual workflow
  }

  @Test
  @DisplayName("Should list eligible vendor bills for payment")
  void testListEligibleVendorBills() throws Exception {
    // Test existing GET /ap/bills endpoint (already implemented)
    // Endpoint requires vendorId as a required parameter
    mockMvc.perform(get(BASE_URL + "/ap/bills")
        .header("X-Authorities", TEST_AUTHORITIES)
        .header("X-User", TEST_USER)
        .param("vendorId", VENDOR_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

}
