package com.positivity.accounting.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.positivity.tenancy.TenantIterator;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FailedAccountingEventRetryJobTest {

    @Mock
    private EventIngestionService eventIngestionService;

    @Mock
    private TenantIterator tenantIterator;

    @Test
    @DisplayName("each poll runs processFailed with the configured cap once per active tenant")
    void retryFailed_runsPerTenant() {
        doAnswer(inv -> {
                    Consumer<UUID> work = inv.getArgument(0);
                    work.accept(UUID.randomUUID());
                    work.accept(UUID.randomUUID());
                    return 2;
                })
                .when(tenantIterator)
                .forEachActiveTenant(any());

        new FailedAccountingEventRetryJob(eventIngestionService, tenantIterator, 4).retryFailed();

        verify(eventIngestionService, org.mockito.Mockito.times(2)).processFailed(4);
    }
}
