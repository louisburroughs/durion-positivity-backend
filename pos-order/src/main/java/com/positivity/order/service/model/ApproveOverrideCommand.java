package com.positivity.order.service.model;

import org.jspecify.annotations.NonNull;

public record ApproveOverrideCommand(
        @NonNull String approverRole,
        String comments) {
}