package com.positivity.supplier.internal.exception;

/**
 * Thrown when a supplier-domain resource referenced by id does not exist (or does not belong
 * to the addressed profile). 404 material per ADR-0017; the {@code code} is the
 * machine-readable {@code ApiError} code.
 */
public class SupplierNotFoundException extends RuntimeException {

    public static final String PROFILE_NOT_FOUND = "SUPPLIER_PROFILE_NOT_FOUND";
    public static final String AUTH_CONFIG_NOT_FOUND = "SUPPLIER_AUTH_CONFIG_NOT_FOUND";
    public static final String ACCOUNT_NOT_FOUND = "SUPPLIER_ACCOUNT_NOT_FOUND";
    public static final String BINDING_NOT_FOUND = "SUPPLIER_BINDING_NOT_FOUND";

    /**
     * No exchange-audit row with that id. Not the same thing as a row that carries no payload, which is
     * a normal state served as an empty payload view rather than a 404 (ADR-0050 §7).
     */
    public static final String EXCHANGE_AUDIT_NOT_FOUND = "SUPPLIER_EXCHANGE_AUDIT_NOT_FOUND";

    /**
     * No purchase-order transmission intent with that id (ADR-0052 §1).
     *
     * <p>Distinct from {@link #EXCHANGE_AUDIT_NOT_FOUND}, which is about a single vendor call. A
     * transmission is the whole intent to place one order, spanning many calls, and an operator
     * chasing a missing order needs to know which of the two the system cannot find.
     */
    public static final String TRANSMISSION_NOT_FOUND = "SUPPLIER_TRANSMISSION_NOT_FOUND";

    /**
     * No stock snapshot with that id for the addressed vendor profile — or, for the latest-snapshot
     * read, no snapshot at all (CAP-322).
     *
     * <p>Deliberately one code for both "no such snapshot" and "that snapshot belongs to a different
     * profile": a snapshot is addressed under its profile, and confirming that an id exists under
     * some <em>other</em> profile would leak another trading relationship's fetch history.
     */
    public static final String STOCK_SNAPSHOT_NOT_FOUND = "SUPPLIER_STOCK_SNAPSHOT_NOT_FOUND";

    /**
     * The availability read could not resolve the named product identity to any vendor-queryable
     * code in the local catalog replica (#1637 decision 1): the product is unknown here, or is
     * known but carries no EAN/UPC code — including a SKU the replica has not learned yet.
     *
     * <p>404 rather than a degraded 200: a vendor being down is an answer, but a product this
     * module cannot even name to a vendor is a question it cannot ask, and pretending every vendor
     * said NOT_LISTED would send an operator chasing vendor listings when the fix is in the
     * catalog.
     */
    public static final String PRODUCT_CODES_NOT_FOUND = "SUPPLIER_PRODUCT_CODES_NOT_FOUND";

    private final String code;

    public SupplierNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
