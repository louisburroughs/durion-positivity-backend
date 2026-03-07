package com.positivity.shopmanager.service;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkorderOperationalContextService {

    @NonNull
    Map<String, Object> getOperationalContext(
            @NonNull Long locationId,
            @NonNull Long workorderId,
            @Nullable String filters);
}

