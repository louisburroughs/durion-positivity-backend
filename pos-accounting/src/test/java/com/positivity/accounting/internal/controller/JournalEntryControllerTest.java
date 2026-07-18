package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.service.JournalEntryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests for JournalEntryController (Story A2, Issue #942): posted-entry
 * number ({@code JE-&#123;YYYYMM&#125;-&#123;seq&#125;}) exposure on the journal
 * entry read/write surface and the exact-match {@code entryNumber} list
 * filter. Numbering assignment semantics themselves are covered by
 * {@code JournalEntryNumberingTest}.
 */
@DisplayName("JournalEntryController Tests")
class JournalEntryControllerTest extends BaseIntegrationTest {

    private static final UUID ENTRY_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678901234");
    private static final UUID GL_ACCOUNT_ID = UUID.fromString("01936e5d-1234-7a3d-8b6e-3c4567890123");
    private static final String ENTRY_NUMBER = "JE-202607-1";

    @MockitoBean
    private JournalEntryService journalEntryService;

    private static JournalEntry draftEntry() {
        JournalEntry entry = new JournalEntry(ENTRY_ID);
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setTransactionDate(LocalDateTime.of(2026, 7, 15, 10, 30));
        entry.setDescription("A2 controller test entry");
        return entry;
    }

    private static JournalEntry postedEntry() {
        JournalEntry entry = draftEntry();
        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setEntryNumber(ENTRY_NUMBER);
        entry.setPostedAt(Instant.parse("2026-07-15T10:40:00Z"));
        entry.setPostedBy(TEST_USER);
        return entry;
    }

    private String createBody() {
        return objectMapper.writeValueAsString(JournalEntryCreateRequest.builder()
                .transactionDate(LocalDateTime.of(2026, 7, 15, 10, 30))
                .description("A2 controller test entry")
                .lines(List.of(
                        JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                .glAccountId(GL_ACCOUNT_ID)
                                .debitAmount(new BigDecimal("100.00"))
                                .build(),
                        JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                .glAccountId(GL_ACCOUNT_ID)
                                .creditAmount(new BigDecimal("100.00"))
                                .build()))
                .build());
    }

    @Nested
    @DisplayName("POST /v1/accounting/journal-entries")
    class CreateJournalEntry {

        @Test
        @DisplayName("Should return null entryNumber for a newly created draft")
        void createdDraft_hasNullEntryNumber() throws Exception {
            when(journalEntryService.createJournalEntry(any(JournalEntry.class)))
                    .thenReturn(draftEntry());

            mockMvc.perform(withAuth(post("/v1/accounting/journal-entries"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.journalEntryId").value(ENTRY_ID.toString()))
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    // non_null serialization: an unnumbered draft omits entryNumber
                    .andExpect(jsonPath("$.entryNumber").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /v1/accounting/journal-entries/{id}/post")
    class PostJournalEntry {

        @Test
        @DisplayName("Should return the assigned entryNumber in the post response")
        void postResponse_containsEntryNumber() throws Exception {
            when(journalEntryService.postJournalEntry(ENTRY_ID)).thenReturn(postedEntry());

            mockMvc.perform(withAuth(post("/v1/accounting/journal-entries/{id}/post", ENTRY_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("POSTED"))
                    .andExpect(jsonPath("$.entryNumber").value(ENTRY_NUMBER));
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/journal-entries/{id}")
    class GetJournalEntry {

        @Test
        @DisplayName("Should return entryNumber for a posted entry")
        void get_postedEntry_containsEntryNumber() throws Exception {
            when(journalEntryService.getJournalEntry(ENTRY_ID)).thenReturn(postedEntry());

            mockMvc.perform(withAuth(get("/v1/accounting/journal-entries/{id}", ENTRY_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entryNumber").value(ENTRY_NUMBER));
        }

        @Test
        @DisplayName("Should return null entryNumber for a draft entry")
        void get_draftEntry_hasNullEntryNumber() throws Exception {
            when(journalEntryService.getJournalEntry(ENTRY_ID)).thenReturn(draftEntry());

            mockMvc.perform(withAuth(get("/v1/accounting/journal-entries/{id}", ENTRY_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    // non_null serialization: an unnumbered draft omits entryNumber
                    .andExpect(jsonPath("$.entryNumber").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/journal-entries")
    class ListJournalEntries {

        @Test
        @DisplayName("Should include entryNumber per item, null for drafts, without a filter")
        void list_withoutFilter_exposesEntryNumbers() throws Exception {
            JournalEntry draft = draftEntry();
            draft.setJournalEntryId(UUID.fromString("01936e5e-0000-7000-8000-00000000000d"));
            when(journalEntryService.listJournalEntries(any(Pageable.class), isNull()))
                    .thenReturn(new PageImpl<>(List.of(postedEntry(), draft)));

            mockMvc.perform(withAuth(get("/v1/accounting/journal-entries")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].entryNumber").value(ENTRY_NUMBER))
                    .andExpect(jsonPath("$.items[1].status").value("DRAFT"))
                    // non_null serialization: an unnumbered draft omits entryNumber
                    .andExpect(jsonPath("$.items[1].entryNumber").doesNotExist());
        }

        @Test
        @DisplayName("Should pass the entryNumber filter through and return the match")
        void list_withEntryNumberFilter_returnsMatch() throws Exception {
            when(journalEntryService.listJournalEntries(any(Pageable.class), eq(ENTRY_NUMBER)))
                    .thenReturn(new PageImpl<>(List.of(postedEntry())));

            mockMvc.perform(withAuth(get("/v1/accounting/journal-entries").param("entryNumber", ENTRY_NUMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalCount").value(1))
                    .andExpect(jsonPath("$.items[0].journalEntryId").value(ENTRY_ID.toString()))
                    .andExpect(jsonPath("$.items[0].entryNumber").value(ENTRY_NUMBER));

            ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
            verify(journalEntryService).listJournalEntries(any(Pageable.class), filterCaptor.capture());
            assertThat(filterCaptor.getValue()).isEqualTo(ENTRY_NUMBER);
        }
    }
}
