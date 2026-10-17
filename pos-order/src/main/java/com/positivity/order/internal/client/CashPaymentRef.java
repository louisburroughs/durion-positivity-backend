package com.positivity.order.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** pos-invoice's answer to a recorded cash tender: the payment intent that now carries it (#2662). */
public record CashPaymentRef(@NonNull UUID paymentIntentId) {}
