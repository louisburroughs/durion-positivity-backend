package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.serial.SerialUnitResponse;
import com.positivity.inventory.internal.enums.InventorySerialStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read API over the serial unit master (odoo-parity E4, issue #1050).
 *
 * <p>Serial units are created and transitioned exclusively by the ledger posting funnel's serial
 * capture/consumption path; this service only reads. It is the join point for warranty:
 * {@code WarrantyPartReturnHold.serialNumber} resolves to a stock record via
 * {@link #findBySerial(String, String)}.
 */
public interface InventorySerialUnitService {

    /**
     * Lists serial unit master records matching the optional filters, newest first.
     *
     * @param stockItemId optional stock item filter
     * @param status optional lifecycle status filter
     * @param serialNumber optional exact serial-number filter
     * @return matching serial units
     */
    @NonNull
    List<SerialUnitResponse> listSerialUnits(
            @Nullable String stockItemId, @Nullable InventorySerialStatus status, @Nullable String serialNumber);

    /**
     * One serial unit by id.
     *
     * @param serialUnitId the serial unit id
     * @return the serial unit
     */
    @NonNull
    SerialUnitResponse getSerialUnit(@NonNull UUID serialUnitId);

    /**
     * One serial unit by its (stockItemId, serialNumber) natural key — the warranty join point.
     *
     * @param stockItemId the stock item id
     * @param serialNumber the serial number
     * @return the serial unit
     */
    @NonNull
    SerialUnitResponse findBySerial(@NonNull String stockItemId, @NonNull String serialNumber);
}
