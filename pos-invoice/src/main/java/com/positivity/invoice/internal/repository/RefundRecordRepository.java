package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.RefundRecord;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRecordRepository extends JpaRepository<RefundRecord, UUID> {

    List<RefundRecord> findByPaymentIntent_Id(@NonNull UUID paymentIntentId);

    List<RefundRecord> findByInvoice_Id(@NonNull UUID invoiceId);

    List<RefundRecord> findByPartyIdAndPaymentIntentIsNull(@NonNull String partyId);

    /**
     * All refund records anchored to an invoice, whichever way the anchor runs (#922 warranty
     * reconciliation): directly via {@code invoice_id} (payment-anchored refunds stamp the
     * intent's invoice; standalone invoice refunds anchor there too) or transitively via a
     * payment intent belonging to the invoice. LEFT JOINs keep rows whose other anchor is
     * null out of an inner-join elimination; requestedAt ascending gives a stable audit order.
     */
    @Query("SELECT DISTINCT r FROM RefundRecord r "
            + "LEFT JOIN r.invoice inv "
            + "LEFT JOIN r.paymentIntent pi "
            + "LEFT JOIN pi.invoice piInv "
            + "WHERE inv.id = :invoiceId OR piInv.id = :invoiceId "
            + "ORDER BY r.requestedAt ASC")
    @NonNull
    List<RefundRecord> findAllAnchoredToInvoice(@Param("invoiceId") @NonNull UUID invoiceId);
}
