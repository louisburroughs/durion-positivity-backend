package com.positivity.invoice.internal.payment;

import java.math.BigDecimal;

public record GatewayRefundRequest(String gatewayReference, BigDecimal amount) {
}