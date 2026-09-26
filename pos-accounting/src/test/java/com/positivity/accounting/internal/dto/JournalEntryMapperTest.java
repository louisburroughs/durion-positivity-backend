package com.positivity.accounting.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JournalEntryMapper#toResponse}: journal entry lines answer with their GL
 * account's code and name (issue #2238).
 */
@DisplayName("JournalEntryMapper Tests")
class JournalEntryMapperTest {

    private static final UUID GL_ACCOUNT_ID = UUID.fromString("01900000-0000-7000-8000-000000005100");

    @Test
    @DisplayName("toResponse - line answers with its stamped accountCode and accountName")
    void toResponse_lineCarriesStampedAccountCodeAndName() {
        JournalEntryLine line = line();
        line.setGlAccountId(GL_ACCOUNT_ID);
        line.setAccountCode("5100");
        line.setAccountName("Inventory Shrinkage");

        JournalEntryResponse.JournalEntryLineResponse response = mapSingleLine(line);

        assertThat(response.getGlAccountId()).isEqualTo(GL_ACCOUNT_ID);
        assertThat(response.getAccountCode()).isEqualTo("5100");
        assertThat(response.getAccountName()).isEqualTo("Inventory Shrinkage");
    }

    @Test
    @DisplayName("toResponse - stamped line does not touch its lazy GL account")
    void toResponse_stampedLineDoesNotLoadGlAccount() {
        GLAccount account = mock(GLAccount.class);
        JournalEntryLine line = line();
        line.setGlAccount(account);
        line.setAccountCode("5100");
        line.setAccountName("Inventory Shrinkage");

        mapSingleLine(line);

        verify(account, never()).getAccountCode();
        verify(account, never()).getAccountName();
    }

    @Test
    @DisplayName("toResponse - unstamped line falls back to its GL account")
    void toResponse_unstampedLineFallsBackToGlAccount() {
        GLAccount account = new GLAccount(GL_ACCOUNT_ID);
        account.setAccountCode("5100");
        account.setAccountName("Inventory Shrinkage");
        JournalEntryLine line = line();
        line.setGlAccount(account);

        JournalEntryResponse.JournalEntryLineResponse response = mapSingleLine(line);

        assertThat(response.getAccountCode()).isEqualTo("5100");
        assertThat(response.getAccountName()).isEqualTo("Inventory Shrinkage");
    }

    private static JournalEntryLine line() {
        JournalEntryLine line = new JournalEntryLine();
        line.setLineNumber(1);
        line.setDebitAmount(new BigDecimal("25.00"));
        line.setCreditAmount(BigDecimal.ZERO);
        return line;
    }

    private static JournalEntryResponse.JournalEntryLineResponse mapSingleLine(JournalEntryLine line) {
        JournalEntry entry = new JournalEntry();
        entry.setLines(new ArrayList<>(List.of(line)));

        JournalEntryResponse response = JournalEntryMapper.toResponse(entry);

        assertThat(response.getLines()).hasSize(1);
        return response.getLines().get(0);
    }
}
