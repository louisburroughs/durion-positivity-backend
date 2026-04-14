package com.positivity.accounting.internal.audit.event;

import com.positivity.accounting.internal.audit.entity.ExceptionType;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Event emitted when authorization is denied.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthorizationDenied {

    private ExceptionType exceptionType;
    private String actorId;
    private String actorRole;
    private String reasonDenied;
    private String policyVersion;

    private Instant timestamp;
}
