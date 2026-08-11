package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.AddSuppressionRequest;
import com.positivity.customer.internal.entity.SuppressionEntry;
import com.positivity.customer.internal.enums.ConsentChangeSource;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.SuppressionReason;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.repository.SuppressionEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link SuppressionServiceImpl}.
 *
 * <p>
 * The suppression list is the do-not-contact register: an address on it must
 * never receive marketing. Two properties carry the weight.
 *
 * <p>
 * <b>Adding is idempotent and silent on repeat.</b> Bounce feeds replay the same
 * address routinely, so a second add returns the existing entry without touching
 * the audit trail or re-emitting a fact. Getting that wrong would churn
 * downstream consumers on every bounce redelivery.
 *
 * <p>
 * <b>Addresses are stored hashed, never raw.</b> The entry keeps a hash for
 * matching and a short hint for operators; the plain address is not persisted, so
 * the register cannot become a mailing list. The tests assert the raw address
 * never reaches the entity.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SuppressionServiceImpl — do-not-contact register")
class SuppressionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID SUPPRESSION_ID = UUID.randomUUID();
    private static final UUID PARTY_ID = UUID.randomUUID();
    private static final String ADDRESS = "Ada@Example.COM";

    @Mock
    private SuppressionEntryRepository suppressionRepository;

    @Mock
    private CustomerFactPublisher factPublisher;

    @Captor
    private ArgumentCaptor<SuppressionEntry> savedEntry;

    private SuppressionServiceImpl sut;

    @BeforeEach
    void setUp() {
        sut = new SuppressionServiceImpl(Clock.fixed(NOW, ZoneOffset.UTC), suppressionRepository, factPublisher);
    }

    private static AddSuppressionRequest request(ConsentChangeSource source) {
        return new AddSuppressionRequest(
                MarketingChannel.EMAIL, ADDRESS, PARTY_ID, SuppressionReason.HARD_BOUNCE, source);
    }

    private static SuppressionEntry entry() {
        return SuppressionEntry.builder()
                .suppressionId(SUPPRESSION_ID)
                .channel(MarketingChannel.EMAIL)
                .addressHash(MarketingAddressNormalizer.hash(MarketingChannel.EMAIL, ADDRESS))
                .addressHint(MarketingAddressNormalizer.hint(MarketingChannel.EMAIL, ADDRESS))
                .partyId(PARTY_ID)
                .reason(SuppressionReason.HARD_BOUNCE)
                .source(ConsentChangeSource.CSR)
                .createdBy("system")
                .createdAt(NOW)
                .build();
    }

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("stores a hashed address and a hint, never the raw address")
        void add_storesHashNotRawAddress() {
            when(suppressionRepository.findByChannelAndAddressHash(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(suppressionRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            sut.add(request(ConsentChangeSource.CSR));

            verify(suppressionRepository).saveAndFlush(savedEntry.capture());
            SuppressionEntry saved = savedEntry.getValue();
            assertThat(saved.getAddressHash())
                    .isEqualTo(MarketingAddressNormalizer.hash(MarketingChannel.EMAIL, ADDRESS));
            // The register must not become a mailing list.
            assertThat(saved.getAddressHash()).isNotEqualTo(ADDRESS);
            assertThat(saved.getAddressHint()).isNotEqualTo(ADDRESS);
            assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("publishes a suppression fact keyed on the hash, not the address")
        void add_publishesFactWithHash() {
            when(suppressionRepository.findByChannelAndAddressHash(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(suppressionRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            sut.add(request(ConsentChangeSource.CSR));

            verify(factPublisher)
                    .suppressionChanged(
                            eq("EMAIL"),
                            eq(MarketingAddressNormalizer.hash(MarketingChannel.EMAIL, ADDRESS)),
                            eq(PARTY_ID),
                            eq(true),
                            eq("HARD_BOUNCE"));
        }

        @Test
        @DisplayName("returns the existing entry on a repeat add without re-writing or re-emitting")
        void add_whenAlreadySuppressed_isSilentlyIdempotent() {
            when(suppressionRepository.findByChannelAndAddressHash(any(), anyString()))
                    .thenReturn(Optional.of(entry()));

            assertThat(sut.add(request(ConsentChangeSource.CSR)).suppressionId())
                    .isEqualTo(SUPPRESSION_ID);

            // Bounce feeds replay the same address routinely; churning the audit
            // trail and the fact stream on every replay would be noise.
            verify(suppressionRepository, never()).saveAndFlush(any());
            verify(factPublisher, never()).suppressionChanged(any(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("matches an existing entry regardless of address casing")
        void add_matchesExistingRegardlessOfCasing() {
            String hash = MarketingAddressNormalizer.hash(MarketingChannel.EMAIL, ADDRESS);
            when(suppressionRepository.findByChannelAndAddressHash(MarketingChannel.EMAIL, hash))
                    .thenReturn(Optional.of(entry()));

            AddSuppressionRequest differentCasing = new AddSuppressionRequest(
                    MarketingChannel.EMAIL,
                    ADDRESS.toLowerCase(),
                    PARTY_ID,
                    SuppressionReason.HARD_BOUNCE,
                    ConsentChangeSource.CSR);

            sut.add(differentCasing);

            verify(suppressionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("defaults the change source to CSR when the request omits it")
        void add_whenSourceOmitted_defaultsToCsr() {
            when(suppressionRepository.findByChannelAndAddressHash(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(suppressionRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            sut.add(request(null));

            verify(suppressionRepository).saveAndFlush(savedEntry.capture());
            assertThat(savedEntry.getValue().getSource()).isEqualTo(ConsentChangeSource.CSR);
        }

        @Test
        @DisplayName("keeps an explicitly supplied change source")
        void add_keepsSuppliedSource() {
            when(suppressionRepository.findByChannelAndAddressHash(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(suppressionRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            sut.add(request(ConsentChangeSource.UNSUBSCRIBE_LINK));

            verify(suppressionRepository).saveAndFlush(savedEntry.capture());
            assertThat(savedEntry.getValue().getSource()).isEqualTo(ConsentChangeSource.UNSUBSCRIBE_LINK);
        }

        @Test
        @DisplayName("converges on the winning row when a concurrent add loses the unique-constraint race")
        void add_whenConstraintRaceLost_convergesOnWinner() {
            when(suppressionRepository.findByChannelAndAddressHash(any(), anyString()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(entry()));
            when(suppressionRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("unique violation"));

            assertThat(sut.add(request(ConsentChangeSource.CSR)).suppressionId())
                    .isEqualTo(SUPPRESSION_ID);
            verify(factPublisher, never()).suppressionChanged(any(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("rethrows when the constraint violation was not a losing race after all")
        void add_whenViolationUnexplained_rethrows() {
            when(suppressionRepository.findByChannelAndAddressHash(any(), anyString()))
                    .thenReturn(Optional.empty());
            when(suppressionRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("some other violation"));

            assertThatThrownBy(() -> sut.add(request(ConsentChangeSource.CSR)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("deletes the entry and publishes a lifted fact with no reason")
        void remove_deletesAndPublishes() {
            SuppressionEntry existing = entry();
            when(suppressionRepository.findById(SUPPRESSION_ID)).thenReturn(Optional.of(existing));

            sut.remove(SUPPRESSION_ID);

            verify(suppressionRepository).delete(existing);
            verify(factPublisher)
                    .suppressionChanged(eq("EMAIL"), eq(existing.getAddressHash()), eq(PARTY_ID), eq(false), eq(null));
        }

        @Test
        @DisplayName("throws 404 for an unknown suppression id and deletes nothing")
        void remove_whenAbsent_throwsNotFound() {
            when(suppressionRepository.findById(SUPPRESSION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.remove(SUPPRESSION_ID)).isInstanceOf(CrmResourceNotFoundException.class);
            verify(suppressionRepository, never()).delete(any());
            verify(factPublisher, never()).suppressionChanged(any(), any(), any(), eq(false), any());
        }
    }

    @Nested
    @DisplayName("queries")
    class Queries {

        private Page<SuppressionEntry> onePage() {
            return new PageImpl<>(List.of(entry()));
        }

        @ParameterizedTest(name = "page {0} size {1} requests page {2} size {3}")
        @CsvSource({
            "0,   25,  0, 25",
            "3,   50,  3, 50",
            "-1,  25,  0, 25", // negative page floors to the first page
            "0,   0,   0, 1", // size is clamped into 1..200
            "0,   500, 0, 200",
        })
        @DisplayName("clamps paging inputs rather than passing them through to the query")
        void list_clampsPaging(int page, int size, int expectedPage, int expectedSize) {
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.captor();
            when(suppressionRepository.findAll(pageable.capture())).thenReturn(onePage());

            sut.list(null, page, size);

            assertThat(pageable.getValue().getPageNumber()).isEqualTo(expectedPage);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(expectedSize);
        }

        @Test
        @DisplayName("lists newest first, so the most recent suppressions surface")
        void list_sortsByCreatedAtDescending() {
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.captor();
            when(suppressionRepository.findAll(pageable.capture())).thenReturn(onePage());

            sut.list(null, 0, 25);

            assertThat(pageable.getValue().getSort().getOrderFor("createdAt"))
                    .isNotNull()
                    .satisfies(order -> assertThat(order.isDescending()).isTrue());
        }

        @Test
        @DisplayName("uses the channel-filtered query when a channel is supplied")
        void list_withChannel_usesFilteredQuery() {
            when(suppressionRepository.findByChannel(eq(MarketingChannel.EMAIL), any()))
                    .thenReturn(onePage());

            assertThat(sut.list(MarketingChannel.EMAIL, 0, 25).items()).hasSize(1);
            verify(suppressionRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("listForParty returns every entry held against the party")
        void listForParty_returnsEntries() {
            when(suppressionRepository.findByPartyId(PARTY_ID)).thenReturn(List.of(entry()));

            assertThat(sut.listForParty(PARTY_ID))
                    .singleElement()
                    .satisfies(response -> assertThat(response.channel()).isEqualTo("EMAIL"));
        }

        @Test
        @DisplayName("isSuppressed hashes the address before checking, so casing does not matter")
        void isSuppressed_hashesBeforeChecking() {
            String hash = MarketingAddressNormalizer.hash(MarketingChannel.EMAIL, ADDRESS);
            when(suppressionRepository.existsByChannelAndAddressHash(MarketingChannel.EMAIL, hash))
                    .thenReturn(true);

            assertThat(sut.isSuppressed(MarketingChannel.EMAIL, ADDRESS.toLowerCase()))
                    .isTrue();
        }

        @Test
        @DisplayName("isSuppressed reports false for an address not on the register")
        void isSuppressed_whenAbsent_isFalse() {
            when(suppressionRepository.existsByChannelAndAddressHash(any(), anyString()))
                    .thenReturn(false);

            assertThat(sut.isSuppressed(MarketingChannel.EMAIL, ADDRESS)).isFalse();
        }

        @Test
        @DisplayName("isPartySuppressed checks the party-and-channel pair")
        void isPartySuppressed_delegatesToRepository() {
            when(suppressionRepository.existsByPartyIdAndChannel(PARTY_ID, MarketingChannel.SMS))
                    .thenReturn(true);

            assertThat(sut.isPartySuppressed(PARTY_ID, MarketingChannel.SMS)).isTrue();
        }
    }
}
