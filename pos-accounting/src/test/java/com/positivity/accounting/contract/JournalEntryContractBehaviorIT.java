package com.positivity.accounting.contract;

import java.time.ZoneOffset;
import java.time.Instant;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.dto.GLAccountCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryReversalRequest;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;

/**
 * Contract Behavioral Integration Tests for Journal Entry operations.
 *
 * <p>
 * This test suite validates the behavioral contracts for Journal Entry REST
 * endpoints
 * including creation, listing, posting, and reversal operations.
 *
 * <p>
 * Tests verify:
 * - Happy path CRUD operations
 * - Balance validation (debits = credits)
 * - Entry lifecycle (DRAFT → POSTED → REVERSED)
 * - Immutability of posted entries
 * - GL account validation
 * - Pagination and sorting
 */
@DisplayName("Journal Entry Backend Contract Behavioral Tests")
class JournalEntryContractBehaviorIT extends BaseContractIntegrationTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Autowired
        private JournalEntryRepository journalEntryRepository;

        @Autowired
        private GLAccountRepository glAccountRepository;

        @Autowired
        private DefaultGLMappingRepository defaultGLMappingRepository;

        private static final String API_V1_JOURNAL_ENTRIES = "/v1/accounting/journal-entries";
        private static final String API_V1_GL_ACCOUNTS = "/v1/accounting/gl-accounts";

        // Test data
        private UUID cashAccountId;
        private UUID revenueAccountId;

        @BeforeEach
        void setUp() {
                // Clean up before each test
                journalEntryRepository.deleteAll();
                defaultGLMappingRepository.deleteAll();
                glAccountRepository.deleteAll();

                // Create test GL accounts
                try {
                        cashAccountId = createGLAccount("1000", "Cash", AccountType.ASSET);
                        revenueAccountId = createGLAccount("4000", "Revenue", AccountType.REVENUE);
                } catch (Exception e) {
                        throw new RuntimeException("Failed to create test GL accounts", e);
                }
        }

        @AfterEach
        void tearDown() {
                journalEntryRepository.deleteAll();
                defaultGLMappingRepository.deleteAll();
                glAccountRepository.deleteAll();
        }

        /**
         * Helper method to create a GL account for testing.
         */
        private UUID createGLAccount(String code, String name, AccountType type) throws Exception {
                GLAccountCreateRequest request = GLAccountCreateRequest.builder()
                                .accountCode(code)
                                .accountName(name)
                                .accountType(type)
                                .activationDate(LocalDateTime.now(TEST_CLOCK))
                                .build();

                MvcResult result = mockMvc.perform(withAuth(post(API_V1_GL_ACCOUNTS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.glAccountId").exists())
                                .andReturn();

                String responseBody = result.getResponse().getContentAsString();

                var response = objectMapper.readValue(responseBody, java.util.Map.class);
                return UUID.fromString(response.get("glAccountId").toString());
        }

        // ===============================================
        // HAPPY PATH SCENARIOS
        // ===============================================

        @Test
        @DisplayName("Create balanced journal entry - happy path")
        void testCreateJournalEntry_Success() throws Exception {
                // Given - balanced journal entry
                JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .transactionDate(LocalDateTime.now(TEST_CLOCK))
                                .description("Test journal entry")
                                .sourceEventType("SALE")
                                .lines(List.of(
                                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                                                .glAccountId(cashAccountId)
                                                                .debitAmount(new BigDecimal("100.00"))
                                                                .creditAmount(BigDecimal.ZERO)
                                                                .description("Debit cash")
                                                                .build(),
                                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                                                .glAccountId(revenueAccountId)
                                                                .debitAmount(BigDecimal.ZERO)
                                                                .creditAmount(new BigDecimal("100.00"))
                                                                .description("Credit revenue")
                                                                .build()))
                                .build();

                // When/Then
                mockMvc.perform(withAuth(post(API_V1_JOURNAL_ENTRIES))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.journalEntryId").exists())
                                .andExpect(jsonPath("$.description").value("Test journal entry"))
                                .andExpect(jsonPath("$.status").value("DRAFT"))
                                .andExpect(jsonPath("$.lines").isArray())
                                .andExpect(jsonPath("$.lines.length()").value(2))
                                .andExpect(jsonPath("$.createdAt").exists())
                                .andExpect(jsonPath("$.modifiedAt").exists());
        }

        @Test
        @DisplayName("List journal entries with pagination")
        void testListJournalEntries_Pagination() throws Exception {
                // Given - create multiple journal entries
                for (int i = 0; i < 5; i++) {
                        createBalancedJournalEntry("Entry " + i);
                }

                // When/Then - list with pagination
                mockMvc.perform(withAuth(get(API_V1_JOURNAL_ENTRIES))
                                .param("page", "0")
                                .param("size", "3")
                                .param("sort", "createdAt"))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content").isArray())
                                .andExpect(jsonPath("$.content.length()").value(3))
                                .andExpect(jsonPath("$.pageNumber").value(0))
                                .andExpect(jsonPath("$.pageSize").value(3))
                                .andExpect(jsonPath("$.totalElements").value(5));
        }

        @Test
        @DisplayName("Get journal entry by ID - happy path")
        void testGetJournalEntry_Success() throws Exception {
                // Given - create a journal entry
                UUID entryId = createBalancedJournalEntry("Test entry");

                // When/Then
                mockMvc.perform(withAuth(get(API_V1_JOURNAL_ENTRIES + "/{journalEntryId}", entryId)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.journalEntryId").value(entryId.toString()))
                                .andExpect(jsonPath("$.description").value("Test entry"))
                                .andExpect(jsonPath("$.status").value("DRAFT"))
                                .andExpect(jsonPath("$.lines").isArray());
        }

        @Test
        @DisplayName("Update draft journal entry - happy path")
        void testUpdateJournalEntry_Success() throws Exception {
                // Given - create a draft entry
                UUID entryId = createBalancedJournalEntry("Original description");

                // When - update the entry
                JournalEntryCreateRequest updateRequest = JournalEntryCreateRequest.builder()
                                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .transactionDate(LocalDateTime.now(TEST_CLOCK))
                                .description("Updated description")
                                .sourceEventType("SALE")
                                .lines(List.of(
                                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                                                .glAccountId(cashAccountId)
                                                                .debitAmount(new BigDecimal("200.00"))
                                                                .creditAmount(BigDecimal.ZERO)
                                                                .description("Updated debit")
                                                                .build(),
                                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                                                .glAccountId(revenueAccountId)
                                                                .debitAmount(BigDecimal.ZERO)
                                                                .creditAmount(new BigDecimal("200.00"))
                                                                .description("Updated credit")
                                                                .build()))
                                .build();

                // Then
                mockMvc.perform(withAuth(put(API_V1_JOURNAL_ENTRIES + "/{journalEntryId}", entryId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateRequest)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.journalEntryId").value(entryId.toString()))
                                .andExpect(jsonPath("$.description").value("Updated description"))
                                .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        @DisplayName("Post draft journal entry - happy path")
        void testPostJournalEntry_Success() throws Exception {
                // Given - create a draft entry
                UUID entryId = createBalancedJournalEntry("Entry to post");

                // When/Then - post the entry
                mockMvc.perform(withAuth(post(API_V1_JOURNAL_ENTRIES + "/{journalEntryId}/post", entryId)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.journalEntryId").value(entryId.toString()))
                                .andExpect(jsonPath("$.status").value("POSTED"))
                                .andExpect(jsonPath("$.postedAt").exists());
        }

        @Test
        @DisplayName("Reverse posted journal entry - happy path")
        void testReverseJournalEntry_Success() throws Exception {
                // Given - create and post an entry
                UUID entryId = createBalancedJournalEntry("Entry to reverse");
                mockMvc.perform(withAuth(post(API_V1_JOURNAL_ENTRIES + "/{journalEntryId}/post", entryId)))
                                .andExpect(status().isOk());

                // When - reverse the entry
                JournalEntryReversalRequest reversalRequest = new JournalEntryReversalRequest();
                reversalRequest.setReason("Test reversal");

                // Then
                mockMvc.perform(withAuth(post(API_V1_JOURNAL_ENTRIES + "/{journalEntryId}/reverse", entryId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reversalRequest)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.journalEntryId").exists())
                                .andExpect(jsonPath("$.description")
                                                .value(org.hamcrest.Matchers.containsString("REVERSAL")));

                // Verify reversal created a new entry
                assertThat(journalEntryRepository.count()).isEqualTo(2);
        }

        // ===============================================
        // VALIDATION SCENARIOS
        // ===============================================

        @Test
        @DisplayName("Create unbalanced journal entry - validation error")
        void testCreateJournalEntry_Unbalanced() throws Exception {
                // Given - unbalanced journal entry
                JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .transactionDate(LocalDateTime.now(TEST_CLOCK))
                                .description("Unbalanced entry")
                                .sourceEventType("SALE")
                                .lines(List.of(
                                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                                                .glAccountId(cashAccountId)
                                                                .debitAmount(new BigDecimal("100.00"))
                                                                .creditAmount(BigDecimal.ZERO)
                                                                .description("Debit cash")
                                                                .build(),
                                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                                                .glAccountId(revenueAccountId)
                                                                .debitAmount(BigDecimal.ZERO)
                                                                .creditAmount(new BigDecimal("50.00"))
                                                                .description("Credit revenue")
                                                                .build()))
                                .build();

                // When/Then - expect 422 Unprocessable Content
                mockMvc.perform(withAuth(post(API_V1_JOURNAL_ENTRIES))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("Get non-existent journal entry - 400 bad request")
        void testGetJournalEntry_NotFound() throws Exception {
                // Given - random UUID that doesn't exist
                UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                // When/Then - Service throws IllegalArgumentException which maps to 400 via
                // APPaymentExceptionHandler
                mockMvc.perform(withAuth(get(API_V1_JOURNAL_ENTRIES + "/{journalEntryId}", nonExistentId)))
                                .andDo(print())
                                .andExpect(status().isBadRequest()); // Service throws IllegalArgumentException which
                                                                     // maps to 400
        }

        @Test
        @DisplayName("Update posted journal entry - immutability violation")
        void testUpdateJournalEntry_Posted_Fails() throws Exception {
                // Given - create and post an entry
                UUID entryId = createBalancedJournalEntry("Posted entry");
                mockMvc.perform(withAuth(post(API_V1_JOURNAL_ENTRIES + "/{journalEntryId}/post", entryId)))
                                .andExpect(status().isOk());

                // When - try to update the posted entry
                JournalEntryCreateRequest updateRequest = JournalEntryCreateRequest.builder()
                                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .transactionDate(LocalDateTime.now(TEST_CLOCK))
                                .description("Attempted update")
                                .sourceEventType("SALE")
                                .lines(List.of(
                                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                                                .glAccountId(cashAccountId)
                                                                .debitAmount(new BigDecimal("100.00"))
                                                                .creditAmount(BigDecimal.ZERO)
                                                                .build(),
                                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                                                .glAccountId(revenueAccountId)
                                                                .debitAmount(BigDecimal.ZERO)
                                                                .creditAmount(new BigDecimal("100.00"))
                                                                .build()))
                                .build();

                // Then - expect 409 Conflict (immutability violation)
                mockMvc.perform(withAuth(put(API_V1_JOURNAL_ENTRIES + "/{journalEntryId}", entryId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateRequest)))
                                .andDo(print())
                                .andExpect(status().isConflict());
        }

        // ===============================================
        // HELPER METHODS
        // ===============================================

        /**
         * Helper method to create a balanced journal entry.
         */
        private UUID createBalancedJournalEntry(String description) throws Exception {
                JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .transactionDate(LocalDateTime.now(TEST_CLOCK))
                                .description(description)
                                .sourceEventType("SALE")
                                .lines(List.of(
                                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                                                .glAccountId(cashAccountId)
                                                                .debitAmount(new BigDecimal("100.00"))
                                                                .creditAmount(BigDecimal.ZERO)
                                                                .description("Debit cash")
                                                                .build(),
                                                JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                                                .glAccountId(revenueAccountId)
                                                                .debitAmount(BigDecimal.ZERO)
                                                                .creditAmount(new BigDecimal("100.00"))
                                                                .description("Credit revenue")
                                                                .build()))
                                .build();

                MvcResult result = mockMvc.perform(withAuth(post(API_V1_JOURNAL_ENTRIES))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andReturn();

                String responseBody = result.getResponse().getContentAsString();

                var response = objectMapper.readValue(responseBody, java.util.Map.class);
                return UUID.fromString(response.get("journalEntryId").toString());
        }
}
