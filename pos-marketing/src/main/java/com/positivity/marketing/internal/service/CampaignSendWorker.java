package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.domain.TemplateToken;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.CampaignSend;
import com.positivity.marketing.internal.entity.MessageTemplate;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.enums.SendStatus;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.CampaignSendRepository;
import com.positivity.marketing.internal.repository.MessageTemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains queued recipients and delivers them through the transport port (Story #1149).
 *
 * <p>The critical behaviour here is the <em>send-time</em> consent and suppression re-check.
 * An audience preview can be minutes or days old by the time a scheduled campaign runs, and in
 * that window someone can unsubscribe, an account can set its master gate, or a bounce can
 * suppress an address. Filtering only at audience-build time would mail those people anyway —
 * which is both the compliance failure and the reputational one.
 *
 * <p>A denied recipient is recorded as {@code SUPPRESSED} with the CRM's reason code rather
 * than deleted, so the funnel can show how many people were dropped and why.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.marketing.send", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CampaignSendWorker {

    private final Clock clock;
    private final CampaignSendRepository sendRepository;
    private final CampaignRepository campaignRepository;
    private final MessageTemplateRepository templateRepository;
    private final AudienceEligibilityService eligibilityService;
    private final MessageChannelPort messageChannel;
    private final TemplateRenderService renderService;
    private final MarketingFactPublisher factPublisher;
    private final int batchSize;
    private final int maxAttempts;

    public CampaignSendWorker(
            Clock clock,
            CampaignSendRepository sendRepository,
            CampaignRepository campaignRepository,
            MessageTemplateRepository templateRepository,
            AudienceEligibilityService eligibilityService,
            MessageChannelPort messageChannel,
            TemplateRenderService renderService,
            MarketingFactPublisher factPublisher,
            @Value("${pos.marketing.send.batch-size:100}") int batchSize,
            @Value("${pos.marketing.send.max-attempts:3}") int maxAttempts) {
        this.clock = clock;
        this.sendRepository = sendRepository;
        this.campaignRepository = campaignRepository;
        this.templateRepository = templateRepository;
        this.eligibilityService = eligibilityService;
        this.messageChannel = messageChannel;
        this.renderService = renderService;
        this.factPublisher = factPublisher;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${pos.marketing.send.poll-delay-ms:5000}")
    @Transactional
    public void drain() {
        List<CampaignSend> pending =
                sendRepository.findByStatusOrderByQueuedAtAsc(SendStatus.PENDING, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return;
        }
        Map<UUID, Campaign> campaigns = new HashMap<>();
        for (CampaignSend send : pending) {
            Campaign campaign = campaigns.computeIfAbsent(
                    send.getCampaignId(), id -> campaignRepository.findById(id).orElse(null));
            if (campaign == null) {
                fail(send, "Campaign no longer exists");
                continue;
            }
            // A paused or cancelled campaign must stop mid-flight, not finish its backlog.
            if (campaign.getStatus() == CampaignStatus.PAUSED || campaign.getStatus() == CampaignStatus.CANCELLED) {
                continue;
            }
            deliver(send, campaign);
        }
        campaigns.values().stream().filter(java.util.Objects::nonNull).forEach(this::closeIfDrained);
    }

    private void deliver(CampaignSend send, Campaign campaign) {
        Optional<MessageTemplate> template = templateFor(campaign, send.getChannel());
        if (template.isEmpty()) {
            fail(send, "No " + send.getChannel() + " template attached to the campaign");
            return;
        }

        String denial = denialReason(send);
        if (denial != null) {
            send.setStatus(SendStatus.SUPPRESSED);
            send.setFailureReason(denial);
            touch(send);
            sendRepository.save(send);
            return;
        }

        Map<String, String> tokens = tokenValues(campaign);
        String subject = template.get().getSubject() == null
                ? null
                : renderService.render(template.get().getSubject(), tokens);
        String body = renderService.render(template.get().getBody(), tokens);

        send.setAttempts(send.getAttempts() + 1);
        MessageChannelPort.SendOutcome outcome = messageChannel.send(new MessageChannelPort.OutboundMessage(
                send.getCampaignSendId(),
                send.getChannel(),
                send.getRecipientPartyId(),
                send.getContactId(),
                campaign.getCode(),
                subject,
                body));

        if (outcome.accepted()) {
            send.setStatus(SendStatus.SENT);
            send.setProviderMessageId(outcome.providerMessageId());
            if (outcome.addressHash() != null) {
                send.setAddressHash(outcome.addressHash());
            }
            send.setSentAt(Instant.now(clock));
            send.setFailureReason(null);
            touch(send);
            sendRepository.save(send);
            factPublisher.campaignSent(send, campaign.getCode(), subject);
            return;
        }

        // A transient failure stays PENDING for the next drain, but only up to maxAttempts —
        // otherwise a permanently misconfigured provider retries the whole audience forever.
        if (outcome.retryable() && send.getAttempts() < maxAttempts) {
            send.setFailureReason(outcome.failureReason());
            touch(send);
            sendRepository.save(send);
            return;
        }
        fail(send, outcome.failureReason() != null ? outcome.failureReason() : "Provider rejected the message");
    }

    /**
     * Why this recipient must not be contacted right now, or null if they may be.
     *
     * <p>Delegates to {@link AudienceEligibilityService}, which reads the replicated CRM
     * decision and <strong>fails closed on missing or stale data</strong>. That matters here:
     * with no synchronous CRM call available, a replica that has fallen behind is the one way
     * this worker could mail someone who has since opted out, so absence of a fresh answer is
     * treated as refusal rather than permission.
     */
    private String denialReason(CampaignSend send) {
        AudienceEligibilityService.Decision decision =
                eligibilityService.decide(send.getRecipientPartyId(), send.getChannel());
        return decision.allowed() ? null : decision.reason();
    }

    private Map<String, String> tokenValues(Campaign campaign) {
        Map<String, String> tokens = new HashMap<>();
        tokens.put(TemplateToken.CAMPAIGN_CODE.tokenName(), campaign.getCode());
        // Recipient-specific token values arrive with the sender contract (FI-2), which resolves
        // the contact record. Until then only campaign-scoped tokens substitute; the rest render
        // empty rather than leaking a raw {{token}} into a customer's message.
        return tokens;
    }

    private Optional<MessageTemplate> templateFor(Campaign campaign, CampaignChannel channel) {
        UUID templateId =
                channel == CampaignChannel.EMAIL ? campaign.getEmailTemplateId() : campaign.getSmsTemplateId();
        return templateId == null ? Optional.empty() : templateRepository.findById(templateId);
    }

    /** Mark a campaign SENT once nothing is left queued, so its status reflects reality. */
    private void closeIfDrained(Campaign campaign) {
        if (campaign.getStatus() != CampaignStatus.SENDING) {
            return;
        }
        if (sendRepository.countByCampaignIdAndStatus(campaign.getCampaignId(), SendStatus.PENDING) == 0) {
            campaign.setStatus(CampaignStatus.SENT);
            campaignRepository.save(campaign);
            log.info("Campaign {} ({}) fully drained; marked SENT", campaign.getCode(), campaign.getCampaignId());
        }
    }

    private void fail(CampaignSend send, String reason) {
        send.setStatus(SendStatus.FAILED);
        send.setFailureReason(reason);
        touch(send);
        sendRepository.save(send);
        log.warn("Send {} failed: {}", send.getCampaignSendId(), reason);
    }

    private void touch(CampaignSend send) {
        send.setUpdatedAt(Instant.now(clock));
    }
}
