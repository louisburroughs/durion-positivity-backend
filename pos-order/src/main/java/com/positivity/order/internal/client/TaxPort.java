package com.positivity.order.internal.client;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Tax calculation against pos-tax (ADR-0044 utility module; parity story B3). Empty result =
 * service unreachable; callers decide whether that blocks (quote/checkout) or defers.
 */
public interface TaxPort {

    @NonNull
    Optional<TaxCalculationResponse> calculate(@NonNull TaxCalculationRequest request);
}
