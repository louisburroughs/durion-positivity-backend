package com.positivity.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.internal.client.CancelWorkorderCommand;
import com.positivity.order.internal.client.DefaultWorkexecPortAdapter;
import com.positivity.order.internal.client.WorkorderCancelResult;
import com.positivity.order.internal.client.WorkorderStatusResult;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultWorkexecPortAdapter} stub adapter — Issue #19.
 */
@DisplayName("DefaultWorkexecPortAdapter Unit Tests")
class DefaultWorkexecPortAdapterTest {

    private final DefaultWorkexecPortAdapter adapter = new DefaultWorkexecPortAdapter();

    @Test
    @DisplayName("checkWorkorderStatus returns OPEN and cancellable=true with null reason")
    void checkWorkorderStatus_returnsOpenCancellable() {
        UUID workorderId = UUID.randomUUID();

        WorkorderStatusResult result = adapter.checkWorkorderStatus(workorderId);

        assertThat(result.status()).isEqualTo("OPEN");
        assertThat(result.cancellable()).isTrue();
        assertThat(result.nonCancellableReason()).isNull();
    }

    @Test
    @DisplayName("cancelWorkorder returns success=true with default message")
    void cancelWorkorder_returnsSuccess() {
        UUID workorderId = UUID.randomUUID();
        CancelWorkorderCommand command = new CancelWorkorderCommand(
                workorderId, "test-user", "CANCEL_REQUESTED", UUID.randomUUID().toString());

        WorkorderCancelResult result = adapter.cancelWorkorder(workorderId, command);

        assertThat(result.success()).isTrue();
        assertThat(result.resultMessage()).isEqualTo("Cancelled by default adapter");
    }
}
