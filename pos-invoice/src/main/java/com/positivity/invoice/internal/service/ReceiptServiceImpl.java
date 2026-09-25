package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.ReceiptViewResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.enums.ReceiptDeliveryMethod;
import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import com.positivity.invoice.internal.enums.ReceiptStatus;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.exception.ReceiptNotFoundException;
import com.positivity.invoice.internal.exception.ReprintLimitExceededException;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.PaymentIntentRepository;
import com.positivity.invoice.internal.repository.ReceiptRepository;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReceiptServiceImpl implements ReceiptService {

    private static final String RECEIPT_NOT_FOUND_PREFIX = "Receipt not found: ";
    private static final int INITIAL_REFERENCE_SEQUENCE = 1;

    private final ReceiptRepository receiptRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final Clock clock;

    public ReceiptServiceImpl(
            @NonNull ReceiptRepository receiptRepository,
            @NonNull InvoiceRepository invoiceRepository,
            @NonNull PaymentIntentRepository paymentIntentRepository,
            @NonNull Clock clock) {
        this.receiptRepository = receiptRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.clock = clock;
    }

    @Override
    @NonNull
    public Receipt generateReceipt(
            @NonNull UUID invoiceId,
            @NonNull UUID paymentIntentId,
            @NonNull String terminalId,
            @NonNull String templateId,
            @NonNull String templateVersion) {
        requireAuthority(InvoicePermissions.RECEIPT_GENERATE);

        Invoice invoice =
                invoiceRepository.findById(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        PaymentIntent paymentIntent = paymentIntentRepository
                .findById(paymentIntentId)
                .orElseThrow(() -> new PaymentIntentNotFoundException("Payment intent not found: " + paymentIntentId));
        if (paymentIntent.getInvoice() == null
                || !invoiceId.equals(paymentIntent.getInvoice().getId())) {
            throw new PaymentIntentNotFoundException(
                    "Payment intent " + paymentIntentId + " not found under invoice " + invoiceId);
        }

        // ADR-0061 §3 (#2226): after the existence check, before the receipt is created.
        SecurityContextHelper.locationScope()
                .require(
                        InvoicePermissions.RECEIPT_GENERATE,
                        invoice.getLocationId() == null
                                ? ""
                                : invoice.getLocationId().toString());

        String cashierId = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        int nextReferenceSequence =
                Math.toIntExact(receiptRepository.countByInvoice_Id(invoiceId) + INITIAL_REFERENCE_SEQUENCE);
        String referenceSuffix = String.format("%03d", nextReferenceSequence);

        String reference = "RCP-" + invoice.getInvoiceNumber() + "-"
                + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now(clock))
                + "-" + referenceSuffix;

        com.positivity.invoice.internal.entity.Receipt receiptEntity =
                new com.positivity.invoice.internal.entity.Receipt();
        receiptEntity.setInvoice(invoice);
        receiptEntity.setPaymentIntent(paymentIntent);
        receiptEntity.setCashierId(cashierId);
        receiptEntity.setTerminalId(terminalId);
        receiptEntity.setTemplateId(templateId);
        receiptEntity.setTemplateVersion(templateVersion);
        receiptEntity.setReference(reference);
        receiptEntity.setStatus(ReceiptStatus.GENERATED);
        receiptEntity.setReprintCount(0);

        return toServiceReceipt(receiptRepository.save(receiptEntity));
    }

    @Override
    public void recordPrintDelivery(@NonNull UUID receiptId, @NonNull ReceiptDeliveryStatus status) {
        com.positivity.invoice.internal.entity.Receipt receipt = receiptRepository
                .findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(RECEIPT_NOT_FOUND_PREFIX + receiptId));

        receipt.setDeliveryMethod(ReceiptDeliveryMethod.PRINT);
        receipt.setDeliveryStatus(status);
        receiptRepository.save(receipt);
    }

    @Override
    public void sendEmailReceipt(
            @NonNull UUID receiptId, @NonNull String emailAddress, @NonNull ReceiptDeliveryStatus status) {
        com.positivity.invoice.internal.entity.Receipt receipt = receiptRepository
                .findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(RECEIPT_NOT_FOUND_PREFIX + receiptId));

        receipt.setDeliveryMethod(ReceiptDeliveryMethod.EMAIL);
        receipt.setDeliveryEmailAddress(emailAddress);
        receipt.setDeliveryStatus(status);
        receiptRepository.save(receipt);
    }

    @Override
    @NonNull
    public Receipt reprintReceipt(@NonNull UUID receiptId, @NonNull String reason) {
        com.positivity.invoice.internal.entity.Receipt receipt = receiptRepository
                .findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(RECEIPT_NOT_FOUND_PREFIX + receiptId));

        if (receipt.getReprintCount() >= 5) {
            // ADR-0061 §3 (#2226): only reached once the cap is exceeded, so an ordinary
            // under-cap reprint never pays for a location check it does not need. A caller who
            // does not hold invoice:receipt:reprint_override at all takes no scope decision here
            // (LocationScope#require is a no-op for an alternate not held) and falls straight
            // into the authority check below; a supervisor override is itself location-bound
            // like every other elevation, so a holder scoped away from this invoice is denied
            // even before the authority check runs.
            UUID receiptInvoiceLocation =
                    receipt.getInvoice() == null ? null : receipt.getInvoice().getLocationId();
            SecurityContextHelper.locationScope()
                    .require(
                            InvoicePermissions.RECEIPT_REPRINT_OVERRIDE,
                            receiptInvoiceLocation == null ? "" : receiptInvoiceLocation.toString());
            if (!SecurityContextHelper.hasAuthority(InvoicePermissions.RECEIPT_REPRINT_OVERRIDE)) {
                throw new ReprintLimitExceededException("Reprint limit of 5 exceeded");
            }
        }

        receipt.setReprintCount(receipt.getReprintCount() + 1);
        receipt.setLastReprintReason(reason);
        receipt.setLastReprintedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        return toServiceReceipt(receiptRepository.save(receipt));
    }

    @Override
    @NonNull
    public ReceiptViewResponse getReceipt(@NonNull UUID invoiceId, @NonNull UUID receiptId) {
        com.positivity.invoice.internal.entity.Receipt receipt = receiptRepository
                .findByIdAndInvoice_Id(receiptId, invoiceId)
                .orElseThrow(() -> new ReceiptNotFoundException(RECEIPT_NOT_FOUND_PREFIX + receiptId));

        // ADR-0061 §3 (#1872), mirrors InvoiceServiceImpl.loadInvoiceDetail: scope check lives here,
        // after the existence check, so a denial cannot be used to probe which receipt/invoice ids
        // exist. A receipt whose invoice has no location fails closed for a scoped caller; an
        // unscoped or pre-rollout caller is unchanged.
        UUID invoiceLocation =
                receipt.getInvoice() == null ? null : receipt.getInvoice().getLocationId();
        SecurityContextHelper.locationScope()
                .require(InvoicePermissions.VIEW, invoiceLocation == null ? "" : invoiceLocation.toString());

        PaymentIntent paymentIntent = receipt.getPaymentIntent();

        ReceiptViewResponse view = new ReceiptViewResponse();
        view.setReceiptId(receipt.getId());
        view.setReference(receipt.getReference());
        view.setStatus(receipt.getStatus());
        view.setInvoiceId(invoiceId);
        view.setInvoiceNumber(
                receipt.getInvoice() == null ? null : receipt.getInvoice().getInvoiceNumber());
        view.setPaymentIntentId(paymentIntent == null ? null : paymentIntent.getId());
        view.setPaidAmount(paymentIntent == null ? null : paymentIntent.getCapturedAmount());
        view.setPaymentMethod(paymentIntent == null ? null : paymentIntent.getGatewayProvider());
        view.setGatewayReference(paymentIntent == null ? null : paymentIntent.getGatewayReference());
        view.setCashierId(receipt.getCashierId());
        view.setTerminalId(receipt.getTerminalId());
        view.setTemplateId(receipt.getTemplateId());
        view.setTemplateVersion(receipt.getTemplateVersion());
        view.setDeliveryMethod(receipt.getDeliveryMethod());
        view.setDeliveryStatus(receipt.getDeliveryStatus());
        view.setDeliveryEmailAddress(receipt.getDeliveryEmailAddress());
        view.setReprintCount(receipt.getReprintCount());
        view.setLastReprintReason(receipt.getLastReprintReason());
        view.setLastReprintedBy(receipt.getLastReprintedBy());
        view.setCreatedAt(receipt.getCreatedAt());
        return view;
    }

    private static Receipt toServiceReceipt(com.positivity.invoice.internal.entity.Receipt entity) {
        Receipt receipt = new Receipt();
        receipt.setId(entity.getId());
        receipt.setInvoiceId(
                entity.getInvoice() == null ? null : entity.getInvoice().getId());
        receipt.setPaymentIntentId(
                entity.getPaymentIntent() == null
                        ? null
                        : entity.getPaymentIntent().getId());
        receipt.setCashierId(entity.getCashierId());
        receipt.setReference(entity.getReference());
        receipt.setStatus(entity.getStatus());
        receipt.setReprintCount(entity.getReprintCount());
        receipt.setLastReprintReason(entity.getLastReprintReason());
        receipt.setLastReprintedBy(entity.getLastReprintedBy());
        return receipt;
    }

    private void requireAuthority(String authority) {
        if (!SecurityContextHelper.hasAuthority(authority)) {
            throw new AccessDeniedException("Missing authority: " + authority);
        }
    }
}
