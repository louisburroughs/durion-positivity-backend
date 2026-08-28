package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.CustomerInteractionResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.RecordInteractionRequest;
import com.positivity.customer.internal.entity.CustomerInteraction;
import com.positivity.customer.internal.enums.InteractionDirection;
import com.positivity.customer.internal.enums.InteractionType;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.repository.CustomerInteractionRepository;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerInteractionServiceImpl implements CustomerInteractionService {

    private static final int MAX_PAGE_SIZE = 200;

    private final Clock clock;
    private final CustomerInteractionRepository interactionRepository;

    @Override
    @Transactional
    public @NonNull CustomerInteractionResponse record(
            @NonNull UUID partyId, @NonNull RecordInteractionRequest request) {
        CustomerInteraction interaction = CustomerInteraction.builder()
                .partyId(partyId)
                .contactId(request.getContactId())
                .type(request.getType())
                .channel(request.getChannel())
                .direction(request.getDirection() != null ? request.getDirection() : InteractionDirection.OUTBOUND)
                .subject(request.getSubject())
                .summary(request.getSummary())
                .body(request.getBody())
                .actor(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .occurredAt(request.getOccurredAt() != null ? request.getOccurredAt() : Instant.now(clock))
                .build();
        return toResponse(interactionRepository.save(interaction));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PagedResponse<CustomerInteractionResponse> listForParty(
            @NonNull UUID partyId, @Nullable InteractionType type, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PagedResponse.from(
                type == null
                        ? interactionRepository.findByPartyIdOrderByOccurredAtDesc(partyId, pageable)
                        : interactionRepository.findByPartyIdAndTypeOrderByOccurredAtDesc(partyId, type, pageable),
                CustomerInteractionServiceImpl::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<CustomerInteractionResponse> recentForParty(@NonNull UUID partyId) {
        return interactionRepository.findTop5ByPartyIdOrderByOccurredAtDesc(partyId).stream()
                .map(CustomerInteractionServiceImpl::toResponse)
                .toList();
    }

    /**
     * Ingest an event-sourced interaction. Returns {@code false} when the source event was
     * already applied, so campaign-send and workorder replays are no-ops.
     */
    @Transactional
    public boolean ingest(@NonNull CustomerInteraction interaction) {
        String sourceEventId = interaction.getSourceEventId();
        if (sourceEventId != null && interactionRepository.existsBySourceEventId(sourceEventId)) {
            log.debug("Interaction for source event {} already recorded; skipping", sourceEventId);
            return false;
        }
        if (interaction.getOccurredAt() == null) {
            interaction.setOccurredAt(Instant.now(clock));
        }
        interactionRepository.save(interaction);
        return true;
    }

    private static CustomerInteractionResponse toResponse(CustomerInteraction interaction) {
        return new CustomerInteractionResponse(
                interaction.getInteractionId(),
                interaction.getPartyId(),
                interaction.getContactId(),
                interaction.getType().name(),
                interaction.getChannel() != null ? interaction.getChannel().name() : null,
                interaction.getDirection().name(),
                interaction.getCampaignId(),
                interaction.getCampaignCode(),
                interaction.getSubject(),
                interaction.getSummary(),
                InteractionBodyRedactor.redact(interaction.getBody()),
                interaction.getActor(),
                interaction.getSourceWorkorderId(),
                interaction.getOccurredAt());
    }

    /** Kept for the channel-typed ingest paths so callers need not import the enum twice. */
    static @Nullable MarketingChannel channelOrNull(@Nullable String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        return MarketingChannel.valueOf(channel);
    }
}
