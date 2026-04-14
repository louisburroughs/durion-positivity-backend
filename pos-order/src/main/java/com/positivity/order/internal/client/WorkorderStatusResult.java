package com.positivity.order.internal.client;

public record WorkorderStatusResult(String status, boolean cancellable, String nonCancellableReason) {}
