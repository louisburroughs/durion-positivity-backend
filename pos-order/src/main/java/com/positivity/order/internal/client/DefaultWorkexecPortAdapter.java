package com.positivity.order.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class DefaultWorkexecPortAdapter implements WorkexecPort {

    @Override
    public @NonNull WorkorderStatusResult checkWorkorderStatus(@NonNull UUID workOrderId) {
        return new WorkorderStatusResult("OPEN", true, null);
    }

    @Override
    public @NonNull WorkorderCancelResult cancelWorkorder(@NonNull UUID workOrderId, @NonNull CancelWorkorderCommand command) {
        return new WorkorderCancelResult(true, "Cancelled by default adapter");
    }
}