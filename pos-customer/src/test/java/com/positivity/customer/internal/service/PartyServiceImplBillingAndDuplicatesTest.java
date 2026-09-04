package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.config.OutboxEventWriter;
import com.positivity.customer.internal.dto.DuplicateCheckResponse;
import com.positivity.customer.internal.dto.UpsertBillingRulesRequest;
import com.positivity.customer.internal.dto.snapshot.BillingRuleRef;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.InvoiceDeliveryMethod;
import com.positivity.customer.internal.exception.CrmValidationException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.customer.BillingRulesUpdatedV1;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the billing-rules and duplicate-check surfaces of
 * {@link PartyServiceImpl}.
 *
 * <p>
 * Billing rules gate real money — a credit hold is what stops an on-account sale
 * at the register — so the write path has three obligations that all have to
 * happen inside the one transaction: persist the rules, emit
 * {@code customer.billing-rules.updated} through the outbox for
 * pos-accounting's replica, and re-emit the party fact, because credit hold
 * feeds that fact's {@code requirementsMet} verdict (#889). Missing the
 * re-emission would leave downstream modules believing a held account is still
 * good for credit.
 *
 * <p>
 * The duplicate check is the guard against two records for one organization. It
 * distinguishes an exact name match from a fuzzy one so the caller can decide
 * automatically in the clear case and prompt a human otherwise.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PartyServiceImpl — billing rules and duplicate detection")
class PartyServiceImplBillingAndDuplicatesTest {

    private static final UUID PARTY_ID = UUID.fromString("00000000-0000-0000-0000-000000000a01");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final String TOPIC = "customer.events.v1";

    @Mock
    private CommercialPartyRepository partyRepository;

    @Mock
    private PersonPartyRepository personPartyRepository;

    @Mock
    private PartyRelationshipRepository partyRelationshipRepository;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private PersonDirectoryService personDirectoryService;

    @Mock
    private ExtVehicleRepository extVehicleRepository;

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxEventWriter;

    @Mock
    private OutboxEventWriter writer;

    @Mock
    private CustomerFactPublisher customerFactPublisher;

    @Mock
    private MarketingConsentService marketingConsentService;

    @Mock
    private CustomerInteractionService customerInteractionService;

    @Mock
    private EntityManager entityManager;

