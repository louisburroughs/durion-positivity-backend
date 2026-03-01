package com.positivity.accounting.internal.service;

import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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

    private final WorkorderInvoiceClient workorderInvoiceClient;

    @Override
    @NonNull
    public InvoiceGenerationResponse regenerateInvoiceFromWorkorder(
            @NonNull UUID workorderId,
            @Nullable String idempotencyKey) {
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
}
