package com.positivity.order.internal.service.model;

import org.jspecify.annotations.NonNull;

public record RejectOverrideCommand(
        @NonNull String reviewerRole, @NonNull String reason, String comments) {}
