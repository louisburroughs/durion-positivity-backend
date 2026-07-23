package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.config.InvoiceEventPublisher;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.InvoiceItem;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.service.OrderInvoiceService;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceLineItem;
import com.positivity.shared.dto.OrderInvoiceResponse;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * From-order invoice creation (order parity story C2, #1070). The order's monetary figures are
 * authoritative — pos-order priced the lines and computed tax via pos-tax at checkout — so this
 * path records them verbatim instead of re-running the draft tax calculator; the workorder
 * finalization pipeline ({@code InvoiceFinalizationService}) is deliberately not reused
 * (resolved Q1).
 */
@Slf4j
@Service
@Transactional
public class OrderInvoiceServiceImpl implements OrderInvoiceService {

    private final Clock clock;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceEventPublisher invoiceEventPublisher;

    public OrderInvoiceServiceImpl(
            @NonNull InvoiceRepository invoiceRepository,
            @NonNull InvoiceEventPublisher invoiceEventPublisher,
            Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceEventPublisher = invoiceEventPublisher;
        this.clock = clock;
    }

    @Override
    @NonNull
    public OrderInvoiceResponse createInvoiceForOrder(@NonNull OrderInvoiceCreationRequest request) {
        if (request.getOrderId() == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        validateTotals(request);

        Invoice replayed = invoiceRepository.findByOrderId(request.getOrderId()).orElse(null);
        if (replayed != null) {
            return toResponse(replayed, true);
        }

        // Settlement-path dedupe (spec R7.2): a workorder-linked order tenders the workorder's
        // existing invoice instead of creating a duplicate financial document.
        if (request.getWorkorderId() != null) {
            Invoice workorderInvoice = invoiceRepository
                    .findByWorkorderId(request.getWorkorderId())
                    .orElse(null);
            if (workorderInvoice != null) {
                if (workorderInvoice.getOrderId() == null) {
                    workorderInvoice.setOrderId(request.getOrderId());
                    workorderInvoice = invoiceRepository.save(workorderInvoice);
                }
                return toResponse(workorderInvoice, true);
            }
        }

        Invoice invoice = new Invoice();
        invoice.setOrderId(request.getOrderId());
        invoice.setWorkorderId(request.getWorkorderId());
        invoice.setLocationId(request.getLocationId());
        // Canonical lowercase form (UUID.toString()) — party-scoped lookups match on the string
        // column; null for anonymous counter sales.
        invoice.setPartyId(
                request.getCustomerId() == null ? null : request.getCustomerId().toString());
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setAdjustments(BigDecimal.ZERO);
        invoice.setAdjustmentsAmount(BigDecimal.ZERO);

        for (OrderInvoiceLineItem line : request.getLines()) {
            InvoiceItem item = new InvoiceItem();
            String description = line.getDescription();
            item.setDescription(description == null || description.isBlank() ? "Order line item" : description.trim());
            item.setQuantity(money(line.getQuantity(), BigDecimal.ONE));
            item.setUnitPrice(money(line.getUnitPrice(), BigDecimal.ZERO));
            item.setLineTotal(money(line.getAmount(), BigDecimal.ZERO));
            item.setType(line.getType());
            invoice.addItem(item);
        }

        invoice.setSubtotal(money(request.getSubtotal(), BigDecimal.ZERO));
        invoice.setTax(money(request.getTaxAmount(), BigDecimal.ZERO));
        invoice.setTotal(money(request.getTotalAmount(), BigDecimal.ZERO));

        Invoice saved = invoiceRepository.save(invoice);
        if (saved.getInvoiceNumber() == null || saved.getInvoiceNumber().isBlank()) {
            saved.setInvoiceNumber(generateInvoiceNumber(saved));
            saved = invoiceRepository.save(saved);
        }
        invoiceEventPublisher.publishInvoiceUpdated(saved);
        log.info("Created invoice {} for order {}", saved.getId(), request.getOrderId());
        return toResponse(saved, false);
    }

    /** The order's figures must be internally consistent before they become a financial document. */
    private static void validateTotals(@NonNull OrderInvoiceCreationRequest request) {
        BigDecimal subtotal = money(request.getSubtotal(), null);
        BigDecimal tax = money(request.getTaxAmount(), null);
        BigDecimal total = money(request.getTotalAmount(), null);
        if (subtotal == null || tax == null || total == null) {
            throw new IllegalArgumentException("subtotal, taxAmount, and totalAmount are required");
        }
        if (total.signum() < 0) {
            throw new IllegalArgumentException("totalAmount cannot be negative");
        }
        List<OrderInvoiceLineItem> lines = request.getLines();
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }
        // Cent-exact invariants: pos-order sends line amounts net of all discounts, so the
        // figures must reconcile exactly before they become a financial document.
        BigDecimal lineSum = lines.stream()
                .map(line -> money(line.getAmount(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (lineSum.compareTo(subtotal) != 0) {
            throw new IllegalArgumentException(
                    "line amounts (" + lineSum + ") do not sum to subtotal (" + subtotal + ")");
        }
        if (subtotal.add(tax).compareTo(total) != 0) {
            throw new IllegalArgumentException(
                    "subtotal (" + subtotal + ") + taxAmount (" + tax + ") does not equal totalAmount (" + total + ")");
        }
    }

    @Nullable
    private static BigDecimal money(@Nullable BigDecimal value, @Nullable BigDecimal fallback) {
        BigDecimal resolved = value != null ? value : fallback;
        return resolved == null ? null : resolved.setScale(4, RoundingMode.HALF_UP);
    }

    @NonNull
    private String generateInvoiceNumber(@NonNull Invoice invoice) {
        String idPart = invoice.getId() == null
                ? UUIDv7Generator.generate().toString().substring(0, 8)
                : invoice.getId().toString().substring(0, 8);
        return "INV-" + Instant.now(clock).toEpochMilli() + "-" + idPart;
    }

    @NonNull
    private static OrderInvoiceResponse toResponse(@NonNull Invoice invoice, boolean existing) {
        return OrderInvoiceResponse.builder()
                .invoiceId(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .status(invoice.getStatus().name())
                .subtotal(invoice.getSubtotal())
                .taxAmount(invoice.getTax())
                .totalAmount(invoice.getTotal())
                .existing(existing)
                .build();
    }
}
