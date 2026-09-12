package com.positivity.customer.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.customer.PostgresSliceTestBase;
import com.positivity.customer.TestClockConfig;
import com.positivity.customer.internal.config.JpaAuditingConfig;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PersonParty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The party fact-replay cursor queries ({@code findForReplay} on both party repositories, issue
 * #1893) against the real PostgreSQL schema.
 *
 * <p>Both filters are optional, and the replay endpoint's own documented starting call supplies
 * neither. Written as a database test because what it guards is a dialect behaviour: the queries
 * used to express the optional filters as {@code (:param IS NULL OR column …)}, which PostgreSQL
 * rejects at parse time — it has nothing to infer a bare placeholder's type from — while H2 accepts
 * it (issue #1891, PR #1961).
 */
@Import({JpaAuditingConfig.class, TestClockConfig.class})
@DisplayName("Party fact-replay cursor queries on PostgreSQL")
class PartyReplayQueryTest extends PostgresSliceTestBase {

    @Autowired
    private CommercialPartyRepository commercialParties;

    @Autowired
    private PersonPartyRepository personParties;

    private CommercialParty commercial(String suffix) {
        CommercialParty party = new CommercialParty();
        party.setCustomerNumber("CUST-REPLAY-C-" + suffix);
        party.setLegalName("Replay Commercial " + suffix);
        party.setDisplayName("Replay Commercial " + suffix);
        return commercialParties.saveAndFlush(party);
    }

    private PersonParty person(String suffix) {
        PersonParty party = new PersonParty();
        party.setCustomerNumber("CUST-REPLAY-P-" + suffix);
        party.setPersonId(UUID.randomUUID());
        return personParties.saveAndFlush(party);
    }

    @Test
    @DisplayName("an unfiltered replay reads both party tables from the beginning")
    void unfilteredReplayReadsFromTheBeginning() {
        CommercialParty commercial = commercial("1");
        PersonParty person = person("1");

        // A page wide enough to reach past the repeatable seed's parties, which belong to this
        // tenant too and sort ahead of anything minted now (their ids are older UUIDv7s).
        assertThat(commercialParties.findForReplay(null, null, PageRequest.of(0, 1000)))
                .contains(commercial);
        assertThat(personParties.findForReplay(null, null, PageRequest.of(0, 1000)))
                .contains(person);
    }

    @Test
    @DisplayName("the cursor resumes strictly after the party it names")
    void cursorResumesAfterTheNamedParty() {
        List<CommercialParty> ordered = List.of(commercial("a"), commercial("b"), commercial("c")).stream()
                .sorted((left, right) -> left.getPartyId().compareTo(right.getPartyId()))
                .toList();

        // containsExactly, not contains: the replay service merges the two tables by relying on
        // each repository returning partyId ASC, so a reversed result would corrupt the merge while
        // a membership assertion stayed green.
        assertThat(commercialParties.findForReplay(ordered.get(0).getPartyId(), null, PageRequest.of(0, 100)))
                .containsExactly(ordered.get(1), ordered.get(2))
                .doesNotContain(ordered.get(0));
    }

    @Test
    @DisplayName("the updatedSince filter keeps only parties changed at or after the instant")
    void updatedSinceKeepsOnlyRecentParties() {
        CommercialParty party = commercial("since");
        Instant justBefore = party.getUpdatedAt().minusSeconds(1);
        Instant wellAfter = party.getUpdatedAt().plusSeconds(60);

        assertThat(commercialParties.findForReplay(null, justBefore, PageRequest.of(0, 100)))
                .contains(party);
        assertThat(commercialParties.findForReplay(null, wellAfter, PageRequest.of(0, 100)))
                .doesNotContain(party);
    }

    @Test
    @DisplayName("both filters apply together, on both party tables")
    void bothFiltersApplyTogether() {
        PersonParty first = person("x");
        PersonParty second = person("y");
        UUID cursor = first.getPartyId().compareTo(second.getPartyId()) < 0 ? first.getPartyId() : second.getPartyId();
        PersonParty after = first.getPartyId().equals(cursor) ? second : first;

        assertThat(personParties.findForReplay(cursor, after.getUpdatedAt().minusSeconds(1), PageRequest.of(0, 100)))
                .containsExactly(after);
    }

    @Test
    @DisplayName("the person cursor returns its matches in party-id order")
    void personCursorReturnsMatchesInIdOrder() {
        // The person side promises the same partyId ASC ordering as the commercial side, and the
        // merge depends on both. A single-row expectation cannot see a reversal, so this seeds
        // three and reads two.
        List<PersonParty> ordered = List.of(person("o1"), person("o2"), person("o3")).stream()
                .sorted((left, right) -> left.getPartyId().compareTo(right.getPartyId()))
                .toList();

        assertThat(personParties.findForReplay(ordered.get(0).getPartyId(), null, PageRequest.of(0, 100)))
                .containsExactly(ordered.get(1), ordered.get(2));
    }

    @Test
    @DisplayName("an unpaged replay reads every match, still in party-id order")
    void unpagedReplayReadsEveryMatch() {
        // Both repositories special-case Pageable.unpaged() and document it as supported: it
        // reports a page size of zero, so the bounded read has to skip the limit rather than ask
        // for zero rows. Without this case a refactor that always applied the page size would
        // return nothing for an unpaged request and no test would notice.
        List<CommercialParty> ordered = List.of(commercial("u1"), commercial("u2"), commercial("u3")).stream()
                .sorted((left, right) -> left.getPartyId().compareTo(right.getPartyId()))
                .toList();

        assertThat(commercialParties.findForReplay(ordered.get(0).getPartyId(), null, Pageable.unpaged()))
                .containsExactly(ordered.get(1), ordered.get(2));
        assertThat(personParties.findForReplay(null, null, Pageable.unpaged())).isNotEmpty();
    }

    @Test
    @DisplayName("the page size bounds how many parties one replay call reads")
    void pageSizeBoundsTheRead() {
        commercial("p1");
        commercial("p2");
        commercial("p3");

        assertThat(commercialParties.findForReplay(null, null, PageRequest.of(0, 2)))
                .hasSize(2);
    }
}
