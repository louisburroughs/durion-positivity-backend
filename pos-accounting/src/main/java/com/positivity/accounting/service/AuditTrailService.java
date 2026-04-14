package com.positivity.accounting.service;

import com.positivity.accounting.internal.audit.dto.AuditTrailResponse;
import com.positivity.accounting.internal.audit.dto.CancellationRequest;
import com.positivity.accounting.internal.audit.dto.PriceOverrideRequest;
import com.positivity.accounting.internal.audit.dto.RefundRequest;
import org.jspecify.annotations.NonNull;

public interface AuditTrailService {

    @NonNull
    AuditTrailResponse recordPriceOverride(@NonNull PriceOverrideRequest request);

    @NonNull
    AuditTrailResponse recordRefund(@NonNull RefundRequest request);

    @NonNull
    AuditTrailResponse recordCancellation(@NonNull CancellationRequest request);
}
