package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.client.CustomerClient;
import com.positivity.marketing.internal.dto.CampaignSendResponse;
import com.positivity.marketing.internal.dto.PagedResponse;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.CampaignSend;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.enums.SendStatus;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.exception.MarketingUnprocessableEntityException;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.CampaignSendRepository;
import com.positivity.marketing.service.CampaignSendService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes a campaign's audience into per-recipient send rows (Story #1149).
 *
 * <p>Dispatch is deliberately split from delivery. This method resolves the audience and
 * queues it, then returns {@code 202}; {@link CampaignSendWorker} performs the actual sends.
 * Doing the whole campaign inside the request would hold an HTTP connection and a database
 * transaction open for the length of a bulk send, and a client timeout mid-way would leave the
 * operator with no idea how many people had been contacted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignSendServiceImpl implements CampaignSendService {

    private static final int MAX_PAGE_SIZE = 200;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final CampaignSendRepository sendRepository;
    private final CustomerClient customerClient;

    @Override
    @Transactional
    public int dispatch(@NonNull UUID campaignId) {
        Campaign campaign = campaignRepository
                .findById(campaignId)
                .orElseThrow(() -> new MarketingResourceNotFoundException("Campaign", campaignId));
        if (campaign.getStatus() != CampaignStatus.SCHEDULED && campaign.getStatus() != CampaignStatus.SENDING) {
            throw new MarketingUnprocessableEntityException(
                    "Only a SCHEDULED or SENDING campaign can dispatch; this one is " + campaign.getStatus());
        }
        if (campaign.getSegmentId() == null) {
            throw new MarketingUnprocessableEntityException("Campaign has no segment bound");
        }

        List<UUID> recipients = customerClient.resolveSegmentPartyIds(campaign.getSegmentId());
        Instant now = Instant.now(clock);
        List<CampaignSend> queued = new ArrayList<>();

        for (UUID partyId : recipients) {
            for (CampaignChannel channel : campaign.getChannels()) {
                // The uniqueness constraint is the real guarantee; this check just avoids
                // generating rows we know will collide on a re-dispatch.
                if (sendRepository
                        .findByCampaignIdAndRecipientPartyIdAndChannel(campaignId, partyId, channel)
                        .isPresent()) {
                    continue;
                }
                queued.add(CampaignSend.builder()
                        .campaignId(campaignId)
                        .recipientPartyId(partyId)
                        .channel(channel)
                        .status(SendStatus.PENDING)
                        .queuedAt(now)
                        .updatedAt(now)
                        .build());
            }
        }

        int persisted = persistIgnoringCollisions(queued);
        if (campaign.getStatus() == CampaignStatus.SCHEDULED) {
            campaign.setStatus(CampaignStatus.SENDING);
            campaignRepository.save(campaign);
        }
        log.info(
                "Campaign {} dispatch queued {} recipient-channel row(s) from {} segment member(s)",
                campaignId,
                persisted,
                recipients.size());
        return persisted;
    }

    /**
     * Saves rows one at a time so a concurrent dispatch losing the uniqueness race costs that
     * single recipient, not the whole batch. Re-dispatch is expected — an operator retrying after
     * a partial failure must not be punished with a 500.
     */
    private int persistIgnoringCollisions(List<CampaignSend> queued) {
        int persisted = 0;
        for (CampaignSend send : queued) {
            try {
                sendRepository.saveAndFlush(send);
                persisted++;
            } catch (DataIntegrityViolationException ex) {
                log.debug(
                        "Recipient {} already queued for campaign {} on {}",
                        send.getRecipientPartyId(),
                        send.getCampaignId(),
                        send.getChannel());
            }
        }
        return persisted;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PagedResponse<CampaignSendResponse> listSends(@NonNull UUID campaignId, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), Sort.by(Sort.Direction.ASC, "queuedAt"));
        return PagedResponse.from(
                sendRepository.findByCampaignId(campaignId, pageable), CampaignSendServiceImpl::toResponse);
    }

    static CampaignSendResponse toResponse(CampaignSend send) {
        return new CampaignSendResponse(
                send.getCampaignSendId(),
                send.getCampaignId(),
                send.getRecipientPartyId(),
                send.getContactId(),
                send.getChannel().name(),
                send.getStatus().name(),
                send.getFailureReason(),
                send.getProviderMessageId(),
                send.getAttempts(),
                send.getQueuedAt(),
                send.getSentAt());
    }
}
