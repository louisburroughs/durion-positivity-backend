package com.positivity.workorder.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkOrderPart {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_service_id")
    private WorkOrderService workOrderService;

    private Long productEntityId; // Reference to ProductEntity in pos-catalog
    private Long nonInventoryProductEntityId; // Reference to NonInventoryProductEntity in pos-catalog
    private Integer quantity;
}

