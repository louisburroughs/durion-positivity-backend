package com.positivity.customer.internal.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.positivity.customer.internal.dto.PromotionRedemptionMapper;
import com.positivity.customer.internal.entity.PromotionCounter;
import com.positivity.customer.internal.enums.RedemptionStatus;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.customer.internal.dto.PromotionRedemptionResponse;
import com.positivity.customer.internal.entity.PromotionRedemption;
import com.positivity.customer.internal.dto.RecordRedemptionRequest;
import com.positivity.customer.internal.exception.DuplicateRedemptionException;
import com.positivity.customer.internal.repository.PromotionCounterRepository;
import com.positivity.customer.internal.repository.PromotionRedemptionRepository;
import com.positivity.customer.service.PromotionRedemptionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PromotionRedemptionServiceImpl implements PromotionRedemptionService {

    private final PromotionRedemptionRepository promotionRedemptionRepository;
    private final PromotionCounterRepository promotionCounterRepository;

    @Override
    @Transactional
    public PromotionRedemptionResponse recordRedemption(@NonNull RecordRedemptionRequest request) {
        if (promotionRedemptionRepository.existsByPromotionIdAndWorkorderId(
                request.getPromotionId(), request.getWorkorderId())) {
            throw new DuplicateRedemptionException(request.getPromotionId(), request.getWorkorderId());
        }

        PromotionRedemption redemption = new PromotionRedemption();
        redemption.setPromotionId(request.getPromotionId());
        redemption.setCustomerId(request.getCustomerId());
        redemption.setWorkorderId(request.getWorkorderId());
        redemption.setInvoiceId(request.getInvoiceId());
        redemption.setDiscountAmount(request.getDiscountAmount());
        redemption.setDiscountType(request.getDiscountType());
        redemption.setPromotionCode(request.getPromotionCode());
        redemption.setRecordedOverLimit(
                request.getRecordedOverLimit() != null ? request.getRecordedOverLimit() : false);
        redemption.setStatus(Boolean.TRUE.equals(request.getRecordedOverLimit())
                ? RedemptionStatus.RECORDED_OVER_LIMIT
                : RedemptionStatus.RECORDED);
        redemption.setRedemptionTimestamp(request.getRedemptionTimestamp() != null
                ? request.getRedemptionTimestamp()
                : LocalDateTime.now());

        PromotionRedemption saved;
        try {
            saved = promotionRedemptionRepository.save(redemption);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateRedemptionException(request.getPromotionId(), request.getWorkorderId());
        }

        PromotionCounter counter = promotionCounterRepository.findByPromotionId(request.getPromotionId())
                .orElseGet(() -> {
                    PromotionCounter createdCounter = new PromotionCounter();
                    createdCounter.setPromotionId(request.getPromotionId());
                    createdCounter.setTotalUsageCount(0);
                    return createdCounter;
                });
        counter.setTotalUsageCount(counter.getTotalUsageCount() + 1);
        promotionCounterRepository.save(counter);

        return PromotionRedemptionMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PromotionRedemptionResponse> getRedemptionsByCustomer(@NonNull UUID customerId) {
        return promotionRedemptionRepository.findByCustomerId(customerId)
                .stream()
                .map(PromotionRedemptionMapper::toResponse)
                .collect(Collectors.toList());
    }
}