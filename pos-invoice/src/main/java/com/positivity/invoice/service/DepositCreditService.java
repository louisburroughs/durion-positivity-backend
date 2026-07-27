package com.positivity.invoice.service;

import com.positivity.invoice.internal.enums.DepositSourceType;
import com.positivity.invoice.service.model.CreateDepositCommand;
import com.positivity.invoice.service.model.DepositCreditSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Deposit / down-payment credits (odoo-parity story E4, issue #1085, spec R7.4). The dedicated
 * artifact resolving gate V2: a deposit is a first-class credit with typed source provenance, an
 * original/remaining balance for partial draw-down, and an application-audit trail — capabilities an
 * {@code InvoiceAdjustment} cannot provide.
 */
public interface DepositCreditService {

    /** Registers a deposit taken by a pos-order sale, idempotent on the taking {@code orderId}. */
    @NonNull
    DepositCreditSummary createDeposit(@NonNull CreateDepositCommand command);

    /**
     * Applies available deposit credits held against a source document to a settlement invoice,
     * FIFO up to {@code invoiceTotal}, writing an application-audit row and drawing down each
     * credit's remaining balance. Idempotent per (credit, invoice). Returns the total applied.
     */
    @NonNull
    BigDecimal applyAvailableCredits(
            @NonNull DepositSourceType sourceType,
            @NonNull UUID sourceId,
            @NonNull UUID invoiceId,
            @NonNull BigDecimal invoiceTotal);

    /**
     * Refunds a deposit credit's remaining balance (source cancelled after deposit, spec R7.4):
     * marks it REFUNDED and zeroes the remaining balance. Returns the refunded amount. Idempotent —
     * an already-REFUNDED credit returns zero.
     */
    @NonNull
    BigDecimal refundDeposit(@NonNull UUID depositCreditId, @NonNull String reason);

    /** Fetches a deposit credit by id (404 if unknown). */
    @NonNull
    DepositCreditSummary getDeposit(@NonNull UUID depositCreditId);

    /** All deposit credits held against a source document. */
    @NonNull
    List<DepositCreditSummary> listBySource(@NonNull DepositSourceType sourceType, @NonNull UUID sourceId);
}
