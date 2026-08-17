package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.supplier.internal.adapter.michelins2s.MichelinS2SWorkorderAuthCodec;
import com.positivity.supplier.internal.adapter.michelins2s.WorkorderAuthDecodeException;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.client.SupplierRequests;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.exception.FleetLookupException;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.SupplierCodecs;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.service.model.FleetContract;
import com.positivity.supplier.service.model.FleetPolicy;
import com.positivity.supplier.service.model.FleetVehicle;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Reads vehicles, contracts and policies from a fleet program (CAP-323 #1229).
 *
 * <h2>These are quoting aids, not gates</h2>
 *
 * A service advisor uses them to know what to offer before asking for authorization. None of them
 * decides anything: the vendor decides, when authorization is requested. Treating a policy read here
 * as permission would either quote work the fleet refuses or refuse work the fleet would have paid
 * for, and both failures are invisible because no request was ever made.
 *
 * <h2>A missing vehicle is empty, not an error</h2>
 *
 * "The fleet does not know this plate" is an ordinary answer to an ordinary question, and it happens
 * every time somebody mistypes. It comes back as an empty {@link Optional} so a caller handles it as
 * a result. Vendor unreachability is different and does throw, because a blank screen that means
 * "not a fleet vehicle" and a blank screen that means "we could not ask" would otherwise look
 * identical to the person deciding whether to start work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FleetLookupRunner {

    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;

    /**
     * Looks up one vehicle in the fleet program.
     *
     * @param vehicleIdent plate, VIN, fleet number or vendor vehicle id, as the vendor accepts
     * @return the vehicle, or empty when the fleet does not know it
     * @throws SupplierConfigurationException when the capability is not configured for this vendor
     * @throws FleetLookupException when the vendor could not be reached or answered unreadably
     */
    @NonNull
    public Optional<FleetVehicle> findVehicle(@NonNull SupplierRef supplierRef, @NonNull String vehicleIdent) {
        Call call = prepare(supplierRef);
        SupplierRequestSpec spec = call.codec().buildVehicleLookup(vehicleIdent, call.partnerId());
        SupplierHttpResponse response = baseClient.exchange(SupplierRequests.toHttpRequest(call.binding(), spec));

        if (response.httpStatus() != null && response.httpStatus() == 404) {
            return Optional.empty();
        }
        require(response, "vehicle lookup");
        try {
            return Optional.of(call.codec().decodeVehicle(response.body()));
        } catch (WorkorderAuthDecodeException e) {
            throw new FleetLookupException("vehicle lookup answer could not be read: " + e.getMessage(), e);
        }
    }

    /** Lists the fleet contracts this service provider may claim work under. */
    @NonNull
    public List<FleetContract> listContracts(@NonNull SupplierRef supplierRef) {
        Call call = prepare(supplierRef);
        SupplierRequestSpec spec = call.codec().buildContractLookup(call.partnerId());
        SupplierHttpResponse response = baseClient.exchange(SupplierRequests.toHttpRequest(call.binding(), spec));
        require(response, "contract lookup");
        try {
            return call.codec().decodeContracts(response.body());
        } catch (WorkorderAuthDecodeException e) {
            throw new FleetLookupException("contract lookup answer could not be read: " + e.getMessage(), e);
        }
    }

    /** Lists the fleet's own inclusion and exclusion policies. Advisory; see the class note. */
    @NonNull
    public List<FleetPolicy> listPolicies(@NonNull SupplierRef supplierRef) {
        Call call = prepare(supplierRef);
        SupplierRequestSpec spec = call.codec().buildPolicyLookup(call.partnerId());
        SupplierHttpResponse response = baseClient.exchange(SupplierRequests.toHttpRequest(call.binding(), spec));
        require(response, "policy lookup");
        return call.codec().decodePolicies(response.body());
    }

    private void require(@NonNull SupplierHttpResponse response, @NonNull String what) {
        if (!response.isSuccess()) {
            throw new FleetLookupException(what + " failed: " + response.outcome()
                    + (response.failureDetail() == null ? "" : " — " + response.failureDetail()));
        }
    }

    @NonNull
    private Call prepare(@NonNull SupplierRef supplierRef) {
        ResolvedBinding binding =
                profileResolver.resolveBinding(supplierRef, SupplierCapability.WORKORDER_AUTHORIZATION);
        MichelinS2SWorkorderAuthCodec codec = SupplierCodecs.require(
                adapterRegistry,
                binding,
                SupplierCapability.WORKORDER_AUTHORIZATION,
                MichelinS2SWorkorderAuthCodec.class);
        return new Call(binding, codec, S2SPartnerId.resolve(profileResolver, supplierRef));
    }

    private record Call(ResolvedBinding binding, MichelinS2SWorkorderAuthCodec codec, String partnerId) {}
}
