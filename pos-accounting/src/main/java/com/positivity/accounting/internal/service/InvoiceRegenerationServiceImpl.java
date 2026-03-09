package com.positivity.accounting.internal.service;

import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.accounting.internal.client.WorkorderInvoiceClient;
import com.positivity.accounting.internal.client.WorkorderServiceException;
import com.positivity.accounting.service.InvoiceRegenerationService;
import com.positivity.shared.dto.InvoiceGenerationResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InvoiceRegenerationServiceImpl implements InvoiceRegenerationService {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(InvoiceRegenerationServiceImpl.class);
    private final WorkorderInvoiceClient workorderInvoiceClient;

    @Override
    @NonNull
        public InvoiceGenerationResponse regenerateInvoiceFromWorkorder(
                @NonNull UUID workorderId,
                @Nullable String idempotencyKey) {
        if (log.isInfoEnabled()) {
            log.info("Regenerating invoice from workorder {}", maskWorkorderId(workorderId));
        }
        try {
            return workorderInvoiceClient.regenerateInvoiceFromWorkorder(workorderId, idempotencyKey);
        } catch (WorkorderServiceException e) {
            HttpStatus status = HttpStatus.resolve(e.getHttpStatus());
            if (status == null) {
                status = HttpStatus.SERVICE_UNAVAILABLE;
            }
            throw new ResponseStatusException(status, e.getMessage(), e);
        }
    }

    private String maskWorkorderId(UUID workorderId) {
        if (workorderId == null) {
            return "null";
        }

        String value = workorderId.toString();
        return value.substring(0, 8) + "...";
    }
}
