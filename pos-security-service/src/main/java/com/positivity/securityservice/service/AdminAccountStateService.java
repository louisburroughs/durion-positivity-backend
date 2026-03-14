package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.AccountStateResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface AdminAccountStateService {

    void unlock(@NonNull UUID userId);

    void enable(@NonNull UUID userId);

    void disable(@NonNull UUID userId);

    void expireAccount(@NonNull UUID userId);

    void expireCredentials(@NonNull UUID userId);

    @NonNull
    AccountStateResponse getAccountState(@NonNull UUID userId);
}