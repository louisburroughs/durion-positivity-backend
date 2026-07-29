package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.ConsentEventResponse;
import com.positivity.customer.internal.dto.MarketingConsentDecision;
import com.positivity.customer.internal.dto.MarketingConsentSummaryResponse;
import com.positivity.customer.internal.dto.PagedResponse;
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
import com.positivity.customer.service.MarketingConsentService;
import com.positivity.customer.service.SuppressionService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingConsentServiceImpl implements MarketingConsentService {

    private static final int MAX_PAGE_SIZE = 200;

    private final Clock clock;
    private final MarketingConsentResolver consentResolver;
    private final SuppressionService suppressionService;
    private final CommunicationPreferenceRepository preferenceRepository;
    private final CommercialPartyRepository commercialPartyRepository;
    private final PersonPartyRepository personPartyRepository;
    private final ConsentEventRepository consentEventRepository;
    private final CustomerFactPublisher factPublisher;

    @Override
    @Transactional(readOnly = true)
    public @NonNull MarketingConsentDecision resolveEligibility(
            @NonNull UUID partyId, @NonNull MarketingChannel channel) {
        MarketingConsentDecision decision = consentResolver.resolve(partyId, channel);
        if (!decision.allowed()) {
            return decision;
        }
        // Suppression is a hard block layered on top of consent: an opted-in party whose
        // address hard-bounced still must not be sent to.
        if (suppressionService.isPartySuppressed(partyId, channel)) {
            return new MarketingConsentDecision(
                    partyId,
                    channel.name(),
                    false,
                    MarketingConsentDecision.REASON_SUPPRESSED,
                    decision.governingPartyId());
        }
        return decision;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull MarketingConsentSummaryResponse getConsent(@NonNull UUID partyId) {
        assertPartyExists(partyId);
        return buildSummary(partyId);
    }

    @Override
    @Transactional
    public @NonNull MarketingConsentSummaryResponse updateConsent(
            @NonNull UUID partyId, @NonNull UpdateMarketingConsentRequest request) {
        assertPartyExists(partyId);
        CommunicationPreference preference = preferenceRepository
                .findByPartyId(partyId)
                .orElseGet(() -> {
                    CommunicationPreference created = new CommunicationPreference();
                    created.setPartyId(partyId);
                    created.setMarketingEmailConsent(MarketingConsent.UNSET);
                    created.setMarketingSmsConsent(MarketingConsent.UNSET);
                    return created;
                });

        ConsentChangeSource source = request.getSource() != null ? request.getSource() : ConsentChangeSource.CSR;
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        Instant now = Instant.now(clock);
        List<ConsentEvent> audit = new ArrayList<>();

        MarketingConsent emailBefore = orUnset(preference.getMarketingEmailConsent());
        if (request.getMarketingEmailConsent() != null && request.getMarketingEmailConsent() != emailBefore) {
            preference.setMarketingEmailConsent(request.getMarketingEmailConsent());
            audit.add(auditEntry(
                    partyId,
                    MarketingChannel.EMAIL,
                    emailBefore,
                    request.getMarketingEmailConsent(),
                    request.getOptOutReason(),
                    source,
                    actor,
                    now));
        }

        MarketingConsent smsBefore = orUnset(preference.getMarketingSmsConsent());
        if (request.getMarketingSmsConsent() != null && request.getMarketingSmsConsent() != smsBefore) {
            preference.setMarketingSmsConsent(request.getMarketingSmsConsent());
            audit.add(auditEntry(
                    partyId,
                    MarketingChannel.SMS,
                    smsBefore,
                    request.getMarketingSmsConsent(),
                    request.getOptOutReason(),
                    source,
                    actor,
                    now));
        }

        boolean withdrew = audit.stream().anyMatch(event -> event.getNewValue() == MarketingConsent.OPT_OUT);
        if (withdrew) {
            preference.setOptOutReason(request.getOptOutReason());
            preference.setOptOutAt(now);
        }
        if (request.getQuietHoursStart() != null) {
            preference.setQuietHoursStart(request.getQuietHoursStart());
        }
        if (request.getQuietHoursEnd() != null) {
            preference.setQuietHoursEnd(request.getQuietHoursEnd());
        }
        if (request.getMaxMarketingSendsPerMonth() != null) {
            preference.setMaxMarketingSendsPerMonth(request.getMaxMarketingSendsPerMonth());
        }

        preferenceRepository.save(preference);
        // Consumers replicate the resolved decision, so re-publish both channels whenever any
        // consent-affecting field moves — a partial emission would leave a stale "allowed" on
        // the other channel, which is the one failure mode that mails an opted-out customer.
        publishDecisions(partyId);
        // Audit rows are written only for channels that actually moved, so replaying an
        // idempotent no-op update does not pollute the compliance trail.
        if (!audit.isEmpty()) {
            consentEventRepository.saveAll(audit);
            log.info("Recorded {} marketing-consent change(s) for party {}", audit.size(), partyId);
        }
        return buildSummary(partyId);
    }

    @Override
    @Transactional
    public @NonNull MarketingConsentSummaryResponse setAccountMarketingOptOut(@NonNull UUID partyId, boolean optOut) {
        CommercialParty account = commercialPartyRepository
                .findById(partyId)
                .orElseThrow(() -> new CrmResourceNotFoundException("Commercial party", partyId));
        if (account.isAccountMarketingOptOut() != optOut) {
            account.setAccountMarketingOptOut(optOut);
            account.setAccountMarketingOptOutAt(optOut ? Instant.now(clock) : null);
            commercialPartyRepository.save(account);
            // The gate governs every contact on the account, so the decision changes for the
            // account party itself and must be republished.
            publishDecisions(partyId);
            log.info("Account marketing gate for {} set to optOut={}", partyId, optOut);
        }
        return buildSummary(partyId);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PagedResponse<ConsentEventResponse> getConsentHistory(@NonNull UUID partyId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PagedResponse.from(
                consentEventRepository.findByPartyIdOrderByOccurredAtDesc(partyId, pageable),
                MarketingConsentServiceImpl::toResponse);
    }

    /** Republish the resolved decision for every channel this module models. */
    private void publishDecisions(UUID partyId) {
        for (MarketingChannel channel : MarketingChannel.values()) {
            MarketingConsentDecision decision = resolveEligibility(partyId, channel);
            factPublisher.consentDecisionChanged(
                    partyId, channel.name(), decision.allowed(), decision.reason(), decision.governingPartyId());
        }
    }

    private MarketingConsentSummaryResponse buildSummary(UUID partyId) {
        Optional<CommunicationPreference> preference = preferenceRepository.findByPartyId(partyId);
        Boolean accountGate = commercialPartyRepository
                .findById(partyId)
                .map(CommercialParty::isAccountMarketingOptOut)
                .orElse(null);
        return new MarketingConsentSummaryResponse(
                partyId,
                preference
                        .map(p -> orUnset(p.getMarketingEmailConsent()))
                        .orElse(MarketingConsent.UNSET)
                        .name(),
                preference
                        .map(p -> orUnset(p.getMarketingSmsConsent()))
                        .orElse(MarketingConsent.UNSET)
                        .name(),
                preference
                        .map(CommunicationPreference::getOptOutReason)
                        .map(OptOutReason::name)
                        .orElse(null),
                preference.map(CommunicationPreference::getOptOutAt).orElse(null),
                accountGate,
                preference.map(CommunicationPreference::getQuietHoursStart).orElse(null),
                preference.map(CommunicationPreference::getQuietHoursEnd).orElse(null),
                preference
                        .map(CommunicationPreference::getMaxMarketingSendsPerMonth)
                        .orElse(null),
                resolveEligibility(partyId, MarketingChannel.EMAIL),
                resolveEligibility(partyId, MarketingChannel.SMS));
    }

    private void assertPartyExists(UUID partyId) {
        if (!commercialPartyRepository.existsById(partyId) && !personPartyRepository.existsById(partyId)) {
            throw new CrmResourceNotFoundException("Party", partyId);
        }
    }

    private static ConsentEvent auditEntry(
            UUID partyId,
            MarketingChannel channel,
            MarketingConsent oldValue,
            MarketingConsent newValue,
            OptOutReason reason,
            ConsentChangeSource source,
            String actor,
            Instant occurredAt) {
        return ConsentEvent.builder()
                .partyId(partyId)
                .channel(channel)
                .oldValue(oldValue)
                .newValue(newValue)
                .reason(newValue == MarketingConsent.OPT_OUT ? reason : null)
                .source(source)
                .actor(actor)
                .occurredAt(occurredAt)
                .build();
    }

    private static MarketingConsent orUnset(MarketingConsent value) {
        return value != null ? value : MarketingConsent.UNSET;
    }

    private static ConsentEventResponse toResponse(ConsentEvent event) {
        return new ConsentEventResponse(
                event.getConsentEventId(),
                event.getPartyId(),
                event.getChannel().name(),
                event.getOldValue().name(),
                event.getNewValue().name(),
                event.getReason() != null ? event.getReason().name() : null,
                event.getSource().name(),
                event.getActor(),
                event.getOccurredAt());
    }
}
