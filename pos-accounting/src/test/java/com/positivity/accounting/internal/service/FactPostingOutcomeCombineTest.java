package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.enums.PostingFailureReason;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FactPostingOutcome#combine}: the one record of a closed session whose over/short and drawer movements both
 * posted in the handler transaction (CAP:550 S17, #2513).
 */
class FactPostingOutcomeCombineTest {

    private static final FactPostingOutcome NOTHING = FactPostingOutcome.nothingToPost();
    private static final FactPostingOutcome HELD = new FactPostingOutcome.CurrencyHeld();
    private static final FactPostingOutcome SKIPPED = FactPostingOutcome.notPostable("stale");

    private final UUID overShortEntry = UUID.randomUUID();
    private final UUID movementEntry = UUID.randomUUID();
    private final FactPostingOutcome overShortPosted = FactPostingOutcome.posted(overShortEntry);
    private final FactPostingOutcome movementPosted = FactPostingOutcome.posted(movementEntry);
    private final FactPostingOutcome overShortEarlier = new FactPostingOutcome.AlreadyPosted(null, UUID.randomUUID());
    private final FactPostingOutcome movementEarlier = new FactPostingOutcome.AlreadyPosted(null, UUID.randomUUID());

    @Test
    @DisplayName("A currency hold wins over anything")
    void currencyHoldWins() {
        assertThat(FactPostingOutcome.combine(overShortPosted, HELD))
                .isInstanceOf(FactPostingOutcome.CurrencyHeld.class);
        assertThat(FactPostingOutcome.combine(HELD, movementPosted))
                .isInstanceOf(FactPostingOutcome.CurrencyHeld.class);
    }

    @Test
    @DisplayName("A new entry wins over an earlier posting; the first path's entry is the one linked")
    void newEntryWins() {
        assertThat(FactPostingOutcome.combine(overShortPosted, movementPosted)).isSameAs(overShortPosted);
        assertThat(FactPostingOutcome.combine(NOTHING, movementPosted)).isSameAs(movementPosted);
        assertThat(FactPostingOutcome.combine(overShortEarlier, movementPosted)).isSameAs(movementPosted);
        assertThat(FactPostingOutcome.combine(overShortPosted, movementEarlier)).isSameAs(overShortPosted);
    }

    @Test
    @DisplayName("Both paths posted before: DUPLICATE_IGNORED through the first path's earlier entry")
    void earlierPostingIsADuplicate() {
        assertThat(FactPostingOutcome.combine(overShortEarlier, movementEarlier))
                .isSameAs(overShortEarlier);
        assertThat(FactPostingOutcome.combine(NOTHING, movementEarlier)).isSameAs(movementEarlier);
    }

    @Test
    @DisplayName("A skip outranks nothing to post; two paths with nothing to post post nothing")
    void skipThenNothing() {
        assertThat(FactPostingOutcome.combine(NOTHING, SKIPPED))
                .isEqualTo(new FactPostingOutcome.Skipped(PostingFailureReason.NOT_POSTABLE, "stale"));
        assertThat(FactPostingOutcome.combine(NOTHING, NOTHING)).isInstanceOf(FactPostingOutcome.NothingToPost.class);
    }

    @Test
    @DisplayName("A skip yields to a new entry and to an earlier posting, on either side")
    void skipYieldsToPostings() {
        assertThat(FactPostingOutcome.combine(SKIPPED, movementPosted)).isSameAs(movementPosted);
        assertThat(FactPostingOutcome.combine(overShortEarlier, SKIPPED)).isSameAs(overShortEarlier);
    }
}
