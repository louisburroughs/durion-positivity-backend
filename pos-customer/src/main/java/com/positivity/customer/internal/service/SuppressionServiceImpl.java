package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.AddSuppressionRequest;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.SuppressionEntryResponse;
import com.positivity.customer.internal.entity.SuppressionEntry;
import com.positivity.customer.internal.enums.ConsentChangeSource;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.repository.SuppressionEntryRepository;
import com.positivity.customer.service.SuppressionService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuppressionServiceImpl implements SuppressionService {

    private static final int MAX_PAGE_SIZE = 200;

    private final Clock clock;
    private final SuppressionEntryRepository suppressionRepository;
    private final CustomerFactPublisher factPublisher;

    @Override
    @Transactional
    public @NonNull SuppressionEntryResponse add(@NonNull AddSuppressionRequest request) {
        MarketingChannel channel = request.getChannel();
        String addressHash = MarketingAddressNormalizer.hash(channel, request.getAddress());
        Optional<SuppressionEntry> existing = suppressionRepository.findByChannelAndAddressHash(channel, addressHash);
        if (existing.isPresent()) {
            // Already blocked. Re-adding must not churn the audit trail or re-emit a fact —
            // bounce feeds replay the same address routinely.
            return toResponse(existing.get());
        }
        SuppressionEntry entry = SuppressionEntry.builder()
                .channel(channel)
                .addressHash(addressHash)
                .addressHint(MarketingAddressNormalizer.hint(channel, request.getAddress()))
                .partyId(request.getPartyId())
                .reason(request.getReason())
                .source(request.getSource() != null ? request.getSource() : ConsentChangeSource.CSR)
                .createdBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .createdAt(Instant.now(clock))
                .build();
        SuppressionEntry saved;
        try {
            saved = suppressionRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException ex) {
            return suppressionRepository
                    .findByChannelAndAddressHash(channel, addressHash)
                    .map(SuppressionServiceImpl::toResponse)
                    .orElseThrow(() -> ex);
        }
        factPublisher.suppressionChanged(
                channel.name(),
                addressHash,
                saved.getPartyId(),
                true,
                saved.getReason().name());
        log.info("Suppressed {} address {} reason={}", channel, saved.getAddressHint(), saved.getReason());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void remove(@NonNull UUID suppressionId) {
        SuppressionEntry entry = suppressionRepository
                .findById(suppressionId)
                .orElseThrow(() -> new CrmResourceNotFoundException("Suppression entry", suppressionId));
        suppressionRepository.delete(entry);
        factPublisher.suppressionChanged(
                entry.getChannel().name(), entry.getAddressHash(), entry.getPartyId(), false, null);
        log.info("Lifted suppression on {} address {}", entry.getChannel(), entry.getAddressHint());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PagedResponse<SuppressionEntryResponse> list(
            @Nullable MarketingChannel channel, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SuppressionEntry> results = channel == null
                ? suppressionRepository.findAll(pageable)
                : suppressionRepository.findByChannel(channel, pageable);
        return PagedResponse.from(results, SuppressionServiceImpl::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<SuppressionEntryResponse> listForParty(@NonNull UUID partyId) {
        return suppressionRepository.findByPartyId(partyId).stream()
                .map(SuppressionServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSuppressed(@NonNull MarketingChannel channel, @NonNull String address) {
        return suppressionRepository.existsByChannelAndAddressHash(
                channel, MarketingAddressNormalizer.hash(channel, address));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPartySuppressed(@NonNull UUID partyId, @NonNull MarketingChannel channel) {
        return suppressionRepository.existsByPartyIdAndChannel(partyId, channel);
    }

    private static SuppressionEntryResponse toResponse(SuppressionEntry entry) {
        return new SuppressionEntryResponse(
                entry.getSuppressionId(),
                entry.getChannel().name(),
                entry.getAddressHint(),
                entry.getPartyId(),
                entry.getReason().name(),
                entry.getSource().name(),
                entry.getCreatedBy(),
                entry.getCreatedAt());
    }
}
