package com.positivity.workorder.internal.exception;

import java.util.UUID;

public class StaleSubstituteLinkVersionException extends RuntimeException {
    public StaleSubstituteLinkVersionException(UUID linkId, int requestedVersion, int currentVersion) {
        super("Version conflict for SubstituteLink " + linkId + ": requested " + requestedVersion + " but current is "
                + currentVersion);
    }
}
