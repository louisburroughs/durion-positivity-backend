package com.positivity.supplier.internal.stockreport.service;

import com.positivity.supplier.internal.adapter.ediwheelb.EdiwheelB21StockReportCodec;
import com.positivity.supplier.internal.adapter.ediwheelb.StockReportDecodeException;
import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.client.SupplierRequests;
import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.domain.model.SupplierStockSnapshot;
import com.positivity.supplier.internal.entity.StockSnapshotEntity;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.exception.StockReportFetchException;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.SupplierCodecs;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedPartyAccounts;
import com.positivity.supplier.internal.spi.SupplierStockReportPort;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runs one vendor stock-report fetch end to end (CAP-322 #1228).
 *
 * <p>Resolve the binding and buyer account, fetch through the shared base client, decode with the
 * registered codec, then hand the snapshot to {@link StockReportSnapshotWriter}, which commits it
 * with its events.
 *
 * <h2>A failed fetch keeps the last good snapshot</h2>
 *
 * Snapshots are append-only and nothing here supersedes or deletes one. A failed exchange or an
 * undecodable document records a {@code FAILED} snapshot and stops, so a consumer's availability
 * hints keep their existing staleness rather than being replaced by an empty answer. A vendor
 * outage is not a statement that a warehouse is empty — the same rule PRICAT applies to prices.
 *
 * <h2>Absence carries through</h2>
 *
 * The codec decodes an unstated quantity to null and this layer never fills it in. Whether an
 * article missing from a snapshot means "gone" or "unmentioned" is the consumer's judgement, and
 * pos-inventory cannot make it correctly if the producer has already guessed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReportImporter implements SupplierStockReportPort {

    /** Same default as PRICAT: a country-level report is as wide as a catalog (ADR-0053 §7). */
    public static final int DEFAULT_CHUNK_SIZE = 500;

    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;
    private final StockReportSnapshotWriter snapshotWriter;
    private final Clock clock;

    @Value("${pos.supplier.stockreport.chunk-size:" + DEFAULT_CHUNK_SIZE + "}")
    private int defaultChunkSize;

    @Value("${pos.supplier.stockreport.send-buyer-party:false}")
    private boolean sendBuyerParty;

    /**
     * Fetches and records the vendor's current stock snapshot.
     *
     * @param supplierRef vendor profile alias to fetch for
     * @return the persisted snapshot row; a failed fetch is a terminal row, not an exception
     * @throws SupplierConfigurationException when the profile, binding, codec or billing account is
     *                                        not configured — a deployment defect, surfaced loudly
     */
    @NonNull
    public StockSnapshotEntity runSnapshot(@NonNull SupplierRef supplierRef) {
        // The binding and billing account are resolved here because the failure path below needs
        // them to write its row. The codec is not: fetchStockSnapshot resolves its own, and an
        // unconfigured one still surfaces from there as a SupplierConfigurationException, which
        // this method does not catch.
        ResolvedBinding binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.STOCK_REPORT);
        ResolvedPartyAccounts accounts = profileResolver.resolvePartyContext(supplierRef, null);
        SupplierAccountEntity billing = accounts.billing();

        String correlationId = SupplierCorrelationContext.currentOrGenerate();
        Instant fetchedAt = Instant.now(clock);

        SupplierStockSnapshot snapshot;
        try {
            snapshot = fetchStockSnapshot(supplierRef);
        } catch (StockReportFetchException e) {
            // The failure is recorded rather than raised: a snapshot run is scheduled work, and an
            // operator needs the row that says this vendor was asked and did not answer.
            return snapshotWriter.persistFailure(binding, billing, correlationId, fetchedAt, e.getMessage());
        }

        int chunkSize = binding.binding().getEventChunkSize() == null
                ? defaultChunkSize
                : binding.binding().getEventChunkSize();

        return snapshotWriter.commit(snapshot, binding, billing, correlationId, fetchedAt, chunkSize);
    }

    /**
     * The vendor conversation, with no persistence (the {@link SupplierStockReportPort}).
     *
     * <p>Throws rather than returning an empty snapshot. An empty snapshot is a claim — "this vendor
     * holds nothing, anywhere" — and a consumer acting on one would report every article unavailable
     * because a request timed out. Recording the failure instead is {@link #runSnapshot}'s job,
     * because what to do about it is this platform's policy rather than the protocol's.
     */
    @Override
    @NonNull
    public SupplierStockSnapshot fetchStockSnapshot(@NonNull SupplierRef supplierRef) {
        ResolvedBinding binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.STOCK_REPORT);
        EdiwheelB21StockReportCodec codec = SupplierCodecs.require(
                adapterRegistry, binding, SupplierCapability.STOCK_REPORT, EdiwheelB21StockReportCodec.class);
        SupplierAccountEntity billing =
                profileResolver.resolvePartyContext(supplierRef, null).billing();

        PartyContext partyContext = new PartyContext(billing.getAccountNumber(), billing.getAgencyCode(), null);
        SupplierRequestSpec spec = codec.buildRequest(partyContext, sendBuyerParty);
        SupplierHttpResponse response = baseClient.exchange(SupplierRequests.toHttpRequest(binding, spec));

        if (!response.isSuccess()) {
            throw new StockReportFetchException("vendor exchange failed: " + response.outcome()
                    + (response.failureDetail() == null ? "" : " — " + response.failureDetail()));
        }

        try {
            return codec.decode(response.body());
        } catch (StockReportDecodeException e) {
            throw new StockReportFetchException(e.getMessage(), e);
        }
    }
}
