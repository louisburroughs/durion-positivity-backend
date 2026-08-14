package com.positivity.supplier.internal.enums;

/**
 * Why a PRICAT line was quarantined instead of applied (ADR-0053 §5).
 *
 * <p>Every line the vendor sent ends up either staged with a matched product or recorded here
 * with one of these reasons — there is no path that drops a line silently. The distinction
 * matters operationally: {@link #NO_CATALOG_MATCH} is catalog work, {@link #CATALOG_UNAVAILABLE}
 * is a retry, and {@link #AMBIGUOUS_CATALOG_MATCH} is dirty catalog data that #1232's constraint
 * exists to prevent.
 */
public enum UnmatchedLineReason {
    /** The line carried no EAN and no cross-reference code, so no deterministic match is possible. */
    NO_IDENTIFIER,

    /** The identifiers were well-formed but no catalog product carries them. */
    NO_CATALOG_MATCH,

    /** More than one product carries the identifier; matching is refused rather than guessed. */
    AMBIGUOUS_CATALOG_MATCH,

    /** The catalog lookup could not be reached; the line is re-applicable without a vendor re-fetch. */
    CATALOG_UNAVAILABLE,

    /** A later line in the same document repeated an identifier already staged from this import. */
    DUPLICATE_LINE,

    /** The line was structurally unusable — missing effective date, unparseable amounts. */
    MALFORMED_LINE
}
