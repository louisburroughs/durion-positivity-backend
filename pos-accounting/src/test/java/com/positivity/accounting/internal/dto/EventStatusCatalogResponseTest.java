package com.positivity.accounting.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EventStatusCatalogResponseTest {

    @Test
    void listsEveryStatusWithLabelAndDescription() {
        EventStatusCatalogResponse response = EventStatusCatalogResponse.fromEnums();
        assertThat(response.statuses()).hasSize(AccountingEventStatus.values().length);
        for (AccountingEventStatus s : AccountingEventStatus.values()) {
            assertThat(response.statuses())
                    .filteredOn(e -> e.code() == s)
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.displayName()).isNotBlank();
                        assertThat(e.description()).isNotBlank();
                        assertThat(e.terminal()).isEqualTo(s.terminal());
                        assertThat(e.actionable()).isEqualTo(s.actionable());
                    });
        }
    }

    @Test
    void listsEveryIdempotencyOutcomeWithLabelAndDescription() {
        EventStatusCatalogResponse response = EventStatusCatalogResponse.fromEnums();
        assertThat(response.idempotencyOutcomes()).hasSize(IdempotencyOutcome.values().length);
        for (IdempotencyOutcome o : IdempotencyOutcome.values()) {
            assertThat(response.idempotencyOutcomes())
                    .filteredOn(e -> e.code() == o)
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.displayName()).isNotBlank();
                        assertThat(e.description()).isNotBlank();
                    });
        }
    }

    @Test
    void terminalAndActionableFollowRetrySemantics() {
        assertThat(Arrays.stream(AccountingEventStatus.values()).filter(AccountingEventStatus::actionable))
                .containsExactly(AccountingEventStatus.FAILED, AccountingEventStatus.SUSPENDED);
        assertThat(Arrays.stream(AccountingEventStatus.values()).filter(AccountingEventStatus::terminal))
                .containsExactly(AccountingEventStatus.PROCESSED, AccountingEventStatus.SKIPPED);
    }
}
