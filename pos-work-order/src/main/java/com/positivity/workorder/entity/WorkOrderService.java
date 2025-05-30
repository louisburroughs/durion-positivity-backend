package com.positivity.workorder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkOrderService {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id")
    private WorkOrder workOrder;

    private Long serviceEntityId; // Reference to ServiceEntity in pos-catalog
    private Long technicianId;    // Reference to Technician

    @OneToMany(mappedBy = "workOrderService", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkOrderPart> parts;
}

