package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.entity.PartyConsentReplica;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.repository.PartyConsentReplicaRepository;
import com.positivity.marketing.internal.repository.SuppressionReplicaRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * With no synchronous CRM call available, a stale replica is the one way this module could mail
 * someone who has since opted out. These tests pin the fail-closed behaviour that prevents it.
 */
@ExtendWith(MockitoExtension.class)
class AudienceEligibilityServiceTest {

    private static final UUID PARTY = UUID.fromString("01960003-0000-7000-8000-000000000040");
    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private PartyConsentReplicaRepository consentRepository;

    @Mock
    private SuppressionReplicaRepository suppressionRepository;

    private AudienceEligibilityService service() {
        return new AudienceEligibilityService(clock, consentRepository, suppressionRepository, Duration.ofHours(24));
    }

    private static PartyConsentReplica consent(boolean allowed, Instant decidedAt) {
        return PartyConsentReplica.builder()
                .consentKey(PartyConsentReplica.keyOf(PARTY, "EMAIL"))
                .partyId(PARTY)
                .channel("EMAIL")
                .allowed(allowed)
                .reason(allowed ? "OPT_IN" : "CONSENT_OPT_OUT")
                .decidedAt(decidedAt)
                .build();
    }

    @Test
    @DisplayName("a party with no replicated decision is refused, not assumed sendable")
    void missingConsentFailsClosed() {
        when(suppressionRepository.existsByPartyIdAndChannel(any(), anyString()))
                .thenReturn(false);
        when(consentRepository.findByPartyIdAndChannel(PARTY, "EMAIL")).thenReturn(Optional.empty());

        AudienceEligibilityService.Decision decision = service().decide(PARTY, CampaignChannel.EMAIL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AudienceEligibilityService.REASON_NO_CONSENT_DATA);
    }

    @Test
    @DisplayName("an opted-in decision older than the bound is refused as stale")
    void staleConsentFailsClosed() {
        when(suppressionRepository.existsByPartyIdAndChannel(any(), anyString()))
                .thenReturn(false);
        when(consentRepository.findByPartyIdAndChannel(PARTY, "EMAIL"))
                .thenReturn(Optional.of(consent(true, NOW.minus(Duration.ofHours(25)))));

        AudienceEligibilityService.Decision decision = service().decide(PARTY, CampaignChannel.EMAIL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AudienceEligibilityService.REASON_CONSENT_STALE);
    }

    @Test
    @DisplayName("a fresh opt-in is allowed")
    void freshOptInIsAllowed() {
        when(suppressionRepository.existsByPartyIdAndChannel(any(), anyString()))
                .thenReturn(false);
        when(consentRepository.findByPartyIdAndChannel(PARTY, "EMAIL"))
                .thenReturn(Optional.of(consent(true, NOW.minus(Duration.ofHours(1)))));

        assertThat(service().decide(PARTY, CampaignChannel.EMAIL).allowed()).isTrue();
    }

    @Test
    @DisplayName("suppression outranks a fresh opt-in and is checked first")
    void suppressionOutranksConsent() {
        when(suppressionRepository.existsByPartyIdAndChannel(PARTY, "EMAIL")).thenReturn(true);

        AudienceEligibilityService.Decision decision = service().decide(PARTY, CampaignChannel.EMAIL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AudienceEligibilityService.REASON_SUPPRESSED);
    }

    @Test
    @DisplayName("a fresh explicit opt-out is refused with the CRM's own reason")
    void optOutCarriesOwnerReason() {
        when(suppressionRepository.existsByPartyIdAndChannel(any(), anyString()))
                .thenReturn(false);
        when(consentRepository.findByPartyIdAndChannel(PARTY, "EMAIL"))
                .thenReturn(Optional.of(consent(false, NOW.minus(Duration.ofMinutes(5)))));

        AudienceEligibilityService.Decision decision = service().decide(PARTY, CampaignChannel.EMAIL);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("CONSENT_OPT_OUT");
    }

    @Test
    @DisplayName("a decision carries the contact whose consent governed it")
    void decisionCarriesGoverningContact() {
        UUID contact = UUID.fromString("01960003-0000-7000-8000-000000000042");
        PartyConsentReplica consent = consent(true, NOW.minus(Duration.ofHours(1)));
        consent.setGoverningPartyId(contact);
        when(suppressionRepository.existsByPartyIdAndChannel(any(), anyString()))
                .thenReturn(false);
        when(consentRepository.findByPartyIdAndChannel(PARTY, "EMAIL")).thenReturn(Optional.of(consent));

        // For a commercial account this is the contact the CRM designates to speak for it; the
        // send row records it so the sender addresses the right person.
        assertThat(service().decide(PARTY, CampaignChannel.EMAIL).contactPartyId())
                .isEqualTo(contact);
    }

