package com.positivity.workorder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId; // Reference to Shop
    private Long vehicleId; // Reference to Vehicle
    private Long customerId; // Reference to Customer
    private Long approvalId; // Reference to CustomerApproval (from pos-customer-approval)

    @OneToMany(mappedBy = "workOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkOrderService> services;
}

