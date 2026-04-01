package com.positivity.workorder.internal.enums;

/**
 * Outcome status for a single consumed pick-task item within a consume operation.
 *
 * <p>Jackson serializes this enum as its {@link #name()} by default, preserving the
 * JSON wire value (e.g. {@code "SUCCESS"}) without any extra annotation.
 */
public enum ConsumeItemStatus {

  /** All requested quantity was successfully consumed. */
  SUCCESS,

  /** Only part of the requested quantity could be consumed. */
  PARTIAL,

  /** The consume operation failed for this item. */
  FAILED
}
