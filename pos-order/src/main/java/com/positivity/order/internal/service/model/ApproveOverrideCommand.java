package com.positivity.order.internal.service.model;

import org.jspecify.annotations.NonNull;

public record ApproveOverrideCommand(@NonNull String approverRole, String comments) {}
