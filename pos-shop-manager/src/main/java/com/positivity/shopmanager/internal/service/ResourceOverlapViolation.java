package com.positivity.shopmanager.internal.service;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Recognises a refusal by {@code appointment_resource_no_overlap} (V8, CAP-326) in the exception a
 * flush throws. The decision is by SQLSTATE — {@code 23P01}, exclusion_violation — never by message
 * text; the constraint name is only checked to keep the match exact should the table ever grow a
 * second exclusion constraint.
 */
final class ResourceOverlapViolation {

    static final String CONSTRAINT_NAME = "appointment_resource_no_overlap";
    static final String EXCLUSION_VIOLATION = "23P01";

    private ResourceOverlapViolation() {}

    static boolean matches(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException hibernate
                    && CONSTRAINT_NAME.equalsIgnoreCase(hibernate.getConstraintName())) {
                return true;
            }
            if (cause instanceof SQLException sql
                    && EXCLUSION_VIOLATION.equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(CONSTRAINT_NAME)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
