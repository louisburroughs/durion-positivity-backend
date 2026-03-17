package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.SelfRegistrationRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationResponse;
import org.jspecify.annotations.NonNull;

public interface SelfRegistrationService {

    @NonNull
    SelfRegistrationResponse selfRegister(@NonNull SelfRegistrationRequest request);
}
