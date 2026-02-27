package com.positivity.shopmanager.internal.adapter;

import java.util.Map;
import java.util.UUID;

public interface CustomerAdapter {
    Map<String, Object> getCustomerById(UUID customerId);
}
