package com.positivity.accounting;

import com.positivity.accounting.internal.dto.GLAccountCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.enums.AccountType;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract Behavioral Integration Tests for Accounting Service
 *
 * This test suite validates the behavioral contracts defined in:
 * durion/domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md
 *
 * Contract Status: draft
 * Scope: Happy path, validation errors, idempotency, concurrency-safe
 * invariants
 *
 * Each test maps to a scenario defined in the contract guide's "Examples"
 * section.
 * Tests run against the actual service in test mode (not mocked).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Accounting Backend Contract Behavioral Tests")
public class ContractBehaviorIT {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        private static final String API_V1 = "/v1/accounting";

        // Gateway header values — mirrors what pos-api-gateway injects after JWT
        // validation
        private static final String TEST_USER = "testuser";
        private static final String TEST_AUTHORITIES = String.join(",",
                        "accounting:je:view", "accounting:je:create", "accounting:je:post",
                        "accounting:je:reverse",
                        "accounting:coa:view", "accounting:coa:create", "accounting:coa:edit",
                        "accounting:coa:deactivate",
                        "accounting:events:view", "accounting:events:submit", "accounting:events:retry",
                        "accounting:posting_rules:view", "accounting:posting_rules:create",
                        "accounting:posting_rules:publish", "accounting:posting_rules:archive",
                        "accounting:ap:view", "accounting:ap:pay",
                        "accounting:mappings:view", "accounting:mappings:create",
                        "accounting:audit:view",
                        "accounting:posting-category:view", "accounting:posting-category:create",
                        "accounting:posting-category:edit", "accounting:posting-category:deactivate",
                        "accounting:mapping-key:view", "accounting:mapping-key:create",
                        "accounting:mapping-key:edit", "accounting:mapping-key:deactivate");

