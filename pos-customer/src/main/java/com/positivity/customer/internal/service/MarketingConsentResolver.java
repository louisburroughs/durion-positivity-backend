package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.MarketingConsentDecision;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.CommunicationPreference;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.MarketingConsent;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single place that answers "may we market to this party on this channel?" (Story #1138,
 * decision O-2).
 *
 * <p>The two party types resolve differently and the difference is the whole point of this
 * class:
 *
 * <ul>
 *   <li><b>Individual</b> — the party's own per-channel consent decides.
 *   <li><b>Commercial</b> — {@code accountMarketingOptOut} is a hard master gate that wins
 *       outright; otherwise the <em>primary business contact's personal</em> consent governs
 *       account-level sends, because an organization cannot itself consent — a person does,
 *       on its behalf.
 * </ul>
 *
 * <p>Suppression is deliberately <em>not</em> consulted here. Consent is a preference the
 * party expresses; suppression is a hard block imposed on an address (bounce, complaint,
 * legal DNC). {@code MarketingConsentServiceImpl} layers the two so each stays independently
 * auditable.
 */
@Component
@RequiredArgsConstructor
public class MarketingConsentResolver {

    private final Clock clock;
    private final CommercialPartyRepository commercialPartyRepository;
    private final PersonPartyRepository personPartyRepository;
    private final PartyRelationshipRepository partyRelationshipRepository;
    private final CommunicationPreferenceRepository preferenceRepository;

    @Transactional(readOnly = true)
    public @NonNull MarketingConsentDecision resolve(@NonNull UUID partyId, @NonNull MarketingChannel channel) {
        Optional<CommercialParty> commercial = commercialPartyRepository.findById(partyId);
        if (commercial.isPresent()) {
            return resolveCommercial(commercial.get(), channel);
        }
        if (personPartyRepository.existsById(partyId)) {
            return personalDecision(partyId, partyId, channel);
        }
        return deny(partyId, channel, MarketingConsentDecision.REASON_PARTY_NOT_FOUND, null);
    }

    private MarketingConsentDecision resolveCommercial(CommercialParty account, MarketingChannel channel) {
        UUID accountId = account.getPartyId();
        if (account.isAccountMarketingOptOut()) {
            return deny(accountId, channel, MarketingConsentDecision.REASON_ACCOUNT_OPT_OUT, null);
        }
        Optional<UUID> contactPartyId = primaryBusinessContactPartyId(accountId);
        if (contactPartyId.isEmpty()) {
            return deny(accountId, channel, MarketingConsentDecision.REASON_NO_PRIMARY_CONTACT, null);
        }
        return personalDecision(accountId, contactPartyId.get(), channel);
    }

    /**
     * The contact whose personal consent speaks for the account: the designated primary
     * billing contact, or — when none is designated — the longest-standing active
     * relationship, so an account with contacts is never silently unreachable.
     */
    private Optional<UUID> primaryBusinessContactPartyId(UUID accountId) {
        LocalDate today = LocalDate.now(clock);
        Optional<PartyRelationship> primary = partyRelationshipRepository.findPrimaryBillingContact(accountId, today);
        if (primary.isPresent()) {
            return primary.map(relationship -> relationship.getToPerson().getPartyId());
        }
        return partyRelationshipRepository.findActiveByFromPartyId(accountId, today).stream()
                .min(Comparator.comparing(PartyRelationship::getEffectiveStartDate))
                .map(relationship -> relationship.getToPerson().getPartyId());
    }

    private MarketingConsentDecision personalDecision(
            UUID subjectPartyId, UUID governingPartyId, MarketingChannel channel) {
        MarketingConsent consent = preferenceRepository
                .findByPartyId(governingPartyId)
                .map(preference -> consentFor(preference, channel))
                // No preference row means the party was never asked, which is UNSET, not consent.
                .orElse(MarketingConsent.UNSET);
        if (consent.allowsMarketing()) {
            return new MarketingConsentDecision(
                    subjectPartyId, channel.name(), true, MarketingConsentDecision.REASON_OPT_IN, governingPartyId);
        }
        String reason = consent == MarketingConsent.OPT_OUT
                ? MarketingConsentDecision.REASON_CONSENT_OPT_OUT
                : MarketingConsentDecision.REASON_CONSENT_UNSET;
        return deny(subjectPartyId, channel, reason, governingPartyId);
    }

    private static MarketingConsent consentFor(CommunicationPreference preference, MarketingChannel channel) {
        MarketingConsent consent = channel == MarketingChannel.EMAIL
                ? preference.getMarketingEmailConsent()
                : preference.getMarketingSmsConsent();
        return consent != null ? consent : MarketingConsent.UNSET;
    }

    private static MarketingConsentDecision deny(
            UUID partyId, MarketingChannel channel, String reason, UUID governingPartyId) {
        return new MarketingConsentDecision(partyId, channel.name(), false, reason, governingPartyId);
    }
}
