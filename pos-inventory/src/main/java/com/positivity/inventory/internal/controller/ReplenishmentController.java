package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.service.ReplenishmentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory/replenishment")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('inventory:stock:view','inventory:stock:adjust','inventory:availability:read','inventory:adjustment:create')")
public class ReplenishmentController {

    private final ReplenishmentService replenishmentService;

    @GetMapping("/tasks")
    public ResponseEntity<List<ReplenishmentTaskResponse>> getReplenishmentTasks() {
        return ResponseEntity.ok(replenishmentService.getReplenishmentTasks());
    }

    @GetMapping("/policies")
    public ResponseEntity<List<ReplenishmentPolicyResponse>> getReplenishmentPolicies() {
        return ResponseEntity.ok(replenishmentService.getReplenishmentPolicies());
    }

    @PostMapping("/policies")
    public ResponseEntity<ReplenishmentPolicyResponse> createReplenishmentPolicy(
            @Valid @RequestBody CreateReplenishmentPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(replenishmentService.createReplenishmentPolicy(request));
    }
}