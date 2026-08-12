package com.positivity.vehiclereferencecarapi.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.vehiclereferencecarapi.internal.entity.CarApiMake;
import com.positivity.vehiclereferencecarapi.internal.entity.CarApiModel;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * First tests for {@link VehicleReferenceMapper} — this module was at 0%.
 *
 * <p>
 * Two ids travel on each row and they are not interchangeable: {@code id} is
 * this cache's own primary key, while {@code makeId} / {@code modelId} are
 * CarAPI's identifiers, which is what a caller uses to ask CarAPI for anything
 * else. Transposing them would produce a response that looks structurally fine
 * and refers to the wrong vehicle everywhere downstream.
 *
 * <p>
 * That transposition has already happened on the model response — see
 * {@link #modelResponseCarriesInternalIdNotProviderId()} and issue #1267. These
 * tests pin it as it stands so the fix is a visible test change.
 */
@DisplayName("VehicleReferenceMapper — cache row to response")
class VehicleReferenceMapperTest {

    private static final UUID ROW_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID CARAPI_MAKE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID CARAPI_MODEL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID MAKE_ROW_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c4");

    @Test
    @DisplayName("keeps the local row id and CarAPI's make id distinct")
    void makeResponseKeepsBothIds() {
        CarApiMake make = new CarApiMake();
        make.setId(ROW_ID);
        make.setMakeId(CARAPI_MAKE_ID);
        make.setMakeName("Toyota");

        CarApiMakeResponse response = VehicleReferenceMapper.toMakeResponse(make);

        assertThat(response.getId()).isEqualTo(ROW_ID);
        assertThat(response.getMakeId()).isEqualTo(CARAPI_MAKE_ID);
        assertThat(response.getMakeName()).isEqualTo("Toyota");
        // Explicit: a transposition here is invisible in the response shape.
        assertThat(response.getId()).isNotEqualTo(response.getMakeId());
    }

    @Test
    @DisplayName("BUG: a model response carries the internal make id under a field documented as the provider id")
    void modelResponseCarriesInternalIdNotProviderId() {
        CarApiMake make = new CarApiMake();
        make.setId(MAKE_ROW_ID);
        make.setMakeId(CARAPI_MAKE_ID);
        CarApiModel model = new CarApiModel();
        model.setId(ROW_ID);
        model.setModelId(CARAPI_MODEL_ID);
        model.setModelName("Corolla");
        model.setMake(make);

        CarApiModelResponse response = VehicleReferenceMapper.toModelResponse(model);

        assertThat(response.getId()).isEqualTo(ROW_ID);
        assertThat(response.getModelId()).isEqualTo(CARAPI_MODEL_ID);
        assertThat(response.getModelName()).isEqualTo("Corolla");

        // Documents current behaviour, not desired behaviour. CarApiModelResponse.makeId is
        // annotated "Provider make identifier the model belongs to", but the value comes from
        // CarApiModel.getMakeId(), which returns make.getId() — this cache's primary key. So
        // the same field name means the provider id on CarApiMakeResponse and the internal id
        // here, and a client that feeds this value back to GET /models/{makeId} is in the
        // wrong id space. Tracked as issue #1267, together with the service-side conflation
        // that makes no argument to getModelsByMakeId correct. When #1267 is fixed this
        // assertion flips to CARAPI_MAKE_ID and the test is renamed.
        assertThat(response.getMakeId()).isEqualTo(MAKE_ROW_ID).isNotEqualTo(CARAPI_MAKE_ID);
    }
}
