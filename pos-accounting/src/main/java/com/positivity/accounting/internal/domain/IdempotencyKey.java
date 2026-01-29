package com.positivity.accounting.internal.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entity for tracking idempotency keys to prevent duplicate payment processing.
 */
@Entity
@Table(name = "idempotency_keys", indexes = {
    @Index(name = "idx_key_value", columnList = "keyValue", unique = true)
})
public class IdempotencyKey {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    private String keyValue;
    
    @Column(nullable = false)
    private String invoiceId;
    
    @Column(nullable = false)
    private Instant createdAt;
    
    @Column(nullable = false)
    private Instant expiresAt;
    
    // Constructors
    public IdempotencyKey() {
    }
    
    public IdempotencyKey(String keyValue, String invoiceId, Instant expiresAt) {
        this.keyValue = keyValue;
        this.invoiceId = invoiceId;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getKeyValue() {
        return keyValue;
    }
    
    public void setKeyValue(String keyValue) {
        this.keyValue = keyValue;
    }
    
    public String getInvoiceId() {
        return invoiceId;
    }
    
    public void setInvoiceId(String invoiceId) {
        this.invoiceId = invoiceId;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
