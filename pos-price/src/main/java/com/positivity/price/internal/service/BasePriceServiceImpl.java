package com.positivity.price.internal.service;

import com.positivity.price.internal.entity.ProductBasePrice;
import com.positivity.price.internal.exception.BasePriceWindowConflictException;
import com.positivity.price.internal.repository.ProductBasePriceRepository;
import com.positivity.price.service.BasePriceService;
import com.positivity.price.service.model.BasePriceView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BasePriceServiceImpl implements BasePriceService {

    private final ProductBasePriceRepository repository;

    @Override
    @Transactional
    public @NonNull UUID saveBasePrice(
            @NonNull UUID productId,
            @NonNull BigDecimal msrp,
            @NonNull String currency,
            @NonNull Instant effectiveFrom) {
        Optional<ProductBasePrice> latest =
                repository.findFirstByProductIdAndCurrencyOrderByEffectiveFromDesc(productId, currency);

        if (latest.isPresent()) {
            ProductBasePrice current = latest.get();
            Instant currentEnd = current.getEffectiveTo();
            boolean openWindow = currentEnd == null;

            if (openWindow && current.getMsrp().compareTo(msrp) == 0) {
                return current.getId();
            }
            if (!effectiveFrom.isAfter(current.getEffectiveFrom())
                    || (currentEnd != null && effectiveFrom.isBefore(currentEnd))) {
                throw new BasePriceWindowConflictException(productId, currency, effectiveFrom);
            }
            if (openWindow) {
                current.setEffectiveTo(effectiveFrom);
                repository.save(current);
            }
        }

        ProductBasePrice appended = new ProductBasePrice();
        appended.setProductId(productId);
        appended.setMsrp(msrp);
        appended.setCurrency(currency);
        appended.setEffectiveFrom(effectiveFrom);
        appended.setEffectiveTo(null);
        return repository.save(appended).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<BasePriceView> getBasePriceHistory(@NonNull UUID productId) {
        return repository.findByProductIdOrderByEffectiveFromDesc(productId).stream()
                .map(BasePriceServiceImpl::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BasePriceView> getBasePriceAt(
            @NonNull UUID productId, @NonNull String currency, @NonNull Instant at) {
        return repository.findActiveAt(productId, currency, at).map(BasePriceServiceImpl::toView);
    }

    private static BasePriceView toView(ProductBasePrice entity) {
        return new BasePriceView(
                entity.getId(),
                entity.getProductId(),
                entity.getMsrp(),
                entity.getCurrency(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo());
    }
}
