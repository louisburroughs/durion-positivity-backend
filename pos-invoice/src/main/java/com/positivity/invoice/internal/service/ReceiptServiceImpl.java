package com.positivity.invoice.internal.service;

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
import com.positivity.invoice.service.Receipt;
import com.positivity.invoice.service.ReceiptService;
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

    private static final String GENERATE_RECEIPT = "GENERATE_RECEIPT";
    private static final String SUPERVISOR_OVERRIDE = "SUPERVISOR_OVERRIDE";
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
        requireAuthority(GENERATE_RECEIPT);

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

        if (receipt.getReprintCount() >= 5 && !SecurityContextHelper.hasAuthority(SUPERVISOR_OVERRIDE)) {
            throw new ReprintLimitExceededException("Reprint limit of 5 exceeded");
        }

        receipt.setReprintCount(receipt.getReprintCount() + 1);
        receipt.setLastReprintReason(reason);
        receipt.setLastReprintedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        return toServiceReceipt(receiptRepository.save(receipt));
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
