package com.positivity.inventory.internal.exception;

import java.util.UUID;

public class PickScanMismatchException extends RuntimeException {
    public PickScanMismatchException(UUID expectedSkuId, UUID scannedSkuId) {
        super(String.format("Pick scan mismatch: expected SKU %s but scanned %s", expectedSkuId, scannedSkuId));
    }
}