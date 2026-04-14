package com.positivity.order.internal.client;

public record PaymentReversalResult(boolean success, String paymentReversalType, String resultMessage) {}
