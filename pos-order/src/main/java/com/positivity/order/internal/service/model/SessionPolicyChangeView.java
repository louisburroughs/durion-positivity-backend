package com.positivity.order.internal.service.model;

import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** One changed drawer-policy setting (CAP:550 S16, #2512): old and new value, actor and justification. */
public record SessionPolicyChangeView(
        @NonNull String setting,
        @Nullable String oldValue,
        @Nullable String newValue,
        @NonNull String actor,
        @NonNull String justification,
        @NonNull Instant changedAt) {}
