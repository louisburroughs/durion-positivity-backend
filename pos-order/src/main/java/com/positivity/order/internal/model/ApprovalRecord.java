package com.positivity.order.internal.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;

/**
 * Represents an approval or rejection record for a price override.
 * 
 * <p>Provides a complete audit trail of who approved/rejected overrides and when.
 * Supports tracking approval workflow and compliance reporting.
 */
@Entity
@Table(name = "approval_record")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalRecord {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long recordId;
    
    /**
     * Reference to the price override being approved/rejected.
     */
    @Column(nullable = false)
    private Long priceOverrideId;
    
    /**
     * User ID of the approver/rejecter.
     */
    @Column(nullable = false)
    private String reviewerUserId;
    
    /**
     * Role of the reviewer (for audit trail).
     */
    @Column(nullable = false)
    private String reviewerRole;
    
    /**
     * Action taken: APPROVED or REJECTED.
     */
    @Column(nullable = false)
    private String action;
    
    /**
     * Optional comments provided by reviewer.
     */
    @Column(length = 2000)
    private String comments;
    
    /**
     * Timestamp of the approval/rejection action.
     */
    @Column(nullable = false, updatable = false)
    private Instant actionTimestamp;
    
    /**
     * IP address of the reviewer (for security audit).
     */
    private String reviewerIpAddress;
    
    @PrePersist
    protected void onCreate() {
        actionTimestamp = Instant.now();
    }
}
