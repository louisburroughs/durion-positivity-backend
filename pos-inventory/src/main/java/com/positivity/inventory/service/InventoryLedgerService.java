package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.InventoryLedgerEntryDto;
import com.positivity.inventory.internal.dto.InventoryLedgerFilterParams;
import com.positivity.inventory.internal.dto.LedgerPage;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface InventoryLedgerService {

  @NonNull
  LedgerPage<InventoryLedgerEntryDto> listLedgerEntries(@NonNull InventoryLedgerFilterParams params);

  @NonNull
  InventoryLedgerEntryDto getLedgerEntry(@NonNull UUID entryId);
}