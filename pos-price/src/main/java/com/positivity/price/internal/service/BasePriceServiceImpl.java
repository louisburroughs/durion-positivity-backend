package com.positivity.price.internal.service;

import com.positivity.price.internal.entity.ProductBasePrice;
import com.positivity.price.internal.repository.ProductBasePriceRepository;
import com.positivity.price.service.BasePriceService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BasePriceServiceImpl implements BasePriceService {

  private final ProductBasePriceRepository repository;

  @Override
  public @NonNull UUID saveBasePrice(
      @NonNull UUID productId,
      @NonNull BigDecimal msrp,
      @NonNull String currency,
      @NonNull Instant effectiveFrom) {
    ProductBasePrice basePrice = new ProductBasePrice();
    basePrice.setProductId(productId);
    basePrice.setMsrp(msrp);
    basePrice.setCurrency(currency);
    basePrice.setEffectiveFrom(effectiveFrom);
    return repository.save(basePrice).getProductId();
  }
}