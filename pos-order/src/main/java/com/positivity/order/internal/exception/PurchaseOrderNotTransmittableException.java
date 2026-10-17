package com.positivity.order.internal.exception;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when a purchase order cannot be sent to its vendor (CAP-320 #1330).
 *
 * <p>Every case here is a refusal to guess. A transmission that goes out with the wrong vendor,
 * the wrong article, or a second time is not an error the system notices later — it is a delivery
 * that arrives wrong, or twice, and the first anyone knows of it is the lorry.
 */
public class PurchaseOrderNotTransmittableException extends RuntimeException {

    private final String errorCode;
    private final @Nullable String referenceId;
    private final @Nullable String nextAction;
    private final @Nullable String supportAction;

    private PurchaseOrderNotTransmittableException(String errorCode, String message) {
        this(errorCode, message, null, null, null);
    }

    private PurchaseOrderNotTransmittableException(
            String errorCode,
            String message,
            @Nullable String referenceId,
            @Nullable String nextAction,
            @Nullable String supportAction) {
        super(message);
        this.errorCode = errorCode;
        this.referenceId = referenceId;
        this.nextAction = nextAction;
        this.supportAction = supportAction;
    }

    /**
     * The vendor has no purchasing channel (#2713, Order ruling on #2682): the copy holds the vendor, so its absence
     * is a fact about the vendor (422), not replication lag. Names the vendor, the vendor id as {@code referenceId},
     * and what the buyer and the administrator each do next.
     */
    public static PurchaseOrderNotTransmittableException noPurchasingChannel(
            @NonNull UUID vendorId, @NonNull String vendorNumber, @NonNull String displayName) {
        return new PurchaseOrderNotTransmittableException(
                "SUPPLIER_REF_MISSING",
                "Vendor " + vendorNumber + " " + displayName + " has no purchase-order channel",
                vendorId.toString(),
                "Ask an administrator to designate a purchasing profile for this vendor in pos-supplier, then send"
                        + " the order again",
                "In pos-supplier, give the vendor exactly one enabled, non-sandbox transmission profile (or designate"
                        + " one); the vendor fact republishes and the order can be sent");
    }

    /** The order names no supplier profile, so there is nobody to send it to. */
    public static PurchaseOrderNotTransmittableException noSupplierRef() {
        return new PurchaseOrderNotTransmittableException(
                "SUPPLIER_REF_MISSING",
                "SUPPLIER_REF_MISSING: the purchase order names no supplier profile, so it cannot be transmitted");
    }

    /** Only an approved order may be sent; a draft is not yet a commitment. */
    public static PurchaseOrderNotTransmittableException notApproved(String status) {
        return new PurchaseOrderNotTransmittableException(
                "PURCHASE_ORDER_NOT_APPROVED",
                "PURCHASE_ORDER_NOT_APPROVED: an order in " + status + " has not been committed to and cannot be sent");
    }

    /**
     * A transmission is already in flight.
     *
     * <p>Refused here rather than left to pos-supplier's active-intent guard, which is a backstop
     * and not the design. A blind re-send is how one purchase order becomes two deliveries
     * (ADR-0052), and the domain that knows why the order exists is the one that must decline.
     */
    public static PurchaseOrderNotTransmittableException alreadyInFlight() {
        return new PurchaseOrderNotTransmittableException(
                "TRANSMISSION_IN_FLIGHT",
                "TRANSMISSION_IN_FLIGHT: this order has already been sent and no answer has come back yet;"
                        + " re-sending it would risk a second delivery");
    }

    /** An operator must settle the previous transmission before another can be raised. */
    public static PurchaseOrderNotTransmittableException awaitingManualReview() {
        return new PurchaseOrderNotTransmittableException(
                "TRANSMISSION_AWAITING_REVIEW",
                "TRANSMISSION_AWAITING_REVIEW: whether the vendor received the previous send is unresolved;"
                        + " it must be settled before this order is sent again");
    }

    /**
     * One or more lines name no article the vendor could recognise.
     *
     * <p>The whole order is refused rather than the lines being withheld. Withholding would send a
     * order that silently differs from the one on screen, and the gap would surface as a short
     * delivery weeks later; refusing surfaces it now, to the person who can fix the article data.
     */
    public static PurchaseOrderNotTransmittableException articlesNotIdentifiable(List<Integer> lineNumbers) {
        return new PurchaseOrderNotTransmittableException(
                "ARTICLE_NOT_IDENTIFIABLE",
                "ARTICLE_NOT_IDENTIFIABLE: line(s) " + lineNumbers
                        + " carry no article code the vendor would recognise; the order was not sent");
    }

    /**
     * A line's quantity is not a whole number.
     *
     * <p>The order command carries an integer quantity, so a fractional line cannot be stated to
     * the vendor at all. Rounding it would order an amount nobody asked for — and be discovered as
     * a delivery of the wrong size — so the order is refused and the buyer decides.
     */
    public static PurchaseOrderNotTransmittableException fractionalQuantity(List<Integer> lineNumbers) {
        return new PurchaseOrderNotTransmittableException(
                "FRACTIONAL_QUANTITY",
                "FRACTIONAL_QUANTITY: line(s) " + lineNumbers
                        + " carry a quantity that is not a whole number, which the vendor order cannot express;"
                        + " the order was not sent");
    }

    /**
     * The deployment has no event publishing wired, so nothing can reach the vendor.
     *
     * <p>Raised rather than quietly doing nothing. A publisher that silently no-ops would leave the
     * order marked as sent and the buyer waiting on goods nobody ever asked for.
     */
    public static PurchaseOrderNotTransmittableException publishingUnavailable() {
        return new PurchaseOrderNotTransmittableException(
                "TRANSMISSION_UNAVAILABLE",
                "TRANSMISSION_UNAVAILABLE: this deployment cannot send orders to vendors, so the order was"
                        + " not sent and has not been marked as sent");
    }

    public @Nullable String getReferenceId() {
        return referenceId;
    }

    public @Nullable String getNextAction() {
        return nextAction;
    }

    public @Nullable String getSupportAction() {
        return supportAction;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
