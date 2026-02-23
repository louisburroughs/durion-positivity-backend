package com.positivity.accounting.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;

import com.positivity.accounting.internal.audit.dto.AuditTrailResponse;
import com.positivity.accounting.internal.audit.entity.ExceptionType;

public interface AuditTrailQueryService {

    @NonNull
    List<AuditTrailResponse> getByOrderId(@NonNull UUID orderId);

    @NonNull
    List<AuditTrailResponse> getByInvoiceId(@NonNull UUID invoiceId);

    @NonNull
    List<AuditTrailResponse> getByTypeAndDateRange(
            @NonNull ExceptionType type,
            @NonNull Instant startDate,
            @NonNull Instant endDate);

    @NonNull
    List<AuditTrailResponse> getByActorAndDateRange(
            @NonNull String actorId,
            @NonNull Instant startDate,
            @NonNull Instant endDate);

    @NonNull
    List<AuditTrailResponse> getByDateRange(@NonNull Instant startDate, @NonNull Instant endDate);
}
