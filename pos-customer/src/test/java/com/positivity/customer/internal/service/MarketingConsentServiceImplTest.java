package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.config.SuppressionService;
import com.positivity.customer.internal.dto.MarketingConsentDecision;
import com.positivity.customer.internal.dto.MarketingConsentSummaryResponse;
import com.positivity.customer.internal.dto.UpdateMarketingConsentRequest;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.CommunicationPreference;
import com.positivity.customer.internal.entity.ConsentEvent;
import com.positivity.customer.internal.enums.ConsentChangeSource;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.MarketingConsent;
import com.positivity.customer.internal.enums.OptOutReason;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.ConsentEventRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MarketingConsentServiceImpl}.
 *
 * <p>
 * This service decides whether a customer may be marketed to, so its failure
 * mode is a compliance one: mailing somebody who opted out. Three rules protect
 * against that and are asserted directly.
 *
 * <ul>
 * <li><b>Suppression outranks consent.</b> An opted-in party whose address hard
 * bounced is still ineligible — suppression is a hard block layered on top of
 * the resolved consent, not an input to it.</li>
 * <li><b>Both channels are republished on any change.</b> Consumers replicate
 * the resolved decision; emitting only the channel that moved would leave a
 * stale "allowed" on the other, which is the one failure mode that mails an
 * opted-out customer.</li>
 * <li><b>Audit rows are written only for channels that actually moved.</b> A
 * replayed no-op update must not pollute the compliance trail with entries
 * recording a change from a value to itself.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketingConsentServiceImpl — consent resolution and audit")
class MarketingConsentServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID PARTY_ID = UUID.randomUUID();

    @Mock
    private MarketingConsentResolver consentResolver;

    @Mock
    private SuppressionService suppressionService;

    @Mock
    private CommunicationPreferenceRepository preferenceRepository;

    @Mock
    private CommercialPartyRepository commercialPartyRepository;

    @Mock
    private PersonPartyRepository personPartyRepository;

    @Mock
    private ConsentEventRepository consentEventRepository;

    @Mock
    private CustomerFactPublisher factPublisher;

    @Captor
    private ArgumentCaptor<List<ConsentEvent>> auditRows;

    private MarketingConsentServiceImpl sut;

    @BeforeEach
    void setUp() {
        sut = new MarketingConsentServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                consentResolver,
                suppressionService,
                preferenceRepository,
                commercialPartyRepository,
                personPartyRepository,
                consentEventRepository,
                factPublisher);
    }

    private static MarketingConsentDecision allowed() {
        return new MarketingConsentDecision(PARTY_ID, "EMAIL", true, MarketingConsentDecision.REASON_OPT_IN, null);
    }

    private static MarketingConsentDecision denied() {
        return new MarketingConsentDecision(
                PARTY_ID, "EMAIL", false, MarketingConsentDecision.REASON_CONSENT_OPT_OUT, null);
    }

    private static CommunicationPreference preference(MarketingConsent email, MarketingConsent sms) {
        CommunicationPreference preference = new CommunicationPreference();
        preference.setPartyId(PARTY_ID);
        preference.setMarketingEmailConsent(email);
        preference.setMarketingSmsConsent(sms);
        return preference;
    }

    private static UpdateMarketingConsentRequest update(MarketingConsent email, MarketingConsent sms) {
        return new UpdateMarketingConsentRequest(
                email, sms, OptOutReason.NOT_INTERESTED, ConsentChangeSource.CSR, null, null, null);
    }

    /** Party exists and every channel resolves as allowed unless a test says otherwise. */
    private void partyExistsAndIsAllowed() {
        lenient().when(commercialPartyRepository.existsById(PARTY_ID)).thenReturn(true);
        lenient().when(consentResolver.resolve(eq(PARTY_ID), any())).thenReturn(allowed());
        lenient()
                .when(suppressionService.isPartySuppressed(eq(PARTY_ID), any()))
                .thenReturn(false);
        lenient().when(commercialPartyRepository.findById(PARTY_ID)).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("resolveEligibility")
    class ResolveEligibility {

        @Test
        @DisplayName("allows a party whose consent resolves as opted in and is not suppressed")
        void allowsOptedInUnsuppressedParty() {
            when(consentResolver.resolve(PARTY_ID, MarketingChannel.EMAIL)).thenReturn(allowed());
            when(suppressionService.isPartySuppressed(PARTY_ID, MarketingChannel.EMAIL))
                    .thenReturn(false);

            assertThat(sut.resolveEligibility(PARTY_ID, MarketingChannel.EMAIL).allowed())
                    .isTrue();
        }

        @Test
        @DisplayName("blocks a suppressed party even when consent says opted in")
        void suppressionOverridesConsent() {
            when(consentResolver.resolve(PARTY_ID, MarketingChannel.EMAIL)).thenReturn(allowed());
            when(suppressionService.isPartySuppressed(PARTY_ID, MarketingChannel.EMAIL))
                    .thenReturn(true);

            MarketingConsentDecision decision = sut.resolveEligibility(PARTY_ID, MarketingChannel.EMAIL);

            // Suppression is a hard block on top of consent, not an input to it.
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(MarketingConsentDecision.REASON_SUPPRESSED);
        }

        @Test
        @DisplayName("does not consult suppression when consent already denies")
        void whenConsentDenies_shortCircuits() {
            when(consentResolver.resolve(PARTY_ID, MarketingChannel.EMAIL)).thenReturn(denied());

            assertThat(sut.resolveEligibility(PARTY_ID, MarketingChannel.EMAIL).reason())
                    .isEqualTo(MarketingConsentDecision.REASON_CONSENT_OPT_OUT);
            verify(suppressionService, never()).isPartySuppressed(any(), any());
        }

        @Test
        @DisplayName("keeps the governing party on a suppression block, so the account link is not lost")
        void suppressionKeepsGoverningParty() {
            UUID governing = UUID.randomUUID();
            when(consentResolver.resolve(PARTY_ID, MarketingChannel.EMAIL))
                    .thenReturn(new MarketingConsentDecision(
                            PARTY_ID, "EMAIL", true, MarketingConsentDecision.REASON_OPT_IN, governing));
            when(suppressionService.isPartySuppressed(any(), any())).thenReturn(true);

            assertThat(sut.resolveEligibility(PARTY_ID, MarketingChannel.EMAIL).governingPartyId())
                    .isEqualTo(governing);
        }
    }

    @Nested
    @DisplayName("updateConsent")
    class UpdateConsent {

        @Test
        @DisplayName("creates a preference row for a party that has none yet")
        void whenNoPreferenceExists_createsOne() {
            partyExistsAndIsAllowed();
            when(preferenceRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.empty());

            sut.updateConsent(PARTY_ID, update(MarketingConsent.OPT_IN, null));

            ArgumentCaptor<CommunicationPreference> saved = ArgumentCaptor.captor();
            verify(preferenceRepository).save(saved.capture());
            assertThat(saved.getValue().getMarketingEmailConsent()).isEqualTo(MarketingConsent.OPT_IN);
            assertThat(saved.getValue().getMarketingSmsConsent()).isEqualTo(MarketingConsent.UNSET);
        }

        @Test
        @DisplayName("writes one audit row per channel that actually changed")
        void writesAuditRowPerChangedChannel() {
            partyExistsAndIsAllowed();
            when(preferenceRepository.findByPartyId(PARTY_ID))
                    .thenReturn(Optional.of(preference(MarketingConsent.UNSET, MarketingConsent.UNSET)));

            sut.updateConsent(PARTY_ID, update(MarketingConsent.OPT_IN, MarketingConsent.OPT_OUT));

            verify(consentEventRepository).saveAll(auditRows.capture());
            assertThat(auditRows.getValue())
                    .extracting(ConsentEvent::getChannel)
                    .containsExactlyInAnyOrder(MarketingChannel.EMAIL, MarketingChannel.SMS);
            assertThat(auditRows.getValue()).allSatisfy(event -> {
                assertThat(event.getOldValue()).isEqualTo(MarketingConsent.UNSET);
                assertThat(event.getOccurredAt()).isEqualTo(NOW);
                assertThat(event.getSource()).isEqualTo(ConsentChangeSource.CSR);
            });
        }

        @Test
        @DisplayName("writes no audit row when the requested value already matches — a replay is not a change")
        void whenValueUnchanged_writesNoAudit() {
            partyExistsAndIsAllowed();
            when(preferenceRepository.findByPartyId(PARTY_ID))
                    .thenReturn(Optional.of(preference(MarketingConsent.OPT_IN, MarketingConsent.UNSET)));

            sut.updateConsent(PARTY_ID, update(MarketingConsent.OPT_IN, null));

            // Replaying an idempotent update must not pollute the compliance trail.
            verify(consentEventRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("republishes both channels on any consent change, not just the one that moved")
        void republishesEveryChannel() {
            partyExistsAndIsAllowed();
            when(preferenceRepository.findByPartyId(PARTY_ID))
                    .thenReturn(Optional.of(preference(MarketingConsent.UNSET, MarketingConsent.UNSET)));

            sut.updateConsent(PARTY_ID, update(MarketingConsent.OPT_OUT, null));

            // A partial emission would leave a stale "allowed" on the other channel.
            for (MarketingChannel channel : MarketingChannel.values()) {
                verify(factPublisher)
                        .consentDecisionChanged(eq(PARTY_ID), eq(channel.name()), anyBoolean(), anyString(), any());
            }
        }

        @Test
        @DisplayName("stamps the opt-out reason and timestamp when consent is withdrawn")
        void whenWithdrawn_stampsOptOutMetadata() {
            partyExistsAndIsAllowed();
            CommunicationPreference existing = preference(MarketingConsent.OPT_IN, MarketingConsent.UNSET);
            when(preferenceRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.of(existing));

            sut.updateConsent(PARTY_ID, update(MarketingConsent.OPT_OUT, null));

            assertThat(existing.getOptOutReason()).isEqualTo(OptOutReason.NOT_INTERESTED);
            assertThat(existing.getOptOutAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("leaves opt-out metadata alone when consent is granted rather than withdrawn")
        void whenGranted_doesNotStampOptOutMetadata() {
            partyExistsAndIsAllowed();
            CommunicationPreference existing = preference(MarketingConsent.UNSET, MarketingConsent.UNSET);
            when(preferenceRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.of(existing));

            sut.updateConsent(PARTY_ID, update(MarketingConsent.OPT_IN, null));

            assertThat(existing.getOptOutAt()).isNull();
        }

        @Test
        @DisplayName("applies quiet hours and the send cap only when supplied")
        void appliesOptionalPreferencesWhenSupplied() {
            partyExistsAndIsAllowed();
            CommunicationPreference existing = preference(MarketingConsent.UNSET, MarketingConsent.UNSET);
            existing.setMaxMarketingSendsPerMonth(4);
            when(preferenceRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.of(existing));

            sut.updateConsent(
                    PARTY_ID,
                    new UpdateMarketingConsentRequest(
                            null,
                            null,
                            null,
                            ConsentChangeSource.SELF_SERVICE,
                            LocalTime.of(21, 0),
                            LocalTime.of(8, 0),
                            null));

            assertThat(existing.getQuietHoursStart()).isEqualTo(LocalTime.of(21, 0));
            assertThat(existing.getQuietHoursEnd()).isEqualTo(LocalTime.of(8, 0));
            // Omitted, so the existing cap survives.
            assertThat(existing.getMaxMarketingSendsPerMonth()).isEqualTo(4);
        }

        @Test
        @DisplayName("defaults the change source to CSR when the request omits it")
        void whenSourceOmitted_defaultsToCsr() {
            partyExistsAndIsAllowed();
            when(preferenceRepository.findByPartyId(PARTY_ID))
                    .thenReturn(Optional.of(preference(MarketingConsent.UNSET, MarketingConsent.UNSET)));

            sut.updateConsent(
                    PARTY_ID,
                    new UpdateMarketingConsentRequest(MarketingConsent.OPT_IN, null, null, null, null, null, null));

            verify(consentEventRepository).saveAll(auditRows.capture());
            assertThat(auditRows.getValue())
                    .singleElement()
                    .satisfies(event -> assertThat(event.getSource()).isEqualTo(ConsentChangeSource.CSR));
        }

        @Test
        @DisplayName("rejects an update for a party that exists as neither a commercial nor a person party")
        void whenPartyUnknown_throwsNotFound() {
            when(commercialPartyRepository.existsById(PARTY_ID)).thenReturn(false);
            when(personPartyRepository.existsById(PARTY_ID)).thenReturn(false);

            assertThatThrownBy(() -> sut.updateConsent(PARTY_ID, update(MarketingConsent.OPT_IN, null)))
                    .isInstanceOf(CrmResourceNotFoundException.class);
            verify(preferenceRepository, never()).save(any());
        }

        @Test
        @DisplayName("accepts a person party, not only a commercial one")
        void whenPersonParty_isAccepted() {
            when(commercialPartyRepository.existsById(PARTY_ID)).thenReturn(false);
            when(personPartyRepository.existsById(PARTY_ID)).thenReturn(true);
            when(consentResolver.resolve(eq(PARTY_ID), any())).thenReturn(allowed());
            when(suppressionService.isPartySuppressed(any(), any())).thenReturn(false);
            when(commercialPartyRepository.findById(PARTY_ID)).thenReturn(Optional.empty());
            when(preferenceRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.empty());

            assertThat(sut.updateConsent(PARTY_ID, update(MarketingConsent.OPT_IN, null)))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("setAccountMarketingOptOut")
    class AccountGate {

        private CommercialParty account(boolean optOut) {
            CommercialParty account = new CommercialParty();
            account.setPartyId(PARTY_ID);
            account.setAccountMarketingOptOut(optOut);
            return account;
        }

        @Test
        @DisplayName("sets the gate, stamps the time, and republishes the decision")
        void whenGateChanges_savesAndRepublishes() {
            CommercialParty account = account(false);
            when(commercialPartyRepository.findById(PARTY_ID)).thenReturn(Optional.of(account));
            when(consentResolver.resolve(eq(PARTY_ID), any())).thenReturn(denied());
            when(preferenceRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.empty());

            sut.setAccountMarketingOptOut(PARTY_ID, true);

            assertThat(account.isAccountMarketingOptOut()).isTrue();
            assertThat(account.getAccountMarketingOptOutAt()).isEqualTo(NOW);
            verify(commercialPartyRepository).save(account);
            verify(factPublisher, times(MarketingChannel.values().length))
                    .consentDecisionChanged(eq(PARTY_ID), anyString(), anyBoolean(), anyString(), any());
        }

        @Test
        @DisplayName("clears the timestamp when the gate is lifted")
        void whenGateLifted_clearsTimestamp() {
            CommercialParty account = account(true);
            account.setAccountMarketingOptOutAt(NOW.minusSeconds(3600));
            when(commercialPartyRepository.findById(PARTY_ID)).thenReturn(Optional.of(account));
            when(consentResolver.resolve(eq(PARTY_ID), any())).thenReturn(allowed());
            when(suppressionService.isPartySuppressed(any(), any())).thenReturn(false);
            when(preferenceRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.empty());

            sut.setAccountMarketingOptOut(PARTY_ID, false);

            assertThat(account.isAccountMarketingOptOut()).isFalse();
            assertThat(account.getAccountMarketingOptOutAt()).isNull();
        }

        @Test
        @DisplayName("writes and publishes nothing when the gate already holds the requested value")
        void whenGateUnchanged_isNoOp() {
            CommercialParty account = account(true);
            when(commercialPartyRepository.findById(PARTY_ID)).thenReturn(Optional.of(account));
            when(consentResolver.resolve(eq(PARTY_ID), any())).thenReturn(denied());
            when(preferenceRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.empty());

            sut.setAccountMarketingOptOut(PARTY_ID, true);

            verify(commercialPartyRepository, never()).save(any());
            verify(factPublisher, never()).consentDecisionChanged(any(), anyString(), anyBoolean(), anyString(), any());
        }

        @Test
        @DisplayName("rejects an unknown commercial party")
        void whenAccountUnknown_throwsNotFound() {
            when(commercialPartyRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.setAccountMarketingOptOut(PARTY_ID, true))
                    .isInstanceOf(CrmResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getConsent")
    class GetConsent {

        @Test
        @DisplayName("reports UNSET on both channels for a party with no preference row")
        void whenNoPreference_reportsUnset() {
            partyExistsAndIsAllowed();
            when(preferenceRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.empty());

            MarketingConsentSummaryResponse summary = sut.getConsent(PARTY_ID);

            assertThat(summary.marketingEmailConsent()).isEqualTo("UNSET");
            assertThat(summary.marketingSmsConsent()).isEqualTo("UNSET");
            assertThat(summary.accountMarketingOptOut()).isNull();
        }

        @Test
        @DisplayName("includes the live per-channel decision alongside the stored values")
        void includesResolvedDecisions() {
            partyExistsAndIsAllowed();
            when(preferenceRepository.findByPartyId(PARTY_ID))
                    .thenReturn(Optional.of(preference(MarketingConsent.OPT_IN, MarketingConsent.OPT_IN)));

            MarketingConsentSummaryResponse summary = sut.getConsent(PARTY_ID);

            assertThat(summary.emailEligibility().allowed()).isTrue();
            assertThat(summary.smsEligibility().allowed()).isTrue();
        }

        @Test
        @DisplayName("normalizes a null stored consent to UNSET rather than emitting null")
        void whenStoredConsentNull_reportsUnset() {
            partyExistsAndIsAllowed();
            when(preferenceRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.of(preference(null, null)));

            MarketingConsentSummaryResponse summary = sut.getConsent(PARTY_ID);

            assertThat(summary.marketingEmailConsent()).isEqualTo("UNSET");
        }

        @Test
        @DisplayName("rejects an unknown party")
        void whenPartyUnknown_throwsNotFound() {
            when(commercialPartyRepository.existsById(PARTY_ID)).thenReturn(false);
            when(personPartyRepository.existsById(PARTY_ID)).thenReturn(false);

            assertThatThrownBy(() -> sut.getConsent(PARTY_ID)).isInstanceOf(CrmResourceNotFoundException.class);
        }
    }
}
