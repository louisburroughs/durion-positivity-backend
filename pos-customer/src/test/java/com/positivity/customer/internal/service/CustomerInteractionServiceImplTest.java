package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.CustomerInteractionResponse;
import com.positivity.customer.internal.dto.RecordInteractionRequest;
import com.positivity.customer.internal.entity.CustomerInteraction;
import com.positivity.customer.internal.enums.InteractionDirection;
import com.positivity.customer.internal.enums.InteractionType;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.repository.CustomerInteractionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link CustomerInteractionServiceImpl}.
 *
 * <p>
 * Interactions are the contact history a service advisor reads before calling a
 * customer. Two behaviours matter beyond the CRUD.
 *
 * <p>
 * <b>Ingest is idempotent on {@code sourceEventId}.</b> Campaign-send and
 * workorder facts are delivered at-least-once, so a replay must not add a second
 * identical row to the customer's history. Ingest reports {@code false} rather
 * than throwing, letting the caller record the event as handled either way.
 *
 * <p>
 * <b>Bodies are redacted on the way out.</b> Emails and long digit runs are
 * masked when an interaction is read, so a note pasted with a card number or an
 * address does not leak through the history endpoint. That is applied on read,
 * not on write, which the tests fix so it cannot be "optimized" into the write
 * path where existing rows would escape it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerInteractionServiceImpl — contact history")
class CustomerInteractionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID PARTY_ID = UUID.randomUUID();
    private static final UUID CONTACT_ID = UUID.randomUUID();

    @Mock
    private CustomerInteractionRepository interactionRepository;

    @Captor
    private ArgumentCaptor<CustomerInteraction> saved;

    private CustomerInteractionServiceImpl sut;

    @BeforeEach
    void setUp() {
        sut = new CustomerInteractionServiceImpl(Clock.fixed(NOW, ZoneOffset.UTC), interactionRepository);
    }

    private static RecordInteractionRequest request(InteractionDirection direction, Instant occurredAt) {
        return new RecordInteractionRequest(
                InteractionType.CALL,
                CONTACT_ID,
                MarketingChannel.EMAIL,
                direction,
                "Follow-up",
                "Called about the estimate",
                "Body text",
                occurredAt);
    }

    private static CustomerInteraction interaction(String body) {
        return CustomerInteraction.builder()
                .interactionId(UUID.randomUUID())
                .partyId(PARTY_ID)
                .type(InteractionType.NOTE)
                .direction(InteractionDirection.OUTBOUND)
                .body(body)
                .occurredAt(NOW)
                .build();
    }

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("persists the interaction against the party")
        void record_persists() {
            when(interactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            CustomerInteractionResponse response = sut.record(PARTY_ID, request(InteractionDirection.INBOUND, NOW));

            assertThat(response.partyId()).isEqualTo(PARTY_ID);
            assertThat(response.type()).isEqualTo("CALL");
            assertThat(response.direction()).isEqualTo("INBOUND");
            assertThat(response.channel()).isEqualTo("EMAIL");
        }

        @Test
        @DisplayName("defaults the direction to OUTBOUND when the request omits it")
        void record_whenDirectionOmitted_defaultsOutbound() {
            when(interactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.record(PARTY_ID, request(null, NOW));

            verify(interactionRepository).save(saved.capture());
            assertThat(saved.getValue().getDirection()).isEqualTo(InteractionDirection.OUTBOUND);
        }

        @Test
        @DisplayName("stamps the clock when the request carries no occurredAt")
        void record_whenOccurredAtOmitted_usesClock() {
            when(interactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.record(PARTY_ID, request(InteractionDirection.OUTBOUND, null));

            verify(interactionRepository).save(saved.capture());
            assertThat(saved.getValue().getOccurredAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("keeps a caller-supplied occurredAt, so backdated history stays accurate")
        void record_keepsSuppliedOccurredAt() {
            Instant earlier = NOW.minusSeconds(86_400);
            when(interactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.record(PARTY_ID, request(InteractionDirection.OUTBOUND, earlier));

            verify(interactionRepository).save(saved.capture());
            assertThat(saved.getValue().getOccurredAt()).isEqualTo(earlier);
        }

        @Test
        @DisplayName("redacts an email address out of the body on the way back")
        void record_redactsEmailInResponse() {
            when(interactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            RecordInteractionRequest withEmail = new RecordInteractionRequest(
                    InteractionType.NOTE,
                    null,
                    null,
                    InteractionDirection.OUTBOUND,
                    "Subject",
                    "Summary",
                    "Reach them at ada@example.com",
                    NOW);

            CustomerInteractionResponse response = sut.record(PARTY_ID, withEmail);

            assertThat(response.body()).doesNotContain("ada@example.com").contains("[redacted-email]");
        }
    }

    @Nested
    @DisplayName("ingest")
    class Ingest {

        @Test
        @DisplayName("writes an interaction that carries no source event id")
        void ingest_withoutSourceEvent_writes() {
            assertThat(sut.ingest(interaction(null))).isTrue();

            verify(interactionRepository).save(any());
            verify(interactionRepository, never()).existsBySourceEventId(anyString());
        }

        @Test
        @DisplayName("writes the first interaction for a source event")
        void ingest_firstTimeForSourceEvent_writes() {
            CustomerInteraction fromEvent = interaction(null);
            fromEvent.setSourceEventId("event-1");
            when(interactionRepository.existsBySourceEventId("event-1")).thenReturn(false);

            assertThat(sut.ingest(fromEvent)).isTrue();
            verify(interactionRepository).save(fromEvent);
        }

        @Test
        @DisplayName("reports false and writes nothing when the source event was already applied")
        void ingest_whenSourceEventSeen_isNoOp() {
            CustomerInteraction replay = interaction(null);
            replay.setSourceEventId("event-1");
            when(interactionRepository.existsBySourceEventId("event-1")).thenReturn(true);

            // Reported, not thrown: the caller still records the event as handled.
            assertThat(sut.ingest(replay)).isFalse();
            verify(interactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("stamps the clock when the ingested interaction has no occurredAt")
        void ingest_whenOccurredAtMissing_usesClock() {
            CustomerInteraction withoutTime = interaction(null);
            withoutTime.setOccurredAt(null);

            sut.ingest(withoutTime);

            assertThat(withoutTime.getOccurredAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("keeps an occurredAt the caller already set")
        void ingest_keepsSuppliedOccurredAt() {
            Instant earlier = NOW.minusSeconds(3_600);
            CustomerInteraction withTime = interaction(null);
            withTime.setOccurredAt(earlier);

            sut.ingest(withTime);

            assertThat(withTime.getOccurredAt()).isEqualTo(earlier);
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @ParameterizedTest(name = "page {0} size {1} requests page {2} size {3}")
        @CsvSource({"0, 25, 0, 25", "-4, 25, 0, 25", "0, 0, 0, 1", "0, 5000, 0, 200"})
        @DisplayName("clamps paging inputs rather than passing them through")
        void listForParty_clampsPaging(int page, int size, int expectedPage, int expectedSize) {
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.captor();
            when(interactionRepository.findByPartyIdOrderByOccurredAtDesc(eq(PARTY_ID), pageable.capture()))
                    .thenReturn(new PageImpl<>(List.of(interaction(null))));

            sut.listForParty(PARTY_ID, null, page, size);

            assertThat(pageable.getValue().getPageNumber()).isEqualTo(expectedPage);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(expectedSize);
        }

        @Test
        @DisplayName("uses the type-filtered query when a type is supplied")
        void listForParty_withType_usesFilteredQuery() {
            when(interactionRepository.findByPartyIdAndTypeOrderByOccurredAtDesc(
                            eq(PARTY_ID), eq(InteractionType.CALL), any()))
                    .thenReturn(new PageImpl<>(List.of(interaction(null))));

            assertThat(sut.listForParty(PARTY_ID, InteractionType.CALL, 0, 25).items())
                    .hasSize(1);
            verify(interactionRepository, never()).findByPartyIdOrderByOccurredAtDesc(any(), any());
        }

        @Test
        @DisplayName("redacts bodies when listing, so history reads cannot leak masked content")
        void listForParty_redactsBodies() {
            when(interactionRepository.findByPartyIdOrderByOccurredAtDesc(eq(PARTY_ID), any()))
                    .thenReturn(new PageImpl<>(List.of(interaction("card 4111111111111111 and ada@example.com"))));

            assertThat(sut.listForParty(PARTY_ID, null, 0, 25).items())
                    .singleElement()
                    .satisfies(response -> assertThat(response.body())
                            .doesNotContain("4111111111111111")
                            .doesNotContain("ada@example.com"));
        }

        @Test
        @DisplayName("recentForParty returns the most recent handful, redacted")
        void recentForParty_returnsRedacted() {
            when(interactionRepository.findTop5ByPartyIdOrderByOccurredAtDesc(PARTY_ID))
                    .thenReturn(List.of(interaction("ada@example.com")));

            assertThat(sut.recentForParty(PARTY_ID))
                    .singleElement()
                    .satisfies(response -> assertThat(response.body()).contains("[redacted-email]"));
        }

        @Test
        @DisplayName("tolerates an interaction with no channel")
        void toResponse_whenChannelNull_reportsNull() {
            when(interactionRepository.findTop5ByPartyIdOrderByOccurredAtDesc(PARTY_ID))
                    .thenReturn(List.of(interaction(null)));

            assertThat(sut.recentForParty(PARTY_ID))
                    .singleElement()
                    .satisfies(response -> assertThat(response.channel()).isNull());
        }
    }

    @Nested
    @DisplayName("channelOrNull")
    class ChannelOrNull {

        @Test
        @DisplayName("maps a known channel name onto the enum")
        void mapsKnownChannel() {
            assertThat(CustomerInteractionServiceImpl.channelOrNull("EMAIL")).isEqualTo(MarketingChannel.EMAIL);
        }

        @Test
        @DisplayName("treats null and blank as no channel")
        void treatsBlankAsNull() {
            assertThat(CustomerInteractionServiceImpl.channelOrNull(null)).isNull();
            assertThat(CustomerInteractionServiceImpl.channelOrNull("   ")).isNull();
        }
    }
}
