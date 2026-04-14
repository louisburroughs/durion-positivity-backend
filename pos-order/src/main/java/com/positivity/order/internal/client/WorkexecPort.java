package com.positivity.order.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface WorkexecPort {
    @NonNull
    WorkorderStatusResult checkWorkorderStatus(@NonNull UUID workOrderId);

    @NonNull
    WorkorderCancelResult cancelWorkorder(@NonNull UUID workOrderId, @NonNull CancelWorkorderCommand command);
}
