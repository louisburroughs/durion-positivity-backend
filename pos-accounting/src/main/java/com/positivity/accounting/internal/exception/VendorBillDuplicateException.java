package com.positivity.accounting.internal.exception;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import java.io.Serial;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A vendor bill refused because a live bill already holds the same vendor, normalised bill number
 * and bill date (#2501; ADR-0070 Decision 4). Maps to 409 {@code AP_BILL_DUPLICATE}; it carries the
 * original so the answer can link to it.
 *
 * <p>The message names the original by business reference only (ADR-0064): its number, its vendor
 * and its date, never an id. The id travels separately, as the envelope's {@code referenceId}.
 */
@Getter
public class VendorBillDuplicateException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID originalBillId;
    private final String originalBillNumber;
    private final @Nullable String originalVendorName;
    private final LocalDate originalBillDate;
    private final VendorBillStatus originalStatus;

    public VendorBillDuplicateException(@NonNull VendorBill original) {
        super(describe(original));
        this.originalBillId = original.getVendorBillId();
        this.originalBillNumber = original.getBillNumber();
        this.originalVendorName = original.getVendorName();
        this.originalBillDate = original.getBillDate().toLocalDate();
        this.originalStatus = original.getStatus();
    }

    private static String describe(VendorBill original) {
        String vendor =
                original.getVendorName() == null || original.getVendorName().isBlank()
                        ? "this vendor"
                        : original.getVendorName().trim();
        return "Bill " + original.getBillNumber() + " from " + vendor + " dated "
                + original.getBillDate().toLocalDate() + " already exists (" + original.getStatus() + ")";
    }
}
