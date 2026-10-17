package com.positivity.inventory.internal.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A vendor feed mapping request was refused (CAP:550 S36, #2525; ADR-0017 per-case error codes): each case carries its
 * own code and HTTP status, mapped by {@code InventoryGlobalExceptionHandler}.
 */
public class VendorFeedMappingException extends RuntimeException {

    public static final String EXISTS = "VENDOR_FEED_MAPPING_EXISTS";
    public static final String NOT_FOUND = "VENDOR_FEED_MAPPING_NOT_FOUND";
    public static final String VENDOR_NOT_FOUND = "VENDOR_NOT_FOUND";
    public static final String VENDOR_INACTIVE = "VENDOR_INACTIVE";
    public static final String STALE_VERSION = "CONFLICT";
    public static final String INVALID_FEED_VENDOR_REF = "VALIDATION_ERROR";

    private final String errorCode;
    private final HttpStatus status;

    private VendorFeedMappingException(String errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** The feed vendor already has a mapping (409). */
    public static VendorFeedMappingException exists(String feedType, String feedVendorRef) {
        return new VendorFeedMappingException(
                EXISTS,
                HttpStatus.CONFLICT,
                "The " + feedType + " feed vendor " + feedVendorRef + " already has a mapping; update it instead");
    }

    /** No mapping has the id (404). */
    public static VendorFeedMappingException notFound(UUID mappingId) {
        return new VendorFeedMappingException(
                NOT_FOUND, HttpStatus.NOT_FOUND, "Vendor feed mapping not found: " + mappingId);
    }

    /** The named vendor is not in the local copy of the pos-supplier vendor master (422). */
    public static VendorFeedMappingException vendorNotFound(UUID vendorId) {
        return new VendorFeedMappingException(
                VENDOR_NOT_FOUND,
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Vendor " + vendorId + " is not a known pos-supplier vendor");
    }

    /** The named vendor is INACTIVE and may not be named by a mapping (422). */
    public static VendorFeedMappingException vendorInactive(UUID vendorId) {
        return new VendorFeedMappingException(
                VENDOR_INACTIVE,
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Vendor " + vendorId + " is inactive; map the feed vendor to an active vendor");
    }

    /** The request carried a version older than the stored mapping's (409). */
    public static VendorFeedMappingException staleVersion(UUID mappingId) {
        return new VendorFeedMappingException(
                STALE_VERSION,
                HttpStatus.CONFLICT,
                "Vendor feed mapping " + mappingId + " was changed by someone else; reload it and retry");
    }

    /** The feed vendor reference is blank or, for a manufacturer, not a UUID (400). */
    public static VendorFeedMappingException invalidFeedVendorRef(String reason) {
        return new VendorFeedMappingException(INVALID_FEED_VENDOR_REF, HttpStatus.BAD_REQUEST, reason);
    }
}
