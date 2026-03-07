package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.ReceiptDeliveryMethod;
import com.positivity.invoice.internal.enums.ReceiptDeliveryStatus;
import com.positivity.invoice.internal.enums.ReceiptStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@Table(name = "receipts")
public class Receipt {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", columnDefinition = "UUID", nullable = false)
    private UUID invoiceId;

    @Column(name = "payment_intent_id", columnDefinition = "UUID", nullable = false)
    private UUID paymentIntentId;

    @Column(name = "reference", nullable = false, unique = true, length = 64)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReceiptStatus status;

    @Column(name = "template_id", nullable = false, length = 128)
    private String templateId;

    @Column(name = "template_version", nullable = false, length = 32)
    private String templateVersion;

    @Column(name = "cashier_id", nullable = false, length = 128)
    private String cashierId;

    @Column(name = "terminal_id", nullable = false, length = 64)
    private String terminalId;

    @Column(name = "reprint_count", nullable = false)
    private int reprintCount = 0;

    @Column(name = "last_reprint_reason", length = 256)
    private String lastReprintReason;

    @Column(name = "last_reprinted_by", length = 128)
    private String lastReprintedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", length = 10)
    private ReceiptDeliveryMethod deliveryMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 10)
    private ReceiptDeliveryStatus deliveryStatus;

    @Column(name = "delivery_email_address", length = 256)
    private String deliveryEmailAddress;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
