package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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
import java.util.Optional;
import java.util.UUID;
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
    @DisplayName("bulk counting applies the same staleness and suppression rules")
    void bulkCountMatchesSingleDecision() {
        UUID stalePartyId = UUID.fromString("01960003-0000-7000-8000-000000000041");
        PartyConsentReplica fresh = consent(true, NOW.minus(Duration.ofHours(1)));
        PartyConsentReplica stale = PartyConsentReplica.builder()
                .consentKey(PartyConsentReplica.keyOf(stalePartyId, "EMAIL"))
                .partyId(stalePartyId)
                .channel("EMAIL")
                .allowed(true)
                .reason("OPT_IN")
                .decidedAt(NOW.minus(Duration.ofDays(3)))
                .build();
        when(consentRepository.findByChannelAndPartyIdIn(anyString(), any())).thenReturn(List.of(fresh, stale));
        lenient()
                .when(suppressionRepository.existsByPartyIdAndChannel(any(), anyString()))
                .thenReturn(false);

        assertThat(service().countEligible(List.of(PARTY, stalePartyId), CampaignChannel.EMAIL))
                .isEqualTo(1);
    }
}
