package com.positivity.accounting.service;

import com.positivity.accounting.internal.enums.RefundPaymentStatus;
import com.positivity.accounting.internal.enums.RefundType;
import com.positivity.accounting.internal.service.RefundAuthorizationServiceImpl.RefundAuthorizationResult;

public interface RefundAuthorizationService {

    /**
     * Validates refund authorization and determines appropriate refund method.
     *
     * @param actorRole     the actor's role
     * @param paymentStatus the original payment status
     * @param refundType    the type of refund
     * @return refund authorization result
     */
    RefundAuthorizationResult validate(String actorRole, RefundPaymentStatus paymentStatus, RefundType refundType);
}
