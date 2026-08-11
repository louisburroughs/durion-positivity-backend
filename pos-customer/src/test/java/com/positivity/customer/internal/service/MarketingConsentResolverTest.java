package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.MarketingConsentDecision;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.CommunicationPreference;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.MarketingConsent;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story #1138, decision O-2: commercial and individual parties resolve consent differently, and
 * the account master gate outranks everything.
 */
@ExtendWith(MockitoExtension.class)
class MarketingConsentResolverTest {

    private static final UUID ACCOUNT = UUID.fromString("01960003-0000-7000-8000-000000000010");
    private static final UUID CONTACT_PARTY = UUID.fromString("01960003-0000-7000-8000-000000000011");
    private static final UUID INDIVIDUAL = UUID.fromString("01960003-0000-7000-8000-000000000012");

    @Mock
    private CommercialPartyRepository commercialPartyRepository;

    @Mock
    private PersonPartyRepository personPartyRepository;

    @Mock
    private PartyRelationshipRepository partyRelationshipRepository;

    @Mock
    private CommunicationPreferenceRepository preferenceRepository;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC);

    private MarketingConsentResolver resolver() {
        return new MarketingConsentResolver(
                clock,
                commercialPartyRepository,
                personPartyRepository,
                partyRelationshipRepository,
                preferenceRepository);
    }

    private static CommercialParty account(boolean gateSet) {
        CommercialParty account = new CommercialParty();
        account.setPartyId(ACCOUNT);
        account.setAccountMarketingOptOut(gateSet);
        return account;
    }

    private static PartyRelationship relationshipTo(UUID personPartyId, boolean primaryBilling) {
        PersonParty person = new PersonParty();
        person.setPartyId(personPartyId);
        PartyRelationship relationship = new PartyRelationship();
        relationship.setFromParty(account(false));
        relationship.setToPerson(person);
        relationship.setPrimaryBillingContact(primaryBilling);
        relationship.setEffectiveStartDate(LocalDate.of(2026, 1, 1));
        return relationship;
    }

    private static CommunicationPreference preference(MarketingConsent email) {
        CommunicationPreference preference = new CommunicationPreference();
        preference.setMarketingEmailConsent(email);
        preference.setMarketingSmsConsent(MarketingConsent.UNSET);
        return preference;
    }

    @Test
    @DisplayName("the account master gate suppresses the whole account regardless of contact consent")
    void accountGateWinsOutright() {
        when(commercialPartyRepository.findById(ACCOUNT)).thenReturn(Optional.of(account(true)));

        MarketingConsentDecision decision = resolver().resolve(ACCOUNT, MarketingChannel.EMAIL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(MarketingConsentDecision.REASON_ACCOUNT_OPT_OUT);
    }

    @Test
    @DisplayName("with the gate clear, the primary business contact's personal consent governs")
    void primaryContactConsentGovernsAccount() {
        when(commercialPartyRepository.findById(ACCOUNT)).thenReturn(Optional.of(account(false)));
        when(partyRelationshipRepository.findPrimaryBillingContact(any(), any()))
                .thenReturn(Optional.of(relationshipTo(CONTACT_PARTY, true)));
        when(preferenceRepository.findByPartyId(CONTACT_PARTY))
                .thenReturn(Optional.of(preference(MarketingConsent.OPT_IN)));

        MarketingConsentDecision decision = resolver().resolve(ACCOUNT, MarketingChannel.EMAIL);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.governingPartyId()).isEqualTo(CONTACT_PARTY);
    }

    @Test
    @DisplayName("an opted-out primary contact blocks account-level sends")
    void optedOutPrimaryContactBlocksAccount() {
        when(commercialPartyRepository.findById(ACCOUNT)).thenReturn(Optional.of(account(false)));
        when(partyRelationshipRepository.findPrimaryBillingContact(any(), any()))
                .thenReturn(Optional.of(relationshipTo(CONTACT_PARTY, true)));
        when(preferenceRepository.findByPartyId(CONTACT_PARTY))
                .thenReturn(Optional.of(preference(MarketingConsent.OPT_OUT)));

        MarketingConsentDecision decision = resolver().resolve(ACCOUNT, MarketingChannel.EMAIL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(MarketingConsentDecision.REASON_CONSENT_OPT_OUT);
    }

    @Test
    @DisplayName("an account with no contacts is unreachable rather than implicitly consenting")
    void accountWithoutContactsIsDenied() {
        when(commercialPartyRepository.findById(ACCOUNT)).thenReturn(Optional.of(account(false)));
        when(partyRelationshipRepository.findPrimaryBillingContact(any(), any()))
                .thenReturn(Optional.empty());
        when(partyRelationshipRepository.findActiveByFromPartyId(any(), any())).thenReturn(List.of());

        MarketingConsentDecision decision = resolver().resolve(ACCOUNT, MarketingChannel.EMAIL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(MarketingConsentDecision.REASON_NO_PRIMARY_CONTACT);
    }

    @Test
    @DisplayName("with no designated primary, the longest-standing active contact speaks for the account")
    void fallsBackToEarliestActiveContact() {
        when(commercialPartyRepository.findById(ACCOUNT)).thenReturn(Optional.of(account(false)));
        when(partyRelationshipRepository.findPrimaryBillingContact(any(), any()))
                .thenReturn(Optional.empty());
        PartyRelationship older = relationshipTo(CONTACT_PARTY, false);
        PartyRelationship newer = relationshipTo(INDIVIDUAL, false);
        newer.setEffectiveStartDate(LocalDate.of(2026, 6, 1));
        when(partyRelationshipRepository.findActiveByFromPartyId(any(), any())).thenReturn(List.of(newer, older));
        when(preferenceRepository.findByPartyId(CONTACT_PARTY))
                .thenReturn(Optional.of(preference(MarketingConsent.OPT_IN)));

        MarketingConsentDecision decision = resolver().resolve(ACCOUNT, MarketingChannel.EMAIL);

        assertThat(decision.governingPartyId()).isEqualTo(CONTACT_PARTY);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("an individual's own consent decides, and a missing record is UNSET, not consent")
    void individualUsesOwnConsent() {
        when(commercialPartyRepository.findById(INDIVIDUAL)).thenReturn(Optional.empty());
        when(personPartyRepository.existsById(INDIVIDUAL)).thenReturn(true);
        when(preferenceRepository.findByPartyId(INDIVIDUAL)).thenReturn(Optional.empty());

        MarketingConsentDecision decision = resolver().resolve(INDIVIDUAL, MarketingChannel.SMS);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(MarketingConsentDecision.REASON_CONSENT_UNSET);
    }

    @Test
    @DisplayName("an unknown party is denied rather than defaulting to sendable")
    void unknownPartyIsDenied() {
        lenient().when(commercialPartyRepository.findById(any())).thenReturn(Optional.empty());
        when(personPartyRepository.existsById(any())).thenReturn(false);

        MarketingConsentDecision decision = resolver().resolve(UUID.randomUUID(), MarketingChannel.EMAIL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(MarketingConsentDecision.REASON_PARTY_NOT_FOUND);
    }
}
