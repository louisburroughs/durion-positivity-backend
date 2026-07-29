package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.dto.AudiencePreviewResponse;
import com.positivity.marketing.internal.dto.CampaignResponse;
import com.positivity.marketing.internal.dto.UpsertCampaignRequest;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.SegmentReplica;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.enums.ScheduleType;
import com.positivity.marketing.internal.exception.MarketingDuplicateResourceException;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.exception.MarketingUnprocessableEntityException;
import com.positivity.marketing.internal.repository.CampaignAudienceMemberRepository;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.MessageTemplateRepository;
import com.positivity.marketing.internal.repository.SegmentReplicaRepository;
import com.positivity.marketing.service.CampaignService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignServiceImpl implements CampaignService {

    private static final String CAMPAIGN = "Campaign";

    private final CampaignRepository campaignRepository;
    private final MessageTemplateRepository templateRepository;
    private final SegmentReplicaRepository segmentReplicaRepository;
    private final CampaignAudienceMemberRepository audienceRepository;
    private final AudienceEligibilityService eligibilityService;
    private final SegmentResolveRequester resolveRequester;
    private final MarketingFactPublisher factPublisher;

    @Override
    @Transactional
    public @NonNull CampaignResponse create(@NonNull UpsertCampaignRequest request) {
        String code = request.getCode().trim();
        if (campaignRepository.existsByCodeIgnoreCase(code)) {
            throw new MarketingDuplicateResourceException(CAMPAIGN, code);
        }
        Campaign campaign = Campaign.builder()
                .code(code)
                .name(request.getName().trim())
                .description(request.getDescription())
                .audienceType(request.getAudienceType())
                .campaignProgramId(request.getCampaignProgramId())
                .status(CampaignStatus.DRAFT)
                .channels(new HashSet<>(request.getChannels()))
                .segmentId(request.getSegmentId())
                .promotionOfferId(request.getPromotionOfferId())
                .catalogFocusRef(request.getCatalogFocusRef())
                .windowStart(request.getWindowStart())
                .windowEnd(request.getWindowEnd())
                .scheduleType(request.getScheduleType() != null ? request.getScheduleType() : ScheduleType.IMMEDIATE)
                .scheduledAt(request.getScheduledAt())
                .emailTemplateId(request.getEmailTemplateId())
                .smsTemplateId(request.getSmsTemplateId())
                .createdBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .build();
        validateShape(campaign);
        return toResponse(campaignRepository.save(campaign));
    }

    @Override
    @Transactional
    public @NonNull CampaignResponse update(@NonNull UUID campaignId, @NonNull UpsertCampaignRequest request) {
        Campaign campaign = requireCampaign(campaignId);
        if (!campaign.getStatus().isEditable()) {
            throw new MarketingUnprocessableEntityException("A campaign in status " + campaign.getStatus()
                    + " can no longer be edited; some recipients may already have been contacted");
        }
        if (request.getAudienceType() != campaign.getAudienceType()) {
            throw new MarketingUnprocessableEntityException(
                    "Campaign audienceType is immutable; its segment and templates are bound to it");
        }
        String code = request.getCode().trim();
        campaignRepository
                .findByCodeIgnoreCase(code)
                .filter(other -> !other.getCampaignId().equals(campaignId))
                .ifPresent(other -> {
                    throw new MarketingDuplicateResourceException(CAMPAIGN, code);
                });
        campaign.setCode(code);
        campaign.setName(request.getName().trim());
        campaign.setDescription(request.getDescription());
        campaign.setCampaignProgramId(request.getCampaignProgramId());
        campaign.setChannels(new HashSet<>(request.getChannels()));
        campaign.setSegmentId(request.getSegmentId());
        campaign.setPromotionOfferId(request.getPromotionOfferId());
        campaign.setCatalogFocusRef(request.getCatalogFocusRef());
        campaign.setWindowStart(request.getWindowStart());
        campaign.setWindowEnd(request.getWindowEnd());
        campaign.setScheduleType(
                request.getScheduleType() != null ? request.getScheduleType() : ScheduleType.IMMEDIATE);
        campaign.setScheduledAt(request.getScheduledAt());
        campaign.setEmailTemplateId(request.getEmailTemplateId());
        campaign.setSmsTemplateId(request.getSmsTemplateId());
        validateShape(campaign);
        return toResponse(campaignRepository.save(campaign));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull CampaignResponse get(@NonNull UUID campaignId) {
        return toResponse(requireCampaign(campaignId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<CampaignResponse> list(@Nullable CampaignStatus status, @Nullable UUID campaignProgramId) {
        List<Campaign> campaigns;
        if (campaignProgramId != null) {
            campaigns = campaignRepository.findByCampaignProgramId(campaignProgramId);
        } else if (status != null) {
            campaigns = campaignRepository.findByStatusOrderByCreatedAtDesc(status);
        } else {
            campaigns = campaignRepository.findAllByOrderByCreatedAtDesc();
        }
        return campaigns.stream()
                .filter(campaign -> status == null || campaign.getStatus() == status)
                .map(CampaignServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull CampaignResponse schedule(@NonNull UUID campaignId) {
        Campaign campaign = requireCampaign(campaignId);
        List<String> problems = readinessProblems(campaign);
        if (!problems.isEmpty()) {
            throw new MarketingUnprocessableEntityException(
                    "Campaign is not ready to schedule: " + String.join("; ", problems));
        }
        transition(campaign, CampaignStatus.SCHEDULED);
        Campaign saved = campaignRepository.save(campaign);
        factPublisher.campaignScheduled(saved);
        log.info("Campaign {} ({}) scheduled", saved.getCode(), campaignId);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull CampaignResponse pause(@NonNull UUID campaignId) {
        return applyTransition(campaignId, CampaignStatus.PAUSED);
    }

    @Override
    @Transactional
    public @NonNull CampaignResponse resume(@NonNull UUID campaignId) {
        Campaign campaign = requireCampaign(campaignId);
        // Resume returns to SCHEDULED, not SENDING: the worker decides when to start again, so a
        // resumed campaign re-enters the queue rather than jumping straight back into dispatch.
        transition(campaign, CampaignStatus.SCHEDULED);
        return toResponse(campaignRepository.save(campaign));
    }

    @Override
    @Transactional
    public @NonNull CampaignResponse cancel(@NonNull UUID campaignId) {
        return applyTransition(campaignId, CampaignStatus.CANCELLED);
    }

    @Override
    @Transactional
    public @NonNull AudiencePreviewResponse previewAudience(@NonNull UUID campaignId) {
        Campaign campaign = requireCampaign(campaignId);
        if (campaign.getSegmentId() == null) {
            throw new MarketingUnprocessableEntityException("Campaign has no segment bound; nothing to preview");
        }
        // Membership arrives asynchronously, so a preview reports the last snapshot and asks for
        // a fresher one. The snapshot's age travels with the numbers so a marketer can see how
        // current they are rather than assuming they are live.
        List<UUID> members = audienceRepository.findPartyIdsByCampaignId(campaignId);
        Instant resolvedAt = audienceRepository.findResolvedAt(campaignId).orElse(null);
        if (campaign.getStatus().audienceIsMutable()) {
            resolveRequester.requestResolve(campaignId, campaign.getSegmentId());
        }

        List<AudiencePreviewResponse.ChannelPreview> channels = campaign.getChannels().stream()
                .map(channel -> new AudiencePreviewResponse.ChannelPreview(
                        channel.name(),
                        members.size(),
                        eligibilityService.countEligible(members, channel),
                        templateFor(campaign, channel).isPresent()))
                .toList();

        return new AudiencePreviewResponse(
                campaignId,
                campaign.getSegmentId(),
                campaign.getAudienceType().name(),
                members.size(),
                resolvedAt,
                false,
                channels,
                readinessProblems(campaign));
    }

    /**
     * Everything that would stop this campaign from going out, gathered rather than thrown one at
     * a time — a marketer fixing a campaign wants the whole list, not a game of whack-a-mole.
     *
     * <p>The segment is checked against the {@code ext_segment} replica. Offer and catalog
     * validation is deliberately absent: pos-price publishes no events, so there is no fact to
     * replicate and no synchronous read permitted across the domain wall. That check returns
     * with FI-1 (#1134), which is the pos-price side of the work.
     */
    private List<String> readinessProblems(Campaign campaign) {
        List<String> problems = new ArrayList<>();
        if (campaign.getChannels().isEmpty()) {
            problems.add("no delivery channel selected");
        }
        if (campaign.getSegmentId() == null) {
            problems.add("no segment bound");
        } else {
            Optional<SegmentReplica> segment = segmentReplicaRepository.findById(campaign.getSegmentId());
            if (segment.isEmpty()) {
                // Either the segment does not exist or its fact has not reached us yet. Both are
                // reasons not to schedule; the operator can retry once the replica catches up.
                problems.add("segment " + campaign.getSegmentId() + " is not known to this module yet");
            } else {
                SegmentReplica replica = segment.get();
                if (!replica.isActive()) {
                    problems.add("segment " + replica.getName() + " is inactive");
                }
                if (replica.getAudienceType() != null
                        && !campaign.getAudienceType().name().equalsIgnoreCase(replica.getAudienceType())) {
                    // A commercial campaign bound to an individual segment would render templates
                    // written for organizations against people, for every recipient.
                    problems.add("segment audienceType " + replica.getAudienceType() + " does not match campaign "
                            + campaign.getAudienceType());
                }
            }
        }
        for (CampaignChannel channel : campaign.getChannels()) {
            if (templateFor(campaign, channel).isEmpty()) {
                problems.add("no " + channel + " template attached");
            }
        }
        return problems;
    }

    private Optional<UUID> templateFor(Campaign campaign, CampaignChannel channel) {
        UUID templateId =
                channel == CampaignChannel.EMAIL ? campaign.getEmailTemplateId() : campaign.getSmsTemplateId();
        if (templateId == null) {
            return Optional.empty();
        }
        // Present but dangling is as broken as absent — verify it still exists.
        return templateRepository.findById(templateId).map(template -> templateId);
    }

    private CampaignResponse applyTransition(UUID campaignId, CampaignStatus target) {
        Campaign campaign = requireCampaign(campaignId);
        transition(campaign, target);
        return toResponse(campaignRepository.save(campaign));
    }

    private void transition(Campaign campaign, CampaignStatus target) {
        if (!campaign.getStatus().canTransitionTo(target)) {
            throw new MarketingUnprocessableEntityException(
                    "Cannot move campaign from " + campaign.getStatus() + " to " + target);
        }
        campaign.setStatus(target);
    }

    private void validateShape(Campaign campaign) {
        if (campaign.getScheduleType() == ScheduleType.SCHEDULED && campaign.getScheduledAt() == null) {
            throw new MarketingUnprocessableEntityException("A SCHEDULED campaign requires scheduledAt");
        }
        if (campaign.getWindowStart() != null
                && campaign.getWindowEnd() != null
                && campaign.getWindowEnd().isBefore(campaign.getWindowStart())) {
            throw new MarketingUnprocessableEntityException("windowEnd must not precede windowStart");
        }
    }

    private Campaign requireCampaign(UUID campaignId) {
        return campaignRepository
                .findById(campaignId)
                .orElseThrow(() -> new MarketingResourceNotFoundException(CAMPAIGN, campaignId));
    }

    static CampaignResponse toResponse(Campaign campaign) {
        Set<String> channels = campaign.getChannels().stream()
                .map(CampaignChannel::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return new CampaignResponse(
                campaign.getCampaignId(),
                campaign.getCode(),
                campaign.getName(),
                campaign.getDescription(),
                campaign.getAudienceType().name(),
                campaign.getCampaignProgramId(),
                campaign.getStatus().name(),
                channels,
                campaign.getSegmentId(),
                campaign.getPromotionOfferId(),
                campaign.getCatalogFocusRef(),
                campaign.getWindowStart(),
                campaign.getWindowEnd(),
                campaign.getScheduleType().name(),
                campaign.getScheduledAt(),
                campaign.getEmailTemplateId(),
                campaign.getSmsTemplateId(),
                campaign.getCreatedAt(),
                campaign.getUpdatedAt());
    }
}