    private PartyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PartyServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                partyRepository,
                personPartyRepository,
                partyRelationshipRepository,
                cacheManager,
                personDirectoryService,
                extVehicleRepository,
                outboxEventWriter,
                customerFactPublisher,
                marketingConsentService,
                customerInteractionService,
                entityManager);
        when(outboxEventWriter.getIfAvailable()).thenReturn(writer);
        when(customerFactPublisher.eventsTopic()).thenReturn(TOPIC);
    }

    private static CommercialParty party(String legalName) {
        CommercialParty party = new CommercialParty();
        party.setPartyId(PARTY_ID);
        party.setLegalName(legalName);
        // Deliberately distinct from NOW's epoch millis, so a test asserting on the retired
        // emission-millis convention would fail loudly rather than passing by coincidence (#1486).
        party.setVersion(7L);
        return party;
    }

    private static UpsertBillingRulesRequest request() {
        return UpsertBillingRulesRequest.builder()
                .paymentTerms("Net 30")
                .creditLimit(new BigDecimal("10000.00"))
                .currency("USD")
                .taxExempt(true)
                .poRequired(true)
                .creditHold(true)
                .autoPayEnabled(false)
                .invoiceDeliveryMethod(InvoiceDeliveryMethod.EMAIL)
                .billingAddressId(UUID.randomUUID())
                .discountPolicyRef("POLICY-1")
                .build();
    }

    @Nested
    @DisplayName("upsertBillingRulesForParty")
    class BillingRules {

        @Test
        @DisplayName("persists the rules, emits the fact, and re-emits the party fact for requirementsMet")
        void persistsPublishesAndReemits() {
            CommercialParty party = party("Fleet Co LLC");
            when(partyRepository.findByPartyId(PARTY_ID)).thenReturn(party);

            BillingRuleRef ref = service.upsertBillingRulesForParty(PARTY_ID, request());

            assertThat(party.getBillingRules()).isNotNull();
            assertThat(party.getBillingRules().getCreditHold()).isTrue();
            assertThat(party.getBillingRules().getPaymentTerms()).isEqualTo("Net 30");
            verify(partyRepository).save(party);
            // Credit hold feeds the party fact's requirementsMet verdict (#889); without this
            // re-emission a held account still reads as good for credit downstream.
            verify(customerFactPublisher).partyChanged(party);
            assertThat(ref.isCreditHold()).isTrue();
            assertThat(ref.getPaymentTerms()).isEqualTo("Net 30");
            assertThat(ref.getCreditLimit()).isEqualByComparingTo("10000.00");
        }

        @Test
        @DisplayName("publishes billing rules to the same topic the manifest computation scans")
        void publishesToTheConfiguredTopic() {
            when(partyRepository.findByPartyId(PARTY_ID)).thenReturn(party("Fleet Co LLC"));

            service.upsertBillingRulesForParty(PARTY_ID, request());

            ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.captor();
            // Reading the topic from the publisher rather than hardcoding it is what stops the
            // manifest computation desyncing from the outbox rows (PR #893).
            verify(writer).publish(org.mockito.ArgumentMatchers.eq(TOPIC), captor.capture());
            DomainEventEnvelope<?> envelope = captor.getValue();
            assertThat(envelope.eventType()).isEqualTo(BillingRulesUpdatedV1.EVENT_TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(PARTY_ID);
            assertThat(envelope.sourceService()).isEqualTo("pos-customer");
            // billingRules is @Embedded on the party row, so the flush below picks up the pending
            // update and the fact carries CommercialParty's @Version (#1486), not a wall clock.
            verify(entityManager).flush();
            assertThat(envelope.aggregateVersion()).isEqualTo(7L);

            BillingRulesUpdatedV1 payload = (BillingRulesUpdatedV1) envelope.payload();
            assertThat(payload.partyId()).isEqualTo(PARTY_ID);
            assertThat(payload.creditHold()).isTrue();
            assertThat(payload.paymentTerms()).isEqualTo("Net 30");
            assertThat(payload.invoiceDeliveryMethod()).isEqualTo("EMAIL");
        }

        @Test
        @DisplayName("carries a null delivery method through rather than failing the publish")
        void nullDeliveryMethodIsCarried() {
            when(partyRepository.findByPartyId(PARTY_ID)).thenReturn(party("Fleet Co LLC"));
            UpsertBillingRulesRequest request = request();
            request.setInvoiceDeliveryMethod(null);

            BillingRuleRef ref = service.upsertBillingRulesForParty(PARTY_ID, request);

            ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.captor();
            verify(writer).publish(anyString(), captor.capture());
            assertThat(((BillingRulesUpdatedV1) captor.getValue().payload()).invoiceDeliveryMethod())
                    .isNull();
            // The ref keeps its default rather than being overwritten with null.
            assertThat(ref.getInvoiceDeliveryMethod())
                    .isEqualTo(BillingRuleRef.defaults().getInvoiceDeliveryMethod());
        }

        @Test
        @DisplayName("still persists when Kafka is disabled and no outbox writer exists")
        void writesWithoutAnOutbox() {
            when(outboxEventWriter.getIfAvailable()).thenReturn(null);
            CommercialParty party = party("Fleet Co LLC");
            when(partyRepository.findByPartyId(PARTY_ID)).thenReturn(party);

            service.upsertBillingRulesForParty(PARTY_ID, request());

            // The business write must not depend on the messaging feature flag.
            verify(partyRepository).save(party);
            verify(customerFactPublisher).partyChanged(party);
        }

        @Test
        @DisplayName("rejects billing rules for a party that does not exist")
        void unknownPartyIs404() {
            when(partyRepository.findByPartyId(PARTY_ID)).thenReturn(null);

            assertThatThrownBy(() -> service.upsertBillingRulesForParty(PARTY_ID, request()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining(PARTY_ID.toString());

            verify(partyRepository, never()).save(any());
            verify(writer, never()).publish(anyString(), any());
        }
    }

    @Nested
    @DisplayName("checkPartyDuplicates")
    class Duplicates {

        @Test
        @DisplayName("separates an exact name match from fuzzy ones and scores them apart")
        void exactAndFuzzyMatches() {
            CommercialParty exact = party("Fleet Co LLC");
            CommercialParty fuzzy = party("Fleet Co Holdings");
            fuzzy.setPartyId(UUID.randomUUID());
            when(partyRepository.findByLegalNameContaining("Fleet Co LLC")).thenReturn(List.of(fuzzy, exact));

            DuplicateCheckResponse response = service.checkPartyDuplicates("  Fleet Co LLC  ");

            assertThat(response.isDuplicatesFound()).isTrue();
            // The exact id is reported separately so a caller can auto-resolve the clear case
            // instead of prompting a human for it.
            assertThat(response.getExactMatchPartyId()).isEqualTo(PARTY_ID.toString());
            assertThat(response.getPotentialDuplicates())
                    .extracting(
                            DuplicateCheckResponse.PartyMatch::getLegalName,
                            DuplicateCheckResponse.PartyMatch::getMatchType,
                            DuplicateCheckResponse.PartyMatch::getScore)
                    .containsExactly(tuple("Fleet Co Holdings", "FUZZY", 0.7), tuple("Fleet Co LLC", "EXACT", 1.0));
        }

        @Test
        @DisplayName("treats an exact match as exact regardless of case")
        void exactMatchIgnoresCase() {
            when(partyRepository.findByLegalNameContaining("fleet co llc")).thenReturn(List.of(party("Fleet Co LLC")));

            DuplicateCheckResponse response = service.checkPartyDuplicates("fleet co llc");

            assertThat(response.getExactMatchPartyId()).isEqualTo(PARTY_ID.toString());
            assertThat(response.getPotentialDuplicates().getFirst().getMatchType())
                    .isEqualTo("EXACT");
        }

        @Test
        @DisplayName("reports no duplicates when nothing matched")
        void noMatches() {
            when(partyRepository.findByLegalNameContaining("Fleet Co LLC")).thenReturn(List.of());

            DuplicateCheckResponse response = service.checkPartyDuplicates("Fleet Co LLC");

            assertThat(response.isDuplicatesFound()).isFalse();
            assertThat(response.getExactMatchPartyId()).isNull();
            assertThat(response.getPotentialDuplicates()).isEmpty();
        }

        @Test
        @DisplayName("answers an all-whitespace name with an empty result rather than a scan")
        void blankNameShortCircuits() {
            DuplicateCheckResponse response = service.checkPartyDuplicates("   ");

            assertThat(response.isDuplicatesFound()).isFalse();
            assertThat(response.getPotentialDuplicates()).isEmpty();
            verify(partyRepository, never()).findByLegalNameContaining(anyString());
        }

        @ParameterizedTest
        @ValueSource(strings = {"A", " B "})
        @DisplayName("rejects a one-character name, which would match most of the table")
        void singleCharacterNameIsRejected(String legalName) {
            assertThatThrownBy(() -> service.checkPartyDuplicates(legalName))
                    .isInstanceOf(CrmValidationException.class)
                    .hasMessageContaining("at least 2");

            verify(partyRepository, never()).findByLegalNameContaining(anyString());
        }
    }
}