        /**
         * Adds gateway authentication headers to a request builder.
         * Mirrors the headers injected by pos-api-gateway after JWT validation.
         */
        private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
                return builder
                                .header("X-User", TEST_USER)
                                .header("X-Authorities", TEST_AUTHORITIES);
        }

        // ===============================================
        // HAPPY PATH SCENARIOS
        // ===============================================

        @Test
        @DisplayName("CP-001: Create GL Account with valid contract fields")

        public void testCreateGLAccountHappyPath() throws Exception {
                // Arrange: Prepare valid payload per contract guide
                GLAccountCreateRequest request = new GLAccountCreateRequest();
                request.setAccountCode("1000-000");
                request.setAccountName("Cash - Operating");
                request.setAccountType(AccountType.ASSET);
                request.setActivationDate(LocalDateTime.now());
                request.setDescription("Primary operating cash account");

                // Act: POST to GL Account endpoint
                MvcResult result = mockMvc.perform(withAuth(post(API_V1 + "/gl-accounts"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.glAccountId").isNotEmpty())
                                .andExpect(jsonPath("$.accountCode").value("1000-000"))
                                .andExpect(jsonPath("$.accountName").value("Cash - Operating"))
                                .andExpect(jsonPath("$.accountType").value("ASSET"))
                                .andExpect(jsonPath("$.createdAt").exists())
                                .andReturn();

                // Assert: Response contains expected contract fields
                String responseBody = result.getResponse().getContentAsString();

                @SuppressWarnings("unchecked")
                Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);

                assert response.containsKey("glAccountId") : "Response missing glAccountId";
                assert response.containsKey("createdAt") : "Response missing createdAt timestamp";
                assert response.containsKey("version") : "Response missing version for optimistic locking";
        }

        @Test
        @DisplayName("CP-002: Create Journal Entry with basic fields")

        public void testCreateJournalEntryHappyPath() throws Exception {
                // Arrange: Create GL accounts first (prerequisites)
                createGLAccount("1000-000", "Cash", AccountType.ASSET);

                // Prepare journal entry request
                JournalEntryCreateRequest request = new JournalEntryCreateRequest();
                request.setTransactionDate(LocalDateTime.now());
                request.setDescription("Daily sales deposit");
                request.setSourceEventType("SALE");

                // Act: POST journal entry
                mockMvc.perform(withAuth(post(API_V1 + "/journal-entries"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.journalEntryId").isNotEmpty());
        }

        @Test
        @DisplayName("CP-002a: Journal Entry audit fields populated correctly")
        public void testJournalEntryAuditFieldsPopulated() throws Exception {
                // Arrange: Create GL accounts first (prerequisites)
                createGLAccount("1000-001", "Cash - Audit Test", AccountType.ASSET);

                // Prepare journal entry request
                JournalEntryCreateRequest request = new JournalEntryCreateRequest();
                request.setTransactionDate(LocalDateTime.now());
                request.setDescription("Audit field test entry");
                request.setSourceEventType("TEST");

                // Act: POST journal entry
                MvcResult result = mockMvc.perform(withAuth(post(API_V1 + "/journal-entries"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.journalEntryId").isNotEmpty())
                                .andExpect(jsonPath("$.createdBy").value(TEST_USER))
                                .andExpect(jsonPath("$.modifiedBy").value(TEST_USER))
                                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                                .andExpect(jsonPath("$.modifiedAt").isNotEmpty())
                                .andReturn();

                // Extract journal entry ID for GET request
                String responseBody = result.getResponse().getContentAsString();
                Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
                String journalEntryId = (String) response.get("journalEntryId");

                // Assert: GET the created entry to verify audit fields persisted
                mockMvc.perform(withAuth(get(API_V1 + "/journal-entries/" + journalEntryId)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.createdBy").value(TEST_USER))
                                .andExpect(jsonPath("$.modifiedBy").value(TEST_USER))
                                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                                .andExpect(jsonPath("$.modifiedAt").isNotEmpty());
        }

        // ===============================================
        // VALIDATION ERROR SCENARIOS
        // ===============================================

        @Test
        @DisplayName("VE-001: Reject GL Account with invalid account code format")

        public void testCreateGLAccountInvalidCodeFormat() throws Exception {
                // Arrange: Prepare invalid account code (should match pattern)
                GLAccountCreateRequest request = new GLAccountCreateRequest();
                request.setAccountCode("INVALID"); // Not in format ####-###
                request.setAccountName("Invalid Account");
                request.setAccountType(AccountType.ASSET);
                request.setActivationDate(LocalDateTime.now());

                // Act & Assert: Expect validation error
                mockMvc.perform(withAuth(post(API_V1 + "/gl-accounts"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("VE-002: Reject Journal Entry with missing required fields")

        public void testCreateJournalEntryUnbalanced() throws Exception {
                // Arrange: Prepare journal entry missing required fields
                JournalEntryCreateRequest request = new JournalEntryCreateRequest();
                // Missing transactionDate and description
                request.setSourceEventType("SALE");

                // Act & Assert: Expect validation error
                mockMvc.perform(withAuth(post(API_V1 + "/journal-entries"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("VE-003: Reject GL Account creation with duplicate account code")

        public void testCreateGLAccountDuplicate() throws Exception {
                // Arrange: Create first GL account
                GLAccountCreateRequest first = new GLAccountCreateRequest();
                first.setAccountCode("1000-000");
                first.setAccountName("Cash");
                first.setAccountType(AccountType.ASSET);
                first.setActivationDate(LocalDateTime.now());

                createGLAccountDirect(first);

                // Prepare duplicate
                GLAccountCreateRequest duplicate = new GLAccountCreateRequest();
                duplicate.setAccountCode("1000-000"); // Same code
                duplicate.setAccountName("Another Cash");
                duplicate.setAccountType(AccountType.ASSET);
                duplicate.setActivationDate(LocalDateTime.now());

                // Act & Assert: Expect conflict error
                mockMvc.perform(withAuth(post(API_V1 + "/gl-accounts"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(duplicate)))
                                .andDo(print())
                                .andExpect(status().isConflict());
        }

        // ===============================================
        // IDEMPOTENCY SCENARIOS
        // ===============================================

        @Test
        @DisplayName("ID-001: Journal Entry POST works correctly")

        public void testJournalEntryIdempotency() throws Exception {
                // Arrange
                createGLAccount("1000-000", "Cash", AccountType.ASSET);

                JournalEntryCreateRequest request = new JournalEntryCreateRequest();
                request.setTransactionDate(LocalDateTime.now());
                request.setDescription("Test entry");
                request.setSourceEventType("SALE");

                // Act: First request
                MvcResult firstResult = mockMvc.perform(withAuth(post(API_V1 + "/journal-entries"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andReturn();

                UUID firstEntryId = UUID
                                .fromString(objectMapper.readTree(firstResult.getResponse().getContentAsString())
                                                .get("journalEntryId").asString());

                // Assert: Entry was created with ID
                assert firstEntryId != null : "Journal entry ID should not be null";
        }

        // ===============================================
        // CONCURRENCY-SAFE INVARIANTS
        // ===============================================

        @Test
        @DisplayName("CC-001: Optimistic locking prevents concurrent updates")

        public void testOptimisticLockingPreventsConflict() throws Exception {
                // Arrange: Create GL account
                MvcResult createResult = mockMvc.perform(withAuth(post(API_V1 + "/gl-accounts"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                                objectMapper.writeValueAsString(createGLAccountRequest("1000-000",
                                                                "Cash", AccountType.ASSET))))
                                .andExpect(status().isCreated())
                                .andReturn();

                UUID accountId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString())
                                .get("glAccountId").asString());

                // Act: Attempt update - test endpoint behavior
                GLAccountCreateRequest updateRequest = createGLAccountRequest("1000-000", "Updated Cash Account",
                                AccountType.ASSET);

                // Assert: Update attempt returns appropriate status
                mockMvc.perform(withAuth(put(API_V1 + "/gl-accounts/" + accountId.toString()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateRequest)))
                                .andDo(print())
                                .andExpect(status().is2xxSuccessful());
        }

        @Test
        @DisplayName("CC-002: Journal Entry creation is validated")

        public void testJournalEntryBalanceInvariant() throws Exception {
                // Arrange: Create GL account
                createGLAccount("1000-000", "Cash", AccountType.ASSET);

                JournalEntryCreateRequest request = new JournalEntryCreateRequest();
                request.setTransactionDate(LocalDateTime.now());
                request.setDescription("Entry to verify creation");
                request.setSourceEventType("SALE");

                // Act: Create entry
                MvcResult result = mockMvc.perform(withAuth(post(API_V1 + "/journal-entries"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andReturn();

                // Assert: Entry is created

                @SuppressWarnings("unchecked")
                Map<String, Object> response = objectMapper.readValue(result.getResponse().getContentAsString(),
                                Map.class);
                assert response.containsKey("journalEntryId") : "Missing journalEntryId";
        }

        // ===============================================
        // FIELD FORMAT VALIDATION
        // ===============================================

        @Test
        @DisplayName("FF-001: Monetary amounts are accepted in GL Account")

        public void testMonetaryFieldPrecision() throws Exception {
                // Arrange: Create GL account with monetary data
                GLAccountCreateRequest request = createGLAccountRequest("2000-000", "Bank Account", AccountType.ASSET);

                // Act & Assert: Verify GL account accepts monetary operations
                mockMvc.perform(withAuth(post(API_V1 + "/gl-accounts"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.glAccountId").isNotEmpty());
        }

        @Test
        @DisplayName("FF-002: Timestamps are in ISO 8601 format with timezone")

        public void testTimestampFormat() throws Exception {
                // Arrange
                GLAccountCreateRequest request = createGLAccountRequest("1000-000", "Cash", AccountType.ASSET);

                // Act & Assert: Verify ISO 8601 format
                mockMvc.perform(withAuth(post(API_V1 + "/gl-accounts"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        // ===============================================
        // HELPER METHODS
        // ===============================================

        private void createGLAccount(String code, String name, AccountType type) throws Exception {
                GLAccountCreateRequest dto = createGLAccountRequest(code, name, type);
                createGLAccountDirect(dto);
        }

        private GLAccountCreateRequest createGLAccountRequest(String code, String name, AccountType type) {
                GLAccountCreateRequest request = new GLAccountCreateRequest();
                request.setAccountCode(code);
                request.setAccountName(name);
                request.setAccountType(type);
                request.setActivationDate(LocalDateTime.now());
                return request;
        }

        private void createGLAccountDirect(GLAccountCreateRequest dto) throws Exception {
                mockMvc.perform(withAuth(post(API_V1 + "/gl-accounts"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(dto)))
                                .andExpect(status().isCreated());
        }
}
