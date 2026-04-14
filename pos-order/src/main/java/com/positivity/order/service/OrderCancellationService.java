package com.positivity.order.service;

import com.positivity.order.service.model.CancelOrderCommand;
import com.positivity.order.service.model.CancellationResult;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface OrderCancellationService {

    @NonNull
    CancellationResult cancelOrder(@NonNull UUID orderId, @NonNull CancelOrderCommand command);

    @NonNull
    CancellationResult retryCancellation(@NonNull UUID orderId, @NonNull String idempotencyKey);
}
