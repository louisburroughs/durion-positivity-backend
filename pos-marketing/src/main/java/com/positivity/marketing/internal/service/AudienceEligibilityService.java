package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.entity.PartyConsentReplica;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.repository.PartyConsentReplicaRepository;
import com.positivity.marketing.internal.repository.SuppressionReplicaRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a party may be contacted, from replicated CRM state (ADR-0044 R3).
 *
 * <p>This module holds no live connection to the CRM, so eligibility is answered from
 * {@code ext_party_consent} and {@code ext_suppression}. That trade has one sharp edge: a
 * replica can be behind, and being behind on consent means contacting someone who has since
 * opted out.
 *
 * <p>So the check <strong>fails closed on staleness</strong>. A party with no replicated
 * decision, or one older than {@code pos.marketing.send.max-consent-age}, is refused rather
 * than trusted. A delayed send is recoverable; an unwanted one is not. The refusal reason
 * distinguishes the two cases so an operator can tell "they opted out" from "our replica is
 * behind" — the second is an alert, not a marketing outcome.
 */
@Slf4j
@Component
public class AudienceEligibilityService {

    /** Refusal reason when no decision has been replicated for the party. */
    public static final String REASON_NO_CONSENT_DATA = "NO_CONSENT_DATA";

    /** Refusal reason when the replicated decision is older than the configured bound. */
    public static final String REASON_CONSENT_STALE = "CONSENT_STALE";

    public static final String REASON_SUPPRESSED = "SUPPRESSED";

    /**
     * Ceiling on the identifiers sent in one {@code IN (...)} predicate. An audience snapshot can
     * run to tens of thousands of parties and PostgreSQL caps a statement at 65535 bind
     * parameters, so a whole-snapshot query would fail on exactly the campaigns that matter most.
     */
    private static final int LOOKUP_BATCH_SIZE = 1_000;

    private final Clock clock;
    private final PartyConsentReplicaRepository consentRepository;
    private final SuppressionReplicaRepository suppressionRepository;
    private final Duration maxConsentAge;

    public AudienceEligibilityService(
            Clock clock,
            PartyConsentReplicaRepository consentRepository,
            SuppressionReplicaRepository suppressionRepository,
            @Value("${pos.marketing.send.max-consent-age:PT24H}") Duration maxConsentAge) {
        this.clock = clock;
        this.consentRepository = consentRepository;
        this.suppressionRepository = suppressionRepository;
        this.maxConsentAge = maxConsentAge;
    }

    /** Allow/deny for one party and channel, with the reason. */
    @Transactional(readOnly = true)
    public @NonNull Decision decide(@NonNull UUID partyId, @NonNull CampaignChannel channel) {
        if (suppressionRepository.existsByPartyIdAndChannel(partyId, channel.name())) {
            return new Decision(false, REASON_SUPPRESSED);
        }
        Optional<PartyConsentReplica> replicated = consentRepository.findByPartyIdAndChannel(partyId, channel.name());
        if (replicated.isEmpty()) {
            return new Decision(false, REASON_NO_CONSENT_DATA);
        }
        PartyConsentReplica consent = replicated.get();
        if (isStale(consent)) {
            log.warn(
                    "Consent replica for party {} on {} is older than {}; refusing to send",
                    partyId,
                    channel,
                    maxConsentAge);
            return new Decision(false, REASON_CONSENT_STALE);
        }
        return new Decision(consent.isAllowed(), consent.getReason(), consent.getGoverningPartyId());
    }

    /**
     * Decide for a whole audience snapshot in a fixed number of queries.
     *
     * <p>Audience preview evaluates every member of a snapshot and would otherwise issue two
     * queries per party. The batched form answers identically to {@link #decide} — same
     * precedence, same reason codes — because a preview that disagreed with the send worker
     * would report reach the campaign never achieves.
     *
     * @return one decision per distinct id, in the order the ids were given
     */
    @Transactional(readOnly = true)
    public @NonNull Map<UUID, Decision> decideAll(
            @NonNull Collection<UUID> partyIds, @NonNull CampaignChannel channel) {
        List<UUID> distinct = partyIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Set<UUID> suppressed = new HashSet<>();
        Map<UUID, PartyConsentReplica> consents = new HashMap<>();
        for (List<UUID> batch : partition(distinct)) {
            suppressed.addAll(suppressionRepository.findPartyIdsByChannelAndPartyIdIn(channel.name(), batch));
            for (PartyConsentReplica consent : consentRepository.findByChannelAndPartyIdIn(channel.name(), batch)) {
                consents.put(consent.getPartyId(), consent);
            }
        }

        Map<UUID, Decision> decisions = new LinkedHashMap<>();
        for (UUID partyId : distinct) {
            decisions.put(partyId, decideFrom(partyId, channel, suppressed, consents.get(partyId)));
        }
        return decisions;
    }

    /**
     * How many of these parties are currently sendable. Used by audience preview, which
     * evaluates a whole snapshot and would otherwise issue one query per member.
     */
    @Transactional(readOnly = true)
    public long countEligible(@NonNull Collection<UUID> partyIds, @NonNull CampaignChannel channel) {
        return decideAll(partyIds, channel).values().stream()
                .filter(Decision::allowed)
                .count();
    }

    private Decision decideFrom(
            UUID partyId, CampaignChannel channel, Set<UUID> suppressed, @Nullable PartyConsentReplica consent) {
        if (suppressed.contains(partyId)) {
            return new Decision(false, REASON_SUPPRESSED);
        }
        if (consent == null) {
            return new Decision(false, REASON_NO_CONSENT_DATA);
        }
        if (isStale(consent)) {
            log.warn(
                    "Consent replica for party {} on {} is older than {}; refusing to send",
                    partyId,
                    channel,
                    maxConsentAge);
            return new Decision(false, REASON_CONSENT_STALE);
        }
        return new Decision(consent.isAllowed(), consent.getReason(), consent.getGoverningPartyId());
    }

    private static List<List<UUID>> partition(List<UUID> ids) {
        List<List<UUID>> batches = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += LOOKUP_BATCH_SIZE) {
            batches.add(ids.subList(start, Math.min(start + LOOKUP_BATCH_SIZE, ids.size())));
        }
        return batches;
    }

    private boolean isStale(PartyConsentReplica consent) {
        return consent.getDecidedAt() == null
                || consent.getDecidedAt().isBefore(Instant.now(clock).minus(maxConsentAge));
    }

    /**
     * @param reason machine-readable code, recorded on the send row when denied
     * @param contactPartyId the party whose personal consent governed the decision — for a
     *     commercial account the contact the CRM designates for it, for an individual the person
     *     themselves. Null when the CRM reported no decision, or replicated one before this field
     *     existed.
     */
    public record Decision(
            boolean allowed,
            @NonNull String reason,
            @Nullable UUID contactPartyId) {

        public Decision(boolean allowed, @NonNull String reason) {
            this(allowed, reason, null);
        }
    }
}
