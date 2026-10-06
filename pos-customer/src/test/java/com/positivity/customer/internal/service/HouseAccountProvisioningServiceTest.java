package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.AccountTier;
import com.positivity.customer.internal.enums.HouseAccountKind;
import com.positivity.customer.internal.enums.LifecycleStage;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@DisplayName("HouseAccountProvisioningService (CAP:550 S7)")
class HouseAccountProvisioningServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c1");

    private final CommercialPartyRepository repository = mock(CommercialPartyRepository.class);
    private final CustomerFactPublisher factPublisher = mock(CustomerFactPublisher.class);
    private final HouseAccountProvisioningService service =
            new HouseAccountProvisioningService(Clock.fixed(NOW, ZoneOffset.UTC), repository, factPublisher);

    @Test
    @DisplayName("creates the CASH account with the fixed shape and publishes its party fact")
    void createsTheCashAccount() {
        when(repository.findByHouseAccount(HouseAccountKind.CASH_SALE)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(CommercialParty.class))).thenAnswer(invocation -> {
            CommercialParty saved = invocation.getArgument(0);
            saved.setPartyId(PARTY_ID);
            return saved;
        });

        Optional<UUID> created = service.createCashAccountIfMissing();

        assertThat(created).contains(PARTY_ID);
        ArgumentCaptor<CommercialParty> captor = ArgumentCaptor.forClass(CommercialParty.class);
        verify(repository).saveAndFlush(captor.capture());
        CommercialParty account = captor.getValue();
        assertThat(account.getHouseAccount()).isEqualTo(HouseAccountKind.CASH_SALE);
        assertThat(account.getLegalName()).isEqualTo("Walk-in customer");
        assertThat(account.getDisplayName()).isEqualTo("Walk-in customer");
        assertThat(account.getCustomerNumber()).isEqualTo("CASH");
        assertThat(account.getPartyType()).isEqualTo(PartyType.COMMERCIAL);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getLifecycleStage()).isEqualTo(LifecycleStage.ACTIVE);
        // STANDARD under a manual override: tier resolution never re-tiers it.
        assertThat(account.getTier()).isEqualTo(AccountTier.STANDARD);
        assertThat(account.isTierManualOverride()).isTrue();
        // Marketing is gated off at the account level.
        assertThat(account.isAccountMarketingOptOut()).isTrue();
        assertThat(account.getAccountMarketingOptOutAt()).isEqualTo(NOW);
        // No personal data and nothing attached.
        assertThat(account.getTaxId()).isNull();
        assertThat(account.getPrimaryAddress()).isNull();
        assertThat(account.getBillingRules()).isNull();
        assertThat(account.getBillingTermsId()).isNull();
        assertThat(account.getParentParty()).isNull();
        assertThat(account.getVehicleVins()).isEmpty();
        assertThat(account.getExternalIdentifiers()).isEmpty();

        // Flushed before the fact is queued, so a lost race never queues one.
        InOrder order = inOrder(repository, factPublisher);
        order.verify(repository).saveAndFlush(account);
        order.verify(factPublisher).partyChanged(account);
    }

    @Test
    @DisplayName("warns that consumers need a fact replay when the account is created with publication off")
    void warnsWhenCreatedWithoutFactPublication() {
        when(repository.findByHouseAccount(HouseAccountKind.CASH_SALE)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(CommercialParty.class))).thenAnswer(invocation -> {
            CommercialParty saved = invocation.getArgument(0);
            saved.setPartyId(PARTY_ID);
            return saved;
        });
        when(factPublisher.publicationEnabled()).thenReturn(false);

        try (LogCapture logs = LogCapture.of(HouseAccountProvisioningService.class)) {
            assertThat(service.createCashAccountIfMissing()).contains(PARTY_ID);

            assertThat(logs.messagesAt(Level.WARN))
                    .singleElement()
                    .asString()
                    .contains(PARTY_ID.toString())
                    .contains("facts/replay");
        }
    }

    @Test
    @DisplayName("says nothing about a replay when the fact was queued")
    void noReplayWarningWhenPublicationIsOn() {
        when(repository.findByHouseAccount(HouseAccountKind.CASH_SALE)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(CommercialParty.class))).thenAnswer(invocation -> {
            CommercialParty saved = invocation.getArgument(0);
            saved.setPartyId(PARTY_ID);
            return saved;
        });
        when(factPublisher.publicationEnabled()).thenReturn(true);

        try (LogCapture logs = LogCapture.of(HouseAccountProvisioningService.class)) {
            service.createCashAccountIfMissing();

            assertThat(logs.messagesAt(Level.WARN)).isEmpty();
        }
    }

    @Test
    @DisplayName("names the ordinary party that already holds customer number CASH")
    void findsTheCustomerNumberHolder() {
        CommercialParty holder = new CommercialParty();
        holder.setPartyId(PARTY_ID);
        when(repository.findFirstByCustomerNumber("CASH")).thenReturn(Optional.of(holder));

        assertThat(service.findCashCustomerNumberHolder()).contains(PARTY_ID);
    }

    @Test
    @DisplayName("the house account itself is not a collision")
    void theHouseAccountIsNotItsOwnCollision() {
        CommercialParty house = new CommercialParty();
        house.setPartyId(PARTY_ID);
        house.setHouseAccount(HouseAccountKind.CASH_SALE);
        when(repository.findFirstByCustomerNumber("CASH")).thenReturn(Optional.of(house));

        assertThat(service.findCashCustomerNumberHolder()).isEmpty();
    }

    @Test
    @DisplayName("does nothing when the tenant is already provisioned")
    void isIdempotent() {
        when(repository.findByHouseAccount(HouseAccountKind.CASH_SALE)).thenReturn(Optional.of(new CommercialParty()));

        assertThat(service.createCashAccountIfMissing()).isEmpty();

        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).save(any());
        verifyNoInteractions(factPublisher);
    }
}
