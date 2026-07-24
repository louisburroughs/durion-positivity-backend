package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventorySerialUnit;
import com.positivity.inventory.internal.entity.WarrantyPartReturnHold;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.InventorySerialStatus;
import com.positivity.inventory.internal.exception.SerialCountMismatchException;
import com.positivity.inventory.internal.exception.SerialNotAvailableException;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.InventorySerialUnitRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.WarrantyPartReturnHoldRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end tests for odoo-parity E4 (issue #1050): serial-number tracking. Exercises the
 * serial=quantity invariant, enumeration on receipt, consumption/double-issue on issue, the
 * warranty serial join, the serial-vs-ledger verifier, and the zero-behavior-change guarantee for
 * LOT/NONE stock items — all through the real ledger posting funnel against the H2 stack.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Serial tracking (odoo-parity E4, #1050)")
class InventorySerialTrackingTest {

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private InventorySerialUnitRepository serialRepository;

    @Autowired
    private InventoryStockSummaryRepository summaryRepository;

    @Autowired
    private ExtProductReplicaRepository extProductReplicaRepository;

    @Autowired
    private WarrantyPartReturnHoldRepository warrantyPartReturnHoldRepository;

    @Autowired
    private SerialUnitOnHandVerifier verifier;

    private UUID seedProduct(String trackingLevel) {
        UUID productId = UUID.randomUUID();
        extProductReplicaRepository.save(ExtProductReplica.builder()
                .productId(productId)
                .baseUom("EA")
                .trackingLevel(trackingLevel)
                .aggregateVersion(1L)
                .build());
        return productId;
    }

    private InventoryLedgerEntry receipt(String sku, UUID location, int qty, List<String> serials) {
        return InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(location)
                .toLocationId(location)
                .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                .changeInQuantity(qty)
                .quantityAfter(0)
                .transactionUserId("serial-test")
                .serialNumbers(serials)
                .build();
    }

    private InventoryLedgerEntry issue(
            String sku,
            UUID location,
            int qty,
            List<String> serials,
            String workorderId,
            InventoryLedgerEventType type) {
        return InventoryLedgerEntry.builder()
                .stockItemId(sku)
                .locationId(location)
                .fromLocationId(location)
                .eventType(type)
                .changeInQuantity(-qty)
                .quantityAfter(0)
                .transactionUserId("serial-test")
                .serialNumbers(serials)
                .serialWorkorderId(workorderId)
                .build();
    }

    // ─── receipt enumerates N serials for N units ────────────────────────────────

    @Test
    @DisplayName("Receipt of N units enumerates exactly N IN_STOCK serial units at the location")
    void receiptEnumeratesSerials() {
        UUID product = seedProduct("SERIAL");
        UUID location = UUID.randomUUID();

        ledgerPostingService.post(receipt(product.toString(), location, 3, List.of("SN-1", "SN-2", "SN-3")));

        List<InventorySerialUnit> units = serialRepository.findAll().stream()
                .filter(u -> product.toString().equals(u.getStockItemId()))
                .toList();
        assertThat(units).hasSize(3);
        assertThat(units).allSatisfy(u -> {
            assertThat(u.getStatus()).isEqualTo(InventorySerialStatus.IN_STOCK);
            assertThat(u.getLocationId()).isEqualTo(location);
            assertThat(u.getReceiptLedgerEntryId()).isNotNull();
            assertThat(u.getReceivedAt()).isNotNull();
        });
        assertThat(units)
                .extracting(InventorySerialUnit::getSerialNumber)
                .containsExactlyInAnyOrder("SN-1", "SN-2", "SN-3");
        assertThat(serialRepository.countByStockItemIdAndStatus(product.toString(), InventorySerialStatus.IN_STOCK))
                .isEqualTo(3);
    }

    // ─── serial = quantity invariant ─────────────────────────────────────────────

    @Test
    @DisplayName("Receipt whose serial count != quantity is rejected (SERIAL_COUNT_MISMATCH), nothing posted")
    void receiptCountMismatchRejected() {
        UUID product = seedProduct("SERIAL");
        UUID location = UUID.randomUUID();

        assertThatThrownBy(() ->
                        ledgerPostingService.post(receipt(product.toString(), location, 3, List.of("SN-A", "SN-B"))))
                .isInstanceOf(SerialCountMismatchException.class);

        assertThat(serialRepository.findAll().stream()
                        .anyMatch(u -> product.toString().equals(u.getStockItemId())))
                .isFalse();
        // The rejection rolls back the whole posting: no serial units, and the summary on-hand
        // never moved off zero (a bare row may have been created by the nested row initializer,
        // but its balance stays 0).
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(product.toString(), location)
                        .map(s -> s.getOnHand())
                        .orElse(0L))
                .isZero();
    }

    @Test
    @DisplayName("Receipt with duplicate serials is rejected (dedup drops below required count)")
    void receiptDuplicateSerialsRejected() {
        UUID product = seedProduct("SERIAL");
        UUID location = UUID.randomUUID();

        assertThatThrownBy(() -> ledgerPostingService.post(
                        receipt(product.toString(), location, 2, List.of("SN-DUP", "SN-DUP"))))
                .isInstanceOf(SerialCountMismatchException.class);
    }

    // ─── consumption: named serial decrements, records workorder linkage ─────────

    @Test
    @DisplayName("Issue of a named serial flips it to ISSUED with the consuming entry and workorder linkage")
    void issueConsumesNamedSerial() {
        UUID product = seedProduct("SERIAL");
        UUID location = UUID.randomUUID();
        String workorderId = "WO-" + UUID.randomUUID();

        ledgerPostingService.post(receipt(product.toString(), location, 2, List.of("SN-X", "SN-Y")));
        ledgerPostingService.post(issue(
                product.toString(), location, 1, List.of("SN-X"), workorderId, InventoryLedgerEventType.GOODS_ISSUE));

        InventorySerialUnit issued = serialRepository
                .findByStockItemIdAndSerialNumber(product.toString(), "SN-X")
                .orElseThrow();
        assertThat(issued.getStatus()).isEqualTo(InventorySerialStatus.ISSUED);
        assertThat(issued.getWorkorderId()).isEqualTo(workorderId);
        assertThat(issued.getConsumptionLedgerEntryId()).isNotNull();
        assertThat(issued.getConsumedAt()).isNotNull();

        // The other unit stays in stock; serial on-hand drops to 1.
        assertThat(serialRepository.countByStockItemIdAndStatus(product.toString(), InventorySerialStatus.IN_STOCK))
                .isEqualTo(1);
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(product.toString(), location)
                        .orElseThrow()
                        .getOnHand())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Double-issue of a serial is rejected (SERIAL_NOT_AVAILABLE)")
    void doubleIssueRejected() {
        UUID product = seedProduct("SERIAL");
        UUID location = UUID.randomUUID();

        ledgerPostingService.post(receipt(product.toString(), location, 2, List.of("SN-1", "SN-2")));
        ledgerPostingService.post(
                issue(product.toString(), location, 1, List.of("SN-1"), null, InventoryLedgerEventType.GOODS_ISSUE));

        assertThatThrownBy(() -> ledgerPostingService.post(issue(
                        product.toString(), location, 1, List.of("SN-1"), null, InventoryLedgerEventType.GOODS_ISSUE)))
                .isInstanceOf(SerialNotAvailableException.class);
    }

    @Test
    @DisplayName("Issue naming an unknown serial is rejected (SERIAL_NOT_AVAILABLE)")
    void issueUnknownSerialRejected() {
        UUID product = seedProduct("SERIAL");
        UUID location = UUID.randomUUID();
        ledgerPostingService.post(receipt(product.toString(), location, 1, List.of("SN-KNOWN")));

        assertThatThrownBy(() -> ledgerPostingService.post(issue(
                        product.toString(),
                        location,
                        1,
                        List.of("SN-GHOST"),
                        null,
                        InventoryLedgerEventType.GOODS_ISSUE)))
                .isInstanceOf(SerialNotAvailableException.class);
    }

    // ─── lifecycle transitions: scrap, return-to-stock ──────────────────────────

    @Test
    @DisplayName("Scrap flips the named serial to SCRAPPED; a return re-stocks an issued serial to IN_STOCK")
    void scrapAndReturnTransitions() {
        UUID product = seedProduct("SERIAL");
        UUID location = UUID.randomUUID();

        ledgerPostingService.post(receipt(product.toString(), location, 3, List.of("SN-1", "SN-2", "SN-3")));

        // Scrap SN-1.
        ledgerPostingService.post(
                issue(product.toString(), location, 1, List.of("SN-1"), null, InventoryLedgerEventType.SCRAP_OUT));
        assertThat(serialRepository
                        .findByStockItemIdAndSerialNumber(product.toString(), "SN-1")
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(InventorySerialStatus.SCRAPPED);

        // Issue then return SN-2.
        ledgerPostingService.post(
                issue(product.toString(), location, 1, List.of("SN-2"), "WO-1", InventoryLedgerEventType.GOODS_ISSUE));
        InventoryLedgerEntry returnEntry = InventoryLedgerEntry.builder()
                .stockItemId(product.toString())
                .locationId(location)
                .toLocationId(location)
                .eventType(InventoryLedgerEventType.RETURN_TO_STOCK)
                .changeInQuantity(1)
                .quantityAfter(0)
                .transactionUserId("serial-test")
                .serialNumbers(List.of("SN-2"))
                .build();
        ledgerPostingService.post(returnEntry);
        InventorySerialUnit returned = serialRepository
                .findByStockItemIdAndSerialNumber(product.toString(), "SN-2")
                .orElseThrow();
        assertThat(returned.getStatus()).isEqualTo(InventorySerialStatus.IN_STOCK);
        assertThat(returned.getWorkorderId()).isNull();
        assertThat(returned.getConsumedAt()).isNull();
    }

    // ─── warranty serial join ────────────────────────────────────────────────────

    @Test
    @DisplayName("A consumed serial joins to warranty's WarrantyPartReturnHold by serial number")
    void warrantySerialJoin() {
        UUID product = seedProduct("SERIAL");
        UUID location = UUID.randomUUID();
        String serial = "SN-WARRANTY-1";
        String workorderId = "WO-" + UUID.randomUUID();

        ledgerPostingService.post(receipt(product.toString(), location, 1, List.of(serial)));
        ledgerPostingService.post(issue(
                product.toString(), location, 1, List.of(serial), workorderId, InventoryLedgerEventType.GOODS_ISSUE));

        // pos-warranty materializes a defective-part return carrying the same serial number.
        warrantyPartReturnHoldRepository.save(WarrantyPartReturnHold.builder()
                .partReturnId(UUID.randomUUID())
                .claimId(UUID.randomUUID())
                .productEntityId(product)
                .serialNumber(serial)
                .status(WarrantyPartReturnHold.STATUS_REQUESTED)
                .aggregateVersion(1L)
                .requestedAt(Instant.now())
                .build());

        WarrantyPartReturnHold hold = warrantyPartReturnHoldRepository.findAll().stream()
                .filter(h -> serial.equals(h.getSerialNumber()))
                .findFirst()
                .orElseThrow();
        InventorySerialUnit stockRecord = serialRepository
                .findByStockItemIdAndSerialNumber(product.toString(), hold.getSerialNumber())
                .orElseThrow();
        assertThat(stockRecord.getStatus()).isEqualTo(InventorySerialStatus.ISSUED);
        assertThat(stockRecord.getWorkorderId()).isEqualTo(workorderId);
    }

    // ─── verifier detects an injected mismatch ──────────────────────────────────

    @Test
    @DisplayName("Serial-vs-ledger verifier is clean after receipt and flags an injected mismatch")
    void verifierDetectsInjectedMismatch() {
        UUID product = seedProduct("SERIAL");
        UUID location = UUID.randomUUID();
        ledgerPostingService.post(receipt(product.toString(), location, 3, List.of("SN-V1", "SN-V2", "SN-V3")));

        // Baseline: serial IN_STOCK count (3) matches summary on-hand (3) for this key.
        long baselineDrift = countDriftFor(product, location);
        assertThat(baselineDrift).isZero();

        // Inject a mismatch: physically drop one serial row without a ledger posting.
        InventorySerialUnit toDelete = serialRepository
                .findByStockItemIdAndSerialNumber(product.toString(), "SN-V1")
                .orElseThrow();
        serialRepository.delete(toDelete);

        assertThat(countDriftFor(product, location)).isEqualTo(1);
        assertThat(verifier.verify()).isGreaterThanOrEqualTo(1);
    }

    /** Drift for one key: the verifier reports a global count, so isolate this key's contribution. */
    private long countDriftFor(UUID product, UUID location) {
        long serialInStock =
                serialRepository.countByStockItemIdAndStatus(product.toString(), InventorySerialStatus.IN_STOCK);
        long summaryOnHand = summaryRepository
                .findByStockItemIdAndLocationId(product.toString(), location)
                .map(s -> s.getOnHand())
                .orElse(0L);
        return serialInStock == summaryOnHand ? 0 : 1;
    }

    // ─── LOT / NONE unaffected: zero behavior change ────────────────────────────

    @Test
    @DisplayName("LOT-tracked and untracked receipts post with no serials and create no serial units")
    void lotAndNoneUnaffected() {
        UUID lotProduct = seedProduct("LOT");
        UUID noneProduct = seedProduct("NONE");
        String freeTextSku = "SKU-LEGACY-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();

        // No serials supplied, no invariant enforced — receipts succeed and enumerate nothing.
        ledgerPostingService.post(receipt(lotProduct.toString(), location, 5, List.of()));
        ledgerPostingService.post(receipt(noneProduct.toString(), location, 5, List.of()));
        ledgerPostingService.post(receipt(freeTextSku, location, 5, List.of()));

        assertThat(serialRepository.findAll().stream()
                        .anyMatch(u -> u.getStockItemId().equals(lotProduct.toString())
                                || u.getStockItemId().equals(noneProduct.toString())
                                || u.getStockItemId().equals(freeTextSku)))
                .isFalse();
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(lotProduct.toString(), location)
                        .orElseThrow()
                        .getOnHand())
                .isEqualTo(5);
        assertThat(summaryRepository
                        .findByStockItemIdAndLocationId(noneProduct.toString(), location)
                        .orElseThrow()
                        .getOnHand())
                .isEqualTo(5);
    }
}
