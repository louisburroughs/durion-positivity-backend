package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.domain.TemplateToken;
import com.positivity.marketing.internal.dto.MessageTemplateResponse;
import com.positivity.marketing.internal.dto.UpsertMessageTemplateRequest;
import com.positivity.marketing.internal.entity.MessageTemplate;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.exception.MarketingDuplicateResourceException;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.exception.MarketingUnprocessableEntityException;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.MessageTemplateRepository;
import com.positivity.security.common.SecurityContextHelper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageTemplateServiceImpl implements MessageTemplateService {

    private static final String TEMPLATE = "Message template";

    private final MessageTemplateRepository templateRepository;
    private final CampaignRepository campaignRepository;
    private final TemplateRenderService renderService;

    @Override
    @Transactional
    public @NonNull MessageTemplateResponse create(@NonNull UpsertMessageTemplateRequest request) {
        String name = request.getName().trim();
        if (templateRepository.existsByNameIgnoreCase(name)) {
            throw new MarketingDuplicateResourceException(TEMPLATE, name);
        }
        renderService.validate(
                request.getChannel(), request.getAudienceType(), request.getSubject(), request.getBody());
        MessageTemplate template = MessageTemplate.builder()
                .name(name)
                .channel(request.getChannel())
                .audienceType(request.getAudienceType())
                .subject(request.getChannel() == CampaignChannel.SMS ? null : request.getSubject())
                .body(request.getBody())
                .createdBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .build();
        return toResponse(templateRepository.save(template));
    }

    @Override
    @Transactional
    public @NonNull MessageTemplateResponse update(
            @NonNull UUID templateId, @NonNull UpsertMessageTemplateRequest request) {
        MessageTemplate template = requireTemplate(templateId);
        String name = request.getName().trim();
        templateRepository
                .findByNameIgnoreCase(name)
                .filter(other -> !other.getTemplateId().equals(templateId))
                .ifPresent(other -> {
                    throw new MarketingDuplicateResourceException(TEMPLATE, name);
                });
        if (request.getChannel() != template.getChannel() || request.getAudienceType() != template.getAudienceType()) {
            // Either change silently invalidates every campaign already bound to this template —
            // an SMS campaign would suddenly hold an email body, or a fleet campaign a template
            // written with individual tokens.
            throw new MarketingUnprocessableEntityException(
                    "Template channel and audienceType are immutable; create a new template instead");
        }
        renderService.validate(
                request.getChannel(), request.getAudienceType(), request.getSubject(), request.getBody());
        template.setName(name);
        template.setSubject(request.getChannel() == CampaignChannel.SMS ? null : request.getSubject());
        template.setBody(request.getBody());
        return toResponse(templateRepository.save(template));
    }

    @Override
    @Transactional
    public void delete(@NonNull UUID templateId) {
        MessageTemplate template = requireTemplate(templateId);
        // A campaign whose template vanished cannot render, and the failure would surface at
        // dispatch across the whole audience at once.
        boolean inUse = campaignRepository.findAll().stream()
                .anyMatch(campaign -> templateId.equals(campaign.getEmailTemplateId())
                        || templateId.equals(campaign.getSmsTemplateId()));
        if (inUse) {
            throw new MarketingUnprocessableEntityException(
                    "Template is attached to at least one campaign and cannot be deleted");
        }
        templateRepository.delete(template);
        log.info("Deleted message template {} ({})", template.getName(), templateId);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull MessageTemplateResponse get(@NonNull UUID templateId) {
        return toResponse(requireTemplate(templateId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<MessageTemplateResponse> list(
            @Nullable CampaignChannel channel, @Nullable AudienceType audienceType) {
        List<MessageTemplate> templates = channel != null && audienceType != null
                ? templateRepository.findByChannelAndAudienceTypeOrderByNameAsc(channel, audienceType)
                : templateRepository.findAllByOrderByNameAsc();
        return templates.stream()
                .filter(template -> channel == null || template.getChannel() == channel)
                .filter(template -> audienceType == null || template.getAudienceType() == audienceType)
                .map(MessageTemplateServiceImpl::toResponse)
                .toList();
    }

    private MessageTemplate requireTemplate(UUID templateId) {
        return templateRepository
                .findById(templateId)
                .orElseThrow(() -> new MarketingResourceNotFoundException(TEMPLATE, templateId));
    }

    private static MessageTemplateResponse toResponse(MessageTemplate template) {
        return new MessageTemplateResponse(
                template.getTemplateId(),
                template.getName(),
                template.getChannel().name(),
                template.getAudienceType().name(),
                template.getSubject(),
                template.getBody(),
                TemplateToken.namesFor(template.getAudienceType()),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
