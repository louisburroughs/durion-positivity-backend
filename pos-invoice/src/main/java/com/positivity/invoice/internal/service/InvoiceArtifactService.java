package com.positivity.invoice.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.invoice.internal.client.DocumentRenderClient;
import com.positivity.invoice.internal.dto.ArtifactDownloadTokenResponse;
import com.positivity.invoice.internal.dto.InvoiceArtifactResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.Receipt;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.ReceiptRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists, authorizes, and renders the downloadable documents (artifacts) for an invoice.
 *
 * <p>Artifacts are not stored: the listing is derived from the invoice and its receipts, and the
 * PDF bytes are rendered on demand by pos-documents. Downloads are authorized by a short-lived
 * signed token (see {@link ArtifactTokenService}).
 */
@Service
@Transactional(readOnly = true)
public class InvoiceArtifactService {

    private static final String INVOICE_TEMPLATE_ID = "invoice-default";
    private static final String RECEIPT_TEMPLATE_ID = "receipt-default";

    private final InvoiceRepository invoiceRepository;
    private final ReceiptRepository receiptRepository;
    private final DocumentRenderClient documentRenderClient;
    private final ArtifactTokenService tokenService;
    private final ObjectMapper objectMapper;

    public InvoiceArtifactService(
            InvoiceRepository invoiceRepository,
            ReceiptRepository receiptRepository,
            DocumentRenderClient documentRenderClient,
            ArtifactTokenService tokenService,
            ObjectMapper objectMapper) {
        this.invoiceRepository = invoiceRepository;
        this.receiptRepository = receiptRepository;
        this.documentRenderClient = documentRenderClient;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    /** List the artifacts available for an invoice: the invoice document plus each receipt. */
    @NonNull
    public List<InvoiceArtifactResponse> listArtifacts(@NonNull UUID invoiceId) {
        Invoice invoice = requireInvoice(invoiceId);

        List<InvoiceArtifactResponse> artifacts = new ArrayList<>();
        artifacts.add(new InvoiceArtifactResponse(
                new ArtifactRef(ArtifactRef.Type.INVOICE, invoiceId).encode(),
                invoiceFileName(invoice),
                "application/pdf",
                invoice.getCreatedAt()));

        for (Receipt receipt : receiptRepository.findByInvoice_IdOrderByCreatedAtAsc(invoiceId)) {
            artifacts.add(new InvoiceArtifactResponse(
                    new ArtifactRef(ArtifactRef.Type.RECEIPT, receipt.getId()).encode(),
                    "Receipt-" + receipt.getReference() + ".pdf",
                    "application/pdf",
                    receipt.getCreatedAt()));
        }
        return artifacts;
    }

    /** Issue a short-lived download token for an artifact, after validating it belongs to the invoice. */
    @NonNull
    public ArtifactDownloadTokenResponse createDownloadToken(@NonNull UUID invoiceId, @NonNull String artifactRefId) {
        resolveAndValidate(invoiceId, artifactRefId);
        ArtifactTokenService.IssuedToken issued = tokenService.issue(invoiceId, artifactRefId);
        return new ArtifactDownloadTokenResponse(issued.token(), issued.expiresAt());
    }

    /** Verify the download token and render the artifact to PDF bytes. */
    @NonNull
    public byte[] downloadArtifact(@NonNull UUID invoiceId, @NonNull String artifactRefId, @NonNull String token) {
        tokenService.verify(token, invoiceId, artifactRefId);
        ArtifactRef ref = resolveAndValidate(invoiceId, artifactRefId);
        return switch (ref.type()) {
            case INVOICE ->
                documentRenderClient.renderPdf(INVOICE_TEMPLATE_ID, invoiceContent(requireInvoice(invoiceId)));
            case RECEIPT ->
                documentRenderClient.renderPdf(RECEIPT_TEMPLATE_ID, receiptContent(requireReceipt(ref.id())));
        };
    }

    /** Suggested file name for a downloaded artifact. */
    @NonNull
    public String fileNameFor(@NonNull UUID invoiceId, @NonNull String artifactRefId) {
        ArtifactRef ref = resolveAndValidate(invoiceId, artifactRefId);
        return switch (ref.type()) {
            case INVOICE -> invoiceFileName(requireInvoice(invoiceId));
            case RECEIPT -> "Receipt-" + requireReceipt(ref.id()).getReference() + ".pdf";
        };
    }

    private ArtifactRef resolveAndValidate(UUID invoiceId, String artifactRefId) {
        ArtifactRef ref = ArtifactRef.decode(artifactRefId);
        switch (ref.type()) {
            case INVOICE -> {
                if (!ref.id().equals(invoiceId)) {
                    throw new ArtifactNotFoundException("Artifact does not belong to invoice " + invoiceId);
                }
                requireInvoice(invoiceId);
            }
            case RECEIPT -> {
                Receipt receipt = requireReceipt(ref.id());
                UUID receiptInvoiceId = receipt.getInvoice() == null
                        ? null
                        : receipt.getInvoice().getId();
                if (!invoiceId.equals(receiptInvoiceId)) {
                    throw new ArtifactNotFoundException("Receipt does not belong to invoice " + invoiceId);
                }
            }
        }
        return ref;
    }

    private Invoice requireInvoice(UUID invoiceId) {
        return invoiceRepository.findById(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
    }

    private Receipt requireReceipt(UUID receiptId) {
        return receiptRepository
                .findById(receiptId)
                .orElseThrow(() -> new ArtifactNotFoundException("Receipt not found: " + receiptId));
    }

    private String invoiceFileName(Invoice invoice) {
        String number = invoice.getInvoiceNumber() == null ? invoice.getId().toString() : invoice.getInvoiceNumber();
        return "Invoice-" + number + ".pdf";
    }

    private String invoiceContent(Invoice invoice) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("documentType", "INVOICE");
        content.put("invoiceId", invoice.getId());
        content.put("invoiceNumber", invoice.getInvoiceNumber());
        content.put("status", invoice.getStatus());
        content.put("subtotal", invoice.getSubtotal());
        content.put("tax", invoice.getTax());
        content.put("adjustments", invoice.getAdjustmentsAmount());
        content.put("total", invoice.getTotal());
        content.put("createdAt", invoice.getCreatedAt());
        return toJson(content);
    }

    private String receiptContent(Receipt receipt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("documentType", "RECEIPT");
        content.put("receiptId", receipt.getId());
        content.put("reference", receipt.getReference());
        content.put("status", receipt.getStatus());
        content.put("createdAt", receipt.getCreatedAt());
        return toJson(content);
    }

    private String toJson(Map<String, Object> content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialise artifact render content", ex);
        }
    }

    /** Raised when a requested artifact does not exist or does not belong to the invoice. */
    public static class ArtifactNotFoundException extends RuntimeException {
        public ArtifactNotFoundException(String message) {
            super(message);
        }
    }
}
