package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ItemCostAuditDto;
import com.positivity.catalog.internal.dto.ItemCostsDto;
import com.positivity.catalog.internal.dto.PurchaseOrderReceivedEventDto;
import com.positivity.catalog.internal.dto.UpdateStandardCostRequestDto;
import com.positivity.catalog.internal.entity.ItemCostAuditEntity;
import com.positivity.catalog.internal.entity.ItemCostEntity;
import com.positivity.catalog.internal.enums.ChangeSourceType;
import com.positivity.catalog.internal.enums.CostType;
import com.positivity.catalog.internal.exception.CatalogForbiddenOperationException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ItemCostAuditRepository;
import com.positivity.catalog.internal.repository.ItemCostRepository;
import com.positivity.catalog.service.ItemCostService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItemCostServiceImpl implements ItemCostService {

    private static final Logger log = LoggerFactory.getLogger(ItemCostServiceImpl.class);

    private final ItemCostRepository itemCostRepository;
    private final ItemCostAuditRepository itemCostAuditRepository;

    public ItemCostServiceImpl(ItemCostRepository itemCostRepository, ItemCostAuditRepository itemCostAuditRepository) {
        this.itemCostRepository = itemCostRepository;
        this.itemCostAuditRepository = itemCostAuditRepository;
    }

    @Override
    @Transactional
    public ItemCostsDto updateStandardCost(@NonNull UUID itemId, @NonNull UpdateStandardCostRequestDto request,
            @NonNull String actor) {
        if (request.getReasonCode() == null || request.getReasonCode().isBlank()) {
            throw new CatalogValidationException("reasonCode is required.");
        }
        if (request.getNewCost() == null || request.getNewCost().signum() <= 0) {
            throw new CatalogValidationException("newCost must be positive.");
        }

        ItemCostEntity itemCost = itemCostRepository.findByItemId(itemId)
                .orElseGet(() -> initializeItemCost(itemId));

        BigDecimal oldValue = itemCost.getStandardCost();
        BigDecimal newValue = request.getNewCost().setScale(4, RoundingMode.HALF_UP);
        itemCost.setStandardCost(newValue);
        itemCostRepository.save(itemCost);

        itemCostAuditRepository.save(buildAudit(itemId, CostType.STANDARD, oldValue, newValue,
                new AuditSource(ChangeSourceType.MANUAL, "MANUAL"), actor, request.getReasonCode()));

        return toItemCostsDto(itemCost);
    }

    @Override
    @Transactional(readOnly = true)
    public ItemCostsDto getItemCosts(@NonNull UUID itemId) {
        return itemCostRepository.findByItemId(itemId)
                .map(this::toItemCostsDto)
                .orElseGet(() -> {
                    ItemCostsDto dto = new ItemCostsDto();
                    dto.setItemId(itemId);
                    return dto;
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemCostAuditDto> getAuditHistory(@NonNull UUID itemId) {
        return itemCostAuditRepository.findByItemIdOrderByTimestampDesc(itemId)
                .stream()
                .map(this::toAuditDto)
                .toList();
    }

    @Override
    @Transactional
    public void processPoReceivedEvent(@NonNull PurchaseOrderReceivedEventDto event) {
        if (event.getReceivedUnitCost() == null || event.getReceivedUnitCost().signum() <= 0) {
            log.error("Ignoring PO event with invalid unit cost: itemId={}, purchaseOrderId={}", event.getItemId(),
                    event.getPurchaseOrderId());
            return;
        }
        if (event.getReceivedQty() == null || event.getReceivedQty().signum() <= 0) {
            log.error("Ignoring PO event with invalid quantity: itemId={}, purchaseOrderId={}", event.getItemId(),
                    event.getPurchaseOrderId());
            return;
        }

        ItemCostEntity itemCost = itemCostRepository.findByItemId(event.getItemId())
                .orElseGet(() -> initializeItemCost(event.getItemId()));

        BigDecimal oldLast = itemCost.getLastCost();
        BigDecimal oldAverage = itemCost.getAverageCost();
        BigDecimal oldQtyOnHand = itemCost.getQtyOnHand() == null ? BigDecimal.ZERO : itemCost.getQtyOnHand();

        BigDecimal receivedQty = event.getReceivedQty();
        BigDecimal receivedUnitCost = event.getReceivedUnitCost().setScale(4, RoundingMode.HALF_UP);
        BigDecimal newQtyOnHand = oldQtyOnHand.add(receivedQty);

        BigDecimal oldAverageForMath = oldAverage == null ? BigDecimal.ZERO : oldAverage;
        BigDecimal weightedValue = oldQtyOnHand.multiply(oldAverageForMath)
                .add(receivedQty.multiply(receivedUnitCost));
        BigDecimal newAverage = weightedValue.divide(newQtyOnHand, 4, RoundingMode.HALF_UP);

        itemCost.setLastCost(receivedUnitCost);
        itemCost.setAverageCost(newAverage);
        itemCost.setQtyOnHand(newQtyOnHand);
        itemCostRepository.save(itemCost);

        itemCostAuditRepository.save(buildAudit(event.getItemId(), CostType.LAST, oldLast, receivedUnitCost,
                new AuditSource(ChangeSourceType.PURCHASE_ORDER, event.getPurchaseOrderId()), "system", null));
        itemCostAuditRepository.save(buildAudit(event.getItemId(), CostType.AVERAGE, oldAverage, newAverage,
                new AuditSource(ChangeSourceType.PURCHASE_ORDER, event.getPurchaseOrderId()), "system", null));
    }

    public void rejectManualSystemManagedFieldUpdate() {
        throw new CatalogForbiddenOperationException("This field is system-managed and cannot be manually updated");
    }

    private ItemCostEntity initializeItemCost(UUID itemId) {
        ItemCostEntity entity = new ItemCostEntity();
        entity.setItemId(itemId);
        entity.setQtyOnHand(BigDecimal.ZERO);
        return entity;
    }

    private ItemCostAuditEntity buildAudit(UUID itemId, CostType costType, BigDecimal oldValue, BigDecimal newValue,
            AuditSource source, String actor, String reasonCode) {
        ItemCostAuditEntity audit = new ItemCostAuditEntity();
        audit.setItemId(itemId);
        audit.setCostTypeChanged(costType);
        audit.setOldValue(oldValue);
        audit.setNewValue(newValue);
        audit.setChangeSourceType(source.type());
        audit.setChangeSourceId(source.id());
        audit.setActor(actor);
        audit.setReasonCode(reasonCode);
        return audit;
    }

    private record AuditSource(ChangeSourceType type, String id) {
    }

    private ItemCostsDto toItemCostsDto(ItemCostEntity entity) {
        ItemCostsDto dto = new ItemCostsDto();
        dto.setItemId(entity.getItemId());
        dto.setStandardCost(entity.getStandardCost());
        dto.setLastCost(entity.getLastCost());
        dto.setAverageCost(entity.getAverageCost());
        return dto;
    }

    private ItemCostAuditDto toAuditDto(ItemCostAuditEntity entity) {
        ItemCostAuditDto dto = new ItemCostAuditDto();
        dto.setAuditId(entity.getAuditId());
        dto.setItemId(entity.getItemId());
        dto.setTimestamp(entity.getTimestamp());
        dto.setCostTypeChanged(entity.getCostTypeChanged());
        dto.setOldValue(entity.getOldValue());
        dto.setNewValue(entity.getNewValue());
        dto.setChangeSourceType(entity.getChangeSourceType());
        dto.setChangeSourceId(entity.getChangeSourceId());
        dto.setActor(entity.getActor());
        dto.setReasonCode(entity.getReasonCode());
        return dto;
    }
}
