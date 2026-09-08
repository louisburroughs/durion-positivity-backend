package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.PartyFactReplayResultDto;
import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.exception.CrmConflictException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link PartyFactReplayServiceImpl} (issue #1893).
 *
 * <p>What is under test is the bounded, resumable walk and the single cursor spanning the two
 * concrete party tables — not the fact payload, which {@link CustomerFactPublisher} owns.
 */
@DisplayName("PartyFactReplayServiceImpl (issue #1893)")
class PartyFactReplayServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    private final CommercialPartyRepository commercialPartyRepository = mock(CommercialPartyRepository.class);
    private final PersonPartyRepository personPartyRepository = mock(PersonPartyRepository.class);
    private final CustomerFactPublisher factPublisher = mock(CustomerFactPublisher.class);

    private PartyFactReplayServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PartyFactReplayServiceImpl(
                commercialPartyRepository, personPartyRepository, factPublisher, TEST_CLOCK);
        when(factPublisher.publicationEnabled()).thenReturn(true);
        when(commercialPartyRepository.findForReplay(any(), any(), any())).thenReturn(List.of());
        when(personPartyRepository.findForReplay(any(), any(), any())).thenReturn(List.of());
    }

    /** UUIDs whose ordering is explicit, so the merge across the two tables is checkable. */
    private static UUID id(int n) {
        return UUID.fromString("018f0000-0000-7000-8000-%012d".formatted(n));
    }

    private static CommercialParty commercial(int n) {
        CommercialParty party = new CommercialParty();
        party.setPartyId(id(n));
        return party;
    }

    private static PersonParty person(int n) {
        PersonParty party = new PersonParty();
        party.setPartyId(id(n));
        return party;
    }

    @Test
    @DisplayName("Refuses with a 409-mapped conflict when fact publication is disabled")
    void refusesWhenPublicationDisabled() {
        when(factPublisher.publicationEnabled()).thenReturn(false);

        assertThatExceptionOfType(CrmConflictException.class)
                .isThrownBy(() -> service.replayPage(null, null, 500))
                .withMessageContaining("Fact publication is disabled");

        // Reporting a page it never emitted would tell an operator a replica was seeded.
        verify(factPublisher, never()).partyChanged(any());
        verify(commercialPartyRepository, never()).findForReplay(any(), any(), any());
    }

    @Test
    @DisplayName("Publishes both party types in one id-ordered page, so the cursor spans both tables")
    void mergesBothTablesInIdOrder() {
        when(commercialPartyRepository.findForReplay(any(), any(), any()))
                .thenReturn(List.of(commercial(1), commercial(3)));
        when(personPartyRepository.findForReplay(any(), any(), any())).thenReturn(List.of(person(2), person(4)));

        PartyFactReplayResultDto result = service.replayPage(null, null, 500);

        assertThat(result.emitted()).isEqualTo(4);
        assertThat(result.complete()).isTrue();
        assertThat(result.nextAfterId()).isNull();

        InOrder order = Mockito.inOrder(factPublisher);
        for (int n = 1; n <= 4; n++) {
            int expected = n;
            order.verify(factPublisher)
                    .partyChanged(org.mockito.ArgumentMatchers.argThat(
                            (AbstractParty party) -> party.getPartyId().equals(id(expected))));
        }
    }

    @Test
    @DisplayName("A full page reports the last published id as the resume cursor and is not complete")
    void fullPageReportsCursor() {
        // Three of each fills a page of five; the sixth party belongs to the next page.
        when(commercialPartyRepository.findForReplay(any(), any(), any()))
                .thenReturn(List.of(commercial(1), commercial(3), commercial(5)));
        when(personPartyRepository.findForReplay(any(), any(), any()))
                .thenReturn(List.of(person(2), person(4), person(6)));

        PartyFactReplayResultDto result = service.replayPage(null, null, 5);

        assertThat(result.emitted()).isEqualTo(5);
        assertThat(result.complete()).isFalse();
        // Party 6 was read but not published, so the next page must resume at 5, not 6.
        assertThat(result.nextAfterId()).isEqualTo(id(5));
        verify(factPublisher, never())
                .partyChanged(org.mockito.ArgumentMatchers.argThat(
                        (AbstractParty party) -> party.getPartyId().equals(id(6))));
    }

    @Test
    @DisplayName("Two exhausted tables that exactly fill the page still report complete")
    void exhaustedTablesFillingThePageExactlyAreComplete() {
        // The regression #1900's review caught: each side returned fewer than the limit, so neither
        // has more to give, but the merge is exactly the page size. Reading completeness off the
        // merged size alone reported complete=false and handed back a cursor whose only possible
        // next page is empty.
        when(commercialPartyRepository.findForReplay(any(), any(), any()))
                .thenReturn(List.of(commercial(1), commercial(3)));
        when(personPartyRepository.findForReplay(any(), any(), any()))
                .thenReturn(List.of(person(2), person(4), person(5)));

        PartyFactReplayResultDto result = service.replayPage(null, null, 5);

        assertThat(result.emitted()).isEqualTo(5);
        assertThat(result.complete()).isTrue();
        assertThat(result.nextAfterId()).isNull();
    }

    @Test
    @DisplayName("Two exhausted tables that overflow the page are not complete, and resume mid-merge")
    void exhaustedTablesOverflowingThePageAreNotComplete() {
        // Both sides are exhausted, but together they exceed one page: the overflow has to be left
        // for the next call, so this is not complete even though neither table has more rows.
        when(commercialPartyRepository.findForReplay(any(), any(), any()))
                .thenReturn(List.of(commercial(1), commercial(3)));
        when(personPartyRepository.findForReplay(any(), any(), any())).thenReturn(List.of(person(2), person(4)));

        PartyFactReplayResultDto result = service.replayPage(null, null, 3);

        assertThat(result.emitted()).isEqualTo(3);
        assertThat(result.complete()).isFalse();
        assertThat(result.nextAfterId()).isEqualTo(id(3));
    }

    @Test
    @DisplayName("A table cut off at exactly the page size is not treated as exhausted")
    void aFullSingleTablePageIsNotComplete() {
        // The commercial side returned exactly the limit, so the limit — not the data — ended it.
        when(commercialPartyRepository.findForReplay(any(), any(), any()))
                .thenReturn(List.of(commercial(1), commercial(2), commercial(3)));
        when(personPartyRepository.findForReplay(any(), any(), any())).thenReturn(List.of());

        PartyFactReplayResultDto result = service.replayPage(null, null, 3);

        assertThat(result.complete()).isFalse();
        assertThat(result.nextAfterId()).isEqualTo(id(3));
    }

    @Test
    @DisplayName("The cursor and updatedSince filter are passed to both repositories unchanged")
    void passesCursorAndFilterToBothTables() {
        UUID after = id(7);
        Instant since = Instant.parse("2026-08-01T00:00:00Z");

        PartyFactReplayResultDto result = service.replayPage(after, since, 250);

        verify(commercialPartyRepository).findForReplay(eq(after), eq(since), any(Pageable.class));
        verify(personPartyRepository).findForReplay(eq(after), eq(since), any(Pageable.class));
        // Echoed back so a paging script cannot drift off its own filter.
        assertThat(result.updatedSince()).isEqualTo(since);
        assertThat(result.startedAt()).isEqualTo(Instant.parse("2026-09-08T12:00:00Z"));
    }

    @Test
    @DisplayName("An exhausted customer base emits nothing and reports complete")
    void exhaustedBaseIsComplete() {
        PartyFactReplayResultDto result = service.replayPage(id(99), null, 500);

        assertThat(result.emitted()).isZero();
        assertThat(result.complete()).isTrue();
        assertThat(result.nextAfterId()).isNull();
        verify(factPublisher, never()).partyChanged(any());
    }

    @Test
    @DisplayName("The page size is clamped into 1..MAX_LIMIT rather than trusted")
    void clampsPageSize() {
        when(commercialPartyRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(pageOfCommercial(PartyFactReplayServiceImpl.MAX_LIMIT));

        // Over the cap: the request is honoured at the cap, not at the number asked for.
        assertThat(service.replayPage(null, null, 99_999).emitted()).isEqualTo(PartyFactReplayServiceImpl.MAX_LIMIT);

        Mockito.reset(commercialPartyRepository, personPartyRepository);
        when(commercialPartyRepository.findForReplay(any(), any(), any())).thenReturn(List.of(commercial(1)));
        when(personPartyRepository.findForReplay(any(), any(), any())).thenReturn(List.of());

        // Below the floor: clamped up to one rather than looping on a zero-sized page.
        assertThat(service.replayPage(null, null, 0).emitted()).isEqualTo(1);
    }

    private static List<CommercialParty> pageOfCommercial(int size) {
        List<CommercialParty> parties = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            parties.add(commercial(i));
        }
        return parties;
    }
}
