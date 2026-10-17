package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryChange;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The change-as-values mapping onto the history row (#2675), independent of Postgres. */
@DisplayName("Petty-expense category history values (#2675)")
class PettyExpenseCategoryHistoryValuesTest {

    private static GLAccount account(String code, String name) {
        GLAccount account = new GLAccount(UUID.randomUUID());
        account.setAccountCode(code);
        account.setAccountName(name);
        return account;
    }

    @Test
    @DisplayName("CREATE writes the new label, examples, account and the date; nothing old")
    void created() {
        PettyExpenseCategoryChange row = new PettyExpenseCategoryChange();
        PettyExpenseCategoryServiceImpl.Values.created(
                        "Tire disposal", "Scrap tire hauler fees", account("6360", "Misc"), LocalDate.of(2026, 10, 16))
                .writeTo(row);

        assertThat(row.getNewLabel()).isEqualTo("Tire disposal");
        assertThat(row.getNewExamples()).isEqualTo("Scrap tire hauler fees");
        assertThat(row.getNewAccountNumber()).isEqualTo("6360");
        assertThat(row.getNewAccountName()).isEqualTo("Misc");
        assertThat(row.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(row.getOldLabel()).isNull();
        assertThat(row.getOldAccountNumber()).isNull();
    }

    @Test
    @DisplayName("RELABEL writes labels and examples before and after; no account, no date")
    void relabelled() {
        PettyExpenseCategoryChange row = new PettyExpenseCategoryChange();
        PettyExpenseCategoryServiceImpl.Values.relabelled("Staff meals", "Staff meals and coffee", null, "Coffee")
                .writeTo(row);

        assertThat(row.getOldLabel()).isEqualTo("Staff meals");
        assertThat(row.getNewLabel()).isEqualTo("Staff meals and coffee");
        assertThat(row.getOldExamples()).isNull();
        assertThat(row.getNewExamples()).isEqualTo("Coffee");
        assertThat(row.getNewAccountNumber()).isNull();
        assertThat(row.getEffectiveFrom()).isNull();
    }

    @Test
    @DisplayName("REMAP writes the replaced and the new account with their names and the date; old is null when none")
    void remapped() {
        PettyExpenseCategoryChange row = new PettyExpenseCategoryChange();
        PettyExpenseCategoryServiceImpl.Values.remapped(
                        account("6340", "Shop supplies"), account("6360", "Misc"), LocalDate.of(2026, 11, 1))
                .writeTo(row);
        assertThat(row.getOldAccountNumber()).isEqualTo("6340");
        assertThat(row.getOldAccountName()).isEqualTo("Shop supplies");
        assertThat(row.getNewAccountNumber()).isEqualTo("6360");
        assertThat(row.getNewAccountName()).isEqualTo("Misc");
        assertThat(row.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(row.getNewLabel()).isNull();

        PettyExpenseCategoryChange first = new PettyExpenseCategoryChange();
        PettyExpenseCategoryServiceImpl.Values.remapped(null, account("6360", "Misc"), LocalDate.of(2026, 11, 1))
                .writeTo(first);
        assertThat(first.getOldAccountNumber()).isNull();
        assertThat(first.getOldAccountName()).isNull();
    }

    @Test
    @DisplayName("NONE (DEACTIVATE) writes nothing")
    void none() {
        PettyExpenseCategoryChange row = new PettyExpenseCategoryChange();
        PettyExpenseCategoryServiceImpl.Values.NONE.writeTo(row);
        assertThat(row.getNewLabel()).isNull();
        assertThat(row.getNewAccountNumber()).isNull();
        assertThat(row.getEffectiveFrom()).isNull();
    }
}
