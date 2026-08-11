package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.customer.BillingRulesUpdatedV1;
import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.order.internal.entity.ExtBillingRules;
import com.positivity.order.internal.entity.ExtCustomer;
import com.positivity.order.internal.repository.ExtBillingRulesRepository;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link CustomerEventsListener} (parity story I2, story C4 /
 * spec R4.5 for billing rules).
 *
 * <p>
 * The billing-rules replica gates the on-account tender at checkout, and there
 * the difference between "not stated" and "false" is real money: a null
 * {@code creditHold} means the CRM has said nothing, while {@code false} means it
 * has explicitly cleared the customer. The tests pin that those stay distinct
 * rather than collapsing to a primitive default.
 *
 * <p>
 * The delivery contract shared with the other replica listeners is covered once
 * in {@link ReplicaListenerContractTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CustomerEventsListener — customer and billing-rules replicas")
class CustomerEventsListenerTest {

    private static final UUID PARTY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtCustomerRepository extCustomerRepository;

    @Mock
    private ExtBillingRulesRepository extBillingRulesRepository;

    private CustomerEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new CustomerEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                extCustomerRepository,
                extBillingRulesRepository);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(extCustomerRepository.findById(any())).thenReturn(Optional.empty());
        when(extBillingRulesRepository.findById(any())).thenReturn(Optional.empty());
    }

    private static String updateEnvelope(long version) {
        return """
                {"eventId":"evt-1","eventType":"%s","aggregateVersion":%d,
                 "payload":{"partyId":"%s","status":"ACTIVE","displayName":"Fleet Co",
                   "partyType":"ORGANIZATION","requirementsMet":true}}
                """.formatted(CustomerPartyUpdatedV1.EVENT_TYPE, version, PARTY_ID);
    }

    private static String billingRulesEnvelope(String creditLimit, String creditHold, String poRequired) {
        return """
                {"eventId":"evt-2","eventType":"%s","aggregateVersion":2,
                 "payload":{"partyId":"%s","paymentTerms":"NET30","creditLimit":%s,
                   "creditHold":%s,"poRequired":%s}}
                """.formatted(BillingRulesUpdatedV1.EVENT_TYPE, PARTY_ID, creditLimit, creditHold, poRequired);
    }

    @Test
    @DisplayName("replicates a customer party")
    void replicatesCustomer() {
        listener.onCustomerEvent(updateEnvelope(3));

        ArgumentCaptor<ExtCustomer> captor = ArgumentCaptor.forClass(ExtCustomer.class);
        verify(extCustomerRepository).save(captor.capture());
        ExtCustomer saved = captor.getValue();
        assertThat(saved.getPartyId()).isEqualTo(PARTY_ID);
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getDisplayName()).isEqualTo("Fleet Co");
        assertThat(saved.getPartyType()).isEqualTo("ORGANIZATION");
        assertThat(saved.isRequirementsMet()).isTrue();
        assertThat(saved.getAggregateVersion()).isEqualTo(3);
        assertThat(saved.getSyncedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("defaults a missing status to UNKNOWN rather than storing null")
    void missingStatusBecomesUnknown() {
        listener.onCustomerEvent("""
                {"eventId":"evt-1","eventType":"%s","aggregateVersion":1,"payload":{"partyId":"%s"}}""".formatted(CustomerPartyUpdatedV1.EVENT_TYPE, PARTY_ID));

        ArgumentCaptor<ExtCustomer> captor = ArgumentCaptor.forClass(ExtCustomer.class);
        verify(extCustomerRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("UNKNOWN");
        assertThat(captor.getValue().isRequirementsMet()).isFalse();
    }

    @Test
    @DisplayName("ignores an update no newer than the replica it already holds")
    void staleUpdateIsIgnored() {
        ExtCustomer existing = new ExtCustomer();
        existing.setPartyId(PARTY_ID);
        existing.setAggregateVersion(7);
        when(extCustomerRepository.findById(PARTY_ID)).thenReturn(Optional.of(existing));

        listener.onCustomerEvent(updateEnvelope(7));

        verify(extCustomerRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("removes the replica on a party deletion")
    void deleteRemovesReplica() {
        listener.onCustomerEvent("""
                {"eventId":"evt-3","eventType":"%s","payload":{"partyId":"%s"}}""".formatted(CustomerPartyDeletedV1.EVENT_TYPE, PARTY_ID));

        verify(extCustomerRepository).deleteById(PARTY_ID);
        verify(extCustomerRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("replicates AR billing terms that gate the on-account tender")
    void replicatesBillingRules() {
        listener.onCustomerEvent(billingRulesEnvelope("5000.00", "false", "true"));

        ArgumentCaptor<ExtBillingRules> captor = ArgumentCaptor.forClass(ExtBillingRules.class);
        verify(extBillingRulesRepository).save(captor.capture());
        ExtBillingRules saved = captor.getValue();
        assertThat(saved.getPartyId()).isEqualTo(PARTY_ID);
        assertThat(saved.getPaymentTerms()).isEqualTo("NET30");
        assertThat(saved.getCreditLimit()).isEqualByComparingTo("5000.00");
        assertThat(saved.getCreditHold()).isFalse();
        assertThat(saved.getPoRequired()).isTrue();
    }

    @Test
    @DisplayName("keeps an unstated billing flag null rather than collapsing it to false")
    void unstatedBillingFlagsStayNull() {
        listener.onCustomerEvent(billingRulesEnvelope("null", "null", "null"));

        ArgumentCaptor<ExtBillingRules> captor = ArgumentCaptor.forClass(ExtBillingRules.class);
        verify(extBillingRulesRepository).save(captor.capture());
        // Null means "the CRM has said nothing", which the checkout gate must not read as an
        // explicit clearance.
        assertThat(captor.getValue().getCreditLimit()).isNull();
        assertThat(captor.getValue().getCreditHold()).isNull();
        assertThat(captor.getValue().getPoRequired()).isNull();
    }

    @Test
    @DisplayName("ignores billing rules no newer than the replica it already holds")
    void staleBillingRulesAreIgnored() {
        ExtBillingRules existing = new ExtBillingRules();
        existing.setPartyId(PARTY_ID);
        existing.setAggregateVersion(4);
        when(extBillingRulesRepository.findById(PARTY_ID)).thenReturn(Optional.of(existing));

        listener.onCustomerEvent(billingRulesEnvelope("100", "false", "false"));

        verify(extBillingRulesRepository, never()).save(any());
    }
}