    @Test
    @DisplayName("bulk decisions apply the same staleness rule as a single one")
    void bulkMatchesSingleDecisionOnStaleness() {
        UUID stalePartyId = UUID.fromString("01960003-0000-7000-8000-000000000041");
        PartyConsentReplica fresh = consent(true, NOW.minus(Duration.ofHours(1)));
        PartyConsentReplica stale = consentFor(stalePartyId, NOW.minus(Duration.ofDays(3)));
        when(consentRepository.findByChannelAndPartyIdIn(anyString(), any())).thenReturn(List.of(fresh, stale));
        when(suppressionRepository.findPartyIdsByChannelAndPartyIdIn(anyString(), any()))
                .thenReturn(List.of());

        Map<UUID, AudienceEligibilityService.Decision> decisions =
                service().decideAll(List.of(PARTY, stalePartyId), CampaignChannel.EMAIL);

        assertThat(decisions.get(PARTY).allowed()).isTrue();
        assertThat(decisions.get(stalePartyId).reason()).isEqualTo(AudienceEligibilityService.REASON_CONSENT_STALE);
    }

    @Test
    @DisplayName("bulk decisions let suppression outrank a fresh opt-in, as the single decision does")
    void bulkAppliesSuppression() {
        UUID suppressedPartyId = UUID.fromString("01960003-0000-7000-8000-000000000043");
        when(consentRepository.findByChannelAndPartyIdIn(anyString(), any()))
                .thenReturn(List.of(
                        consent(true, NOW.minus(Duration.ofHours(1))),
                        consentFor(suppressedPartyId, NOW.minus(Duration.ofHours(1)))));
        when(suppressionRepository.findPartyIdsByChannelAndPartyIdIn(anyString(), any()))
                .thenReturn(List.of(suppressedPartyId));

        Map<UUID, AudienceEligibilityService.Decision> decisions =
                service().decideAll(List.of(PARTY, suppressedPartyId), CampaignChannel.EMAIL);

        assertThat(decisions.get(suppressedPartyId).reason()).isEqualTo(AudienceEligibilityService.REASON_SUPPRESSED);
        assertThat(service().countEligible(List.of(PARTY, suppressedPartyId), CampaignChannel.EMAIL))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a party the CRM has said nothing about still gets a decision, refusing it")
    void bulkCoversPartiesWithNoReplicatedDecision() {
        UUID unknown = UUID.fromString("01960003-0000-7000-8000-000000000044");
        when(consentRepository.findByChannelAndPartyIdIn(anyString(), any()))
                .thenReturn(List.of(consent(true, NOW.minus(Duration.ofHours(1)))));
        when(suppressionRepository.findPartyIdsByChannelAndPartyIdIn(anyString(), any()))
                .thenReturn(List.of());

        // Every member of the snapshot must appear, or a preview would silently undercount.
        Map<UUID, AudienceEligibilityService.Decision> decisions =
                service().decideAll(List.of(PARTY, unknown), CampaignChannel.EMAIL);

        assertThat(decisions).containsOnlyKeys(PARTY, unknown);
        assertThat(decisions.get(unknown).reason()).isEqualTo(AudienceEligibilityService.REASON_NO_CONSENT_DATA);
    }

    @Test
    @DisplayName("an audience larger than one IN clause is looked up in batches, not one statement")
    void largeAudienceIsBatched() {
        List<UUID> audience = Stream.generate(UUID::randomUUID).limit(2_500).toList();
        when(consentRepository.findByChannelAndPartyIdIn(anyString(), any())).thenReturn(List.of());
        when(suppressionRepository.findPartyIdsByChannelAndPartyIdIn(anyString(), any()))
                .thenReturn(List.of());

        // PostgreSQL caps a statement at 65535 bind parameters, so a whole-snapshot IN clause
        // would fail on exactly the campaigns that matter most.
        assertThat(service().decideAll(audience, CampaignChannel.EMAIL)).hasSize(2_500);
        verify(consentRepository, times(3)).findByChannelAndPartyIdIn(anyString(), any());
    }

    @Test
    @DisplayName("an empty audience asks the database nothing")
    void emptyAudienceSkipsQueries() {
        assertThat(service().decideAll(List.of(), CampaignChannel.EMAIL)).isEmpty();
        verifyNoInteractions(consentRepository, suppressionRepository);
    }

    private static PartyConsentReplica consentFor(UUID partyId, Instant decidedAt) {
        return PartyConsentReplica.builder()
                .consentKey(PartyConsentReplica.keyOf(partyId, "EMAIL"))
                .partyId(partyId)
                .channel("EMAIL")
                .allowed(true)
                .reason("OPT_IN")
                .decidedAt(decidedAt)
                .build();
    }
}
