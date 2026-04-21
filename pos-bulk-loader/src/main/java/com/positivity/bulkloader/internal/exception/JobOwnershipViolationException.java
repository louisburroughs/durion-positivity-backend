package com.positivity.bulkloader.internal.exception;

public class JobOwnershipViolationException extends RuntimeException {
    public JobOwnershipViolationException(String jobId) {
        super("Access denied to job: " + jobId);
    }
}
