package com.positivity.inventory.internal.exception;

/**
 * A request to this module is malformed on its face: a required field missing, an empty
 * collection where one is required, a date outside the range the operation allows, or a
 * reference to a storage location that is not in this module's replica.
 *
 * <p>Extends {@link IllegalArgumentException} on purpose. This module's advice already answers a
 * bare {@code IllegalArgumentException} with 400 {@code VALIDATION_ERROR}, so every throw site
 * retyped to this class keeps the status, code and message it had, and every caller and test
 * asserting {@code IllegalArgumentException} still matches. What changes is that the failure now
 * has a name: bulk ingest reports row outcomes inside a 200 body where no advice can classify
 * anything, and it has to decide per row whether a message describes the caller's record or the
 * server's internals (issue #1718). A bare {@code IllegalArgumentException} cannot carry that
 * distinction — it is equally what Hibernate, {@code UUID.fromString} and a JPA converter raise —
 * so a row that failed on one is reported generically. Named this way, a genuine validation
 * failure keeps telling the operator which row to fix.
 *
 * <p>The same narrowing #1694 applied to pos-vehicle-inventory's blanket
 * {@code IllegalArgumentException} handler, done here one throw site at a time rather than by
 * removing the module's handler.
 */
public class InventoryValidationException extends IllegalArgumentException {

    public InventoryValidationException(String message) {
        super(message);
    }
}
