package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.asn.AsnResponse;
import com.positivity.inventory.internal.dto.asn.CreateAsnRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface AsnService {

    @NonNull
    AsnResponse createAsn(@NonNull CreateAsnRequest request, @NonNull String actorId);

    @NonNull
    AsnResponse getAsn(@NonNull UUID asnId);

    @NonNull
    GoodsReceiptResponse createGoodsReceipt(@NonNull CreateGoodsReceiptRequest request, @NonNull String actorId);

    @NonNull
    GoodsReceiptResponse getGoodsReceipt(@NonNull UUID receiptId);
}
